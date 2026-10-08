package org.bsl.cartonloading.buyers.es.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.es.dto.ShipmentRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.springframework.data.domain.Page;
import java.util.Map;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.es.service.ShipmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Stage 3: Sales publishes and cancels shipment plans. */
@Tag(name = AppConstants.WorkflowStage.SALES_PLAN)
@RestController
@RequestMapping(AppConstants.ApiPath.BARCODE_WORKFLOW_SALES_SHIPMENTS)
@PreAuthorize("@barcodeWorkflowBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
public class SalesShipmentController {
    private final ShipmentService service;
    public SalesShipmentController(ShipmentService service) { this.service = service; }

    @GetMapping
    public Page<Map<String, Object>> list(@PathVariable String buyer,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page, @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.list(buyer, page, size);
    }
    @GetMapping("/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page, @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE_SIZE) int size) {
        return service.cartons(buyer, orderId, keyword, shipmentId, lifecycle, page, size);
    }
    @PostMapping
    public Shipment create(@PathVariable String buyer, @RequestBody ShipmentRequest request) {
        return service.create(buyer, request);
    }
    @DeleteMapping("/{id}")
    public void cancel(@PathVariable String buyer, @PathVariable String id) { service.cancel(buyer, id); }
}
