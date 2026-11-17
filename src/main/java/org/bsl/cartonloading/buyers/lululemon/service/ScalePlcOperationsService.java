package org.bsl.cartonloading.buyers.lululemon.service;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonDeviceConnectionLifecycle;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ScalePlcDeviceFacade;

import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.buyers.lululemon.dto.SimulateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;

/** Configures/read-only tests LULULEMON scales; never submits a production carton weight. */


@Service
public class ScalePlcOperationsService {
    private static final Logger log = LoggerFactory.getLogger(ScalePlcOperationsService.class);
    private final ScalePlcDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    private final LululemonDeviceConnectionLifecycle lifecycle;
    public ScalePlcOperationsService(ScalePlcDeviceFacade service,
            LululemonDeviceStatusNotifier status,
            LululemonDeviceConnectionLifecycle lifecycle) {
        this.service = service; this.status = status; this.lifecycle = lifecycle;
    }


    public List<ScalePlcDevice> list(String buyer) { return service.forBuyer(buyer, impl -> impl.list()); }

    /** Admin-only PLC communication check. Does not require connected scales or read weight/stable registers. */


    public ScalePlcDeviceService.PlcConnectionCheck testSerialConnection(
            String buyer,
            ScalePlcDeviceService.Settings settings) {
        try {
            var result = service.forBuyer(buyer, impl -> impl.testPlcConnection(settings));
            // Only update persisted device status if the unsaved settings match exactly.
            if (lifecycle.matchesSavedScale(settings))
                status.publishFresh(result.factoryCode(), "PLC", result.stationCode(),
                        AppConstants.DeviceConnection.CONNECTED, "Admin PLC-only test: communication verified; weight not tested");
            return result;
        } catch (IOException e) {
            if (lifecycle.matchesSavedScale(settings))
                status.publishFresh(settings.factoryCode(), "PLC", settings.stationCode(),
                        AppConstants.DeviceConnection.DISCONNECTED, "Admin PLC-only test failed");
            log.warn("Delta serial PLC-only check failed: {}", e.toString());
            // Keep raw operating-system errors out of browser responses. The user is an authenticated Admin.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeSerialFailure(e), e);
        }
    }

    private static String safeSerialFailure(IOException e) {
        String error = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (error.contains("pc agent") || error.contains("local plc agent is offline"))
            return "Factory Local PLC Agent is offline. Start the Windows Agent, check its key, Factory code and HTTPS connection.";
        if (error.contains("plc agent timed out") || error.contains("plc agent request cancelled"))
            return "Factory Local PLC Agent did not return the read-only result in time. Check the Windows Agent console and COM port.";
        if (error.contains("plc read failed"))
            return "Factory Local Agent reached the server but could not read the PLC. Check COM, WPLSoft and Delta serial settings.";
        if (error.contains("not allowed"))
            return "Backend serial port is not permitted. Set CARTON_SCALE_SERIAL_ALLOWED_PORTS=COM8 in the Java run environment, then restart backend.";
        if (error.contains("cannot open") || error.contains("configure serial") || error.contains("unable to configure"))
            return "Backend cannot open serial COM port. Check that Java runs on the USB-connected PC, COM port exists, driver is installed and WPLSoft is closed.";
        if (error.contains("timeout") || error.contains("no reply"))
            return "COM port opened, but PLC did not reply to Modbus ASCII D0 read. Check 9600/7/E/1, station address, correct Delta cable and PLC protocol.";
        if (error.contains("lrc") || error.contains("frame") || error.contains("hex"))
            return "PLC serial response was malformed or failed LRC. Check cable, parity, ASCII protocol and serial noise.";
        if (error.contains("exception code"))
            return "PLC replied with a Modbus exception. Verify supported D0 register and controller protocol.";
        if (error.contains("write modbus ascii"))
            return "Backend opened COM port but could not send the read-only request to PLC.";
        return "PLC serial communication failed. Inspect backend log; verify COM port and Modbus ASCII settings.";
    }

    /** Read-only check of unsaved Admin settings; never creates a station or arms a carton. */


    public ScalePlcDeviceService.Reading test(
            String buyer,
            ScalePlcDeviceService.Settings settings) throws IOException {
        try {
            var result = service.forBuyer(buyer, impl -> impl.testConnection(settings));
            // A preview of unsaved edits must not overwrite the saved Factory status.
            if (lifecycle.matchesSavedScale(settings))
                status.publishFresh(result.factoryCode(), "PLC", result.stationCode(),
                        AppConstants.DeviceConnection.SIMULATOR.equals(result.mode()) ? AppConstants.DeviceConnection.SIMULATOR : AppConstants.DeviceConnection.CONNECTED, "Admin Test succeeded: " + result.status());
            return result;
        } catch (IOException | IllegalArgumentException e) {
            if (lifecycle.matchesSavedScale(settings))
                status.publishFresh(settings.factoryCode(), "PLC", settings.stationCode(), AppConstants.DeviceConnection.DISCONNECTED,
                        "Admin Test failed: " + e.getMessage());
            if (e instanceof IOException io && settings != null &&
                    AppConstants.DeviceConnection.DELTA_SERIAL.equals(settings.connectionType())) {
                log.warn("Delta Serial weight read-only test failed: {}", io.toString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeSerialFailure(io), io);
            }
            throw e;
        }
    }


    public List<ScalePlcDeviceService.AssignedStation> mine(String buyer) { return service.forBuyer(buyer, impl -> impl.mine()); }


    public ScalePlcDevice save(String buyer, String code,
            ScalePlcDeviceService.Settings settings) {
        var saved = service.forBuyer(buyer, impl -> impl.save(code, settings));

        // The saved settings are authoritative. A failed read affects status only,
        // never the successful persistence transaction or the Scale's other fields.
        lifecycle.afterScaleSaved(saved);
        return saved;
    }


    public void delete(String buyer, String code) {
        var before = service.forBuyer(buyer, impl -> impl.list()).stream()
                .filter(d -> code.equals(d.getStationCode())).findFirst().orElse(null);
        service.forBuyer(buyer, impl -> { impl.delete(code); return null; });
        if (before != null) status.removed(before.getFactoryCode(), "PLC", code);
    }

public ScalePlcDeviceService.Reading simulate(String buyer, String code,
            SimulateRequest request) {
        var result = service.forBuyer(buyer, impl -> impl.simulate(code, request == null ? null : request.weightKg()));
        status.publish(result.factoryCode(), "PLC", result.stationCode(), AppConstants.DeviceConnection.SIMULATOR, "Simulator weight submitted");
        return result;
    }


    public ScalePlcDeviceService.Reading read(String buyer, String code) throws IOException {
        try {
            var result = service.forBuyer(buyer, impl -> impl.read(code));
            status.publish(result.factoryCode(), "PLC", result.stationCode(),
                    AppConstants.DeviceConnection.SIMULATOR.equals(result.mode()) ? AppConstants.DeviceConnection.SIMULATOR : AppConstants.DeviceConnection.CONNECTED, "Weight read succeeded");
            return result;
        } catch (IOException e) {
            var device = service.forBuyer(buyer, impl -> impl.list()).stream()
                    .filter(d -> code.equals(d.getStationCode())).findFirst().orElse(null);
            if (device != null) status.publish(device.getFactoryCode(), "PLC", code, AppConstants.DeviceConnection.DISCONNECTED, "Scale PLC weight read failed");
            if (device != null && AppConstants.DeviceConnection.DELTA_SERIAL.equals(device.getConnectionType()))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, safeSerialFailure(e), e);
            throw e;
        }
    }
}
