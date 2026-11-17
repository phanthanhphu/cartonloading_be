package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.facade.PoShippingPlanOperationsFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.service.facade.PoShippingPlanFacade;
import org.bsl.cartonloading.service.OrderManagementService;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.springframework.data.domain.Page;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.service.PoShippingPlanService;
import org.bsl.cartonloading.buyers.lululemon.service.ShippingScheduleQcReportService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
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
    private final PoShippingPlanOperationsFacade actions;
    public PoShippingPlanController(PoShippingPlanOperationsFacade actions) { this.actions = actions; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<PoShippingPlanService.ShippingDay> list(@PathVariable String buyer, @PathVariable String orderId,
                                                         @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.list(buyer, orderId, factory));
    }

    @GetMapping("/{day}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public PoShippingPlanService.ShippingDayDetail detail(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.detail(buyer, orderId, day, factory));
    }

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
            @RequestParam(required = false) String cartonStatus,
            @RequestParam(required = false) String ssccStatus,
            @RequestParam(required = false) String skuStatus,
            @RequestParam(required = false) String rfidStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scanDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return actions.forBuyer(buyer, impl -> impl.shippingDatePos(buyer, orderId, day, factory, poNumber, factoryFilter, style, sku, destination, status, exFtyDate, inspectionStatus, cartonStatus, ssccStatus, skuStatus, rfidStatus, scanDate, page, size));
    }

    @GetMapping("/{day}/qc-report")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public ShippingScheduleQcReportService.Report qcReport(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scanDate) {
        return actions.forBuyer(buyer, impl -> impl.qcReport(buyer, orderId, day, factory, scanDate));
    }

    @PostMapping
    // This endpoint serves both new dates and adding to existing ones. The service checks the appropriate grant.
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canCreateShippingSchedule() or @accessControl.canManageShipping())")
    public Map<String, Object> schedule(@PathVariable String buyer, @PathVariable String orderId,
                                        @RequestBody PoShippingPlanRequest request) {
        return actions.forBuyer(buyer, impl -> impl.schedule(buyer, orderId, request));
    }

    @PostMapping("/{day}/submit")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public PoShippingPlanService.ShippingDay submit(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.submit(buyer, orderId, day, factory));
    }

    @PostMapping("/{day}/unsubmit")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canReopenShippingSchedule()")
    public PoShippingPlanService.ShippingDay unsubmit(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.unsubmit(buyer, orderId, day, factory));
    }

    @DeleteMapping("/{day}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public Map<String, Object> deleteDay(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.deleteDay(buyer, orderId, day, factory));
    }

    @DeleteMapping("/{day}/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageShipping()")
    public PoShippingPlanService.ShippingDayDetail removePo(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @PathVariable String poId, @RequestParam(required = false) String factory) {
        return actions.forBuyer(buyer, impl -> impl.removePo(buyer, orderId, day, poId, factory));
    }

    @GetMapping("/{day}/export")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public ResponseEntity<byte[]> exportExcel(@PathVariable String buyer, @PathVariable String orderId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) List<String> columns,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String factoryFilter,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) String sku,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exFtyDate,
            @RequestParam(required = false) String inspectionStatus,
            @RequestParam(required = false) String cartonStatus,
            @RequestParam(required = false) String ssccStatus,
            @RequestParam(required = false) String skuStatus,
            @RequestParam(required = false) String rfidStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scanDate,
            @RequestParam(defaultValue = "true") boolean includeQcColumns,
            @RequestParam(defaultValue = "false") boolean includeSsccAssigned,
            @RequestParam(defaultValue = "false") boolean includeSkuItemsAssigned) {
        return actions.forBuyer(buyer, impl -> impl.exportExcel(buyer, orderId, day, factory, columns, poNumber, factoryFilter, style, sku, destination, status, exFtyDate, inspectionStatus, cartonStatus, ssccStatus, skuStatus, rfidStatus, scanDate, includeQcColumns, includeSsccAssigned, includeSkuItemsAssigned));
    }

}
