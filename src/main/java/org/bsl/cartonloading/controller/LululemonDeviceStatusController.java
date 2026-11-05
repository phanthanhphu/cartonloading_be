package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** Read-only, non-probing last-observed status for Device Management. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/device-status")
public class LululemonDeviceStatusController {
    private final LululemonDeviceStatusNotifier notifier;
    public LululemonDeviceStatusController(LululemonDeviceStatusNotifier notifier) { this.notifier = notifier; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<LululemonDeviceStatusNotifier.DeviceStatus> snapshot(@PathVariable String buyer) {
        return notifier.snapshot();
    }
}
