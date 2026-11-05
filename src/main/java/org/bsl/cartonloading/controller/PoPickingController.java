package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.service.facade.PoPickingFacade;

import java.util.List;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickEvent;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** PO picking was retired; historic pick/unpick events remain readable for audit only. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/po-picking")
@PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
public class PoPickingController {
    private final PoPickingFacade service;
    public PoPickingController(PoPickingFacade service) { this.service = service; }

    @GetMapping("/{poNumber}/history")
    public List<PoPickEvent> history(@PathVariable String buyer, @PathVariable String orderId,
                                     @PathVariable String poNumber) {
        return service.forBuyer(buyer, impl -> impl.history(buyer, orderId, poNumber));
    }
}
