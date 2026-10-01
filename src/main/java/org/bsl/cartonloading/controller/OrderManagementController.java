package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderRequest;
import org.bsl.cartonloading.buyers.es.dto.PackingOrderResponse;
import org.bsl.cartonloading.dto.management.CartonManagementRow;
import org.bsl.cartonloading.dto.management.ItemManagementRow;
import org.bsl.cartonloading.dto.management.PoManagementRow;
import org.bsl.cartonloading.service.OrderManagementService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/order-management")
public class OrderManagementController {
    private final OrderManagementService service;
    public OrderManagementController(OrderManagementService service) { this.service = service; }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders")
    public Page<PackingOrderResponse> orders(@RequestParam String buyer,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate orderDate,
                                             @RequestParam(required = false) String orderName,
                                             @RequestParam(required = false) String factory,
                                             @RequestParam(required = false) String supplier,
                                             @RequestParam(required = false) String createdBy,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return service.listOrders(buyer, orderDate, orderName, factory, supplier, createdBy, status, page, size);
    }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{id}")
    public PackingOrderResponse order(@RequestParam String buyer, @PathVariable String id) {
        return service.getOrder(buyer, id);
    }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping("/orders") public PackingOrderResponse create(@RequestParam String buyer, @Valid @RequestBody PackingOrderRequest request) { return service.createOrder(buyer, request); }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PutMapping("/orders/{id}") public PackingOrderResponse update(@RequestParam String buyer, @PathVariable String id, @Valid @RequestBody PackingOrderRequest request) { return service.updateOrder(buyer, id, request); }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @DeleteMapping("/orders/{id}") public ResponseEntity<Void> delete(@RequestParam String buyer, @PathVariable String id) { service.deleteOrder(buyer, id); return ResponseEntity.noContent().build(); }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{orderId}/pos") public Page<PoManagementRow> pos(@RequestParam String buyer, @PathVariable String orderId,
            @RequestParam(required=false) String poNumber, @RequestParam(required=false) String factory,
            @RequestParam(required=false) String style, @RequestParam(required=false) String sku,
            @RequestParam(required=false) String destination, @RequestParam(required=false) String status,
            @RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate exFtyDate,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return service.listPos(buyer, orderId, poNumber, factory, style, sku, destination, status, exFtyDate, page, size);
    }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{orderId}/pos/{poKey}")
    public PoManagementRow po(@RequestParam String buyer, @PathVariable String orderId, @PathVariable String poKey) {
        return service.getPo(buyer, orderId, poKey);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{orderId}/pos/{poKey}/cartons") public Page<CartonManagementRow> cartons(@RequestParam String buyer,
            @PathVariable String orderId, @PathVariable String poKey,
            @RequestParam(required=false) String cartonNo, @RequestParam(required=false) String sscc,
            @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return service.listCartons(buyer, orderId, poKey, cartonNo, sscc, status, page, size);
    }
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{orderId}/cartons/{cartonId}")
    public CartonManagementRow carton(@RequestParam String buyer, @PathVariable String orderId, @PathVariable String cartonId) {
        return service.getCarton(buyer, orderId, cartonId);
    }

    @PreAuthorize("@accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/orders/{orderId}/cartons/{cartonId}/items") public Page<ItemManagementRow> items(@RequestParam String buyer,
            @PathVariable String orderId, @PathVariable String cartonId,
            @RequestParam(required=false) String itemNo, @RequestParam(required=false) String sku,
            @RequestParam(required=false) String style, @RequestParam(required=false) String color,
            @RequestParam(required=false) String sizeValue, @RequestParam(required=false) String status,
            @RequestParam(required=false) String scannedBy,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return service.listItems(buyer, orderId, cartonId, itemNo, sku, style, color, sizeValue, status, scannedBy, page, size);
    }
}
