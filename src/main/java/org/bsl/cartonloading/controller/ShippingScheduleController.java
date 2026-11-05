package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.facade.ShippingScheduleFacade;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}/shipping-schedules")
public class ShippingScheduleController {
    private final ShippingScheduleFacade service;

    public ShippingScheduleController(ShippingScheduleFacade service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<ShippingScheduleSummary> list(@PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.list(buyer, orderId));
    }

    @GetMapping("/{scheduleId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public ShippingScheduleDetail detail(@PathVariable String buyer, @PathVariable String orderId, @PathVariable String scheduleId) {
        return service.forBuyer(buyer, impl -> impl.detail(buyer, orderId, scheduleId));
    }

    @PostMapping
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail create(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ShippingScheduleCreateRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.create(buyer, orderId, request));
    }

    @DeleteMapping("/{scheduleId}")
    @PreAuthorize("denyAll()")
    public void remove(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        service.forBuyer(buyer, impl -> { impl.remove(buyer, orderId, scheduleId); return null; });
    }

    @PostMapping("/{scheduleId}/check")
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail check(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.forBuyer(buyer, impl -> impl.review(buyer, orderId, scheduleId));
    }

    @PostMapping("/{scheduleId}/send-to-carton-weight")
    @PreAuthorize("denyAll()")
    public ShippingScheduleDetail sendToCartonWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.forBuyer(buyer, impl -> impl.sendToCartonWeight(buyer, orderId, scheduleId));
    }

    @GetMapping("/{scheduleId}/weight-history")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<WeighingResult> weightHistory(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String scheduleId
    ) {
        return service.forBuyer(buyer, impl -> impl.weightHistory(buyer, orderId, scheduleId));
    }
}
