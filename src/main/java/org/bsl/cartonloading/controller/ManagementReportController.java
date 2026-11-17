package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.service.ManagementReportService;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ManagementReportFacade;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/buyers/LULULEMON/lululemon/management-reports")
public class ManagementReportController {
    private final ManagementReportFacade reports;
    public ManagementReportController(ManagementReportFacade reports) { this.reports = reports; }

    private ManagementReportService.Filters filters(LocalDate from, LocalDate to, String factory,
                                                     String shippingDate, String orderId, String status) {
        return new ManagementReportService.Filters(from, to, factory, shippingDate, orderId, status);
    }

    @GetMapping
    @PreAuthorize("@accessControl.canLululemonViewManagementReports()")
    public ManagementReportService.Report report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String shippingDate,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reports.forBuyer(null, impl -> impl.report(filters(from, to, factory, shippingDate, orderId, status), page, size));
    }

    @GetMapping("/dashboard-summary")
    @PreAuthorize("@accessControl.canLululemonViewManagementReports()")
    public ManagementReportService.DashboardSummary dashboardSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String shippingDate,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) {
        return reports.forBuyer(null, impl -> impl.dashboardSummary(filters(from, to, factory, shippingDate, orderId, status)));
    }

    @GetMapping("/dashboard-options")
    @PreAuthorize("@accessControl.canLululemonViewManagementReports()")
    public ManagementReportService.Options dashboardOptions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String factory) {
        return reports.forBuyer(null, impl -> impl.dashboardOptions(filters(from, to, factory, "", "", "")));
    }

    @GetMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canLululemonViewManagementReports()")
    public ManagementReportService.PoDetail detail(
            @PathVariable String poId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String shippingDate,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reports.forBuyer(null, impl -> impl.poDetail(filters(from, to, factory, shippingDate, orderId, status), poId, shippingDate, page, size));
    }

    @GetMapping("/pos/{poId}/cartons/{cartonId}/history")
    @PreAuthorize("@accessControl.canLululemonViewManagementReports()")
    public ManagementReportService.CartonActivity cartonHistory(
            @PathVariable String poId, @PathVariable String cartonId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String shippingDate,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) {
        return reports.forBuyer(null, impl -> impl.cartonHistory(filters(from, to, factory, shippingDate, orderId, status),
                poId, shippingDate, cartonId));
    }

    @GetMapping(value = "/pos/{poId}/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @PreAuthorize("@accessControl.canLululemonExportManagementReports()")
    public ResponseEntity<byte[]> exportPo(@PathVariable String poId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String shippingDate,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) throws IOException {
        byte[] bytes = reports.forBuyer(null, impl -> impl.exportPo(filters(from, to, factory, shippingDate, orderId, status), poId, shippingDate));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=PO_" + poId + "_Carton_Details_" + shippingDate + ".xlsx")
                .body(bytes);
    }

    @GetMapping(value = "/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @PreAuthorize("@accessControl.canLululemonExportManagementReports()")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String factory,
            @RequestParam(required = false) String shippingDate,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String status) throws IOException {
        byte[] bytes = reports.forBuyer(null, impl -> impl.export(filters(from, to, factory, shippingDate, orderId, status)));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=Shipping_Order_Report_" + from + "_" + to + ".xlsx")
                .body(bytes);
    }
}
