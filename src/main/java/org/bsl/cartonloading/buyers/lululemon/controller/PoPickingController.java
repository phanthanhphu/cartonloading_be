package org.bsl.cartonloading.buyers.lululemon.controller;

import java.util.List;
import org.bsl.cartonloading.buyers.lululemon.service.PoPickingService;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickEvent;
import org.bsl.cartonloading.buyers.lululemon.service.PoPickingService.PickRow;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** New Lululemon packing assignment API; operations validate scope again in service. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/po-picking")
@PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
public class PoPickingController {
    private final PoPickingService service;
    public PoPickingController(PoPickingService service) { this.service = service; }
    public record PickRequest(String factoryCode) {}
    @GetMapping public List<PickRow> list(@PathVariable String buyer, @PathVariable String orderId) {
        return service.list(buyer, orderId);
    }
    @PostMapping("/{poNumber}/pick")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public PickRow pick(@PathVariable String buyer, @PathVariable String orderId,
                 @PathVariable String poNumber, @RequestBody PickRequest request) {
        return service.pick(buyer, orderId, poNumber, request.factoryCode());
    }
    @GetMapping("/{poNumber}/history")
    public List<PoPickEvent> history(@PathVariable String buyer, @PathVariable String orderId,
                                     @PathVariable String poNumber) {
        return service.history(buyer, orderId, poNumber);
    }

    public record UnpickRequest(String reason) {}

    @PostMapping("/{poNumber}/unpick")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public PickRow unpick(@PathVariable String buyer, @PathVariable String orderId, @PathVariable String poNumber,
                          @RequestBody(required = false) UnpickRequest request) {
        return service.unpick(buyer, orderId, poNumber, request == null ? null : request.reason());
    }
}
