package org.bsl.cartonloading.buyers.es.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.buyers.es.constants.WorkflowStage;

import org.bsl.cartonloading.buyers.es.dto.ShipmentRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.springframework.data.domain.Page;
import java.util.Map;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.es.service.ShipmentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Stage 3: Sales publishes and cancels shipment plans. */
@Tag(name = WorkflowStage.SALES_PLAN)
@RestController
@RequestMapping("/api/buyers/{buyer}/sales/shipments")
@PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
public class SalesShipmentController {
    private final ShipmentService service;
    public SalesShipmentController(ShipmentService service) { this.service = service; }

    @GetMapping
    public Page<Map<String, Object>> list(@PathVariable String buyer,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.list(buyer, page, size);
    }
    @GetMapping("/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return service.cartons(buyer, orderId, keyword, shipmentId, lifecycle, page, size);
    }
    @PostMapping
    public Shipment create(@PathVariable String buyer, @RequestBody ShipmentRequest request) {
        return service.create(buyer, request);
    }
    @DeleteMapping("/{id}")
    public void cancel(@PathVariable String buyer, @PathVariable String id) { service.cancel(buyer, id); }
}
