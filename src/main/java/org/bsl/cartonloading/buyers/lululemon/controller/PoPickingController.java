package org.bsl.cartonloading.buyers.lululemon.controller;

import java.util.List;
import org.bsl.cartonloading.buyers.lululemon.service.PoPickingService;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickEvent;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** PO picking was retired; historic pick/unpick events remain readable for audit only. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/po-picking")
@PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
public class PoPickingController {
    private final PoPickingService service;
    public PoPickingController(PoPickingService service) { this.service = service; }

    @GetMapping("/{poNumber}/history")
    public List<PoPickEvent> history(@PathVariable String buyer, @PathVariable String orderId,
                                     @PathVariable String poNumber) {
        return service.history(buyer, orderId, poNumber);
    }
}
