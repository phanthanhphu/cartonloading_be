package org.bsl.cartonloading.buyers.es.controller;

import org.bsl.cartonloading.buyers.es.model.*;
import org.bsl.cartonloading.buyers.es.dto.CartonInspectionRequest;
import org.bsl.cartonloading.buyers.es.dto.ShipmentRequest;
import org.bsl.cartonloading.buyers.es.service.ShipmentService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/buyers/{buyer}/shipment-management")
@PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
public class ShipmentController {
    private final ShipmentService service;
    public ShipmentController(ShipmentService service) { this.service = service; }

    @GetMapping("/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.cartons(buyer, orderId, keyword, shipmentId, lifecycle, page, size);
    }
    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@PathVariable String buyer,
            @RequestParam(required = false) String orderId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String shipmentId, @RequestParam(required = false) String lifecycle) {
        return ResponseEntity.ok().header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=carton-tracking.csv")
                .body(service.export(buyer, orderId, keyword, shipmentId, lifecycle).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/shipments")
    public Page<Map<String,Object>> shipments(@PathVariable String buyer, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) { return service.list(buyer, page, size); }

    @PostMapping("/shipments")
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public Shipment create(@PathVariable String buyer, @RequestBody ShipmentRequest request) { return service.create(buyer, request); }

    @PostMapping("/cartons/{id}/inspect")
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public CartonScanTransaction inspect(@PathVariable String buyer, @PathVariable String id,
            @RequestBody CartonInspectionRequest request) { return service.inspect(buyer, id, request); }

    @PostMapping("/cartons/{id}/complete")
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public CartonScanTransaction complete(@PathVariable String buyer, @PathVariable String id) { return service.complete(buyer, id); }

    @PostMapping("/shipments/{id}/dispatch")
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public Shipment dispatch(@PathVariable String buyer, @PathVariable String id) { return service.dispatch(buyer, id); }

    @DeleteMapping("/shipments/{id}")
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    public void cancel(@PathVariable String buyer, @PathVariable String id) { service.cancel(buyer, id); }
}
