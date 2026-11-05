package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.service.facade.ShipmentFacade;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.es.model.*;
import org.bsl.cartonloading.buyers.es.dto.CartonInspectionRequest;
import org.bsl.cartonloading.buyers.es.dto.ShipmentRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping(AppConstants.ApiPath.BARCODE_WORKFLOW_SHIPMENT_MANAGEMENT)
@PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
public class ShipmentController {
    private final ShipmentFacade service;
    public ShipmentController(ShipmentFacade service) { this.service = service; }

    @GetMapping("/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page, @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size) {
        return service.forBuyer(buyer, impl -> impl.cartons(buyer, orderId, keyword, shipmentId, lifecycle, page, size));
    }
    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle) {
        return ResponseEntity.ok().header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=carton-tracking.csv")
                .body(service.forBuyer(buyer, impl -> impl.export(buyer, orderId, keyword, shipmentId, lifecycle)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/shipments")
    public Page<Map<String,Object>> shipments(@PathVariable String buyer, @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) { return service.forBuyer(buyer, impl -> impl.list(buyer, page, size)); }

    @PostMapping("/shipments")
    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public Shipment create(@PathVariable String buyer, @RequestBody ShipmentRequest request) { return service.forBuyer(buyer, impl -> impl.create(buyer, request)); }

    @PostMapping("/cartons/{id}/inspect")
    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public CartonScanTransaction inspect(@PathVariable String buyer, @PathVariable String id,
            @RequestBody CartonInspectionRequest request) { return service.forBuyer(buyer, impl -> impl.inspect(buyer, id, request)); }

    @PostMapping("/cartons/{id}/complete")
    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public CartonScanTransaction complete(@PathVariable String buyer, @PathVariable String id) { return service.forBuyer(buyer, impl -> impl.complete(buyer, id)); }

    @PostMapping("/shipments/{id}/dispatch")
    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public Shipment dispatch(@PathVariable String buyer, @PathVariable String id) { return service.forBuyer(buyer, impl -> impl.dispatch(buyer, id)); }

    @DeleteMapping("/shipments/{id}")
    @PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public void cancel(@PathVariable String buyer, @PathVariable String id) { service.forBuyer(buyer, impl -> { impl.cancel(buyer, id); return null; }); }
}
