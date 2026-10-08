package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.CheckingResultRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CheckingSearchResponse;
import org.bsl.cartonloading.buyers.lululemon.model.CartonCheckingRecord;
import org.bsl.cartonloading.buyers.lululemon.service.CartonCheckingService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_ORDER + "/checking")
public class CartonCheckingController {
    private final CartonCheckingService service;
    public CartonCheckingController(CartonCheckingService service) { this.service = service; }

    @GetMapping("/search")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public CheckingSearchResponse search(@PathVariable String buyer, @PathVariable String orderId,
                                         @RequestParam String type, @RequestParam String value) {
        return service.search(buyer, orderId, type, value);
    }

    @GetMapping("/cartons/{cartonId}/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public List<CartonCheckingRecord> history(@PathVariable String buyer, @PathVariable String orderId,
                                               @PathVariable String cartonId) {
        return service.history(buyer, orderId, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/result")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public CartonCheckingRecord check(@PathVariable String buyer, @PathVariable String orderId,
                                      @PathVariable String cartonId, @RequestBody CheckingResultRequest request) {
        return service.check(buyer, orderId, cartonId, request);
    }
}
