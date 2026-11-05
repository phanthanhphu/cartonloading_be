package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.service.CartonProcessingService;
import org.bsl.cartonloading.service.facade.CartonProcessingFacade;

import org.bsl.cartonloading.buyers.lululemon.dto.CartonProcessingIdentityRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonProcessingWeightRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Direct operator station: no PO self-pick or Shipping Schedule dispatch. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/carton-processing")
public class CartonProcessingController {
    private final CartonProcessingFacade service;
    public CartonProcessingController(CartonProcessingFacade service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public CartonProcessingService.StationSummary summary(@PathVariable String buyer,
                                                            @RequestParam(required = false) String factoryCode,
                                                            @RequestParam(required = false) String orderId,
                                                            @RequestParam(required = false) String poId) {
        return service.forBuyer(buyer, impl -> impl.summary(buyer, factoryCode, orderId, poId));
    }

    @GetMapping("/sscc/check")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.SsccPrecheck checkSscc(
            @PathVariable String buyer, @RequestParam String sscc,
            @RequestParam String factoryCode, @RequestParam String orderId, @RequestParam String poId) {
        return service.forBuyer(buyer, impl -> impl.checkSscc(buyer, sscc, factoryCode, orderId, poId));
    }

    @PostMapping("/identify")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.IdentifiedCarton identify(@PathVariable String buyer,
                                                               @RequestBody CartonProcessingIdentityRequest request) {
        return service.forBuyer(buyer, impl -> impl.identify(buyer, request));
    }

    @PostMapping("/manual-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.WeightResult saveManualWeight(@PathVariable String buyer,
                                                                  @RequestBody CartonProcessingWeightRequest request) {
        return service.forBuyer(buyer, impl -> impl.manualWeight(buyer, request));
    }
    public record AutoWeightRequest(String sscc, String stationCode, String eventId) { }

    public record ArmWeightRequest(String sscc, String stationCode) { }

    @PostMapping("/auto-weight/arm")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public void armAutoWeight(@PathVariable String buyer, @RequestBody ArmWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("Station and SSCC required");
        service.forBuyer(buyer, impl -> { impl.armAutoWeight(buyer, request.sscc(), request.stationCode()); return null; });
    }

    @PostMapping("/auto-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.WeightResult autoWeight(@PathVariable String buyer,
            @RequestBody AutoWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("Scale event is required");
        return service.forBuyer(buyer, impl -> impl.autoWeight(buyer, request.sscc(), request.stationCode(), request.eventId()));
    }

    public record RecallFailedRequest(String orderId, String poId, String cartonId, String reason) { }

    /** Operator recall, scoped to a completed FINAL FAILED carton in this PO. */
    @PostMapping("/recall-failed")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.IdentifiedCarton recallFailed(
            @PathVariable String buyer, @RequestBody RecallFailedRequest request) {
        if (request == null) throw new IllegalArgumentException("Recall request is required.");
        return service.forBuyer(buyer, impl -> impl.recallFailed(buyer, request.orderId(), request.poId(), request.cartonId(), request.reason()));
    }

    public record ReopenWeightRequest(String sscc, String reason) { }

    @PostMapping("/reopen-weight")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public Carton reopenWeight(
            @PathVariable String buyer, @RequestBody ReopenWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("SSCC and reason are required.");
        return service.forBuyer(buyer, impl -> impl.reopenManualWeight(buyer, request.sscc(), request.reason()));
    }

}
