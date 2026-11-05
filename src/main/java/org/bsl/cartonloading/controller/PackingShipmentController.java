package org.bsl.cartonloading.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.service.PackingShipmentFacade;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Shared controller. Validation and Buyer-specific operations are delegated to service layer. */
@Tag(name = AppConstants.WorkflowStage.PACKING_WEIGHT)
@RestController
@RequestMapping(AppConstants.ApiPath.BARCODE_WORKFLOW_PACKING_SHIPMENTS)
@PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
public class PackingShipmentController {
    private final PackingShipmentFacade service;
    public PackingShipmentController(PackingShipmentFacade service) { this.service = service; }

    @GetMapping
    public Page<Map<String, Object>> inbox(@PathVariable String buyer,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.inbox(buyer, page, size);
    }

    @GetMapping("/{id}")
    public Shipment get(@PathVariable String buyer, @PathVariable String id) {
        return service.get(buyer, id);
    }

    @GetMapping("/{id}/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer, @PathVariable String id,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.cartons(buyer, id, keyword, page, size);
    }

    @PostMapping("/{id}/scan")
    public CartonScanTransaction scan(@PathVariable String buyer, @PathVariable String id,
            @RequestBody FactoryBarcodeScanRequest request) {
        return service.scan(buyer, id, request);
    }

    @PostMapping("/{id}/cartons/{cartonId}/complete")
    public CartonScanTransaction complete(@PathVariable String buyer, @PathVariable String id,
            @PathVariable String cartonId) {
        return service.complete(buyer, id, cartonId);
    }

    @PostMapping("/{id}/dispatch")
    public Shipment dispatch(@PathVariable String buyer, @PathVariable String id) {
        return service.dispatch(buyer, id);
    }
}
