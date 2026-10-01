package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.dto.management.DashboardSummary;
import org.bsl.cartonloading.service.CartonDashboardService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/carton-dashboard")
public class CartonDashboardController {
    private final CartonDashboardService service;
    public CartonDashboardController(CartonDashboardService service) { this.service = service; }
    @GetMapping("/summary") public DashboardSummary summary() { return service.summary(); }
}
