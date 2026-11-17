package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.service.facade.PoShippingPlanFacade;
import org.bsl.cartonloading.service.OrderManagementService;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.springframework.data.domain.Page;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.service.PoShippingPlanService;
import org.bsl.cartonloading.buyers.lululemon.service.ShippingScheduleQcReportService;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;


@Service
public class PoShippingPlanOperationsService {
    private final PoShippingPlanFacade service;
    private final OrderManagementService orderManagement;
    private final ShippingScheduleQcReportService qcReport;
    public PoShippingPlanOperationsService(PoShippingPlanFacade service, OrderManagementService orderManagement,
                                           ShippingScheduleQcReportService qcReport) {
        this.service = service;
        this.orderManagement = orderManagement;
        this.qcReport = qcReport;
    }


    public List<PoShippingPlanService.ShippingDay> list(String buyer, String orderId,
                                                         String factory) {
        return service.forBuyer(buyer, impl -> impl.list(buyer, orderId, factory));
    }


    public PoShippingPlanService.ShippingDayDetail detail(String buyer, String orderId,
            LocalDate day,
            String factory) {
        return service.forBuyer(buyer, impl -> impl.detail(buyer, orderId, factory, day));
    }

    /** Read-only PO table for one Shipping Date; same row projection as All POs. */


    public Page<PoManagementRow> shippingDatePos(String buyer, String orderId,
            LocalDate day,
            String factory,
            String poNumber,
            String factoryFilter,
            String style,
            String sku,
            String destination,
            String status,
            LocalDate exFtyDate,
            String inspectionStatus,
            String cartonStatus,
            String ssccStatus,
            String skuStatus,
            String rfidStatus,
            LocalDate scanDate,
            int page,
            int size) {
        // Existing Shipping Days permission/Factory/date checks remain authoritative.
        PoShippingPlanService.ShippingDayDetail scoped = service.forBuyer(buyer,
                impl -> impl.detail(buyer, orderId, factory, day));
        Set<String> qcAllowed = qcMatchingIds(scoped, orderId, cartonStatus, ssccStatus, skuStatus, rfidStatus, scanDate);
        return orderManagement.listShippingSchedulePos(buyer, orderId, scoped.factoryCode(), day,
                poNumber, factoryFilter, style, sku, destination, status, exFtyDate, inspectionStatus, qcAllowed, page, size);
    }

    /** All carton/assignment/QC counters scoped to this already authorized Shipping Day. */


    public ShippingScheduleQcReportService.Report qcReport(String buyer, String orderId,
            LocalDate day,
            String factory,
            LocalDate scanDate) {
        PoShippingPlanService.ShippingDayDetail scoped = service.forBuyer(buyer,
                impl -> impl.detail(buyer, orderId, factory, day));
        return qcReport.report(scoped, orderId, scanDate);
    }

    private Set<String> qcMatchingIds(PoShippingPlanService.ShippingDayDetail scoped, String orderId,
            String cartonStatus, String ssccStatus, String skuStatus, String rfidStatus, LocalDate scanDate) {
        if (blank(cartonStatus) && blank(ssccStatus) && blank(skuStatus) && blank(rfidStatus) && scanDate == null) return null;
        validateQcFilter("Carton", cartonStatus, "COMPLETED", "INCOMPLETE");
        validateQcFilter("SSCC", ssccStatus, "ASSIGNED", "MISSING");
        validateQcFilter("SKU", skuStatus, "ASSIGNED", "MISSING");
        validateQcFilter("RFID", rfidStatus, "PASS", "FAILED", "NOT_SCANNED", "MISSING_TAGS");
        ShippingScheduleQcReportService.Report report = qcReport.report(scoped, orderId, scanDate);
        Set<String> scanned = scanDate == null ? null : report.pos().stream()
                .filter(p -> p.scanDayCartons() > 0).map(ShippingScheduleQcReportService.PoQc::poId)
                .collect(Collectors.toSet());
        return report.cartons().stream()
                .filter(c -> scanned == null || scanned.contains(c.poId()))
                .filter(c -> ShippingScheduleQcReportService.matchesCarton(c, cartonStatus, ssccStatus, skuStatus, rfidStatus, scanDate))
                .map(ShippingScheduleQcReportService.CartonQc::poId).collect(Collectors.toSet());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validateQcFilter(String name, String value, String... allowed) {
        if (!blank(value) && java.util.Arrays.stream(allowed).noneMatch(value::equals))
            throw new IllegalArgumentException("Invalid " + name + " QC filter.");
    }

    // This endpoint serves both new dates and adding to existing ones. The service checks the appropriate grant.

    public Map<String, Object> schedule(String buyer, String orderId,
                                        PoShippingPlanRequest request) {
        return service.forBuyer(buyer, impl -> impl.schedule(buyer, orderId, request));
    }


    public PoShippingPlanService.ShippingDay submit(String buyer, String orderId,
            LocalDate day,
            String factory) {
        return service.forBuyer(buyer, impl -> impl.submitDay(buyer, orderId, factory, day));
    }


    public PoShippingPlanService.ShippingDay unsubmit(String buyer, String orderId,
            LocalDate day,
            String factory) {
        return service.forBuyer(buyer, impl -> impl.unsubmitDay(buyer, orderId, factory, day));
    }


    public Map<String, Object> deleteDay(String buyer, String orderId,
            LocalDate day,
            String factory) {
        return service.forBuyer(buyer, impl -> impl.deleteDay(buyer, orderId, factory, day));
    }


    public PoShippingPlanService.ShippingDayDetail removePo(String buyer, String orderId,
            LocalDate day,
            String poId, String factory) {
        return service.forBuyer(buyer, impl -> impl.removePo(buyer, orderId, factory, day, poId));
    }


    public ResponseEntity<byte[]> exportExcel(String buyer, String orderId,
            LocalDate day,
            String factory,
            List<String> columns,
            String poNumber,
            String factoryFilter,
            String style,
            String sku,
            String destination,
            String status,
            LocalDate exFtyDate,
            String inspectionStatus,
            String cartonStatus,
            String ssccStatus,
            String skuStatus,
            String rfidStatus,
            LocalDate scanDate,
            boolean includeQcColumns,
            boolean includeSsccAssigned,
            boolean includeSkuItemsAssigned) {
        PoShippingPlanService.ShippingDayDetail scoped = service.forBuyer(buyer,
                impl -> impl.detail(buyer, orderId, factory, day));
        validateQcFilter("Carton", cartonStatus, "COMPLETED", "INCOMPLETE");
        validateQcFilter("SSCC", ssccStatus, "ASSIGNED", "MISSING");
        validateQcFilter("SKU", skuStatus, "ASSIGNED", "MISSING");
        validateQcFilter("RFID", rfidStatus, "PASS", "FAILED", "NOT_SCANNED", "MISSING_TAGS");
        ShippingScheduleQcReportService.Report report = qcReport.report(scoped, orderId, scanDate);
        Set<String> scanned = scanDate == null ? null : report.pos().stream()
                .filter(p -> p.scanDayCartons() > 0).map(ShippingScheduleQcReportService.PoQc::poId)
                .collect(Collectors.toSet());
        Set<String> allowed = report.cartons().stream()
                .filter(c -> scanned == null || scanned.contains(c.poId()))
                .filter(c -> ShippingScheduleQcReportService.matchesCarton(c, cartonStatus, ssccStatus, skuStatus, rfidStatus, scanDate))
                .map(ShippingScheduleQcReportService.CartonQc::poId).collect(Collectors.toSet());
        // Existing PO filters follow the same server logic and are applied before Excel is generated.
        if (!blank(poNumber) || !blank(factoryFilter) || !blank(style) || !blank(sku) ||
                !blank(destination) || !blank(status) || exFtyDate != null || !blank(inspectionStatus)) {
            Set<String> matchingExisting = new HashSet<>();
            int page = 0;
            Page<PoManagementRow> batch;
            do {
                batch = orderManagement.listShippingSchedulePos(buyer, orderId, scoped.factoryCode(), day,
                        poNumber, factoryFilter, style, sku, destination, status, exFtyDate, inspectionStatus,
                        allowed, page++, 200);
                batch.getContent().forEach(p -> matchingExisting.add(p.id()));
            } while (page < batch.getTotalPages());
            allowed.retainAll(matchingExisting);
        }
        byte[] original = service.forBuyer(buyer,
                impl -> impl.exportExcel(buyer, orderId, scoped.factoryCode(), day, columns, allowed));
        // Both assignment fields are opt-in. Export stays on the original Shipping POs sheet.
        byte[] data = includeQcColumns || includeSsccAssigned || includeSkuItemsAssigned
                ? qcReport.appendExcel(original, report, allowed, scanDate,
                    includeQcColumns, includeSsccAssigned, includeSkuItemsAssigned)
                : original;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Shipping_" + day + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }
}
