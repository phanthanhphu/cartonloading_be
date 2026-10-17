package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.service.LululemonOperationsDashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only, authenticated LULULEMON status dashboard. Factory-level scoping in the service. */
@RestController
@RequestMapping("/api/buyers/LULULEMON/lululemon/operations-dashboard")
public class LululemonOperationsDashboardController {
    private final LululemonOperationsDashboardService service;
    public LululemonOperationsDashboardController(LululemonOperationsDashboardService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer('LULULEMON')")
    public LululemonOperationsDashboardService.Overview overview() {
        return service.overview();
    }
}
