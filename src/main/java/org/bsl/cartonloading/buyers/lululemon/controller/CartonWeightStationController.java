package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.CartonWeightAssignmentRow;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonWeightHistoryRow;
import org.bsl.cartonloading.buyers.lululemon.dto.WeighingLookupRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.WeighingLookupResponse;
import org.bsl.cartonloading.buyers.lululemon.service.WeighingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/carton-weight")
public class CartonWeightStationController {
    private final WeighingService service;

    public CartonWeightStationController(WeighingService service) {
        this.service = service;
    }

    /** Legacy compatibility endpoint: no-op; only Packing SEND can dispatch a shipment. */
    @PostMapping("/prepare")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public int prepare(@PathVariable String buyer) {
        return service.prepareAssignedSchedules(buyer);
    }

    @GetMapping("/assignments")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.canManageSales() or @accessControl.canAssignBarcode())")
    public List<CartonWeightAssignmentRow> assignments(@PathVariable String buyer) {
        return service.stationAssignments(buyer);
    }

    @PostMapping("/lookup")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public WeighingLookupResponse lookup(
            @PathVariable String buyer,
            @RequestBody WeighingLookupRequest request
    ) {
        return service.lookupAssignedSscc(buyer, request);
    }

    @GetMapping("/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.canManageSales() or @accessControl.canAssignBarcode())")
    public List<CartonWeightHistoryRow> history(@PathVariable String buyer) {
        return service.stationHistory(buyer);
    }
}
