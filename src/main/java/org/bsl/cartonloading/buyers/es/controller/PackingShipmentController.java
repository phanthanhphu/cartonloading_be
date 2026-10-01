package org.bsl.cartonloading.buyers.es.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.buyers.es.constants.WorkflowStage;

import java.util.Map;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.es.service.ShipmentService;
import org.bsl.cartonloading.buyers.es.service.CartonLoadingService;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Stage 4: Packing works inside one Sales shipment plan. */
@Tag(name = WorkflowStage.PACKING_WEIGHT)
@RestController
@RequestMapping("/api/buyers/{buyer}/packing/shipments")
@PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
public class PackingShipmentController {
    private final ShipmentService shipments;
    private final CartonLoadingService loading;
    private final MongoTemplate mongo;
    public PackingShipmentController(ShipmentService shipments, CartonLoadingService loading, MongoTemplate mongo) {
        this.shipments = shipments; this.loading = loading; this.mongo = mongo;
    }
    @GetMapping
    public Page<Map<String, Object>> inbox(@PathVariable String buyer,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return shipments.list(buyer, page, size);
    }
    @GetMapping("/{id}")
    public Shipment get(@PathVariable String buyer, @PathVariable String id) { return shipments.shipment(buyer, id); }

    @GetMapping("/{id}/cartons")
    public Page<CartonScanTransaction> cartons(@PathVariable String buyer, @PathVariable String id,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        Shipment plan = shipments.shipment(buyer, id);
        return shipments.cartons(buyer, plan.getOrderId(), keyword, id, null, page, size);
    }
    @PostMapping("/{id}/scan")
    public CartonScanTransaction scan(@PathVariable String buyer, @PathVariable String id,
            @RequestBody FactoryBarcodeScanRequest request) {
        Shipment plan = shipments.shipment(buyer, id);
        if (request == null || request.stationCode() == null || request.stationCode().isBlank()
                || Boolean.TRUE.equals(request.manualMode()) || request.factoryBarcode() == null) {
            throw new IllegalArgumentException("Select a PLC station and scan the assigned Factory Barcode.");
        }
        String barcode = request.factoryBarcode().trim();
        CartonScanTransaction carton = mongo.findOne(Query.query(Criteria.where("buyerCode").is(plan.getBuyerCode())
                .and("orderId").is(plan.getOrderId()).and("shipmentId").is(id).and("factoryBarcode").is(barcode)),
                CartonScanTransaction.class);
        if (carton == null || !plan.getCartonIds().contains(carton.getId())) {
            throw new IllegalArgumentException("This barcode is not a carton in the selected shipment plan.");
        }
        return loading.scanAssignedFactoryBarcode(plan.getBuyerCode(), plan.getOrderId(),
                new FactoryBarcodeScanRequest(request.stationCode(), barcode, request.palletCode(), request.scanId(), false));
    }
    @PostMapping("/{id}/cartons/{cartonId}/complete")
    public CartonScanTransaction complete(@PathVariable String buyer, @PathVariable String id, @PathVariable String cartonId) {
        Shipment plan = shipments.shipment(buyer, id);
        CartonScanTransaction carton = loading.get(buyer, cartonId);
        if (!id.equals(carton.getShipmentId()) || !plan.getCartonIds().contains(cartonId)) {
            throw new IllegalArgumentException("Carton does not belong to the selected shipment.");
        }
        return shipments.complete(buyer, cartonId);
    }
    @PostMapping("/{id}/dispatch")
    public Shipment dispatch(@PathVariable String buyer, @PathVariable String id) { return shipments.dispatch(buyer, id); }
}
