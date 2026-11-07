package org.bsl.cartonloading.buyers.lululemon.agent;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent makes outbound HTTP(S) requests; no inbound ports on Factory PCs and no raw COM access on Linux.
 * In-memory pending READ_ONLY requests: a backend restart fails/clears pending jobs; never replays them.
 * One agent/Factory, one operation at a time. A cluster requires a distributed broker (not supported here).
 */
@Service
public class ScaleAgentBroker {
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(10);
    private static final SecureRandom RNG = new SecureRandom();
    private final ScaleAgentRegistrationRepository registrations;
    private final ScalePlcDeviceRepository scales;
    private final FactoryCatalogService factories;
    private final AuditLogService audit;
    private final MongoTemplate mongo;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> heartbeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();

    public ScaleAgentBroker(ScaleAgentRegistrationRepository registrations,
                            ScalePlcDeviceRepository scales, FactoryCatalogService factories,
                            AuditLogService audit, MongoTemplate mongo) {
        this.registrations = registrations;
        this.scales = scales;
        this.factories = factories;
        this.audit = audit;
        this.mongo = mongo;
    }

    public record Enrollment(String factoryCode, String agentId, String token, String note) { }
    public record AgentStatus(String factoryCode, String agentId, boolean registered, boolean online, Instant lastSeen) { }
    public record Job(String jobId, String command, String serialPort, int baudRate, int dataBits,
                      String parity, int stopBits, int stationAddress, int dRegister, String encoding) { }
    public record Result(String jobId, boolean success, Long rawValue, String error) { }

    private record Pending(String agentId, Job job, CompletableFuture<Result> response, AtomicBoolean handedOut) { }
    private record Lease(String agentId, String instanceId, Instant seen) { }

    private static String code(String factoryCode) {
        String normalized = factoryCode == null ? "" : factoryCode.trim().toUpperCase(Locale.ROOT);
        if (!FactoryCodes.isValid(normalized)) throw new IllegalArgumentException("Invalid Factory code");
        return normalized;
    }
    private static String id(String factory) { return AppConstants.Buyer.LULULEMON + ":" + factory; }
    private static String heartbeatKey(String factory, String agentId) { return factory + "|" + agentId; }
    private void checkActive(String factory) { factories.requireActive(AppConstants.Buyer.LULULEMON, factory); }

    /** Admin operation: issues a new one-time secret; invalidates the old agent immediately. */
    public synchronized Enrollment enroll(String factoryCode) {
        String factory = code(factoryCode);
        checkActive(factory);
        // Prevent token rotation while this Factory's scale owns a live carton.
        Instant now = Instant.now();
        for (ScalePlcDevice scale : scales.findAll()) {
            if (factory.equalsIgnoreCase(scale.getFactoryCode()) && scale.getActiveCartonSscc() != null &&
                scale.getActiveUntil() != null && scale.getActiveUntil().isAfter(now))
                throw new IllegalArgumentException("Scale is reserved for a carton. Complete weighing before rotating Agent credentials.");
        }
        byte[] secret = new byte[32];
        RNG.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String agentId = "SCALE-" + factory + "-" + UUID.randomUUID().toString().substring(0, 12);
        var record = registrations.findById(id(factory)).orElseGet(ScaleAgentRegistration::new);
        boolean rotated = record.getAgentId() != null;
        String previousAgentId = record.getAgentId();
        record.setId(id(factory)); record.setBuyerKey(AppConstants.Buyer.LULULEMON);
        record.setFactoryCode(factory); record.setAgentId(agentId);
        record.setSecretHash(sha(token)); record.setIssuedAt(now); record.setLastSeen(null);
        registrations.save(record);
        if (previousAgentId != null) heartbeats.remove(heartbeatKey(factory, previousAgentId));
        leases.remove(factory);
        Pending old = pending.remove(factory);
        if (old != null) old.response().completeExceptionally(new IOException("Agent credentials rotated"));
        audit.logChange(RequestActor.current(), rotated ? "SCALE_AGENT_ROTATED" : "SCALE_AGENT_REGISTERED",
                AppConstants.AuditResource.CARTON_LOADING, agentId, "Factory=" + factory + "; secret not recorded",
                AppConstants.Buyer.LULULEMON, factory, null,
                java.util.Map.of("agentId", agentId, "factoryCode", factory), null, null);
        return new Enrollment(factory, agentId, token, "Copy token now; it will never be shown again. Rotating revokes the prior Agent.");
    }

    public AgentStatus status(String factoryCode) {
        String factory = code(factoryCode);
        // Reading status must not break Device Management for an archived/inactive Factory.
        var saved = registrations.findById(id(factory)).orElse(null);
        if (saved == null) return new AgentStatus(factory, null, false, false, null);
        Instant heartbeat = heartbeats.getOrDefault(heartbeatKey(factory, saved.getAgentId()), saved.getLastSeen());
        return new AgentStatus(factory, saved.getAgentId(), true,
            heartbeat != null && heartbeat.isAfter(Instant.now().minus(ONLINE_WINDOW)), heartbeat);
    }

    private ScaleAgentRegistration authenticated(String factoryCode, String agentId, String token) {
        String factory = code(factoryCode);
        // Uniform 401 response to invalid/rotated credentials. Never print secrets or hashes.
        ScaleAgentRegistration saved = registrations.findById(id(factory)).orElse(null);
        if (saved == null || saved.getSecretHash() == null || agentId == null || token == null || token.length() > 160 ||
            !Objects.equals(agentId, saved.getAgentId()) ||
            !MessageDigest.isEqual(saved.getSecretHash().getBytes(StandardCharsets.US_ASCII),
                                   sha(token).getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Factory Agent credentials");
        return saved;
    }

    /** Agent outbound poll; authenticated even though security filter allows non-JWT endpoint access. */
    public Job poll(String factoryCode, String agentId, String token, String instanceId) {
        ScaleAgentRegistration saved = authenticated(factoryCode, agentId, token);
        String factory = saved.getFactoryCode();
        Instant now = Instant.now();
        if (instanceId == null || !instanceId.matches("[a-fA-F0-9-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Agent instance ID");
        leases.compute(factory, (key, existing) -> {
            if (existing != null && existing.seen().isAfter(now.minus(ONLINE_WINDOW)) &&
                (!existing.agentId().equals(agentId) || !existing.instanceId().equals(instanceId)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Another Agent instance is already active for this Factory");
            return new Lease(agentId, instanceId, now);
        });
        Instant previous = heartbeats.put(heartbeatKey(factory, agentId), now);
        if (previous == null || previous.isBefore(now.minusSeconds(5))) {
            // Conditional Mongo update prevents an old heartbeat overwriting newly rotated credentials.
            mongo.updateFirst(new Query(Criteria.where("_id").is(saved.getId())
                    .and("agentId").is(agentId)), new Update().set("lastSeen", now), ScaleAgentRegistration.class);
        }
        Pending next = pending.get(factory);
        if (next == null || !next.agentId().equals(agentId) || !next.handedOut().compareAndSet(false, true))
            return null;
        return next.job();
    }

    public void result(String factoryCode, String agentId, String token, String instanceId, Result result) {
        ScaleAgentRegistration saved = authenticated(factoryCode, agentId, token);
        Lease lease = leases.get(saved.getFactoryCode());
        if (lease == null || !lease.agentId().equals(agentId) || !lease.instanceId().equals(instanceId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent instance is no longer active");
        Pending waiting = pending.get(saved.getFactoryCode());
        if (waiting == null || result == null || !waiting.agentId().equals(agentId) ||
            !waiting.job().jobId().equals(result.jobId()) || !waiting.handedOut().get())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown or expired read-only job");
        if (result.success() && result.rawValue() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing register value");
        waiting.response().complete(result);
    }

    /** Server-side read dispatcher. Other Factory agents cannot receive or answer this job. */
    public long read(String factoryCode, String comPort, int baud, int bits, String parity, int stops,
                     int station, int register, String encoding) throws IOException {
        String factory = code(factoryCode);
        AgentStatus status = status(factory);
        if (!status.online()) throw new IOException("Factory " + factory + " Local PLC Agent is offline. Start its Windows Agent and check credentials/TLS.");
        // One serial operation per Factory; prevents old/stale concurrent responses becoming weights.
        synchronized (locks.computeIfAbsent(factory, unused -> new Object())) {
            AgentStatus current = status(factory);
            if (!current.online()) throw new IOException("Factory " + factory + " Local PLC Agent is offline");
            Job job = new Job(UUID.randomUUID().toString(), "READ_D", comPort, baud, bits, parity, stops,
                              station, register, encoding);
            Pending call = new Pending(current.agentId(), job, new CompletableFuture<>(), new AtomicBoolean());
            pending.put(factory, call);
            try {
                Result answer = call.response().get(7, TimeUnit.SECONDS);
                if (!answer.success()) throw new IOException("Factory " + factory + " PLC read failed: " +
                    (answer.error() == null ? "Unspecified serial error" : answer.error().substring(0, Math.min(180, answer.error().length()))));
                return answer.rawValue();
            } catch (TimeoutException e) {
                throw new IOException("Factory " + factory + " PLC Agent timed out on read-only request", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("PLC read interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("PLC Agent request cancelled", e);
            } finally {
                pending.remove(factory, call);
            }
        }
    }

    private static String sha(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
