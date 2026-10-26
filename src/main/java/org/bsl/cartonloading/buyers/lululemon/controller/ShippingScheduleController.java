package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.service.ShippingScheduleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}/shipping-schedules")
public class ShippingScheduleController {
    private final ShippingScheduleService service;

    public ShippingScheduleController(ShippingScheduleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.canWeightCheck() or @accessControl.canAssignWeight())")
    public List<ShippingScheduleSummary> list(@PathVariable String buyer, @PathVariable String orderId) {
        return service.list(buyer, orderId);
    }

    @GetMapping("/{scheduleId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.canWeightCheck() or @accessControl.canAssignWeight())")
    public ShippingScheduleDetail detail(@PathVariable String buyer, @PathVariable String orderId, @PathVariable String scheduleId) {
        return service.detail(buyer, orderId, scheduleId);
    }

    @PostMapping
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail create(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ShippingScheduleCreateRequest request
    ) {
        return service.create(buyer, orderId, request);
    }

    @DeleteMapping("/{scheduleId}")
    @PreAuthorize("denyAll()")
    public void remove(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        service.remove(buyer, orderId, scheduleId);
    }

    @PostMapping("/{scheduleId}/check")
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail check(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.review(buyer, orderId, scheduleId);
    }

    @PostMapping("/{scheduleId}/send-to-carton-weight")
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail sendToCartonWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.sendToCartonWeight(buyer, orderId, scheduleId);
    }

    @GetMapping("/{scheduleId}/weight-history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.canWeightCheck() or @accessControl.canAssignWeight())")
    public List<WeighingResult> weightHistory(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.weightHistory(buyer, orderId, scheduleId);
    }
}
