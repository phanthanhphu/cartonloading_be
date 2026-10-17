package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.model.WeightProfile;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingOrder;
import org.bsl.cartonloading.buyers.lululemon.service.WeighingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING)
public class WeighingController {
    private final WeighingService service;

    public WeighingController(WeighingService service) {
        this.service = service;
    }

    @GetMapping("/eligible-pos")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<WeighingEligiblePo> eligiblePos(@PathVariable String buyer, @PathVariable String orderId) {
        return service.eligiblePos(buyer, orderId);
    }

    @PutMapping("/profiles/{poId}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public WeightProfile saveProfile(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeightProfileRequest request
    ) {
        return service.saveProfile(buyer, orderId, poId, request);
    }

    @GetMapping("/orders")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<WeighingOrder> listOrders(@PathVariable String buyer, @PathVariable String orderId) {
        return service.listWeighingOrders(buyer, orderId);
    }

    @GetMapping("/queue")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<WeighingQueueRow> queue(@PathVariable String buyer, @PathVariable String orderId) {
        return service.queue(buyer, orderId);
    }

    @PostMapping("/pos/{poId}/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder startPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeighingSessionRequest request
    ) {
        return service.startPoWeighingSession(buyer, orderId, poId, request);
    }

    @PostMapping("/pos/{poId}/stop")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder stopPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.stopPoWeighingSession(buyer, orderId, poId);
    }

    @PostMapping("/pos/{poId}/complete")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder completePo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId
    ) {
        return service.completePoWeighingSession(buyer, orderId, poId);
    }

    @PostMapping("/pos/{poId}/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeighingLookupResponse lookupPo(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.lookupForPo(buyer, orderId, poId, request);
    }

    @PostMapping("/pos/{poId}/plc-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeightSubmitResponse submitPoPlcWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String poId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.submitWeightForPo(buyer, orderId, poId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC));
    }

    @PostMapping("/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeighingLookupResponse lookupAny(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.lookupAny(buyer, orderId, request);
    }

    /** Station/PLC endpoint that resolves the Weighing Order from the scanned SSCC. */
    @PostMapping("/plc-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeightSubmitResponse submitPlcWeightAny(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.submitWeightAny(buyer, orderId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC));
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
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeighingLookupResponse lookup(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.lookup(buyer, orderId, weighingOrderId, request);
    }

    @PostMapping("/orders/{weighingOrderId}/weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeightSubmitResponse submitWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.submitWeight(buyer, orderId, weighingOrderId, request);
    }

    /** Same contract for the PLC/scale bridge. The request must carry stable=true. */
    @PostMapping("/orders/{weighingOrderId}/plc-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeightSubmitResponse submitPlcWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @RequestBody WeightSubmitRequest request
    ) {
        return service.submitWeight(buyer, orderId, weighingOrderId,
                new WeightSubmitRequest(request.sscc(), request.actualWeightKg(), request.stationCode(), request.stable(), AppConstants.WeightSource.PLC));
    }

    @PostMapping("/orders/{weighingOrderId}/cartons/{cartonId}/reweigh")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public Carton reopenWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.reopenWeight(buyer, orderId, weighingOrderId, cartonId, request);
    }

    @PostMapping("/orders/{weighingOrderId}/cartons/{cartonId}/override-pass")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public Carton overrideWeightPass(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId,
            @PathVariable String cartonId,
            @RequestBody ExceptionRequest request
    ) {
        return service.overrideWeightPass(buyer, orderId, weighingOrderId, cartonId, request);
    }

    @GetMapping("/orders/{weighingOrderId}/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    public List<WeighingResult> history(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String weighingOrderId
    ) {
        return service.history(buyer, orderId, weighingOrderId);
    }
}
