package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.service.facade.DeviceStatusFacade;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** HTTP adapter: Buyer validation / dispatch occurs in DeviceStatusFacade. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/device-status")
public class DeviceStatusController {
    private final DeviceStatusFacade service;
    public DeviceStatusController(DeviceStatusFacade service) { this.service = service; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<LululemonDeviceStatusNotifier.DeviceStatus> snapshot(@PathVariable String buyer) {
        return service.forBuyer(buyer, impl -> impl.snapshot());
    }
}
