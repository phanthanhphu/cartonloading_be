package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.service.facade.CartonLoadingFacade;
import org.bsl.cartonloading.service.facade.ShipmentFacade;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Shared orchestration facade: legacy packing validation and Mongo lookup are no longer in Controller. */
@Service
public class PackingShipmentFacade {
    private final ShipmentFacade shipments;
    private final CartonLoadingFacade loading;
    private final MongoTemplate mongo;

    public PackingShipmentFacade(ShipmentFacade shipments, CartonLoadingFacade loading, MongoTemplate mongo) {
        this.shipments = shipments;
        this.loading = loading;
        this.mongo = mongo;
    }

    public Page<Map<String, Object>> inbox(String buyer, int page, int size) {
        return shipments.forBuyer(buyer, impl -> impl.list(buyer, page, size));
    }

    public Shipment get(String buyer, String id) {
        return shipments.forBuyer(buyer, impl -> impl.shipment(buyer, id));
    }

    public Page<CartonScanTransaction> cartons(String buyer, String id, String keyword, int page, int size) {
        Shipment plan = get(buyer, id);
        return shipments.forBuyer(buyer,
                impl -> impl.cartons(buyer, plan.getOrderId(), keyword, id, null, page, size));
    }

    public CartonScanTransaction scan(String buyer, String id, FactoryBarcodeScanRequest request) {
        Shipment plan = get(buyer, id);
        if (request == null || request.stationCode() == null || request.stationCode().isBlank()
                || Boolean.TRUE.equals(request.manualMode()) || request.factoryBarcode() == null) {
            throw new IllegalArgumentException(AppMessages.SELECT_PLC_AND_SCAN);
        }
        String barcode = request.factoryBarcode().trim();
        CartonScanTransaction carton = mongo.findOne(Query.query(Criteria.where("buyerCode").is(plan.getBuyerCode())
                .and("orderId").is(plan.getOrderId()).and("shipmentId").is(id).and("factoryBarcode").is(barcode)),
                CartonScanTransaction.class);
        if (carton == null || !plan.getCartonIds().contains(carton.getId())) {
            throw new IllegalArgumentException(AppMessages.BARCODE_NOT_IN_SHIPMENT);
        }
        return loading.forBuyer(buyer, impl -> impl.scanAssignedFactoryBarcode(plan.getBuyerCode(), plan.getOrderId(),
                new FactoryBarcodeScanRequest(request.stationCode(), barcode, request.palletCode(), request.scanId(), false)));
    }

    public CartonScanTransaction complete(String buyer, String id, String cartonId) {
        Shipment plan = get(buyer, id);
        CartonScanTransaction carton = loading.forBuyer(buyer, impl -> impl.get(buyer, cartonId));
        if (!id.equals(carton.getShipmentId()) || !plan.getCartonIds().contains(cartonId)) {
            throw new IllegalArgumentException(AppMessages.CARTON_NOT_IN_SHIPMENT);
        }
        return shipments.forBuyer(buyer, impl -> impl.complete(buyer, cartonId));
    }

    public Shipment dispatch(String buyer, String id) {
        return shipments.forBuyer(buyer, impl -> impl.dispatch(buyer, id));
    }
}
