package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.facade.WeighingFacade;

import org.bsl.cartonloading.buyers.lululemon.dto.CartonWeightAssignmentRow;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonWeightHistoryRow;
import org.bsl.cartonloading.buyers.lululemon.dto.WeighingLookupRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.WeighingLookupResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/carton-weight")
public class CartonWeightStationController {
    private final WeighingFacade service;

    public CartonWeightStationController(WeighingFacade service) {
        this.service = service;
    }

    /** Legacy compatibility endpoint: no-op; only Packing SEND can dispatch a shipment. */
    @PostMapping("/prepare")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public int prepare(@PathVariable String buyer) {
        return service.forBuyer(buyer, impl -> impl.prepareAssignedSchedules(buyer));
    }

    @GetMapping("/assignments")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.canManageSales() or @accessControl.canAssignBarcode())")
    public List<CartonWeightAssignmentRow> assignments(@PathVariable String buyer) {
        return service.forBuyer(buyer, impl -> impl.stationAssignments(buyer));
    }

    @PostMapping("/lookup")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public WeighingLookupResponse lookup(
            @PathVariable String buyer,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.forBuyer(buyer, impl -> impl.lookupAssignedSscc(buyer, request));
    }

    @GetMapping("/history")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<CartonWeightHistoryRow> history(@PathVariable String buyer,
            @RequestParam(required = false) String factoryCode) {
        return service.stationHistoryForFactory(buyer, factoryCode);
    }
}
