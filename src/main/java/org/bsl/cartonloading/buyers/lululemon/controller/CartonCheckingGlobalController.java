package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.CheckingResultRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CheckingSearchResponse;
import org.bsl.cartonloading.buyers.lululemon.model.CartonCheckingRecord;
import org.bsl.cartonloading.buyers.lululemon.service.CartonCheckingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Buyer-scoped Checking: operator does not have to choose an Order before scanning. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/checking")
public class CartonCheckingGlobalController {
    private final CartonCheckingService service;
    public CartonCheckingGlobalController(CartonCheckingService service) { this.service = service; }

    @GetMapping("/search")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public CheckingSearchResponse search(@PathVariable String buyer, @RequestParam String type, @RequestParam String value) {
        return service.searchAcrossOrders(buyer, type, value);
    }

    @GetMapping("/pos/{poId}/cartons")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public Page<CheckingSearchResponse.CartonRow> poCartons(@PathVariable String buyer, @PathVariable String poId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "25") int size) {
        return service.cartonsForPo(buyer, poId, page, size);
    }

    @GetMapping("/cartons/{cartonId}/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canAssignBarcode() or @accessControl.canManageSales())")
    public List<CartonCheckingRecord> history(@PathVariable String buyer, @PathVariable String cartonId) {
        return service.historyAcrossOrders(buyer, cartonId);
    }

    @PostMapping("/cartons/{cartonId}/result")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    public CartonCheckingRecord check(@PathVariable String buyer, @PathVariable String cartonId,
                                      @RequestBody CheckingResultRequest request) {
        return service.checkAcrossOrders(buyer, cartonId, request);
    }
}
