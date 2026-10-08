package org.bsl.cartonloading.buyers.es.controller;

import org.bsl.cartonloading.common.constants.AppMessages;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.common.constants.AppConstants;

import jakarta.validation.Valid;
import org.bsl.cartonloading.common.importing.ImportMode;
import org.bsl.cartonloading.common.importing.SpreadsheetImportResult;
import org.bsl.cartonloading.buyers.es.dto.PackingAllocationRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingListGenerationResult;
import org.bsl.cartonloading.buyers.es.dto.PackingListLineRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderResponse;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.service.OrderPackingExcelExportService;
import org.bsl.cartonloading.buyers.es.service.PackingAllocationService;
import org.bsl.cartonloading.buyers.es.service.PackingListLineService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

@Tag(name = AppConstants.WorkflowStage.SALES_MASTER)
@RestController
@Validated
@RequestMapping({AppConstants.ApiPath.BARCODE_WORKFLOW_ORDERS, AppConstants.ApiPath.BARCODE_WORKFLOW_PACKING_ORDERS})
public class PackingListController {
    private final PackingOrderService orderService;
    private final PackingAllocationService masterService;
    private final PackingListLineService packingService;
    private final OrderPackingExcelExportService exportService;

    public PackingListController(
            PackingOrderService orderService,
            PackingAllocationService masterService,
            PackingListLineService packingService,
            OrderPackingExcelExportService exportService
    ) {
        this.orderService = orderService;
        this.masterService = masterService;
        this.packingService = packingService;
        this.exportService = exportService;
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping
    public Page<PackingOrderResponse> listOrders(
            @PathVariable String buyer,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderDate,
            @RequestParam(required = false) String orderName,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assignmentStatus,
            @RequestParam(required = false) Boolean completed,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size
    ) {
        return orderService.list(
                requiredBuyer(buyer), keyword, orderDate, orderName, createdBy, status, assignmentStatus, completed, page, size
        );
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{orderId}")
    public PackingOrderResponse getOrder(@PathVariable String buyer, @PathVariable String orderId) {
        return orderService.getResponse(requiredBuyer(buyer), orderId);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping
    public ResponseEntity<PackingOrderResponse> createOrder(
            @PathVariable String buyer,
            @Valid @RequestBody PackingOrderRequest request
    ) {
        return ResponseEntity.ok(orderService.create(requiredBuyer(buyer), request));
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PutMapping("/{orderId}")
    public PackingOrderResponse updateOrder(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @Valid @RequestBody PackingOrderRequest request
    ) {
        return orderService.update(requiredBuyer(buyer), orderId, request);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteOrder(@PathVariable String buyer, @PathVariable String orderId) {
        orderService.delete(requiredBuyer(buyer), orderId);
        return ResponseEntity.noContent().build();
    }

    /* Order Items (ALLOCATION) */

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping({"/{orderId}/master-lines", "/{orderId}/allocation-lines"})
    public Page<PackingAllocationLine> listMasterLines(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String articleNumber,
            @RequestParam(required = false) String styleNumber,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) String sizeValue,
            @RequestParam(required = false) String shipmentMode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size
    ) {
        return masterService.list(
                requiredBuyer(buyer), orderId, keyword, poNumber, articleNumber, styleNumber,
                color, sizeValue, shipmentMode, status, page, size
        );
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping({"/{orderId}/master-lines/{lineId}", "/{orderId}/allocation-lines/{lineId}"})
    public PackingAllocationLine getMasterLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String lineId
    ) {
        return masterService.get(requiredBuyer(buyer), orderId, lineId);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping({"/{orderId}/master-lines", "/{orderId}/allocation-lines"})
    public ResponseEntity<PackingAllocationLine> createMasterLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @Valid @RequestBody PackingAllocationRequest request
    ) {
        return ResponseEntity.ok(masterService.create(requiredBuyer(buyer), orderId, request));
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PutMapping({"/{orderId}/master-lines/{lineId}", "/{orderId}/allocation-lines/{lineId}"})
    public PackingAllocationLine updateMasterLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String lineId,
            @Valid @RequestBody PackingAllocationRequest request
    ) {
        return masterService.update(requiredBuyer(buyer), orderId, lineId, request);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @DeleteMapping({"/{orderId}/master-lines/{lineId}", "/{orderId}/allocation-lines/{lineId}"})
    public ResponseEntity<Void> deleteMasterLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String lineId
    ) {
        masterService.delete(requiredBuyer(buyer), orderId, lineId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping(value = {"/{orderId}/master-lines/import", "/{orderId}/allocation-lines/import"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SpreadsheetImportResult> importMasterLines(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.RequestDefault.CREATE_ONLY) ImportMode mode
    ) {
        SpreadsheetImportResult result = masterService.upload(requiredBuyer(buyer), orderId, file, mode);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{orderId}/master-lines/export")
    public ResponseEntity<byte[]> exportMaster(@PathVariable String buyer, @PathVariable String orderId) {
        byte[] content = exportService.exportMaster(requiredBuyer(buyer), orderId);
        return download(content, "ORDER_MASTER_" + orderId + AppConstants.Importing.EXCEL_EXTENSION_XLSX);
    }

    /* Packing List inside the Order */

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{orderId}/packing-lines")
    public Page<PackingListLine> listPackingLines(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String articleNumber,
            @RequestParam(required = false) String styleNumber,
            @RequestParam(required = false) String color,
            @RequestParam(required = false) String sizeValue,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size
    ) {
        return packingService.list(requiredBuyer(buyer), orderId, keyword, poNumber, articleNumber,
                styleNumber, color, sizeValue, page, size);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping("/{orderId}/packing-lines")
    public ResponseEntity<PackingListLine> createPackingLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @Valid @RequestBody PackingListLineRequest request
    ) {
        return ResponseEntity.ok(packingService.create(requiredBuyer(buyer), orderId, request));
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PutMapping("/{orderId}/packing-lines/{lineId}")
    public PackingListLine updatePackingLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String lineId,
            @Valid @RequestBody PackingListLineRequest request
    ) {
        return packingService.update(requiredBuyer(buyer), orderId, lineId, request);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @DeleteMapping("/{orderId}/packing-lines/{lineId}")
    public ResponseEntity<Void> deletePackingLine(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String lineId
    ) {
        packingService.delete(requiredBuyer(buyer), orderId, lineId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping("/{orderId}/packing-lines/generate")
    public PackingListGenerationResult generatePackingList(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(defaultValue = AppConstants.RequestDefault.TRUE) boolean replace
    ) {
        return packingService.generateFromMaster(requiredBuyer(buyer), orderId, replace);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping(value = "/{orderId}/packing-lines/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SpreadsheetImportResult> importPackingLines(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.RequestDefault.CREATE_ONLY) ImportMode mode
    ) {
        SpreadsheetImportResult result = packingService.upload(requiredBuyer(buyer), orderId, file, mode);
        return result.isApplied() ? ResponseEntity.ok(result) : ResponseEntity.badRequest().body(result);
    }

    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{orderId}/packing-lines/export")
    public ResponseEntity<byte[]> exportPackingList(@PathVariable String buyer, @PathVariable String orderId) {
        byte[] content = exportService.exportPackingList(requiredBuyer(buyer), orderId);
        return download(content, "PACKING_LIST_" + orderId + AppConstants.Importing.EXCEL_EXTENSION_XLSX);
    }

    private ResponseEntity<byte[]> download(byte[] content, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(AppConstants.MimeType.XLSX))
                .contentLength(content.length)
                .body(content);
    }

    private String requiredBuyer(String value) {
        String normalized = BuyerAccess.normalize(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(AppMessages.unsupportedBuyer(value));
        return normalized;
    }
}
