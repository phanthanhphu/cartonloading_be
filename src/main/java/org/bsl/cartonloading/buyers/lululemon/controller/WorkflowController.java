package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.ScanEvent;
import org.bsl.cartonloading.buyers.lululemon.service.WorkflowService;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER)
public class WorkflowController {
    private final WorkflowService service;

    public WorkflowController(WorkflowService service) {
        this.service = service;
    }

    @PostMapping(value = "/all-bp/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public ImportResult importAllBp(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = AppConstants.RequestDefault.TRUE) boolean replace
    ) {
        return service.importAllBp(buyer, orderId, file, replace);
    }

    @GetMapping("/pos")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public Page<PoSummary> listPos(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size
    ) {
        return service.listPos(buyer, orderId, keyword, status, page, size);
    }

    @GetMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public PoDetailResponse getPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.getPo(buyer, orderId, poId);
    }

    @PutMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public PurchaseOrder updatePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody PoUpdateRequest request
    ) {
        return service.updateImportedPo(buyer, orderId, poId, request);
    }

    @DeleteMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public void deletePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        service.deleteImportedPo(buyer, orderId, poId);
    }

    @GetMapping("/cartons/{cartonId}/items")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<CartonItem> items(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.listItems(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton startCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.startCarton(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/items/scan")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public ItemScanResponse scanItem(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ItemScanRequest request
    ) {
        return service.scanItem(buyer, orderId, cartonId, request);
    }

    @DeleteMapping("/cartons/{cartonId}/items/last")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton undoLast(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.undoLastItem(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/finish")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton finishCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.finishCarton(buyer, orderId, cartonId);
    }

    @PostMapping(value = "/shipping-list/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public ImportResult importShippingList(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file
    ) {
        return service.importShippingList(buyer, orderId, file);
    }

    @PutMapping("/shipping/ex-fty")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public PurchaseOrder updateExFty(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ShippingUpdateRequest request
    ) {
        return service.updateExFtyDate(buyer, orderId, request);
    }

    @PostMapping("/carton-label/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LabelLookupResponse lookupLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LabelLookupRequest request
    ) {
        return service.lookupCartonLabel(buyer, orderId, request);
    }

    @PostMapping("/cartons/{cartonId}/quantity/confirm")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton confirmQuantity(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.confirmCartonQuantity(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/carton-label/confirm")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton confirmLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ConfirmLabelRequest request
    ) {
        return service.confirmCartonLabel(buyer, orderId, cartonId, request);
    }

    @PostMapping("/cartons/{cartonId}/sscc")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public Carton assignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody AssignSsccRequest request
    ) {
        return service.assignSscc(buyer, orderId, cartonId, request);
    }

    @PostMapping("/pos/{poId}/reset-sku")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public PurchaseOrder resetPoSku(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody ExceptionRequest request
    ) {
        return service.resetPoSku(buyer, orderId, poId, request);
    }

    @PostMapping("/cartons/{cartonId}/reopen")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public Carton reopenCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.reopenCarton(buyer, orderId, cartonId, request);
    }

    @PostMapping("/cartons/{cartonId}/sscc/unassign")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public Carton unassignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.unassignSscc(buyer, orderId, cartonId, request);
    }

    @GetMapping("/trace")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<TraceResult> trace(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam String keyword
    ) {
        return service.trace(buyer, orderId, keyword);
    }

    @GetMapping("/scan-events")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<ScanEvent> recentEvents(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.recentEvents(buyer, orderId);
    }
}
