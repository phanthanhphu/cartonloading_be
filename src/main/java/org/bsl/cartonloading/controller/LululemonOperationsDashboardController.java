package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonOperationsDashboardService;
import org.bsl.cartonloading.service.facade.LululemonOperationsDashboardFacade;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Read-only, authenticated LULULEMON status dashboard. Factory-level scoping in the service. */
@RestController
@RequestMapping("/api/buyers/LULULEMON/lululemon/operations-dashboard")
public class LululemonOperationsDashboardController {
    private final LululemonOperationsDashboardFacade service;
    public LululemonOperationsDashboardController(LululemonOperationsDashboardFacade service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer('LULULEMON')")
    public LululemonOperationsDashboardService.Overview overview() {
        return service.forBuyer(null, impl -> impl.overview());
    }
}
