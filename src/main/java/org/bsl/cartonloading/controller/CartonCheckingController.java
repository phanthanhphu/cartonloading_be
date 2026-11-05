package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.service.facade.CartonCheckingFacade;

import org.bsl.cartonloading.buyers.lululemon.dto.CheckingResultRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CheckingSearchResponse;
import org.bsl.cartonloading.buyers.lululemon.model.CartonCheckingRecord;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/checking")
public class CartonCheckingController {
    private final CartonCheckingFacade service;
    public CartonCheckingController(CartonCheckingFacade service) { this.service = service; }

    @GetMapping("/search")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public CheckingSearchResponse search(@PathVariable String buyer, @PathVariable String orderId,
                                         @RequestParam String type, @RequestParam String value) {
        return service.forBuyer(buyer, impl -> impl.search(buyer, orderId, type, value));
    }

    @GetMapping("/cartons/{cartonId}/history")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<CartonCheckingRecord> history(@PathVariable String buyer, @PathVariable String orderId,
                                               @PathVariable String cartonId) {
        return service.forBuyer(buyer, impl -> impl.history(buyer, orderId, cartonId));
    }

    @PostMapping("/cartons/{cartonId}/result")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public CartonCheckingRecord check(@PathVariable String buyer, @PathVariable String orderId,
                                      @PathVariable String cartonId, @RequestBody CheckingResultRequest request) {
        return service.forBuyer(buyer, impl -> impl.check(buyer, orderId, cartonId, request));
    }
}
