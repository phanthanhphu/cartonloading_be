package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonHistoryFilters;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonHistoryRow;
import org.bsl.cartonloading.buyers.lululemon.service.WorkflowService;
import org.bsl.cartonloading.service.facade.WorkflowFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER)
public class WorkflowController {
    private final WorkflowFacade service;

    public WorkflowController(WorkflowFacade service) {
        this.service = service;
    }

    @PostMapping(value = "/all-bp/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public ImportResult importAllBp(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.RequestDefault.TRUE) boolean replace
    ) {
        return service.forBuyer(buyer, impl -> impl.importAllBp(buyer, orderId, file, replace));
    }

    @PostMapping(value = "/all-bp/import-shared", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public ImportResult importSharedAllBp(@PathVariable String buyer, @PathVariable String orderId,
                                           @RequestPart("file") MultipartFile file) {
        return service.forBuyer(buyer, impl -> impl.importSharedAllBp(buyer, orderId, file));
    }

    @GetMapping("/excel-actions/template")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public org.springframework.http.ResponseEntity<byte[]> excelTemplate(@PathVariable String buyer, @PathVariable String orderId) {
        byte[] data = service.forBuyer(buyer, impl -> impl.downloadExcelTemplate(buyer, orderId));
        return excelDownload(data, "Lululemon_PO_Import_Template.xlsx");
    }

    @GetMapping("/excel-actions/export")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public org.springframework.http.ResponseEntity<byte[]> exportExcel(@PathVariable String buyer, @PathVariable String orderId) {
        byte[] data = service.forBuyer(buyer, impl -> impl.exportActionExcel(buyer, orderId));
        String timestamp = java.time.ZonedDateTime.now(java.time.ZoneId.of(AppConstants.Excel.EXPORT_ZONE))
                .format(java.time.format.DateTimeFormatter.ofPattern(AppConstants.Excel.EXPORT_TIMESTAMP_FORMAT));
        return excelDownload(data, AppConstants.Excel.LULULEMON_EXPORT_PREFIX + timestamp + ".xlsx");
    }

    private org.springframework.http.ResponseEntity<byte[]> excelDownload(byte[] data, String filename) {
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @PostMapping(value = "/excel-actions/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public WorkflowService.ExcelActionPreview previewExcelActions(
            @PathVariable String buyer, @PathVariable String orderId, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.ExcelAction.NEW_MODE) String mode) {
        return service.forBuyer(buyer, impl -> impl.previewActionExcel(buyer, orderId, file, mode));
    }

    @PostMapping(value = "/excel-actions/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public WorkflowService.ExcelActionResult applyExcelActions(
            @PathVariable String buyer, @PathVariable String orderId, @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.ExcelAction.NEW_MODE) String mode) {
        return service.forBuyer(buyer, impl -> impl.applyActionExcel(buyer, orderId, file, mode));
    }

    @GetMapping("/pos")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public Page<PoSummary> listPos(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size
    ) {
        return service.forBuyer(buyer, impl -> impl.listPos(buyer, orderId, keyword, status, page, size));
    }

    @GetMapping("/pos/{poId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public PoDetailResponse getPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.forBuyer(buyer, impl -> impl.getPo(buyer, orderId, poId));
    }

    @PutMapping("/pos/{poId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public PurchaseOrder updatePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody PoUpdateRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.updateImportedPo(buyer, orderId, poId, request));
    }

    @DeleteMapping("/pos/{poId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public void deletePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        service.forBuyer(buyer, impl -> { impl.deleteImportedPo(buyer, orderId, poId); return null; });
    }

    @GetMapping("/cartons/{cartonId}/items")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<CartonItem> items(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.forBuyer(buyer, impl -> impl.listItems(buyer, orderId, cartonId));
    }

    @PostMapping("/cartons/{cartonId}/start")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton startCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.forBuyer(buyer, impl -> impl.startCarton(buyer, orderId, cartonId));
    }

    @PostMapping("/cartons/{cartonId}/items/scan")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public ItemScanResponse scanItem(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ItemScanRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.scanItem(buyer, orderId, cartonId, request));
    }

    @DeleteMapping("/cartons/{cartonId}/items/last")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton undoLast(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.forBuyer(buyer, impl -> impl.undoLastItem(buyer, orderId, cartonId));
    }

    @PostMapping("/cartons/{cartonId}/finish")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton finishCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.forBuyer(buyer, impl -> impl.finishCarton(buyer, orderId, cartonId));
    }

    @PostMapping(value = "/shipping-list/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public ImportResult importShippingList(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file
    ) {
        return service.forBuyer(buyer, impl -> impl.importShippingList(buyer, orderId, file));
    }

    @PutMapping("/shipping/ex-fty")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public PurchaseOrder updateExFty(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ShippingUpdateRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.updateExFtyDate(buyer, orderId, request));
    }

    @PostMapping("/carton-label/lookup")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public LabelLookupResponse lookupLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LabelLookupRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.lookupCartonLabel(buyer, orderId, request));
    }

    @PostMapping("/cartons/{cartonId}/quantity/confirm")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton confirmQuantity(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.forBuyer(buyer, impl -> impl.confirmCartonQuantity(buyer, orderId, cartonId));
    }

    @PostMapping("/cartons/{cartonId}/carton-label/confirm")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton confirmLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ConfirmLabelRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.confirmCartonLabel(buyer, orderId, cartonId, request));
    }

    @PostMapping("/cartons/{cartonId}/sscc")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public Carton assignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody AssignSsccRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.assignSscc(buyer, orderId, cartonId, request));
    }

    @PostMapping("/pos/{poId}/reset-sku")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public PurchaseOrder resetPoSku(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.resetPoSku(buyer, orderId, poId, request));
    }

    @PostMapping("/cartons/{cartonId}/reopen")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public Carton reopenCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.reopenCarton(buyer, orderId, cartonId, request));
    }

    @PostMapping("/cartons/{cartonId}/identity/unassign")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public Carton unassignNewIdentity(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.unassignNewCartonIdentity(buyer, orderId, cartonId, request));
    }

    @PostMapping("/cartons/{cartonId}/sscc/unassign")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public Carton unassignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.unassignSscc(buyer, orderId, cartonId, request));
    }

    @GetMapping("/history/filters")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public CartonHistoryFilters historyFilters(
            @PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.historyFilters(buyer, orderId));
    }

    @GetMapping("/history/cartons")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public org.springframework.data.domain.Page<CartonHistoryRow> historyCartons(
            @PathVariable String buyer, @PathVariable String orderId,
            @RequestParam(required = false) String masterPo,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String packingPlan,
            @RequestParam(required = false) String salesOrderPts,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String scannedBy,
            @RequestParam(required = false) String weighedBy,
            @RequestParam(required = false) String rfidStatus,
            @RequestParam(required = false) String weightStatus,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dateFrom,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.forBuyer(buyer, impl -> impl.history(buyer, orderId, masterPo,
                poNumber, packingPlan, salesOrderPts, keyword, status, scannedBy,
                weighedBy, rfidStatus, weightStatus, dateFrom, dateTo, page, size));
    }

    /** Recovery only: explicitly repair one saved completed outcome after a failed projection write. */
    @PostMapping("/history/cartons/{cartonId}/reconcile")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void reconcileCartonHistory(@PathVariable String buyer, @PathVariable String orderId,
                                       @PathVariable String cartonId) {
        service.forBuyer(buyer, impl -> { impl.reconcileCompletedHistory(buyer, orderId, cartonId); return null; });
    }

    @GetMapping("/history/cartons/{cartonId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public TraceResult historyCartonDetail(
            @PathVariable String buyer, @PathVariable String orderId, @PathVariable String cartonId) {
        return service.forBuyer(buyer, impl -> impl.historyDetail(buyer, orderId, cartonId));
    }

    @GetMapping("/trace")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<TraceResult> trace(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam String keyword
    ) {
        return service.forBuyer(buyer, impl -> impl.trace(buyer, orderId, keyword));
    }

    @GetMapping("/scan-events")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<ScanEvent> recentEvents(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.forBuyer(buyer, impl -> impl.recentEvents(buyer, orderId));
    }
}
