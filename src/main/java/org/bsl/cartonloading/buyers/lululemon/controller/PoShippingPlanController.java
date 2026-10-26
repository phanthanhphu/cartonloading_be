package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.service.PoShippingPlanService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Standalone Sales allocation: no Shipping Schedule / Carton Weight transition. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}/po-shipping-plans")
public class PoShippingPlanController {
    private final PoShippingPlanService service;

    public PoShippingPlanController(PoShippingPlanService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public Map<String, Object> schedule(@PathVariable String buyer, @PathVariable String orderId,
                                        @RequestBody PoShippingPlanRequest request) {
        return service.schedule(buyer, orderId, request);
    }
}
