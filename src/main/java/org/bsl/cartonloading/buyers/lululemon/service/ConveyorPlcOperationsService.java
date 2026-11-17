package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceCommandEvent;
import org.bsl.cartonloading.buyers.lululemon.model.DeviceRuntimeSnapshot;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonDeviceConnectionLifecycle;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ConveyorPlcDeviceFacade;
import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.service.ConveyorPlcDeviceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.List;

/** This controller ONLY exposes ModbusPal simulator requests, never real industrial motor actuation. */


@Service
public class ConveyorPlcOperationsService {
    private final ConveyorPlcDeviceFacade facade;
    private final LululemonDeviceStatusNotifier status;
    private final LululemonDeviceConnectionLifecycle lifecycle;
    public ConveyorPlcOperationsService(ConveyorPlcDeviceFacade facade,
            LululemonDeviceStatusNotifier status,
            LululemonDeviceConnectionLifecycle lifecycle) {
        this.facade=facade; this.status=status; this.lifecycle=lifecycle;
    }


    public List<ConveyorPlcDevice> list(String buyer) {return facade.forBuyer(buyer, svc->svc.list());}
    /** Read-only ModbusPal diagnostics for an unsaved simulator; no motor actuation. */


    public ConveyorPlcDeviceService.Status test(String buyer,
            ConveyorPlcDeviceService.Settings settings) throws IOException {
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


    public List<ConveyorPlcDeviceService.Assigned> mine(String buyer) {return facade.forBuyer(buyer, svc->svc.mine());}


    public ConveyorPlcDevice save(String buyer,String code,
        ConveyorPlcDeviceService.Settings req) {
        var saved = facade.forBuyer(buyer, svc->svc.save(code,req));

        // One read-only status check AFTER Save succeeds; never write the Scale PLC.
        lifecycle.afterConveyorSaved(saved);
        return saved;
    }


    public void delete(String buyer,String code) {
        var before = facade.forBuyer(buyer, svc->svc.list()).stream()
                .filter(d -> code.equals(d.getDeviceCode())).findFirst().orElse(null);
        facade.forBuyer(buyer, svc->{svc.delete(code);return null;});
        if (before != null) status.removed(before.getFactoryCode(), "CONVEYOR", code);
    }


    public ConveyorPlcDeviceService.Status read(String buyer,String code) throws IOException {
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


    public ConveyorPlcDeviceService.LabAvailability simulatorLab(String buyer, String code) {
        return facade.forBuyer(buyer, svc -> svc.simulatorLab(code));
    }


    public ConveyorPlcDeviceService.Status simulateSignal(String buyer, String code,
            ConveyorPlcDeviceService.LabSignal req) throws IOException {
        var result = facade.forBuyer(buyer, svc -> svc.simulateSignal(code, req));
        status.publish(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor simulator changed");
        return result;
    }


    public ConveyorPlcDeviceService.LabWeightResult simulateWeight(String buyer, String code,
            ConveyorPlcDeviceService.LabWeight req) throws IOException {
        return facade.forBuyer(buyer, svc -> svc.simulateWeight(code, req));
    }


    public List<DeviceCommandEvent> events(String buyer,String code) {
        return facade.forBuyer(buyer, svc->svc.events(code));
    }


    public DeviceRuntimeSnapshot snapshot(String buyer,String code) {
        return facade.forBuyer(buyer, svc->svc.snapshot(code));
    }


    public ConveyorPlcDeviceService.Status command(String buyer,String code,
              ConveyorPlcDeviceService.Command req) throws IOException {
        if(req==null) throw new IllegalArgumentException("Action required");
        var result = facade.forBuyer(buyer, svc->svc.command(code,req.action()));
        status.publish(result.factoryCode(), "CONVEYOR", result.deviceCode(),
                result.fault() ? "FAULT" : result.motorRunning() ? "RUNNING" : "STOPPED", "Conveyor command acknowledged");
        return result;
    }
}
