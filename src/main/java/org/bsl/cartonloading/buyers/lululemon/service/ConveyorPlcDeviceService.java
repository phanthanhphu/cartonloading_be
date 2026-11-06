package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceCommandEvent;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceRuntimeSnapshot;
import org.bsl.cartonloading.buyers.lululemon.repository.DeviceCommandEventRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.DeviceRuntimeSnapshotRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.StationDeviceBindingRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ConveyorPlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.plc.ModbusCoilClient;
import org.bsl.cartonloading.buyers.lululemon.plc.ModbusHoldingRegisterWriter;
import org.bsl.cartonloading.buyers.lululemon.plc.ModbusTcpReader;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.SelectedFactoryScope;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.factory.FactoryCodes;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Deliberately limited to test ModbusPal, NOT an industrial safety/motion controller. */
@Service
public class ConveyorPlcDeviceService {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern IPV4 = Pattern.compile("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
    private final ConveyorPlcDeviceRepository devices;
    private final UserRepository users;
    private final FactoryCatalogService factories;
    private final AuditLogService audit;
    private final SelectedFactoryScope selectedFactory;
    private final DeviceCommandEventRepository commandEvents;
    private final DeviceRuntimeSnapshotRepository snapshots;
    private final StationDeviceBindingRepository bindings;
    private final ScalePlcDeviceRepository scales;
    // Disabled by default. Requires explicit lab flag AND exact target host allowlist.
    @Value("${lululemon.modbuspal-lab.enabled:false}") private boolean labEnabled;
    @Value("${lululemon.modbuspal-lab.host:}") private String labHost;
    @Value("${lululemon.modbuspal-lab.auto-motor-feedback:true}") private boolean autoMotorFeedback;

    public record Settings(String deviceCode, String factoryCode, String stationCode, String name,
         String host, int port, int unitId, int commandCoil, int motorFeedbackCoil,
         int rfidSensorCoil, int weightSensorCoil, int faultCoil,
         boolean enabled, List<String> assignedUserIds, String mode, String protocol, Long revision) {
        /** Retain existing diagnostic/lifecycle callers that do not Save a profile. */
        public Settings(String deviceCode, String factoryCode, String stationCode, String name,
                String host, int port, int unitId, int commandCoil, int motorFeedbackCoil,
                int rfidSensorCoil, int weightSensorCoil, int faultCoil,
                boolean enabled, List<String> assignedUserIds, String mode, String protocol) {
            this(deviceCode, factoryCode, stationCode, name, host, port, unitId, commandCoil,
                 motorFeedbackCoil, rfidSensorCoil, weightSensorCoil, faultCoil,
                 enabled, assignedUserIds, mode, protocol, null);
        }
    }
    public record Assigned(String deviceCode, String name, String stationCode, String factoryCode) { }
    public record Status(String deviceCode, String factoryCode, String stationCode, Instant readAt,
         boolean commandOn, boolean motorRunning, boolean rfidSensor, boolean weightSensor,
         boolean fault, String mode, String status) { }
    public record Command(String action) { }
    public record LabSignal(String signal, Boolean value) { }
    public record LabWeight(BigDecimal weightKg, boolean stable) { }
    public record LabAvailability(boolean enabled, String factoryCode, String deviceCode,
                                  String host, int port, int unitId, String message) { }
    public record LabWeightResult(BigDecimal weightKg, long rawValue, boolean stable,
                                  String message) { }

    public ConveyorPlcDeviceService(ConveyorPlcDeviceRepository devices, UserRepository users,
                  FactoryCatalogService factories, AuditLogService audit, SelectedFactoryScope selectedFactory,
                  DeviceCommandEventRepository commandEvents, DeviceRuntimeSnapshotRepository snapshots,
                  StationDeviceBindingRepository bindings, ScalePlcDeviceRepository scales) {
        this.devices=devices; this.users=users; this.factories=factories; this.audit=audit;
        this.selectedFactory=selectedFactory; this.commandEvents=commandEvents; this.snapshots=snapshots; this.bindings=bindings; this.scales=scales;
    }

    public List<ConveyorPlcDevice> list() {
        return devices.findAll().stream().filter(d -> AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey()))
            .sorted(Comparator.comparing(ConveyorPlcDevice::getDeviceCode)).toList();
    }
    public List<Assigned> mine() {
        User u = currentUser();
        if (!u.canAccessBuyer(AppConstants.Buyer.LULULEMON)) return List.of();
        String active = selectedFactory.requested(AppConstants.Buyer.LULULEMON);
        return list().stream().filter(ConveyorPlcDevice::isEnabled)
            .filter(d -> selectedFactory.matches(active, d.getFactoryCode()))
            .filter(d -> allowed(u, d))
            .map(d -> new Assigned(d.getDeviceCode(), d.getName(), d.getStationCode(), d.getFactoryCode())).toList();
    }
    /** Unsaved ModbusPal preview. Never sends commands or stores runtime snapshots. */
    public Status testConnection(Settings s) throws IOException {
        if (s == null) throw new IllegalArgumentException("Conveyor settings required");
        String factory = s.factoryCode() == null ? "" : s.factoryCode().trim().toUpperCase();
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory");
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        // Admin-only preview: row-selected Factory is authoritative, even when a global filter exists.
        if (!AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(s.mode()) || !AppConstants.DeviceConnection.MODBUS_TCP.equalsIgnoreCase(s.protocol()) || s.port() != 1502)
            throw new IllegalArgumentException("Only ModbusPal SIMULATOR can be tested; REAL PLC is a configuration-only profile");
        validateHost(s.host());
        if (s.unitId() < 1 || s.unitId() > 247) throw new IllegalArgumentException("Invalid Unit ID");
        int[] coils = {s.commandCoil(), s.motorFeedbackCoil(), s.rfidSensorCoil(), s.weightSensorCoil(), s.faultCoil()};
        java.util.Set<Integer> unique = new LinkedHashSet<>();
        for (int coil : coils) if (coil < 0 || coil > 65535 || !unique.add(coil))
            throw new IllegalArgumentException("Distinct 0-based coil offsets 0–65535 required");
        return new Status(s.deviceCode(), factory, s.stationCode(), Instant.now(),
            ModbusCoilClient.read(s.host().trim(), s.port(), s.unitId(), s.commandCoil()),
            ModbusCoilClient.read(s.host().trim(), s.port(), s.unitId(), s.motorFeedbackCoil()),
            ModbusCoilClient.read(s.host().trim(), s.port(), s.unitId(), s.rfidSensorCoil()),
            ModbusCoilClient.read(s.host().trim(), s.port(), s.unitId(), s.weightSensorCoil()),
            ModbusCoilClient.read(s.host().trim(), s.port(), s.unitId(), s.faultCoil()),
            "MODBUSPAL_SIMULATOR", "CONNECTED / READ ONLY");
    }

    public synchronized ConveyorPlcDevice save(String code, Settings s) {
        if (s == null || !CODE.matcher(code).matches() || !code.equals(s.deviceCode()))
            throw new IllegalArgumentException("Invalid conveyor device code");
        String factory = s.factoryCode() == null ? "" : s.factoryCode().trim().toUpperCase();
        if (!FactoryCodes.isValid(factory)) throw new IllegalArgumentException("Invalid Factory");
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
        // Only one conveyor profile is allowed for a Factory. Never silently
        // reassign an existing device code or overwrite its Factory ownership.
        ConveyorPlcDevice previous = devices.findById(code).orElse(null);
        long currentRevision = previous == null ? 0L : previous.getRevision();
        if (previous != null && (s.revision() == null || s.revision().longValue() != currentRevision))
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Conveyor configuration was updated after this editor opened. Your changes were NOT saved. Close and reopen Edit to review the latest settings.");
        if (previous == null && s.revision() != null && s.revision() != 0L)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Conveyor record no longer exists. Your changes were NOT saved. Reopen the configuration.");
        if (previous != null && !factory.equalsIgnoreCase(previous.getFactoryCode()))
            throw new IllegalArgumentException("A Conveyor Device Code cannot be moved to another Factory");
        if (previous == null && devices.findAll().stream().anyMatch(d ->
                AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())
                && factory.equalsIgnoreCase(d.getFactoryCode())))
            throw new IllegalArgumentException("Factory " + factory + " already has a conveyor. Edit its existing configuration instead.");
        if (s.stationCode() == null || !CODE.matcher(s.stationCode()).matches()
              || s.name() == null || s.name().isBlank() || s.name().length() > 100)
            throw new IllegalArgumentException("Station code and device name are required");
        validateHost(s.host());
        String mode = s.mode() == null || s.mode().isBlank() ? AppConstants.DeviceConnection.SIMULATOR : s.mode().trim().toUpperCase();
        String protocol = s.protocol() == null || s.protocol().isBlank() ? AppConstants.DeviceConnection.MODBUS_TCP : s.protocol().trim().toUpperCase();
        if (!List.of(AppConstants.DeviceConnection.SIMULATOR, "REAL").contains(mode)) throw new IllegalArgumentException("Invalid Device Mode");
        if (!List.of(AppConstants.DeviceConnection.MODBUS_TCP, "OPC_UA", "S7", "VENDOR_API").contains(protocol))
            throw new IllegalArgumentException("Unsupported protocol profile");
        if (s.port() < 1 || s.port() > 65535 || s.unitId() < 1 || s.unitId() > 247)
            throw new IllegalArgumentException("Invalid TCP port or Unit ID");
        if (AppConstants.DeviceConnection.SIMULATOR.equals(mode) && (!AppConstants.DeviceConnection.MODBUS_TCP.equals(protocol) || s.port() != 1502))
            throw new IllegalArgumentException("ModbusPal Simulator uses MODBUS_TCP on port 1502");
        int[] coils = {s.commandCoil(), s.motorFeedbackCoil(), s.rfidSensorCoil(), s.weightSensorCoil(), s.faultCoil()};
        java.util.Set<Integer> unique = new LinkedHashSet<>();
        for (int coil : coils) if (coil < 0 || coil > 65535 || !unique.add(coil))
            throw new IllegalArgumentException("Distinct 0-based coil offsets 0–65535 required");
        // User authorization is provided by User Management; ignore legacy per-device user assignment.
        // Never silently move a bound device to another Factory or packing Station.
        boolean boundElsewhere = bindings.findAll().stream().anyMatch(b -> b.isActive()
           && "CONVEYOR".equals(b.getDeviceRole()) && code.equals(b.getDeviceCode())
           && (!factory.equals(b.getFactoryCode()) || !s.stationCode().equals(b.getStationCode())));
        if (boundElsewhere) throw new IllegalArgumentException("Unassign the device before changing Factory/Station");
        java.util.Map<String, Object> before = audit.snapshot(previous);
        ConveyorPlcDevice d=devices.findById(code).orElseGet(ConveyorPlcDevice::new);
        d.setDeviceCode(code);d.setBuyerKey(AppConstants.Buyer.LULULEMON);
        d.setName(s.name().trim());d.setFactoryCode(factory);d.setStationCode(s.stationCode());
        d.setMode(mode);d.setProtocol(protocol);d.setCommissioningStatus("DRAFT");
        d.setHost(s.host().trim()); d.setPort(s.port());d.setUnitId(s.unitId());
        d.setCommandCoil(s.commandCoil());d.setMotorFeedbackCoil(s.motorFeedbackCoil());
        d.setRfidSensorCoil(s.rfidSensorCoil());d.setWeightSensorCoil(s.weightSensorCoil());
        d.setFaultCoil(s.faultCoil());d.setEnabled(s.enabled());d.setAssignedUserIds(List.of());
        d.setRevision(currentRevision + 1L);
        ConveyorPlcDevice saved = devices.saveIfCurrent(d, currentRevision);
        audit.logChange(RequestActor.current(), "CONVEYOR_DEVICE_SETTINGS", AppConstants.AuditResource.CARTON_LOADING,
            code, "Factory="+factory+"; Station="+s.stationCode()+"; mode="+mode+"; protocol="+protocol,
            AppConstants.Buyer.LULULEMON, factory, before, saved, null, null);
        return saved;
    }
    public void delete(String code) {
        ConveyorPlcDevice d=require(code);
        if (bindings.findAll().stream().anyMatch(b -> b.isActive() && "CONVEYOR".equals(b.getDeviceRole())
                && code.equals(b.getDeviceCode())))
            throw new IllegalArgumentException("Unassign the Conveyor from Station before removing it");
        devices.delete(d);
        audit.logChange(RequestActor.current(), "CONVEYOR_DEVICE_REMOVE", AppConstants.AuditResource.CARTON_LOADING,
            code, "Factory="+d.getFactoryCode(), AppConstants.Buyer.LULULEMON, d.getFactoryCode(), d, null, null, null);
    }
    /** Store a snapshot from actual Modbus read; do not claim CONNECTED just because a UI is open. */
    public Status read(String code) throws IOException {
        ConveyorPlcDevice d=requireAllowed(code);
        requireSimulator(d);
        validateHost(d.getHost());
        try {
            Status result = new Status(code, d.getFactoryCode(), d.getStationCode(), Instant.now(),
               ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getCommandCoil()),
               ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getMotorFeedbackCoil()),
               ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getRfidSensorCoil()),
               ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getWeightSensorCoil()),
               ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getFaultCoil()),
               "MODBUSPAL_SIMULATOR", AppConstants.DeviceConnection.CONNECTED);
            DeviceRuntimeSnapshot state = snapshots.findById(code).orElseGet(DeviceRuntimeSnapshot::new);
            state.setDeviceCode(code);state.setFactoryCode(d.getFactoryCode());state.setStationCode(d.getStationCode());
            state.setStatus(AppConstants.DeviceConnection.CONNECTED);state.setObservedAt(result.readAt());state.setErrorMessage(null);
            state.setSignals(Map.of("runCommand",result.commandOn(),"motorRunning",result.motorRunning(),
                 "rfidSensor",result.rfidSensor(),"weightSensor",result.weightSensor(),"fault",result.fault()));
            snapshots.save(state);
            return result;
        } catch (IOException error) {
            DeviceRuntimeSnapshot state = snapshots.findById(code).orElseGet(DeviceRuntimeSnapshot::new);
            state.setDeviceCode(code);state.setFactoryCode(d.getFactoryCode());state.setStationCode(d.getStationCode());
            state.setStatus(AppConstants.DeviceConnection.DISCONNECTED);state.setObservedAt(Instant.now());
            state.setErrorMessage("Modbus communication failed");state.setSignals(Map.of()); snapshots.save(state);
            throw error;
        }
    }
    private static void requireSimulator(ConveyorPlcDevice device) {
        if (!AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(device.getMode()) || !AppConstants.DeviceConnection.MODBUS_TCP.equalsIgnoreCase(device.getProtocol())
            || device.getPort() != 1502)
            throw new IllegalArgumentException("REAL PLC profile is DRAFT/read-only: industrial commissioning and safety approval required");
    }
    public Status command(String code, String action) throws IOException {
        ConveyorPlcDevice d=requireAllowed(code);
        requireSimulator(d); // Fail closed: never write to an actual industrial PLC.
        String cmd = action == null ? "" : action.trim().toUpperCase();
        if (!"START".equals(cmd) && !"STOP".equals(cmd))
            throw new IllegalArgumentException("Expected START or STOP");
        DeviceCommandEvent event=new DeviceCommandEvent();
        event.setId(UUID.randomUUID().toString());event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setDeviceCode(code);event.setFactoryCode(d.getFactoryCode());event.setStationCode(d.getStationCode());
        event.setRequestId(event.getId());event.setAction(cmd);event.setRequestedAt(Instant.now());
        event.setRequestedBy(RequestActor.current());
        try {
            validateHost(d.getHost());
            if ("START".equals(cmd) && ModbusCoilClient.read(d.getHost(), d.getPort(), d.getUnitId(), d.getFaultCoil()))
                throw new IllegalArgumentException("PLC simulator fault is active; START refused");
            ModbusCoilClient.writeCommand(d.getHost(), d.getPort(), d.getUnitId(), d.getCommandCoil(), "START".equals(cmd));
            // ONLY in a specifically enabled and host-allowlisted ModbusPal laboratory,
            // mirror the simulated motor feedback after an acknowledged command.
            // Not a claim that any physical motor has started or stopped.
            if (autoMotorFeedback && labTargetAllowed(d)) {
                ModbusCoilClient.writeCommand(d.getHost(), d.getPort(), d.getUnitId(),
                    d.getMotorFeedbackCoil(), "START".equals(cmd));
            }
            event.setOutcome("ACKNOWLEDGED"); // Coil write acknowledged, NOT motor running.
            audit.logChange(RequestActor.current(), "CONVEYOR_SIM_" + cmd, AppConstants.AuditResource.CARTON_LOADING,
                    code, "Factory="+d.getFactoryCode()+"; Station="+d.getStationCode(),
                    AppConstants.Buyer.LULULEMON, d.getFactoryCode(), null,
                    java.util.Map.of("requestedCommand", cmd, "acknowledged", true), null, null);
        } catch (IOException | RuntimeException error) {
            event.setOutcome("FAILED");event.setErrorMessage(error.getClass().getSimpleName());
            throw error;
        } finally {
            event.setCompletedAt(Instant.now());commandEvents.save(event);
        }
        return read(code);
    }
    /** Test-lab access is Admin-only at the controller and never goes to real PLC profiles.
     * The exact LAN host must be explicitly allowlisted by a server operator.
     */
    private boolean labTargetAllowed(ConveyorPlcDevice d) {
        return labEnabled && labHost != null && !labHost.isBlank()
            && AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(d.getMode())
            && AppConstants.DeviceConnection.MODBUS_TCP.equalsIgnoreCase(d.getProtocol()) && d.getPort() == 1502
            && d.isEnabled() && labHost.trim().equals(d.getHost());
    }
    private ConveyorPlcDevice labDevice(String code) {
        ConveyorPlcDevice d = require(code);
        factories.requireActive(AppConstants.Buyer.LULULEMON, d.getFactoryCode());
        if (!labTargetAllowed(d))
            throw new AccessDeniedException("ModbusPal lab is disabled or target host is not allowlisted");
        return d;
    }
    public LabAvailability simulatorLab(String code) {
        ConveyorPlcDevice d = require(code);
        boolean active = labTargetAllowed(d);
        return new LabAvailability(active, d.getFactoryCode(), code, d.getHost(), d.getPort(), d.getUnitId(),
            active ? "Test-lab enabled; no real PLC must use this endpoint"
                   : "Server administrator must enable the isolated ModbusPal lab and allowlist this host");
    }
    /** Test-only feedback/sensor coils; motor COMMAND is writable only by existing START/STOP. */
    public Status simulateSignal(String code, LabSignal request) throws IOException {
        ConveyorPlcDevice d = labDevice(code);
        if (request == null || request.value() == null || request.signal() == null)
            throw new IllegalArgumentException("Signal and value are required");
        int coil = switch (request.signal().trim().toUpperCase()) {
            case "MOTOR_FEEDBACK" -> d.getMotorFeedbackCoil();
            case "RFID_SENSOR" -> d.getRfidSensorCoil();
            case "WEIGHT_SENSOR" -> d.getWeightSensorCoil();
            case "FAULT" -> d.getFaultCoil();
            default -> throw new IllegalArgumentException("Unknown lab feedback signal");
        };
        validateHost(d.getHost());
        ModbusCoilClient.writeCommand(d.getHost(), d.getPort(), d.getUnitId(), coil, request.value());
        audit.logChange(RequestActor.current(), "MODBUSPAL_LAB_SIGNAL", AppConstants.AuditResource.CARTON_LOADING,
            code, "Factory=" + d.getFactoryCode() + "; signal=" + request.signal(),
            AppConstants.Buyer.LULULEMON, d.getFactoryCode(), null,
            java.util.Map.of("signal", request.signal(), "value", request.value()), null, null);
        // Read without creating a carton or assigning a result.
        return testConnection(new Settings(d.getDeviceCode(), d.getFactoryCode(), d.getStationCode(),
            d.getName(), d.getHost(), d.getPort(), d.getUnitId(), d.getCommandCoil(),
            d.getMotorFeedbackCoil(), d.getRfidSensorCoil(), d.getWeightSensorCoil(),
            d.getFaultCoil(), d.isEnabled(), d.getAssignedUserIds(), d.getMode(), d.getProtocol()));
    }
    /** This only writes a weight and stable flag to the approved simulator's FC06 holding registers.
     * Does NOT arm a carton, run RFID or bypass the 0 -> 1 capture condition.
     */
    public LabWeightResult simulateWeight(String code, LabWeight request) throws IOException {
        ConveyorPlcDevice d = labDevice(code);
        if (request == null || request.weightKg() == null || request.weightKg().signum() < 0)
            throw new IllegalArgumentException("Enter a nonnegative simulated weight (kg)");
        List<ScalePlcDevice> matches = scales.findAll().stream()
            .filter(s -> AppConstants.Buyer.LULULEMON.equalsIgnoreCase(s.getBuyerKey())
                && d.getFactoryCode().equalsIgnoreCase(s.getFactoryCode())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Exactly one Scale profile required for this Factory");
        ScalePlcDevice scale = matches.get(0);
        if (!scale.isEnabled() || !AppConstants.DeviceConnection.MODBUS_TCP.equals(scale.getConnectionType())
              || !"UINT16".equals(scale.getEncoding())
              || !d.getHost().equals(scale.getHost()) || d.getPort() != scale.getPort()
              || d.getUnitId() != scale.getUnitId())
            throw new IllegalArgumentException("Configure a UINT16 Modbus TCP Scale on this SAME PLC host/port/unit first");
        if (scale.getStableRegisterAddress() == null
              || scale.getStableRegisterAddress() == scale.getRegisterAddress())
            throw new IllegalArgumentException("Scale requires a distinct Stable Holding Register");
        BigDecimal factor = BigDecimal.valueOf(scale.getKgPerUnit());
        if (factor.signum() <= 0) throw new IllegalArgumentException("Invalid Scale kg/unit");
        BigDecimal rawDecimal = request.weightKg().divide(factor, 0, RoundingMode.HALF_UP);
        if (rawDecimal.compareTo(BigDecimal.valueOf(65535L)) > 0)
            throw new IllegalArgumentException("Weight exceeds UINT16 register range");
        int raw = rawDecimal.intValueExact();
        validateHost(d.getHost());
        // Explicitly write raw first and stable last. Operator must let the backend observe
        // stable=0 after RFID finishes before triggering a new stable=1 edge.
        ModbusHoldingRegisterWriter.write(d.getHost(), d.getPort(), d.getUnitId(), scale.getRegisterAddress(), raw);
        ModbusHoldingRegisterWriter.write(d.getHost(), d.getPort(), d.getUnitId(), scale.getStableRegisterAddress(),
            request.stable() ? 1 : 0);
        long verifiedRaw = ModbusTcpReader.read(d.getHost(), d.getPort(), d.getUnitId(),
            scale.getRegisterAddress(), scale.getEncoding());
        long verifiedStable = ModbusTcpReader.read(d.getHost(), d.getPort(), d.getUnitId(),
            scale.getStableRegisterAddress(), "UINT16");
        if (verifiedRaw != raw || verifiedStable != (request.stable() ? 1 : 0))
            throw new IOException("Simulator register verification mismatch");
        audit.logChange(RequestActor.current(), "MODBUSPAL_LAB_WEIGHT", AppConstants.AuditResource.CARTON_LOADING,
            code, "Factory=" + d.getFactoryCode() + "; no carton record written", AppConstants.Buyer.LULULEMON,
            d.getFactoryCode(), null, java.util.Map.of("raw", raw, "stable", request.stable()), null, null);
        return new LabWeightResult(factor.multiply(BigDecimal.valueOf(raw)).setScale(3, RoundingMode.HALF_UP),
            verifiedRaw, request.stable(), "ModbusPal register write verified; carton workflow unchanged");
    }

    public List<DeviceCommandEvent> events(String code) {
        ConveyorPlcDevice device=requireAllowed(code);
        return commandEvents.findAll().stream()
            .filter(e->code.equals(e.getDeviceCode()) && device.getFactoryCode().equals(e.getFactoryCode()))
            .sorted(Comparator.comparing(DeviceCommandEvent::getRequestedAt).reversed()).limit(100).toList();
    }
    public DeviceRuntimeSnapshot snapshot(String code) {
        ConveyorPlcDevice device=requireAllowed(code);
        DeviceRuntimeSnapshot state=snapshots.findById(code).orElse(null);
        if(state==null) return null;
        if(!device.getFactoryCode().equals(state.getFactoryCode())) throw new AccessDeniedException("Wrong factory");
        if(state.getObservedAt()==null || state.getObservedAt().isBefore(Instant.now().minusSeconds(10))) {
            state.setStatus("STALE");
        }
        return state;
    }
    private ConveyorPlcDevice require(String code) {
        ConveyorPlcDevice d=devices.findById(code).orElseThrow(()->new IllegalArgumentException("Conveyor not found"));
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())) throw new AccessDeniedException("Wrong Buyer");
        return d;
    }
    private ConveyorPlcDevice requireAllowed(String code) {
        ConveyorPlcDevice d=require(code);
        if (!d.isEnabled()) throw new IllegalArgumentException("Conveyor disabled");
        if (!allowed(currentUser(),d)) throw new AccessDeniedException("User lacks Conveyor access for this Factory");
        selectedFactory.assertMatches(selectedFactory.requested(AppConstants.Buyer.LULULEMON), d.getFactoryCode());
        return d;
    }
    private static boolean allowed(User u, ConveyorPlcDevice d) {
        return u.isAdminRole() || (u.canLululemonScanWeight() && u.canAccessBuyer(AppConstants.Buyer.LULULEMON)
          && u.canAccessFactory(d.getFactoryCode()));
    }
    private User currentUser() {
        User u=users.findByEmail(RequestActor.current()).orElseThrow(()->new AccessDeniedException("Login required"));
        if (!u.isEnabled()) throw new AccessDeniedException("User disabled");
        return u;
    }
    private static void validateHost(String host) {
        if (host==null || !IPV4.matcher(host.trim()).matches()) throw new IllegalArgumentException("Private IPv4 required");
        String[] segments=host.trim().split("\\.");
        int a,b;
        try {a=Integer.parseInt(segments[0]);b=Integer.parseInt(segments[1]);
           for(String seg: segments) {int x=Integer.parseInt(seg);if(x<0||x>255) throw new NumberFormatException();}}
        catch (NumberFormatException e) {throw new IllegalArgumentException("Invalid IPv4");}
        if (!(a==10 || a==127 || a==192 && b==168 || a==172 && b>=16 && b<=31))
            throw new IllegalArgumentException("Use LAN/loopback IPv4 only");
    }
}
