package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonDeviceConnectionLifecycle;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.service.facade.ScalePlcDeviceFacade;

import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

/** Configures/read-only tests LULULEMON scales; never submits a production carton weight. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/scale-plc-devices")
public class ScalePlcDeviceController {
    private final ScalePlcDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    private final LululemonDeviceConnectionLifecycle lifecycle;
    public ScalePlcDeviceController(ScalePlcDeviceFacade service,
            LululemonDeviceStatusNotifier status,
            LululemonDeviceConnectionLifecycle lifecycle) {
        this.service = service; this.status = status; this.lifecycle = lifecycle;
    }
    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<ScalePlcDevice> list(@PathVariable String buyer) { return service.forBuyer(buyer, impl -> impl.list()); }

    /** Read-only check of unsaved Admin settings; never creates a station or arms a carton. */
    @PostMapping("/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDeviceService.Reading test(
            @PathVariable String buyer,
            @RequestBody ScalePlcDeviceService.Settings settings) throws IOException {
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
            throw e;
        }
    }

    @GetMapping("/mine")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public List<ScalePlcDeviceService.AssignedStation> mine(@PathVariable String buyer) { return service.forBuyer(buyer, impl -> impl.mine()); }

    @PutMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDevice save(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ScalePlcDeviceService.Settings settings) {
        var saved = service.forBuyer(buyer, impl -> impl.save(code, settings));

        // The saved settings are authoritative. A failed read affects status only,
        // never the successful persistence transaction or the Scale's other fields.
        lifecycle.afterScaleSaved(saved);
        return saved;
    }

    @DeleteMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void delete(@PathVariable String buyer, @PathVariable String code) {
        var before = service.forBuyer(buyer, impl -> impl.list()).stream()
                .filter(d -> code.equals(d.getStationCode())).findFirst().orElse(null);
        service.forBuyer(buyer, impl -> { impl.delete(code); return null; });
        if (before != null) status.removed(before.getFactoryCode(), "PLC", code);
    }

    public record SimulateRequest(java.math.BigDecimal weightKg) { }

    @PostMapping("/{code}/simulate")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDeviceService.Reading simulate(@PathVariable String buyer, @PathVariable String code,
            @RequestBody SimulateRequest request) {
        var result = service.forBuyer(buyer, impl -> impl.simulate(code, request == null ? null : request.weightKg()));
        status.publish(result.factoryCode(), "PLC", result.stationCode(), AppConstants.DeviceConnection.SIMULATOR, "Simulator weight submitted");
        return result;
    }

    @GetMapping("/{code}/read")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ScalePlcDeviceService.Reading read(@PathVariable String buyer, @PathVariable String code) throws IOException {
        try {
            var result = service.forBuyer(buyer, impl -> impl.read(code));
            status.publish(result.factoryCode(), "PLC", result.stationCode(),
                    AppConstants.DeviceConnection.SIMULATOR.equals(result.mode()) ? AppConstants.DeviceConnection.SIMULATOR : AppConstants.DeviceConnection.CONNECTED, "Weight read succeeded");
            return result;
        } catch (IOException e) {
            var device = service.forBuyer(buyer, impl -> impl.list()).stream()
                    .filter(d -> code.equals(d.getStationCode())).findFirst().orElse(null);
            if (device != null) status.publish(device.getFactoryCode(), "PLC", code, AppConstants.DeviceConnection.DISCONNECTED, "Modbus weight read failed");
            throw e;
        }
    }
}
