package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.LululemonPrintRequestCreateRequest;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPrintRequest;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonPrintRequestService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/print-requests")
public class LululemonPrintRequestController {
    private final LululemonPrintRequestService service;

    public LululemonPrintRequestController(LululemonPrintRequestService service) {
        this.service = service;
    }

    @PostMapping("/orders/{orderId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public LululemonPrintRequest create(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LululemonPrintRequestCreateRequest request
    ) {
        return service.create(buyer, orderId, request);
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canPrintRoom() or @accessControl.canManageSales())")
    public Page<LululemonPrintRequest> list(
            @PathVariable String buyer,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String requestNo,
            @RequestParam(required = false) String packingUser,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        return service.list(buyer, orderId, requestNo, packingUser, factoryCode, poNumber, status, mine, page, size);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canPrintRoom() or @accessControl.canManageSales())")
    public LululemonPrintRequest get(@PathVariable String buyer, @PathVariable String requestId) {
        return service.get(buyer, requestId);
    }

    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public LululemonPrintRequest cancel(@PathVariable String buyer, @PathVariable String requestId) {
        return service.cancel(buyer, requestId);
    }
}
