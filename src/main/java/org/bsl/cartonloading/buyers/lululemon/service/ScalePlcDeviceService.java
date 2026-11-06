package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.persistence.SharedDeviceConfigurationStore;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.plc.ModbusTcpReader;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.SelectedFactoryScope;
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
    private final SharedDeviceConfigurationStore sharedDevices;
    private final UserRepository users;
    private final FactoryCatalogService factories;
    private final AuditLogService audit;
    private final AppSocketPublisher events;
    private final MongoTemplate mongo;
    private final SelectedFactoryScope selectedFactory;
    public static final String SIMULATOR = AppConstants.DeviceConnection.SIMULATOR;
    public static final String MODBUS_TCP = AppConstants.DeviceConnection.MODBUS_TCP;

    public record Settings(String stationCode, String name, String factoryCode, String host, int port,
                           int unitId, int registerAddress, String encoding, double kgPerUnit,
                           boolean enabled, List<String> assignedUserIds, String connectionType, Integer stableRegisterAddress) { }
    public record AssignedStation(String stationCode, String stationName, String factoryCode, String connectionType,
                                  boolean stableConfigured) { }
    public record Reading(String stationCode, String factoryCode, String mode, long rawValue,
                          BigDecimal weightKg, Instant readAt, String status, String eventId, boolean stable, boolean consumed) { }

    public ScalePlcDeviceService(ScalePlcDeviceRepository devices, UserRepository users,
            FactoryCatalogService factories, AuditLogService audit, AppSocketPublisher events, MongoTemplate mongo,
            SelectedFactoryScope selectedFactory,
            SharedDeviceConfigurationStore sharedDevices) {
        this.devices = devices;
        this.sharedDevices = sharedDevices;
        this.users = users;
        this.factories = factories;
        this.audit = audit;
        this.events = events;
        this.mongo = mongo;
        this.selectedFactory = selectedFactory;
    }

    /** Seed one simulator per Factory; operators access it through their User Management permission. */
    private synchronized void seedSimulators() {
        for (Factory factory : factories.list(AppConstants.Buyer.LULULEMON, false)) {
            String id = "SCALE-SIM-" + factory.getCode();
            // One Scale PLC profile per Factory. Do not seed a second simulator when a
            // previously configured real PLC already exists for this Factory.
            boolean hasFactoryProfile = devices.findAll().stream().anyMatch(d ->
                AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())
                && factory.getCode().equalsIgnoreCase(d.getFactoryCode()));
            if (!hasFactoryProfile && !devices.existsById(id)) {
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

    /** Operators see enabled Factory stations when User Management permits Weight Check. */
    public List<AssignedStation> mine() {
        User user = currentUser();
        if (!user.canAccessBuyer(AppConstants.Buyer.LULULEMON)) return List.of();
        String activeFactory = selectedFactory.requested(AppConstants.Buyer.LULULEMON);
        return list().stream().filter(ScalePlcDevice::isEnabled)
            .filter(d -> selectedFactory.matches(activeFactory, d.getFactoryCode()))
            .filter(d -> user.isAdminRole() || (user.canLululemonScanWeight() && user.canAccessFactory(d.getFactoryCode())))
            .map(d -> new AssignedStation(d.getStationCode(), d.getName(), d.getFactoryCode(), mode(d),
                    d.getStableRegisterAddress() != null)).toList();
    }

    /** Read only: validate connection against unsaved settings without database writes/arm/claim. */
    public Reading testConnection(Settings s) throws IOException {
        if (s == null) throw new IllegalArgumentException("PLC settings required");
        String factory = s.factoryCode() == null ? "" : s.factoryCode().trim().toUpperCase();
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory code");
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        // Admin-only preview: target Factory comes from the row, not the UI-wide Factory filter.
        String mode = s.connectionType() == null ? "" : s.connectionType().trim().toUpperCase();
        if (SIMULATOR.equals(mode)) {
            return new Reading(s.stationCode(), factory, SIMULATOR, 0, null, Instant.now(),
                "SIMULATOR CONFIGURATION VALID (no physical PLC tested)", null, false, false);
        }
        if (!MODBUS_TCP.equals(mode)) throw new IllegalArgumentException("Invalid PLC connection type");
        validatePrivateHost(s.host());
        if (s.port() < 1 || s.port() > 65535 || s.unitId() < 0 || s.unitId() > 247)
            throw new IllegalArgumentException("Invalid Modbus port or Unit ID");
        if (!List.of(AppConstants.Plc.INT16, AppConstants.Plc.UINT16, AppConstants.Plc.INT32, AppConstants.Plc.UINT32).contains(s.encoding()))
            throw new IllegalArgumentException("Invalid PLC encoding");
        int words = AppConstants.Plc.INT32.equals(s.encoding()) || AppConstants.Plc.UINT32.equals(s.encoding()) ? 2 : 1;
        if (s.registerAddress() < 0 || s.registerAddress() + words > 65536)
            throw new IllegalArgumentException("Invalid Modbus register address");
        if (!Double.isFinite(s.kgPerUnit()) || s.kgPerUnit() <= 0 || s.kgPerUnit() > 1000)
            throw new IllegalArgumentException("Invalid kg multiplier");
        if (s.stableRegisterAddress() != null && (s.stableRegisterAddress() < 0 || s.stableRegisterAddress() >= 65536))
            throw new IllegalArgumentException("Invalid stable register address");
        long raw = ModbusTcpReader.read(s.host().trim(), s.port(), s.unitId(), s.registerAddress(), s.encoding());
        BigDecimal kg = BigDecimal.valueOf(raw).multiply(BigDecimal.valueOf(s.kgPerUnit()))
            .setScale(3, RoundingMode.HALF_UP);
        if (s.stableRegisterAddress() == null) {
            return new Reading(s.stationCode(), factory, MODBUS_TCP, raw, kg, Instant.now(),
                "CONNECTED / READ ONLY / PREVIEW (no stable register)", null, false, false);
        }
        long flag = ModbusTcpReader.read(s.host().trim(), s.port(), s.unitId(), s.stableRegisterAddress(), AppConstants.Plc.UINT16);
        if (flag != 0 && flag != 1) throw new IllegalArgumentException("Scale PLC stable Holding Register returned " + flag + "; expected 0 or 1. Check Stable Register address and Unit ID.");
        return new Reading(s.stationCode(), factory, MODBUS_TCP, raw, kg, Instant.now(),
            flag == 1 ? "CONNECTED / READ ONLY / STABLE" : "CONNECTED / READ ONLY / UNSTABLE",
            null, flag == 1, false);
    }

    public synchronized ScalePlcDevice save(String id, Settings s) {
        if (s == null || id == null || !STATION.matcher(id).matches() || !id.equals(s.stationCode()))
            throw new IllegalArgumentException("Invalid station code");
        String factory = s.factoryCode() == null ? "" : s.factoryCode().trim().toUpperCase();
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory code");
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        // Factory has exactly one PLC profile. Preserve existing legacy records,
        // but refuse new duplicates and forbid changing a device's Factory.
        ScalePlcDevice previous = devices.findById(id).orElse(null);
        if (previous != null && !factory.equalsIgnoreCase(previous.getFactoryCode()))
            throw new IllegalArgumentException("A PLC Station Code cannot be moved to another Factory");
        if (previous == null && devices.findAll().stream().anyMatch(d ->
                AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())
                && factory.equalsIgnoreCase(d.getFactoryCode())))
            throw new IllegalArgumentException("Factory " + factory + " already has a PLC. Edit its existing configuration instead.");
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
        if (s.stableRegisterAddress() != null && (s.stableRegisterAddress() < 0 || s.stableRegisterAddress() >= 65536))
            throw new IllegalArgumentException("Invalid 0-based stable register address");
        if (!Double.isFinite(s.kgPerUnit()) || s.kgPerUnit() <= 0 || s.kgPerUnit() > 1000)
            throw new IllegalArgumentException("Invalid kg-per-unit multiplier");
        // Device access is governed by User Management, not legacy assignedUserIds.
        java.util.Map<String, Object> before = audit.snapshot(previous);
        ScalePlcDevice d = devices.findById(id).orElseGet(ScalePlcDevice::new);
        d.setStationCode(id);
        d.setBuyerKey(AppConstants.Buyer.LULULEMON);
        d.setFactoryCode(factory); d.setName(s.name().trim());
        d.setConnectionType(type); d.setHost(s.host() == null ? "" : s.host().trim());
        d.setPort(s.port()); d.setUnitId(s.unitId()); d.setRegisterAddress(s.registerAddress());
        d.setEncoding(s.encoding()); d.setKgPerUnit(s.kgPerUnit()); d.setEnabled(s.enabled());
        d.setStableRegisterAddress(s.stableRegisterAddress());
        d.setAssignedUserIds(List.of());
        ScalePlcDevice saved = devices.save(d);
        audit.logChange(RequestActor.current(), "SCALE_DEVICE_SETTINGS", AppConstants.AuditResource.CARTON_LOADING, id,
            "Factory=" + factory + "; permissions managed in User Management", AppConstants.Buyer.LULULEMON, factory,
            before, saved, null, null);
        events.organizationChanged(AppConstants.OrganizationRealtime.USER, "SCALE_DEVICE_SETTINGS");
        return saved;
    }

    public void delete(String code) {
        ScalePlcDevice d = require(code);
        if (SIMULATOR.equals(mode(d)) && ("SCALE-SIM-" + d.getFactoryCode()).equals(code))
            throw new IllegalArgumentException("Default Simulator cannot be removed. Disable it instead.");
        devices.delete(d);
        audit.logChange(RequestActor.current(), "SCALE_DEVICE_REMOVE", AppConstants.AuditResource.CARTON_LOADING, code,
            "Factory=" + d.getFactoryCode() + "; saved carton history remains unchanged", AppConstants.Buyer.LULULEMON,
            d.getFactoryCode(), d, null, null, null); // Configuration only, no weighing results are deleted.
        events.organizationChanged(AppConstants.OrganizationRealtime.USER, "SCALE_DEVICE_REMOVE");
    }

    public Reading read(String code) throws IOException {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        if (!d.isEnabled()) throw new IllegalArgumentException("Station is disabled");
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(d.getFactoryCode())))
            throw new org.springframework.security.access.AccessDeniedException("User lacks Scale access for this Factory");
        if (SIMULATOR.equals(mode(d))) {
            BigDecimal kg = d.getSimulatedWeightKg();
            return new Reading(d.getStationCode(), d.getFactoryCode(), SIMULATOR, 0,
                kg, d.getSimulatedAt(), "SIMULATED / CONNECTED", d.getSimulatedEventId(), kg != null, d.getSimulatedConsumedAt() != null);
        }
        validatePrivateHost(d.getHost());
        long raw = ModbusTcpReader.read(d.getHost(), d.getPort(), d.getUnitId(), d.getRegisterAddress(), d.getEncoding());
        BigDecimal kg = BigDecimal.valueOf(raw).multiply(BigDecimal.valueOf(d.getKgPerUnit())).setScale(3, RoundingMode.HALF_UP);
        // A stable flag is mandatory for auto-capture; old devices remain preview-only.
        if (d.getStableRegisterAddress() == null)
            return new Reading(d.getStationCode(), d.getFactoryCode(), MODBUS_TCP, raw, kg, Instant.now(),
                "CONNECTED / PREVIEW ONLY (stable register not configured)", null, false, false);
        long flag = ModbusTcpReader.read(d.getHost(), d.getPort(), d.getUnitId(), d.getStableRegisterAddress(), AppConstants.Plc.UINT16);
        boolean stable = flag == 1;
        if (flag != 0 && flag != 1) throw new IllegalArgumentException("Scale PLC stable Holding Register returned " + flag + "; expected 0 or 1. Check Stable Register address and Unit ID.");
        Instant now = Instant.now();
        if (d.getActiveCartonSscc() != null && d.getActiveUntil() != null && d.getActiveUntil().isAfter(now)) {
            if (!stable) {
                sharedDevices.updateScale(new Query(Criteria.where("_id").is(code).and("activeUntil").gt(now)
                        .and("activeCartonSscc").is(d.getActiveCartonSscc())),
                    new Update().set("modbusUnstableSeen", true));
            } else if (raw > 0 && d.isModbusUnstableSeen() && d.getModbusEventId() == null) {
                String eventId = UUID.randomUUID().toString();
                ScalePlcDevice latched = sharedDevices.findAndModifyScale(new Query(Criteria.where("_id").is(code)
                        .and("activeCartonSscc").is(d.getActiveCartonSscc()).and("activeUntil").gt(now)
                        .and("modbusUnstableSeen").is(true).and("modbusEventId").is(null)),
                    new Update().set("modbusEventId", eventId).set("modbusAt", now)
                        .set("modbusWeightKg", kg).unset("modbusConsumedAt"));
                if (latched != null) d = latched;
            }
        }
        return new Reading(d.getStationCode(), d.getFactoryCode(), MODBUS_TCP, raw, kg, now,
            stable ? "CONNECTED / STABLE" : "CONNECTED / UNSTABLE", d.getModbusEventId(), stable,
            d.getModbusConsumedAt() != null);
    }

    public boolean isModbus(String code) {
        ScalePlcDevice d = require(code);
        return MODBUS_TCP.equals(mode(d));
    }

    /** Arm only after an RFID attempt finished. Require a fresh 0->1 stable edge for each SSCC.
     * A stable weight left on the scale before arming is NEVER accepted.
     */
    public void armModbus(String code, String sscc, String factoryCode) {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        if (!d.isEnabled() || !MODBUS_TCP.equals(mode(d)) || d.getStableRegisterAddress() == null
                || !d.getFactoryCode().equalsIgnoreCase(factoryCode))
            throw new IllegalArgumentException("Enabled Factory Modbus scale with stable register required");
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(factoryCode)))
            throw new org.springframework.security.access.AccessDeniedException("User lacks Modbus scale access for this Factory");
        Instant now = Instant.now();
        // Same-carton re-arm preserves the latch and only extends the lease.
        Query same = new Query(Criteria.where("_id").is(code).and("activeCartonSscc").is(sscc));
        ScalePlcDevice extended = sharedDevices.findAndModifyScale(same, new Update().set("activeUntil", now.plusSeconds(60)));
        if (extended != null) return;
        Query available = new Query(new Criteria().andOperator(Criteria.where("_id").is(code),
            new Criteria().orOperator(Criteria.where("activeCartonSscc").is(null), Criteria.where("activeUntil").lt(now))));
        ScalePlcDevice armed = sharedDevices.findAndModifyScale(available, new Update()
            .set("activeCartonSscc", sscc).set("activeUntil", now.plusSeconds(60))
            .set("modbusUnstableSeen", false).unset("modbusEventId").unset("modbusAt")
            .unset("modbusWeightKg").unset("modbusConsumedAt"));
        if (armed == null) throw new IllegalArgumentException("Scale busy with another carton");
    }

    public Reading claimModbus(String code, String eventId, String sscc, String factoryCode,
                               java.time.LocalDateTime rfidFinishedAt) {
        ScalePlcDevice d = require(code);
        User user = currentUser();
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        if (!d.isEnabled() || !MODBUS_TCP.equals(mode(d)) || !d.getFactoryCode().equalsIgnoreCase(factoryCode))
            throw new IllegalArgumentException("Wrong Factory Modbus scale");
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(factoryCode)))
            throw new org.springframework.security.access.AccessDeniedException("User lacks Modbus scale access for this Factory");
        if (rfidFinishedAt == null || eventId == null || eventId.isBlank() || d.getModbusAt() == null
                || !d.getModbusAt().isAfter(rfidFinishedAt.atZone(java.time.ZoneId.systemDefault()).toInstant()))
            throw new IllegalArgumentException("A fresh stable weight after RFID completion is required");
        Query q = new Query(Criteria.where("_id").is(code).and("modbusEventId").is(eventId)
                .and("activeCartonSscc").is(sscc).and("activeUntil").gt(Instant.now())
                .and("modbusConsumedAt").is(null));
        ScalePlcDevice claimed = sharedDevices.findAndModifyScale(q, new Update().set("modbusConsumedAt", Instant.now()));
        if (claimed == null) throw new IllegalArgumentException("Modbus weight already used or belongs to another carton");
        return new Reading(code, factoryCode, MODBUS_TCP, 0, claimed.getModbusWeightKg(),
                claimed.getModbusAt(), "CAPTURED", eventId, true, true);
    }

    public void undoModbusClaim(String code, String eventId) {
        sharedDevices.updateScale(new Query(Criteria.where("_id").is(code).and("modbusEventId").is(eventId)),
            new Update().unset("modbusConsumedAt"));
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
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        if (!d.isEnabled() || !SIMULATOR.equals(mode(d)) || !d.getFactoryCode().equalsIgnoreCase(factoryCode))
            throw new IllegalArgumentException("Enabled Simulator for this Factory required");
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(factoryCode)))
            throw new org.springframework.security.access.AccessDeniedException("User lacks Scale access for this Factory");
        Instant now = Instant.now();
        Query q = new Query(new Criteria().andOperator(Criteria.where("_id").is(code),
                new Criteria().orOperator(Criteria.where("activeCartonSscc").is(sscc),
                        Criteria.where("activeCartonSscc").is(null), Criteria.where("activeUntil").lt(now))));
        ScalePlcDevice armed = sharedDevices.findAndModifyScale(q,
                new Update().set("activeCartonSscc", sscc).set("activeUntil", now.plusSeconds(60)));
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
        ScalePlcDevice updated = sharedDevices.findAndModifyScale(q, new Update()
                .set("simulatedCartonSscc", d.getActiveCartonSscc())
                .set("simulatedWeightKg", kg.setScale(3, RoundingMode.HALF_UP))
                .set("simulatedAt", now).set("simulatedEventId", UUID.randomUUID().toString())
                .unset("simulatedConsumedAt"));
        if (updated == null) throw new IllegalArgumentException("Simulator reservation changed; retry on the active carton");
        audit.logChange(RequestActor.current(), "SCALE_SIMULATOR_PUBLISH", AppConstants.AuditResource.CARTON_LOADING, code,
            "SIMULATED weight " + kg + " kg for active SSCC " + updated.getActiveCartonSscc(),
            AppConstants.Buyer.LULULEMON, updated.getFactoryCode(),
            java.util.Map.of("simulatedWeightKg", String.valueOf(d.getSimulatedWeightKg())),
            java.util.Map.of("simulatedWeightKg", String.valueOf(updated.getSimulatedWeightKg()),
                "cartonSscc", updated.getActiveCartonSscc()), null, null);
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
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        if (!d.isEnabled() || !SIMULATOR.equals(mode(d))) throw new IllegalArgumentException("An enabled simulator is required");
        if (!d.getFactoryCode().equalsIgnoreCase(factoryCode)) throw new org.springframework.security.access.AccessDeniedException("Wrong Factory scale");
        if (!user.isAdminRole() && (!user.canLululemonScanWeight() || !user.canAccessFactory(d.getFactoryCode())))
            throw new org.springframework.security.access.AccessDeniedException("User lacks Simulator access for this Factory");
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
        ScalePlcDevice claimed = sharedDevices.findAndModifyScale(q, new Update().set("simulatedConsumedAt", Instant.now()));
        if (claimed == null) throw new IllegalArgumentException("Simulator reading already used or replaced");
        return readSimulator(claimed);
    }

    public void releaseSimulator(String code, String sscc) {
        Query q = new Query(Criteria.where("_id").is(code).and("activeCartonSscc").is(sscc));
        sharedDevices.updateScale(q, new Update().unset("activeCartonSscc").unset("activeUntil"));
    }

    public void undoSimulatorClaim(String code, String eventId) {
        Query q = new Query(Criteria.where("_id").is(code).and("simulatedEventId").is(eventId));
        sharedDevices.updateScale(q, new Update().unset("simulatedConsumedAt"));
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
