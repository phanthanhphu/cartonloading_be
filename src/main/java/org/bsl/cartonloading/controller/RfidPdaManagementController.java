package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.dto.RecoveryRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.RebindRequest;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaManagementOperationsFacade;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** RFID registry: ADMIN may assign one Reader to many Factories; locks are per Reader serial. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/rfid-pda-management")
@PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
public class RfidPdaManagementController {
    private final RfidPdaManagementOperationsFacade actions;
    public RfidPdaManagementController(RfidPdaManagementOperationsFacade actions) { this.actions = actions; }

    @PostMapping("/{serial}/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device test(
            @PathVariable String buyer, @PathVariable String serial) {
        return actions.forBuyer(buyer, impl -> impl.test(buyer, serial));
    }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<RfidPdaDeviceService.Device> list(@PathVariable String buyer) {
        return actions.forBuyer(buyer, impl -> impl.list(buyer));
    }

    @PutMapping("/{serial}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device configure(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RfidPdaDeviceService.GlobalDeviceSettings settings) {
        return actions.forBuyer(buyer, impl -> impl.configure(buyer, serial, settings));
    }

    @PutMapping("/{serial}/default")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device makeDefault(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {
        return actions.forBuyer(buyer, impl -> impl.makeDefault(buyer, serial, factoryCode));
    }

    @PostMapping("/{serial}/rebind")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device rebind(
            @PathVariable String buyer, @PathVariable String serial, @RequestBody RebindRequest body) {
        return actions.forBuyer(buyer, impl -> impl.rebind(buyer, serial, body));
    }

    @PostMapping("/{serial}/recover")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.CaptureResult recover(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RecoveryRequest body) {
        return actions.forBuyer(buyer, impl -> impl.recover(buyer, serial, body));
    }

    @DeleteMapping("/{serial}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void deleteDevice(@PathVariable String buyer, @PathVariable String serial) {
        actions.forBuyer(buyer, impl -> { impl.deleteDevice(buyer, serial); return null; });
    }

    @DeleteMapping("/{serial}/assignment")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void unassign(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {
        actions.forBuyer(buyer, impl -> { impl.unassign(buyer, serial, factoryCode); return null; });
    }

}
