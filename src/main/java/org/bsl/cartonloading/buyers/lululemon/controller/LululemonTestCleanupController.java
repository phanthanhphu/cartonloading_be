package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonTestCleanupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Absent by default. Explicitly enable only on an isolated TEST database. */
@RestController
@ConditionalOnProperty(name = "lululemon.test-cleanup.enabled", havingValue = "true")
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/test-cleanup")
public class LululemonTestCleanupController {
    private final LululemonTestCleanupService service;

    public LululemonTestCleanupController(LululemonTestCleanupService service) {
        this.service = service;
    }

    public record PurgeRequest(String confirmation) {}

    @GetMapping("/preview")
    @PreAuthorize("@accessControl.isAdmin() and @accessControl.canAccessBuyer(#buyer)")
    public Map<String, Object> preview(@PathVariable String buyer, @PathVariable String orderId) {
        return service.preview(buyer, orderId);
    }

    @PostMapping("/purge")
    @PreAuthorize("@accessControl.isAdmin() and @accessControl.canAccessBuyer(#buyer)")
    public Map<String, Object> purge(@PathVariable String buyer,
                                     @PathVariable String orderId,
                                     @RequestBody PurgeRequest request) {
        return service.purge(buyer, orderId, request == null ? null : request.confirmation());
    }
}
