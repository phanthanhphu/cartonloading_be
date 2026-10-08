package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.dto.PrintRequestCreateRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.PrintRequestStatusRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PrintRequest;
import org.bsl.cartonloading.buyers.lululemon.service.PrintRequestService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_PRINT_REQUESTS)
public class PrintRequestController {
    private final PrintRequestService service;

    public PrintRequestController(PrintRequestService service) {
        this.service = service;
    }

    @PostMapping("/orders/{orderId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public PrintRequest create(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody PrintRequestCreateRequest request
    ) {
        return service.create(buyer, orderId, request);
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canPrintRoom() or @accessControl.canManageSales())")
    public Page<PrintRequest> list(
            @PathVariable String buyer,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String requestNo,
            @RequestParam(required = false) String packingUser,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size
    ) {
        return service.list(buyer, orderId, requestNo, packingUser, factoryCode, poNumber, status, mine, page, size);
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canPrintRoom() or @accessControl.canManageSales())")
    public PrintRequest get(@PathVariable String buyer, @PathVariable String requestId) {
        return service.get(buyer, requestId);
    }

    @PostMapping("/{requestId}/status")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canPrintRoom() or @accessControl.isAdmin())")
    public PrintRequest updateStatus(
            @PathVariable String buyer,
            @PathVariable String requestId,
            @RequestBody PrintRequestStatusRequest request
    ) {
        return service.updateStatus(buyer, requestId, request);
    }

    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public PrintRequest cancel(@PathVariable String buyer, @PathVariable String requestId) {
        return service.cancel(buyer, requestId);
    }

    @DeleteMapping("/{requestId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public void delete(@PathVariable String buyer, @PathVariable String requestId) {
        service.delete(buyer, requestId);
    }
}
