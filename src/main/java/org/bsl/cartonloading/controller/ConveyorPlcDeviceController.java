package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceCommandEvent;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceRuntimeSnapshot;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonDeviceConnectionLifecycle;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.service.facade.ConveyorPlcDeviceFacade;
import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.service.ConveyorPlcDeviceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

/** This controller ONLY exposes ModbusPal simulator requests, never real industrial motor actuation. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/conveyor-plc-devices")
public class ConveyorPlcDeviceController {
    private final ConveyorPlcDeviceFacade facade;
    private final LululemonDeviceStatusNotifier status;
    private final LululemonDeviceConnectionLifecycle lifecycle;
    public ConveyorPlcDeviceController(ConveyorPlcDeviceFacade facade,
            LululemonDeviceStatusNotifier status,
            LululemonDeviceConnectionLifecycle lifecycle) {
        this.facade=facade; this.status=status; this.lifecycle=lifecycle;
    }
    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<ConveyorPlcDevice> list(@PathVariable String buyer) {return facade.forBuyer(buyer, svc->svc.list());}
    /** Read-only ModbusPal diagnostics for an unsaved simulator; no motor actuation. */
    @PostMapping("/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.Status test(@PathVariable String buyer,
            @RequestBody ConveyorPlcDeviceService.Settings settings) throws IOException {
        try {
            var result = facade.forBuyer(buyer, svc -> svc.testConnection(settings));
            // Only a Test of the saved endpoint updates shared device status.
            if (lifecycle.matchesSavedConveyor(settings))
                status.publishFresh(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                        result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor Test succeeded");
            return result;
        } catch (IOException | IllegalArgumentException e) {
            if (lifecycle.matchesSavedConveyor(settings))
                status.publishFresh(settings.factoryCode(), "CONVEYOR", settings.deviceCode(), AppConstants.DeviceConnection.DISCONNECTED,
                        "Conveyor Test failed: " + e.getMessage());
            throw e;
        }
    }

    @GetMapping("/mine")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<ConveyorPlcDeviceService.Assigned> mine(@PathVariable String buyer) {return facade.forBuyer(buyer, svc->svc.mine());}
    @PutMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDevice save(@PathVariable String buyer,@PathVariable String code,
        @RequestBody ConveyorPlcDeviceService.Settings req) {
        var saved = facade.forBuyer(buyer, svc->svc.save(code,req));

        // One read-only status check AFTER Save succeeds; never write the Scale PLC.
        lifecycle.afterConveyorSaved(saved);
        return saved;
    }
    @DeleteMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void delete(@PathVariable String buyer,@PathVariable String code) {
        var before = facade.forBuyer(buyer, svc->svc.list()).stream()
                .filter(d -> code.equals(d.getDeviceCode())).findFirst().orElse(null);
        facade.forBuyer(buyer, svc->{svc.delete(code);return null;});
        if (before != null) status.removed(before.getFactoryCode(), "CONVEYOR", code);
    }
    @GetMapping("/{code}/read")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ConveyorPlcDeviceService.Status read(@PathVariable String buyer,@PathVariable String code) throws IOException {
        try {
            var result = facade.forBuyer(buyer, svc->svc.read(code));
            status.publish(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                    result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor read succeeded");
            return result;
        } catch (IOException e) {
            var device = facade.forBuyer(buyer, svc->svc.list()).stream()
                    .filter(d -> code.equals(d.getDeviceCode())).findFirst().orElse(null);
            if (device != null) status.publish(device.getFactoryCode(), "CONVEYOR", code, AppConstants.DeviceConnection.DISCONNECTED, "Conveyor read failed");
            throw e;
        }
    }
    /** Explicitly enabled simulator lab; Admin only, host allowlisted on backend. */
    @GetMapping("/{code}/simulator-lab")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.LabAvailability simulatorLab(@PathVariable String buyer, @PathVariable String code) {
        return facade.forBuyer(buyer, svc -> svc.simulatorLab(code));
    }
    @PostMapping("/{code}/simulator-lab/signal")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.Status simulateSignal(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ConveyorPlcDeviceService.LabSignal req) throws IOException {
        var result = facade.forBuyer(buyer, svc -> svc.simulateSignal(code, req));
        status.publish(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor simulator changed");
        return result;
    }
    @PostMapping("/{code}/simulator-lab/weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.LabWeightResult simulateWeight(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ConveyorPlcDeviceService.LabWeight req) throws IOException {
        return facade.forBuyer(buyer, svc -> svc.simulateWeight(code, req));
    }
    @GetMapping("/{code}/events")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<DeviceCommandEvent> events(@PathVariable String buyer,@PathVariable String code) {
        return facade.forBuyer(buyer, svc->svc.events(code));
    }
    @GetMapping("/{code}/snapshot")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public DeviceRuntimeSnapshot snapshot(@PathVariable String buyer,@PathVariable String code) {
        return facade.forBuyer(buyer, svc->svc.snapshot(code));
    }
    @PostMapping("/{code}/command")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ConveyorPlcDeviceService.Status command(@PathVariable String buyer,@PathVariable String code,
              @RequestBody ConveyorPlcDeviceService.Command req) throws IOException {
        if(req==null) throw new IllegalArgumentException("Action required");
        var result = facade.forBuyer(buyer, svc->svc.command(code,req.action()));
        status.publish(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor command acknowledged");
        return result;
    }
}
