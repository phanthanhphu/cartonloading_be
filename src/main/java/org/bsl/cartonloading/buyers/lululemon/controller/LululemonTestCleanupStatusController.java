package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.service.LululemonTestCleanupService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Always present for ADMIN so the UI can discover that TEST cleanup is disabled.
 * Does not enable the destructive /purge route or query test data when disabled. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/test-cleanup")
public class LululemonTestCleanupStatusController {
    private final LululemonTestCleanupService service;

    public LululemonTestCleanupStatusController(LululemonTestCleanupService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @PreAuthorize("@accessControl.isAdmin() and @accessControl.canAccessBuyer(#buyer)")
    public Map<String, Boolean> status(@PathVariable String buyer, @PathVariable String orderId) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("TEST cleanup status is limited to LULULEMON.");
        }
        if (!service.isTestCleanupEnabled()) {
            return Map.of("enabled", false);
        }
        // When enabled, preserve V6 validation that this Order belongs to LULULEMON.
        service.assertAvailable(buyer, orderId);
        return Map.of("enabled", true);
    }
}
