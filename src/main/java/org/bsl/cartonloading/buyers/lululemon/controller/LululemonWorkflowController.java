package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonScanEvent;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonWorkflowService;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}")
public class LululemonWorkflowController {
    private final LululemonWorkflowService service;

    public LululemonWorkflowController(LululemonWorkflowService service) {
        this.service = service;
    }

    @PostMapping(value = "/all-bp/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public LululemonImportResult importAllBp(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean replace
    ) {
        return service.importAllBp(buyer, orderId, file, replace);
    }

    @GetMapping("/pos")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public Page<LululemonPoSummary> listPos(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.listPos(buyer, orderId, keyword, status, page, size);
    }

    @GetMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public LululemonPoDetailResponse getPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.getPo(buyer, orderId, poId);
    }

    @PutMapping("/pos/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public LululemonPo updatePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody LululemonPoUpdateRequest request
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
    public List<LululemonCartonItem> items(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.listItems(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonCarton startCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.startCarton(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/items/scan")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonItemScanResponse scanItem(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody LululemonItemScanRequest request
    ) {
        return service.scanItem(buyer, orderId, cartonId, request);
    }

    @DeleteMapping("/cartons/{cartonId}/items/last")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonCarton undoLast(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.undoLastItem(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/finish")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonCarton finishCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.finishCarton(buyer, orderId, cartonId);
    }

    @PostMapping(value = "/shipping-list/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public LululemonImportResult importShippingList(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestPart("file") MultipartFile file
    ) {
        return service.importShippingList(buyer, orderId, file);
    }

    @PutMapping("/shipping/ex-fty")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public LululemonPo updateExFty(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LululemonShippingUpdateRequest request
    ) {
        return service.updateExFtyDate(buyer, orderId, request);
    }

    @PostMapping("/carton-label/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonLabelLookupResponse lookupLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LululemonLabelLookupRequest request
    ) {
        return service.lookupCartonLabel(buyer, orderId, request);
    }

    @PostMapping("/cartons/{cartonId}/carton-label/confirm")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonCarton confirmLabel(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody LululemonConfirmLabelRequest request
    ) {
        return service.confirmCartonLabel(buyer, orderId, cartonId, request);
    }

    @PostMapping("/cartons/{cartonId}/sscc")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public LululemonCarton assignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody LululemonAssignSsccRequest request
    ) {
        return service.assignSscc(buyer, orderId, cartonId, request);
    }

    @PostMapping("/pos/{poId}/reset-sku")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public LululemonPo resetPoSku(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody LululemonExceptionRequest request
    ) {
        return service.resetPoSku(buyer, orderId, poId, request);
    }

    @PostMapping("/cartons/{cartonId}/reopen")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public LululemonCarton reopenCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody LululemonExceptionRequest request
    ) {
        return service.reopenCarton(buyer, orderId, cartonId, request);
    }

    @PostMapping("/cartons/{cartonId}/sscc/unassign")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public LululemonCarton unassignSscc(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody LululemonExceptionRequest request
    ) {
        return service.unassignSscc(buyer, orderId, cartonId, request);
    }

    @GetMapping("/trace")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<LululemonTraceResult> trace(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam String keyword
    ) {
        return service.trace(buyer, orderId, keyword);
    }

    @GetMapping("/scan-events")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<LululemonScanEvent> recentEvents(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.recentEvents(buyer, orderId);
    }
}
