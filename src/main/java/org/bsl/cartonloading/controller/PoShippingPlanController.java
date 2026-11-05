package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.facade.PoShippingPlanFacade;
import org.bsl.cartonloading.service.OrderManagementService;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.springframework.data.domain.Page;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.service.PoShippingPlanService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}/po-shipping-plans")
public class PoShippingPlanController {
    private final PoShippingPlanFacade service;
    private final OrderManagementService orderManagement;
    public PoShippingPlanController(PoShippingPlanFacade service, OrderManagementService orderManagement) {
        this.service = service;
        this.orderManagement = orderManagement;
    }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<PoShippingPlanService.ShippingDay> list(@PathVariable String buyer, @PathVariable String orderId,
                                                         @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.list(buyer, orderId, factory));
    }

    @GetMapping("/{day}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public PoShippingPlanService.ShippingDayDetail detail(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.detail(buyer, orderId, factory, day));
    }

    /** Read-only PO table for one Shipping Date; same row projection as All POs. */
    @GetMapping("/{day}/pos")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public Page<PoManagementRow> shippingDatePos(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String factoryFilter,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exFtyDate,
            @RequestParam(required = false) String inspectionStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        // Existing Shipping Days permission/Factory/date checks remain authoritative.
        PoShippingPlanService.ShippingDayDetail scoped = service.forBuyer(buyer,
                impl -> impl.detail(buyer, orderId, factory, day));
        return orderManagement.listShippingSchedulePos(buyer, orderId, scoped.factoryCode(), day,
                poNumber, factoryFilter, style, sku, destination, status, exFtyDate, inspectionStatus, page, size);
    }

    @PostMapping
    // This endpoint serves both new dates and adding to existing ones. The service checks the appropriate grant.
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canCreateShippingSchedule() or @accessControl.canManageShipping())")
    public Map<String, Object> schedule(@PathVariable String buyer, @PathVariable String orderId,
                                        @RequestBody PoShippingPlanRequest request) {
        return service.forBuyer(buyer, impl -> impl.schedule(buyer, orderId, request));
    }

    @PostMapping("/{day}/submit")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public PoShippingPlanService.ShippingDay submit(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.submitDay(buyer, orderId, factory, day));
    }

    @PostMapping("/{day}/unsubmit")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canReopenShippingSchedule()")
    public PoShippingPlanService.ShippingDay unsubmit(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.unsubmitDay(buyer, orderId, factory, day));
    }

    @DeleteMapping("/{day}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public Map<String, Object> deleteDay(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.deleteDay(buyer, orderId, factory, day));
    }

    @DeleteMapping("/{day}/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public PoShippingPlanService.ShippingDayDetail removePo(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @PathVariable String poId, @RequestParam(required = false) String factory) {
        return service.forBuyer(buyer, impl -> impl.removePo(buyer, orderId, factory, day, poId));
    }

    @GetMapping("/{day}/export")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public ResponseEntity<byte[]> exportExcel(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) List<String> columns) {
        byte[] data = service.forBuyer(buyer, impl -> impl.exportExcel(buyer, orderId, factory, day, columns));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Shipping_" + day + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }
}
