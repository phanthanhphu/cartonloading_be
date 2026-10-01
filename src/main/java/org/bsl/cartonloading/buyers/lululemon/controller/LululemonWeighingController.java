package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightEvent;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeightProfile;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonWeighingOrder;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonWeighingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/orders/{orderId}/weighing")
public class LululemonWeighingController {
    private final LululemonWeighingService service;

    public LululemonWeighingController(LululemonWeighingService service) {
        this.service = service;
    }

    @GetMapping("/eligible-pos")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<LululemonWeighingEligiblePo> eligiblePos(@PathVariable String buyer, @PathVariable String orderId) {
        return service.eligiblePos(buyer, orderId);
    }

    @PutMapping("/profiles/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public LululemonWeightProfile saveProfile(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody LululemonWeightProfileRequest request
    ) {
        return service.saveProfile(buyer, orderId, poId, request);
    }

    @GetMapping("/orders")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<LululemonWeighingOrder> listOrders(@PathVariable String buyer, @PathVariable String orderId) {
        return service.listWeighingOrders(buyer, orderId);
    }

    @PostMapping("/orders")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.canWeightCheck())")
    public LululemonWeighingOrder createOrder(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody LululemonWeighingOrderCreateRequest request
    ) {
        return service.createWeighingOrder(buyer, orderId, request);
    }

    @PostMapping("/orders/{weighingOrderId}/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public LululemonWeighingLookupResponse lookup(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody LululemonWeighingLookupRequest request
    ) {
        return service.lookup(buyer, orderId, weighingOrderId, request);
    }

    @PostMapping("/orders/{weighingOrderId}/weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public LululemonWeightSubmitResponse submitWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody LululemonWeightSubmitRequest request
    ) {
        return service.submitWeight(buyer, orderId, weighingOrderId, request);
    }

    /** Same contract for the PLC/scale bridge. The request must carry stable=true. */
    @PostMapping("/orders/{weighingOrderId}/plc-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public LululemonWeightSubmitResponse submitPlcWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody LululemonWeightSubmitRequest request
    ) {
        return service.submitWeight(buyer, orderId, weighingOrderId,
                new LululemonWeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), "PLC"));
    }

    @PostMapping("/orders/{weighingOrderId}/cartons/{cartonId}/reweigh")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public LululemonCarton reopenWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @PathVariable String cartonId,
            @RequestBody LululemonExceptionRequest request
    ) {
        return service.reopenWeight(buyer, orderId, weighingOrderId, cartonId, request);
    }

    @GetMapping("/orders/{weighingOrderId}/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<LululemonWeightEvent> history(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId
    ) {
        return service.history(buyer, orderId, weighingOrderId);
    }
}
