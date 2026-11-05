package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.service.facade.WeighingFacade;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.model.WeightProfile;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING)
public class WeighingController {
    private final WeighingFacade service;

    public WeighingController(WeighingFacade service) {
        this.service = service;
    }

    @GetMapping("/eligible-pos")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<WeighingEligiblePo> eligiblePos(@PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.eligiblePos(buyer, orderId));
    }

    @PutMapping("/profiles/{poId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_MANAGE_SALES)
    public WeightProfile saveProfile(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeightProfileRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.saveProfile(buyer, orderId, poId, request));
    }

    @GetMapping("/orders")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<WeighingOrder> listOrders(@PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.listWeighingOrders(buyer, orderId));
    }

    @GetMapping("/queue")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<WeighingQueueRow> queue(@PathVariable String buyer, @PathVariable String orderId) {
        return service.forBuyer(buyer, impl -> impl.queue(buyer, orderId));
    }

    @PostMapping("/pos/{poId}/start")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public PurchaseOrder startPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeighingSessionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.startPoWeighingSession(buyer, orderId, poId, request));
    }

    @PostMapping("/pos/{poId}/stop")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public PurchaseOrder stopPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.forBuyer(buyer, impl -> impl.stopPoWeighingSession(buyer, orderId, poId));
    }

    @PostMapping("/pos/{poId}/complete")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public PurchaseOrder completePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.forBuyer(buyer, impl -> impl.completePoWeighingSession(buyer, orderId, poId));
    }

    @PostMapping("/pos/{poId}/lookup")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeighingLookupResponse lookupPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.lookupForPo(buyer, orderId, poId, request));
    }

    @PostMapping("/pos/{poId}/plc-weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeightSubmitResponse submitPoPlcWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.submitWeightForPo(buyer, orderId, poId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC)));
    }

    @PostMapping("/lookup")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeighingLookupResponse lookupAny(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.lookupAny(buyer, orderId, request));
    }

    /** Station/PLC endpoint that resolves the Weighing Order from the scanned SSCC. */
    @PostMapping("/plc-weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeightSubmitResponse submitPlcWeightAny(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.submitWeightAny(buyer, orderId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC)));
    }

    @PostMapping("/orders")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.canWeightCheck())")
    public WeighingOrder createOrder(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody WeighingOrderCreateRequest request
    ) {
        throw new IllegalArgumentException(
                "Weighing orders are created only by Packing: Shipment Management > SEND TO WEIGHT.");
    }

    @PostMapping("/orders/{weighingOrderId}/lookup")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeighingLookupResponse lookup(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.lookup(buyer, orderId, weighingOrderId, request));
    }

    @PostMapping("/orders/{weighingOrderId}/weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeightSubmitResponse submitWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.submitWeight(buyer, orderId, weighingOrderId, request));
    }

    /** Same contract for the PLC/scale bridge. The request must carry stable=true. */
    @PostMapping("/orders/{weighingOrderId}/plc-weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeightSubmitResponse submitPlcWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.submitWeight(buyer, orderId, weighingOrderId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC)));
    }

    @PostMapping("/orders/{weighingOrderId}/cartons/{cartonId}/reweigh")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public Carton reopenWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.reopenWeight(buyer, orderId, weighingOrderId, cartonId, request));
    }

    @PostMapping("/orders/{weighingOrderId}/cartons/{cartonId}/override-pass")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public Carton overrideWeightPass(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.overrideWeightPass(buyer, orderId, weighingOrderId, cartonId, request));
    }

    @GetMapping("/orders/{weighingOrderId}/history")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ACCESS)
    public List<WeighingResult> history(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId
    ) {
        return service.forBuyer(buyer, impl -> impl.history(buyer, orderId, weighingOrderId));
    }
}
