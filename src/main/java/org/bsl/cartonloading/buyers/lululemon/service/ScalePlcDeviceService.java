package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.plc.ModbusTcpReader;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.dao.DuplicateKeyException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Pattern;

@Service
public class ScalePlcDeviceService {
    private static final Pattern STATION = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private final ScalePlcDeviceRepository devices;
    private final UserRepository users;
    private final FactoryCatalogService factories;
    private final AuditLogService audit;
    private final AppSocketPublisher events;
    private final MongoTemplate mongo;
    public static final String SIMULATOR = "SIMULATOR";
    public static final String MODBUS_TCP = "MODBUS_TCP";

    public record Settings(String stationCode, String name, String factoryCode, String host, int port,
                           int unitId, int registerAddress, String encoding, double kgPerUnit,
                           boolean enabled, List<String> assignedUserIds, String connectionType) { }
    public record AssignedStation(String stationCode, String stationName, String factoryCode, String connectionType) { }
    public record Reading(String stationCode, String factoryCode, String mode, long rawValue,
                          BigDecimal weightKg, Instant readAt, String status, String eventId, boolean stable, boolean consumed) { }

    public ScalePlcDeviceService(ScalePlcDeviceRepository devices, UserRepository users,
            FactoryCatalogService factories, AuditLogService audit, AppSocketPublisher events, MongoTemplate mongo) {
        this.devices = devices;
        this.users = users;
        this.factories = factories;
        this.audit = audit;
        this.events = events;
        this.mongo = mongo;
    }

    /** Create one inactive-for-operators (unassigned) connected simulator for each active Factory.
     * No user is granted device access automatically; an Admin must explicitly assign them.
     */
    private void seedSimulators() {
        for (org.bsl.cartonloading.model.Factory factory : factories.list(AppConstants.Buyer.LULULEMON, false)) {
            String id = "SCALE-SIM-" + factory.getCode();
            if (!devices.existsById(id)) {
                ScalePlcDevice sim = new ScalePlcDevice();
                sim.setStationCode(id);
                sim.setBuyerKey(AppConstants.Buyer.LULULEMON);
                sim.setFactoryCode(factory.getCode());
                sim.setName("PLC Scale Simulator " + factory.getCode());
                sim.setConnectionType(SIMULATOR);
                sim.setEnabled(true);
                // Existing station is never overwritten when seed is called again.
                try { devices.insert(sim); }
                catch (DuplicateKeyException concurrentSeed) { /* Another request created it first. */ }
            }
        }
    }

    public List<ScalePlcDevice> list() {
        seedSimulators();
        return devices.findAll().stream()
            .filter(d -> AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey()))
            .sorted(Comparator.comparing(ScalePlcDevice::getStationCode)).toList();
    }

    /** Users see assigned stations only, with no write access to their Modbus settings. */
    public List<AssignedStation> mine() {
        User user = currentUser();
        if (!user.canAccessBuyer(AppConstants.Buyer.LULULEMON)) return List.of();
        return list().stream().filter(ScalePlcDevice::isEnabled)
            .filter(d -> user.isAdminRole() || (user.canWeightCheck() && user.canAccessWeighingFactory(d.getFactoryCode())
                    && d.getAssignedUserIds() != null && d.getAssignedUserIds().contains(user.getId())))
            .map(d -> new AssignedStation(d.getStationCode(), d.getName(), d.getFactoryCode(), mode(d))).toList();
    }

    public ScalePlcDevice save(String id, Settings s) {
        if (s == null || id == null || !STATION.matcher(id).matches() || !id.equals(s.stationCode()))
            throw new IllegalArgumentException("Invalid station code");
        String factory = s.factoryCode() == null ? "" : s.factoryCode().trim().toUpperCase();
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory code");
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        if (s.name() == null || s.name().isBlank() || s.name().length() > 100)
            throw new IllegalArgumentException("Enter station name (1–100 characters)");
        String type = s.connectionType() == null || s.connectionType().isBlank() ? MODBUS_TCP : s.connectionType().trim();
        if (!SIMULATOR.equals(type) && !MODBUS_TCP.equals(type)) throw new IllegalArgumentException("Invalid connection type");
        if (MODBUS_TCP.equals(type)) validatePrivateHost(s.host());
        if (s.port() < 1 || s.port() > 65535 || s.unitId() < 0 || s.unitId() > 247)
            throw new IllegalArgumentException("Invalid Modbus port or Unit ID");
        int words = AppConstants.Plc.INT32.equals(s.encoding()) || AppConstants.Plc.UINT32.equals(s.encoding()) ? 2 : 1;
        if (!List.of(AppConstants.Plc.INT16, AppConstants.Plc.UINT16, AppConstants.Plc.INT32, AppConstants.Plc.UINT32).contains(s.encoding())
             || s.registerAddress() < 0 || s.registerAddress() + words > 65536)
            throw new IllegalArgumentException("Invalid Modbus register configuration");
        if (!Double.isFinite(s.kgPerUnit()) || s.kgPerUnit() <= 0 || s.kgPerUnit() > 1000)
            throw new IllegalArgumentException("Invalid kg-per-unit multiplier");
        Set<String> assigned = new LinkedHashSet<>();
        if (s.assignedUserIds() != null) for (String userId : s.assignedUserIds()) {
            User user = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
            if (!user.isEnabled() || !user.canAccessBuyer(AppConstants.Buyer.LULULEMON)
                || !user.canWeightCheck() || !user.canAccessWeighingFactory(factory))
                throw new IllegalArgumentException("User must be enabled with Weight Check and Factory " + factory);
            assigned.add(userId);
        }
        ScalePlcDevice d = devices.findById(id).orElseGet(ScalePlcDevice::new);
        d.setStationCode(id);
        d.setBuyerKey(AppConstants.Buyer.LULULEMON);
        d.setFactoryCode(factory); d.setName(s.name().trim());
        d.setConnectionType(type); d.setHost(s.host() == null ? "" : s.host().trim());
        d.setPort(s.port()); d.setUnitId(s.unitId()); d.setRegisterAddress(s.registerAddress());
        d.setEncoding(s.encoding()); d.setKgPerUnit(s.kgPerUnit()); d.setEnabled(s.enabled());
        d.setAssignedUserIds(List.copyOf(assigned));
        audit.log(RequestActor.current(), "SCALE_DEVICE_SETTINGS", AppConstants.AuditResource.CARTON_LOADING, id,
            "Factory=" + factory + "; assigned users=" + assigned.size(), null, null);
        ScalePlcDevice saved = devices.save(d);
        events.organizationChanged(AppConstants.OrganizationRealtime.USER, "SCALE_DEVICE_SETTINGS");
        return saved;
    }

    public void delete(String code) {
        ScalePlcDevice d = require(code);
        if (SIMULATOR.equals(mode(d)) && ("SCALE-SIM-" + d.getFactoryCode()).equals(code))
            throw new IllegalArgumentException("Default Simulator cannot be removed. Disable it instead.");
        audit.log(RequestActor.current(), "SCALE_DEVICE_REMOVE", AppConstants.AuditResource.CARTON_LOADING, code,
            "Factory=" + d.getFactoryCode() + "; saved carton history remains unchanged", null, null);
        devices.delete(d); // Configuration only, no weighing results are deleted.
        events.organizationChanged(AppConstants.OrganizationRealtime.USER, "SCALE_DEVICE_REMOVE");
    }

    public Reading read(String code) throws IOException {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        if (!d.isEnabled()) throw new IllegalArgumentException("Station is disabled");
        if (!user.isAdminRole() && (!user.canWeightCheck() || !user.canAccessWeighingFactory(d.getFactoryCode())
                || d.getAssignedUserIds() == null || !d.getAssignedUserIds().contains(user.getId())))
            throw new org.springframework.security.access.AccessDeniedException("Station not assigned to this user");
        if (SIMULATOR.equals(mode(d))) {
            BigDecimal kg = d.getSimulatedWeightKg();
            return new Reading(d.getStationCode(), d.getFactoryCode(), SIMULATOR, 0,
                kg, d.getSimulatedAt(), "SIMULATED / CONNECTED", d.getSimulatedEventId(), kg != null, d.getSimulatedConsumedAt() != null);
        }
        validatePrivateHost(d.getHost());
        long raw = ModbusTcpReader.read(d.getHost(), d.getPort(), d.getUnitId(), d.getRegisterAddress(), d.getEncoding());
        BigDecimal kg = BigDecimal.valueOf(raw).multiply(BigDecimal.valueOf(d.getKgPerUnit())).setScale(3, RoundingMode.HALF_UP);
        return new Reading(d.getStationCode(), d.getFactoryCode(), "MODBUS_TCP", raw, kg, Instant.now(), "CONNECTED", null, false, false);
    }

    private static String mode(ScalePlcDevice d) {
        return SIMULATOR.equals(d.getConnectionType()) ? SIMULATOR : MODBUS_TCP;
    }

    /** Reserve the assigned station for exactly one RFID-finished carton.
     * A new carton cannot take over a live reservation for another carton.
     * Re-arming the same SSCC extends its lease without discarding readings.
     */
    public void armSimulator(String code, String sscc, String factoryCode) {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        if (!d.isEnabled() || !SIMULATOR.equals(mode(d)) || !d.getFactoryCode().equalsIgnoreCase(factoryCode))
            throw new IllegalArgumentException("Enabled assigned Simulator for this Factory required");
        if (!user.isAdminRole() && (!user.canWeightCheck() || !user.canAccessWeighingFactory(factoryCode)
                || d.getAssignedUserIds() == null || !d.getAssignedUserIds().contains(user.getId())))
            throw new org.springframework.security.access.AccessDeniedException("Scale not assigned");
        Instant now = Instant.now();
        Query q = new Query(new Criteria().andOperator(Criteria.where("_id").is(code),
                new Criteria().orOperator(Criteria.where("activeCartonSscc").is(sscc),
                        Criteria.where("activeCartonSscc").is(null), Criteria.where("activeUntil").lt(now))));
        ScalePlcDevice armed = mongo.findAndModify(q,
                new Update().set("activeCartonSscc", sscc).set("activeUntil", now.plusSeconds(60)),
                FindAndModifyOptions.options().returnNew(true), ScalePlcDevice.class);
        if (armed == null) throw new IllegalArgumentException("Scale busy with another carton; complete or wait for reservation to expire");
    }

    public Reading simulate(String code, BigDecimal kg) {
        ScalePlcDevice d = require(code);
        if (!d.isEnabled() || !SIMULATOR.equals(mode(d))) throw new IllegalArgumentException("Enabled simulator required");
        if (kg == null || kg.signum() <= 0 || kg.compareTo(new BigDecimal("10000")) > 0
                || kg.scale() > 3) throw new IllegalArgumentException("Simulator weight must be 0.001–10000 kg, max 3 decimal places");
        if (d.getActiveCartonSscc() == null || d.getActiveUntil() == null
                || !d.getActiveUntil().isAfter(Instant.now()))
            throw new IllegalArgumentException("No RFID-completed carton waiting on this simulator; finish RFID first");
        Instant now = Instant.now();
        Query q = new Query(Criteria.where("_id").is(code).and("enabled").is(true)
            .and("activeCartonSscc").is(d.getActiveCartonSscc()).and("activeUntil").gt(now));
        ScalePlcDevice updated = mongo.findAndModify(q, new Update()
                .set("simulatedCartonSscc", d.getActiveCartonSscc())
                .set("simulatedWeightKg", kg.setScale(3, RoundingMode.HALF_UP))
                .set("simulatedAt", now).set("simulatedEventId", UUID.randomUUID().toString())
                .unset("simulatedConsumedAt"), FindAndModifyOptions.options().returnNew(true), ScalePlcDevice.class);
        if (updated == null) throw new IllegalArgumentException("Simulator reservation changed; retry on the active carton");
        audit.log(RequestActor.current(), "SCALE_SIMULATOR_PUBLISH", AppConstants.AuditResource.CARTON_LOADING, code,
            "SIMULATED weight " + kg + " kg for active SSCC " + updated.getActiveCartonSscc(), null, null);
        return readSimulator(updated);
    }

    private Reading readSimulator(ScalePlcDevice d) {
        return new Reading(d.getStationCode(), d.getFactoryCode(), SIMULATOR, 0,
            d.getSimulatedWeightKg(), d.getSimulatedAt(), "SIMULATED / CONNECTED", d.getSimulatedEventId(),
            d.getSimulatedWeightKg() != null, d.getSimulatedConsumedAt() != null);
    }

    /** Atomic single-use claim: a simulator event cannot be applied to two cartons. */
    public Reading claimSimulator(String code, String eventId, String sscc, String factoryCode, java.time.LocalDateTime rfidFinishedAt) {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        if (!d.isEnabled() || !SIMULATOR.equals(mode(d))) throw new IllegalArgumentException("An assigned simulator is required");
        if (!d.getFactoryCode().equalsIgnoreCase(factoryCode)) throw new org.springframework.security.access.AccessDeniedException("Wrong Factory scale");
        if (!user.isAdminRole() && (!user.canWeightCheck() || !user.canAccessWeighingFactory(d.getFactoryCode())
                || d.getAssignedUserIds() == null || !d.getAssignedUserIds().contains(user.getId())))
            throw new org.springframework.security.access.AccessDeniedException("Simulator not assigned to user");
        if (!sscc.equals(d.getActiveCartonSscc()) || !sscc.equals(d.getSimulatedCartonSscc())
                || d.getActiveUntil() == null || !d.getActiveUntil().isAfter(Instant.now()))
            throw new IllegalArgumentException("Simulator event does not belong to this active carton");
        if (eventId == null || eventId.isBlank() || rfidFinishedAt == null || d.getSimulatedAt() == null
                || !d.getSimulatedAt().isAfter(rfidFinishedAt.atZone(java.time.ZoneId.systemDefault()).toInstant()))
            throw new IllegalArgumentException("Send a NEW simulator weight after RFID is finished for this carton");
        Query q = new Query(Criteria.where("_id").is(code)
                .and("simulatedEventId").is(eventId).and("simulatedCartonSscc").is(sscc)
                .and("activeCartonSscc").is(sscc).and("activeUntil").gt(Instant.now())
                .and("simulatedConsumedAt").is(null));
        ScalePlcDevice claimed = mongo.findAndModify(q, new Update().set("simulatedConsumedAt", Instant.now()),
                FindAndModifyOptions.options().returnNew(true), ScalePlcDevice.class);
        if (claimed == null) throw new IllegalArgumentException("Simulator reading already used or replaced");
        return readSimulator(claimed);
    }

    public void releaseSimulator(String code, String sscc) {
        Query q = new Query(Criteria.where("_id").is(code).and("activeCartonSscc").is(sscc));
        mongo.updateFirst(q, new Update().unset("activeCartonSscc").unset("activeUntil"), ScalePlcDevice.class);
    }

    public void undoSimulatorClaim(String code, String eventId) {
        Query q = new Query(Criteria.where("_id").is(code).and("simulatedEventId").is(eventId));
        mongo.updateFirst(q, new Update().unset("simulatedConsumedAt"), ScalePlcDevice.class);
    }

    private ScalePlcDevice require(String code) {
        ScalePlcDevice d = devices.findById(code).orElseThrow(() -> new IllegalArgumentException("Scale station not found"));
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey()))
            throw new IllegalArgumentException("Scale station belongs to another buyer");
        return d;
    }

    private User currentUser() {
        User user = users.findByEmail(RequestActor.current()).orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("Login required"));
        if (!user.isEnabled()) throw new org.springframework.security.access.AccessDeniedException("User disabled");
        return user;
    }

    /** Restrict outbound connections to literal IPv4 LAN/loopback addresses; never resolve arbitrary DNS names. */
    private static void validatePrivateHost(String host) {
        if (host == null || !IPV4.matcher(host.trim()).matches()) throw new IllegalArgumentException("Use a private IPv4 address (e.g. 192.168.1.25)");
        String[] parts = host.trim().split("\\.");
        int[] octets = new int[4];
        try { for (int i = 0; i < 4; i++) { octets[i] = Integer.parseInt(parts[i]); if (octets[i] > 255) throw new NumberFormatException(); } }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid IPv4 address"); }
        if (!(octets[0] == 10 || octets[0] == 127 || (octets[0] == 192 && octets[1] == 168)
              || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)))
            throw new IllegalArgumentException("Only private/local IPv4 network addresses are allowed");
    }
}
