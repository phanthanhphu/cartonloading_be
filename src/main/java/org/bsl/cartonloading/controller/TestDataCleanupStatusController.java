package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.facade.LululemonTestCleanupFacade;

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
public class TestDataCleanupStatusController {
    private final LululemonTestCleanupFacade service;

    public TestDataCleanupStatusController(LululemonTestCleanupFacade service) {
        this.service = service;
    }

    @GetMapping("/status")
    @PreAuthorize("@accessControl.isAdmin() and @accessControl.canAccessBuyer(#buyer)")
    public Map<String, Boolean> status(@PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.status(buyer, orderId));
    }
}
