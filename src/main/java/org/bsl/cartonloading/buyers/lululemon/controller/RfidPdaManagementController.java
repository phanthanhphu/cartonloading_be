package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** RFID registry: ADMIN may assign one Reader to many Factories; locks are per Reader serial. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/rfid-pda-management")
@PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
public class RfidPdaManagementController {
    private final RfidPdaDeviceService service;
    public RfidPdaManagementController(RfidPdaDeviceService service) { this.service = service; }

    private void buyer(String buyer) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer))
            throw new IllegalArgumentException("Only Lululemon supports RF88 ADB control.");
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public List<RfidPdaDeviceService.Device> list(@PathVariable String buyer) {
        buyer(buyer);
        return service.managementDevices();
    }

    @PutMapping("/{serial}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public RfidPdaDeviceService.Device configure(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RfidPdaDeviceService.GlobalDeviceSettings settings) {
        buyer(buyer);
        return service.configureDevice(serial, settings);
    }

    @PutMapping("/{serial}/default")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public RfidPdaDeviceService.Device makeDefault(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {
        buyer(buyer);
        return service.setDefault(factoryCode, serial);
    }

    public record RecoveryRequest(String reason, boolean inventoryStopped) { }

    @PostMapping("/{serial}/recover")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public RfidPdaDeviceService.CaptureResult recover(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RecoveryRequest body) {
        buyer(buyer);
        return service.forceRelease(serial, body == null ? null : body.reason(),
                body != null && body.inventoryStopped());
    }

    @DeleteMapping("/{serial}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public void deleteDevice(@PathVariable String buyer, @PathVariable String serial) {
        buyer(buyer);
        service.deleteDevice(serial);
    }

    @DeleteMapping("/{serial}/assignment")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public void unassign(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {
        buyer(buyer);
        service.unassign(factoryCode, serial);
    }
}
