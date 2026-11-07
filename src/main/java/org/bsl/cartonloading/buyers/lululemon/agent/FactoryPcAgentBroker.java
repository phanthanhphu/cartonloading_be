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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * PC-based multi-COM transport (v2). One enrollment per Windows PC, multiple COM ports.
 * A read-only in-memory broker: no queued jobs survive BE restart; no PLC writes.
 * v1 Factory Agent endpoints and existing saved station settings remain unchanged.
 * One live BE process is required; distributed brokers are intentionally unsupported.
 */
@Service
public class FactoryPcAgentBroker {
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final FactoryPcAgentRepository repository;
    private final FactoryCatalogService factories;
    private final ScalePlcDeviceRepository scales;
    private final AuditLogService audit;
    private final MongoTemplate mongo;
    private final ConcurrentHashMap<String, Pending> waiting = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> beats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public FactoryPcAgentBroker(FactoryPcAgentRepository repository, FactoryCatalogService factories,
            ScalePlcDeviceRepository scales, AuditLogService audit, MongoTemplate mongo) {
        this.repository = repository; this.factories = factories; this.scales = scales;
        this.audit = audit; this.mongo = mongo;
    }
    public record Enrollment(String factoryCode, String agentId, String token, String displayName) { }
    public record PcStatus(String factoryCode, String agentId, String displayName, boolean online, Instant lastSeen) { }
    public record Job(String jobId, String command, String serialPort, int baudRate, int dataBits,
                      String parity, int stopBits, int stationAddress, int dRegister, String encoding) { }
    public record Result(String jobId, boolean success, Long rawValue, List<String> ports, String error) { }
    private record Pending(Job job, CompletableFuture<Result> response, AtomicBoolean handedOut) { }
    private record Lease(String instanceId, Instant seen) { }
    private static String normFactory(String value) {
        String factory = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory code");
        return factory;
    }
    private static String docId(String factory, String agentId) { return AppConstants.Buyer.LULULEMON + ":" + factory + ":" + agentId; }
    private FactoryPcAgentRegistration require(String factory, String agentId) {
        if (agentId == null || !agentId.matches("PC-[A-Z0-9]+-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Choose a valid PC Agent");
        var row = repository.findById(docId(normFactory(factory), agentId)).orElse(null);
        if (row == null || row.isRevoked()) throw new IllegalArgumentException("PC Agent is not registered for this Factory");
        return row;
    }
    public void assertRegistered(String factory, String agentId) { require(factory, agentId); }
    private static String sha(String token) {
        try {
            var data = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(data);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Each PC gets an independent identity; enrolling a second PC never rotates a first PC's key. */
    public Enrollment enroll(String factoryCode, String displayName) {
        String factory = normFactory(factoryCode);
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        String label = displayName == null ? "" : displayName.trim();
        if (label.length() < 2 || label.length() > 64 || !label.matches("[\\p{L}\\p{N} _.-]+"))
            throw new IllegalArgumentException("PC Name must contain 2-64 safe characters");
        if (repository.findByFactoryCodeOrderByDisplayNameAsc(factory).stream().filter(r -> !r.isRevoked()).count() >= 30)
            throw new IllegalArgumentException("Factory PC Agent limit reached (30)");
        var secret = new byte[32]; RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String id = "PC-" + factory + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var row = new FactoryPcAgentRegistration();
        row.setId(docId(factory, id)); row.setFactoryCode(factory); row.setBuyerKey(AppConstants.Buyer.LULULEMON);
        row.setAgentId(id); row.setDisplayName(label); row.setSecretHash(sha(token));
        row.setIssuedAt(Instant.now()); row.setRevoked(false);
        repository.save(row);
        audit.logChange(RequestActor.current(), "PC_AGENT_REGISTERED", AppConstants.AuditResource.CARTON_LOADING,
                id, "Factory=" + factory + "; token never logged", AppConstants.Buyer.LULULEMON, factory,
                null, java.util.Map.of("agentId", id, "displayName", label), null, null);
        return new Enrollment(factory, id, token, label);
    }
    public List<PcStatus> list(String factoryCode) {
        String factory = normFactory(factoryCode);
        return repository.findByFactoryCodeOrderByDisplayNameAsc(factory).stream()
                .filter(r -> !r.isRevoked()).map(r -> {
                    Instant beat = beats.get(r.getId());
                    return new PcStatus(factory, r.getAgentId(), r.getDisplayName(),
                            beat != null && beat.isAfter(Instant.now().minus(ONLINE_WINDOW)), beat);
                }).toList();
    }
    public void revoke(String factoryCode, String agentId) {
        String factory = normFactory(factoryCode);
        var row = require(factory, agentId);
        // Do not unpair the PC holding a current Scale reservation (avoid rerouting a carton mid-weigh).
        Instant now = Instant.now();
        for (ScalePlcDevice scale : scales.findAll()) {
            if (factory.equalsIgnoreCase(scale.getFactoryCode()) && agentId.equals(scale.getSerialAgentId()) &&
                scale.getActiveCartonSscc() != null && scale.getActiveUntil() != null && scale.getActiveUntil().isAfter(now))
                throw new IllegalArgumentException("Complete active carton weighing before revoking this PC Agent");
        }
        row.setRevoked(true); row.setSecretHash(null); repository.save(row);
        beats.remove(row.getId()); leases.remove(row.getId());
        Pending old = waiting.remove(row.getId());
        if (old != null) old.response().completeExceptionally(new IOException("PC Agent revoked"));
        audit.logChange(RequestActor.current(), "PC_AGENT_REVOKED", AppConstants.AuditResource.CARTON_LOADING,
                agentId, "Factory=" + factory, AppConstants.Buyer.LULULEMON, factory,
                null, java.util.Map.of("agentId", agentId), null, null);
    }
    private FactoryPcAgentRegistration authenticated(String factory, String agentId, String token) {
        FactoryPcAgentRegistration row;
        try { row = require(factory, agentId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PC Agent credentials"); }
        if (token == null || token.length() > 160 || row.getSecretHash() == null ||
            !MessageDigest.isEqual(row.getSecretHash().getBytes(StandardCharsets.US_ASCII),
                sha(token).getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid PC Agent credentials");
        return row;
    }
    public Job poll(String factory, String agentId, String token, String instanceId) {
        var row = authenticated(factory, agentId, token);
        if (instanceId == null || !instanceId.matches("[0-9a-fA-F-]{36}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Agent instance ID");
        Instant now = Instant.now();
        leases.compute(row.getId(), (key, current) -> {
            if (current != null && current.seen().isAfter(now.minus(ONLINE_WINDOW)) &&
                !current.instanceId().equals(instanceId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Another instance of this PC Agent is active");
            return new Lease(instanceId, now);
        });
        beats.put(row.getId(), now);
        if (row.getLastSeen() == null || row.getLastSeen().isBefore(now.minusSeconds(5))) {
            mongo.updateFirst(new Query(Criteria.where("_id").is(row.getId()).and("agentId").is(agentId).and("revoked").is(false)),
                    new Update().set("lastSeen", now), FactoryPcAgentRegistration.class);
        }
        Pending p = waiting.get(row.getId());
        if (p == null || !p.handedOut().compareAndSet(false, true)) return null;
        return p.job();
    }
    public void result(String factory, String agentId, String token, String instanceId, Result result) {
        var row = authenticated(factory, agentId, token);
        Lease lease = leases.get(row.getId());
        if (lease == null || !lease.instanceId().equals(instanceId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PC Agent instance is no longer active");
        Pending p = waiting.get(row.getId());
        if (p == null || result == null || !p.job().jobId().equals(result.jobId()) || !p.handedOut().get())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unknown or expired Agent job");
        if (result.success() && "READ_D".equals(p.job().command()) && result.rawValue() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing PLC value");
        if (result.success() && "LIST_PORTS".equals(p.job().command()) && result.ports() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing COM port list");
        p.response().complete(result);
    }
    private Result submit(String factory, String agentId, Job job) throws IOException {
        var row = require(factory, agentId);
        Instant heartbeat = beats.get(row.getId());
        if (heartbeat == null || heartbeat.isBefore(Instant.now().minus(ONLINE_WINDOW)))
            throw new IOException("PC Agent " + agentId + " is offline");
        synchronized (locks.computeIfAbsent(row.getId(), ignored -> new Object())) {
            Pending p = new Pending(job, new CompletableFuture<>(), new AtomicBoolean());
            if (waiting.putIfAbsent(row.getId(), p) != null) throw new IOException("PC Agent is busy with another operation");
            try {
                Result result = p.response().get(9, TimeUnit.SECONDS);
                if (!result.success()) throw new IOException("PC Agent operation failed: " +
                    (result.error() == null ? "No detail" : result.error().substring(0, Math.min(result.error().length(), 180))));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IOException("PC Agent request interrupted", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("PC Agent request timed out or was cancelled", e);
            } finally { waiting.remove(row.getId(), p); }
        }
    }
    public List<String> ports(String factory, String agentId) throws IOException {
        Job job = new Job(UUID.randomUUID().toString(), "LIST_PORTS", "", 0, 0, "", 0, 0, 0, "");
        List<String> ports = submit(factory, agentId, job).ports();
        if (ports.size() > 256 || ports.stream().anyMatch(p -> p == null || !p.matches("COM[1-9][0-9]{0,2}")))
            throw new IOException("PC Agent returned an invalid COM list");
        return ports;
    }
    public long read(String factory, String agentId, String com, int baud, int dataBits, String parity,
                     int stops, int station, int register, String encoding) throws IOException {
        if (!com.matches("(?i)COM[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid Windows COM port");
        Job job = new Job(UUID.randomUUID().toString(), "READ_D", com.toUpperCase(Locale.ROOT),
                baud, dataBits, parity, stops, station, register, encoding);
        return Objects.requireNonNull(submit(factory, agentId, job).rawValue());
    }
}
