package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ConveyorPlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single read-only observation for enabled LULULEMON Scale/Conveyor devices when
 * the Backend starts or an Admin saves a configuration. No timer, polling, retry,
 * Modbus write, motor command, carton arming or weight-claim operation is used.
 * Each saved device retains its own Modbus Unit ID.
 */
@Component
public class LululemonDeviceConnectionLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LululemonDeviceConnectionLifecycle.class);
    private final ScalePlcDeviceRepository scales;
    private final ConveyorPlcDeviceRepository conveyors;
    private final ScalePlcDeviceService scaleService;
    private final ConveyorPlcDeviceService conveyorService;
    private final LululemonDeviceStatusNotifier status;
    private final AtomicBoolean startupChecked = new AtomicBoolean();

    public LululemonDeviceConnectionLifecycle(ScalePlcDeviceRepository scales,
            ConveyorPlcDeviceRepository conveyors, ScalePlcDeviceService scaleService,
            ConveyorPlcDeviceService conveyorService, LululemonDeviceStatusNotifier status) {
        this.scales = scales;
        this.conveyors = conveyors;
        this.scaleService = scaleService;
        this.conveyorService = conveyorService;
        this.status = status;
    }

    /** Runs once per Spring context, after MongoDB migration and application initialization. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnceOnStartup() {
        if (!startupChecked.compareAndSet(false, true)) return;
        try {
            for (ScalePlcDevice d : scales.findAll()) {
                if (AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey()))
                    probeScale(d, "Startup check");
            }
        } catch (Exception e) {
            log.warn("Unable to complete LULULEMON Scale startup checks: {}", e.toString());
        }
        try {
            for (ConveyorPlcDevice d : conveyors.findAll()) {
                if (AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey()))
                    probeConveyor(d, "Startup check");
            }
        } catch (Exception e) {
            log.warn("Unable to complete LULULEMON Conveyor startup checks: {}", e.toString());
        }
    }

    /** Save already committed to MongoDB. Socket/snapshot errors must not turn that
     * successful Save into an HTTP failure or trigger a second client-side Save.
     */
    public void afterScaleSaved(ScalePlcDevice saved) {
        try { status.configured(saved.getFactoryCode(), "PLC", saved.getStationCode()); }
        catch (Exception e) { log.warn("Unable to reset Scale status after Save: {}", e.toString()); }
        probeScale(saved, "Saved configuration check");
    }

    public void afterConveyorSaved(ConveyorPlcDevice saved) {
        try { status.configured(saved.getFactoryCode(), "CONVEYOR", saved.getDeviceCode()); }
        catch (Exception e) { log.warn("Unable to reset Conveyor status after Save: {}", e.toString()); }
        probeConveyor(saved, "Saved configuration check");
    }

    /** Called ONLY after a successful database Save; device connectivity never rolls back saved settings. */
    public void probeScale(ScalePlcDevice d, String reason) {
        if (d == null || d.getFactoryCode() == null || d.getStationCode() == null) return;
        if (!d.isEnabled()) {
            publishSafe(d.getFactoryCode(), "PLC", d.getStationCode(), "DISABLED", reason + ": device disabled");
            return;
        }
        if (!ScalePlcDeviceService.MODBUS_TCP.equalsIgnoreCase(d.getConnectionType())
                && !ScalePlcDeviceService.DELTA_SERIAL.equalsIgnoreCase(d.getConnectionType())) {
            publishSafe(d.getFactoryCode(), "PLC", d.getStationCode(), AppConstants.DeviceConnection.SIMULATOR,
                    reason + ": internal simulator (no physical PLC test)");
            return;
        }
        try {
            // DELTA_SERIAL can be connected without any weighing indicator wired to PLC.
            // Keep existing Modbus TCP / Simulator behaviour unchanged.
            String resultDetail;
            if (ScalePlcDeviceService.DELTA_SERIAL.equalsIgnoreCase(d.getConnectionType())) {
                var plc = scaleService.testPlcConnection(scaleSettings(d));
                resultDetail = plc.status() + " (scale / weight not tested)";
            } else {
                resultDetail = scaleService.testConnection(scaleSettings(d)).status();
            }
            publishSafe(d.getFactoryCode(), "PLC", d.getStationCode(), AppConstants.DeviceConnection.CONNECTED,
                    reason + ": " + resultDetail);
        } catch (Exception e) {
            publishSafe(d.getFactoryCode(), "PLC", d.getStationCode(), AppConstants.DeviceConnection.DISCONNECTED,
                    reason + ": " + safeError(e));
            log.warn("Scale PLC {} / {} read-only check failed: {}", d.getFactoryCode(), d.getStationCode(), safeError(e));
        }
    }

    public void probeConveyor(ConveyorPlcDevice d, String reason) {
        if (d == null || d.getFactoryCode() == null || d.getDeviceCode() == null) return;
        if (!d.isEnabled()) {
            publishSafe(d.getFactoryCode(), "CONVEYOR", d.getDeviceCode(), "DISABLED", reason + ": device disabled");
            return;
        }
        // Industrial motor commissioning remains disabled; only the existing ModbusPal
        // read-only test profile is supported. Do not claim REAL devices are online.
        if (!AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(d.getMode()) ||
                !AppConstants.DeviceConnection.MODBUS_TCP.equalsIgnoreCase(d.getProtocol()) || d.getPort() != 1502) {
            publishSafe(d.getFactoryCode(), "CONVEYOR", d.getDeviceCode(), "NOT_CHECKED",
                    reason + ": REAL PLC profile is configuration-only; no live test performed");
            return;
        }
        try {
            var reading = conveyorService.testConnection(conveyorSettings(d));
            String state = reading.fault() ? "FAULT" : reading.motorRunning() ? "RUNNING" : "STOPPED";
            publishSafe(d.getFactoryCode(), "CONVEYOR", d.getDeviceCode(), state,
                    reason + ": connected, read-only; motor " + (reading.motorRunning() ? "running" : "stopped"));
        } catch (Exception e) {
            publishSafe(d.getFactoryCode(), "CONVEYOR", d.getDeviceCode(), AppConstants.DeviceConnection.DISCONNECTED,
                    reason + ": " + safeError(e));
            log.warn("Conveyor {} / {} read-only check failed: {}", d.getFactoryCode(), d.getDeviceCode(), safeError(e));
        }
    }

    /** A manual Test of UNSAVED edits must not mislabel the saved Factory device. */
    public boolean matchesSavedScale(ScalePlcDeviceService.Settings request) {
        if (request == null || request.stationCode() == null) return false;
        return scales.findById(request.stationCode()).filter(d ->
            d.isEnabled() && request.enabled() && Objects.equals(d.getFactoryCode(), request.factoryCode()) &&
            Objects.equals(d.getHost(), request.host()) && d.getPort() == request.port() &&
            d.getUnitId() == request.unitId() && d.getRegisterAddress() == request.registerAddress() &&
            Objects.equals(d.getStableRegisterAddress(), request.stableRegisterAddress()) &&
            Objects.equals(d.getConnectionType(), request.connectionType()) &&
            (!AppConstants.DeviceConnection.DELTA_SERIAL.equals(request.connectionType()) ||
                (Objects.equals(ScalePlcDeviceService.serialLocation(d.getSerialConnectionLocation()),
                    ScalePlcDeviceService.serialLocation(request.serialConnectionLocation())) &&
                 Objects.equals(d.getSerialComPort(), request.serialComPort()) &&
                 d.getSerialBaudRate() == request.serialBaudRate() &&
                 d.getSerialDataBits() == request.serialDataBits() &&
                 Objects.equals(d.getSerialParity(), request.serialParity()) &&
                 d.getSerialStopBits() == request.serialStopBits())) &&
            Objects.equals(d.getEncoding(), request.encoding()) &&
            Double.compare(d.getKgPerUnit(), request.kgPerUnit()) == 0).isPresent();
    }

    public boolean matchesSavedConveyor(ConveyorPlcDeviceService.Settings request) {
        if (request == null || request.deviceCode() == null) return false;
        return conveyors.findById(request.deviceCode()).filter(d ->
            d.isEnabled() && request.enabled() && Objects.equals(d.getFactoryCode(), request.factoryCode()) &&
            Objects.equals(d.getHost(), request.host()) && d.getPort() == request.port() &&
            d.getUnitId() == request.unitId() && Objects.equals(d.getMode(), request.mode()) &&
            Objects.equals(d.getProtocol(), request.protocol()) &&
            d.getCommandCoil() == request.commandCoil() &&
            d.getMotorFeedbackCoil() == request.motorFeedbackCoil() &&
            d.getRfidSensorCoil() == request.rfidSensorCoil() &&
            d.getWeightSensorCoil() == request.weightSensorCoil() &&
            d.getFaultCoil() == request.faultCoil()).isPresent();
    }

    public static ScalePlcDeviceService.Settings scaleSettings(ScalePlcDevice d) {
        return new ScalePlcDeviceService.Settings(d.getStationCode(), d.getName(), d.getFactoryCode(),
                d.getHost(), d.getPort(), d.getUnitId(), d.getRegisterAddress(), d.getEncoding(),
                d.getKgPerUnit(), d.isEnabled(), d.getAssignedUserIds(),
                d.getConnectionType(), d.getStableRegisterAddress(),
                d.getSerialComPort(), d.getSerialBaudRate(), d.getSerialDataBits(),
                d.getSerialParity(), d.getSerialStopBits(), d.getSerialConnectionLocation());
    }

    public static ConveyorPlcDeviceService.Settings conveyorSettings(ConveyorPlcDevice d) {
        return new ConveyorPlcDeviceService.Settings(d.getDeviceCode(), d.getFactoryCode(), d.getStationCode(),
                d.getName(), d.getHost(), d.getPort(), d.getUnitId(),
                d.getCommandCoil(), d.getMotorFeedbackCoil(), d.getRfidSensorCoil(),
                d.getWeightSensorCoil(), d.getFaultCoil(), d.isEnabled(), d.getAssignedUserIds(),
                d.getMode(), d.getProtocol());
    }

    private void publishSafe(String factory, String kind, String deviceCode, String state, String detail) {
        try { status.publishFresh(factory, kind, deviceCode, state, detail); }
        catch (Exception e) { log.warn("Unable to publish {} / {} device status: {}", factory, kind, e.toString()); }
    }

    private static String safeError(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
