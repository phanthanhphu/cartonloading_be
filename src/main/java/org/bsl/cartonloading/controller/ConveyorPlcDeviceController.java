package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ConveyorPlcOperationsFacade;

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
import java.io.IOException;
import java.util.List;

/** This controller ONLY exposes ModbusPal simulator requests, never real industrial motor actuation. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/conveyor-plc-devices")
public class ConveyorPlcDeviceController {
    private final ConveyorPlcOperationsFacade actions;
    public ConveyorPlcDeviceController(ConveyorPlcOperationsFacade actions) { this.actions = actions; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<ConveyorPlcDevice> list(@PathVariable String buyer) {
        return actions.forBuyer(buyer, impl -> impl.list(buyer));
    }

    @PostMapping("/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.Status test(@PathVariable String buyer,
            @RequestBody ConveyorPlcDeviceService.Settings settings) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.test(buyer, settings));
    }

    @GetMapping("/mine")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<ConveyorPlcDeviceService.Assigned> mine(@PathVariable String buyer) {
        return actions.forBuyer(buyer, impl -> impl.mine(buyer));
    }

    @PutMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDevice save(@PathVariable String buyer,@PathVariable String code,
        @RequestBody ConveyorPlcDeviceService.Settings req) {
        return actions.forBuyer(buyer, impl -> impl.save(buyer, code, req));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void delete(@PathVariable String buyer,@PathVariable String code) {
        actions.forBuyer(buyer, impl -> { impl.delete(buyer, code); return null; });
    }

    @GetMapping("/{code}/read")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ConveyorPlcDeviceService.Status read(@PathVariable String buyer,@PathVariable String code) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.read(buyer, code));
    }

    @GetMapping("/{code}/simulator-lab")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.LabAvailability simulatorLab(@PathVariable String buyer, @PathVariable String code) {
        return actions.forBuyer(buyer, impl -> impl.simulatorLab(buyer, code));
    }

    @PostMapping("/{code}/simulator-lab/signal")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.Status simulateSignal(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ConveyorPlcDeviceService.LabSignal req) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.simulateSignal(buyer, code, req));
    }

    @PostMapping("/{code}/simulator-lab/weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ConveyorPlcDeviceService.LabWeightResult simulateWeight(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ConveyorPlcDeviceService.LabWeight req) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.simulateWeight(buyer, code, req));
    }

    @GetMapping("/{code}/events")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public List<DeviceCommandEvent> events(@PathVariable String buyer,@PathVariable String code) {
        return actions.forBuyer(buyer, impl -> impl.events(buyer, code));
    }

    @GetMapping("/{code}/snapshot")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public DeviceRuntimeSnapshot snapshot(@PathVariable String buyer,@PathVariable String code) {
        return actions.forBuyer(buyer, impl -> impl.snapshot(buyer, code));
    }

    @PostMapping("/{code}/command")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ConveyorPlcDeviceService.Status command(@PathVariable String buyer,@PathVariable String code,
              @RequestBody ConveyorPlcDeviceService.Command req) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.command(buyer, code, req));
    }

}
