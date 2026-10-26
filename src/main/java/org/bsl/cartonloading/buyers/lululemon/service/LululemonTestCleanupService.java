package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingAllocationLine;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Admin-only hard purge for disposable TEST data. NEVER enable in production. */
@Service
public class LululemonTestCleanupService {
    private final MongoTemplate template;
    private final AuditLogService audit;
    private final Environment environment;

    @Value("${lululemon.test-cleanup.enabled:false}")
    private boolean enabled;

    public LululemonTestCleanupService(MongoTemplate template, AuditLogService audit, Environment environment) {
        this.template = template;
        this.audit = audit;
        this.environment = environment;
    }

    /** Feature status can be checked safely without enabling the destructive endpoint. */
    public boolean isTestCleanupEnabled() {
        return enabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    public void assertAvailable(String buyer, String orderId) {
        if (!isTestCleanupEnabled()) {
            throw new IllegalStateException("TEST cleanup is disabled. Use an isolated test database.");
        }
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("TEST cleanup is limited to LULULEMON.");
        }
        PackingOrder entity = template.findById(orderId, PackingOrder.class);
        if (entity == null || !Objects.equals(entity.getBuyerCode(), AppConstants.Buyer.LULULEMON)) {
            throw new IllegalArgumentException("Lululemon Order not found.");
        }
    }

    /** Count exactly what will be removed. Read-only; independent of order name/user input. */
    public Map<String, Object> preview(String buyer, String orderId) {
        assertAvailable(buyer, orderId);
        PackingOrder entity = template.findById(orderId, PackingOrder.class);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Target target : targets(orderId)) {
            counts.put(target.label(), template.count(target.query(), target.collection()));
        }
        boolean activeCapture = template.exists(captureQuery(orderId).addCriteria(Criteria.where("stopped").is(false)), RfidPdaCapture.class);
        return Map.of("orderId", orderId, "orderName", entity.getOrderName(),
                "counts", counts, "activeCapture", activeCapture);
    }

    /**
     * TEST-ONLY destructive cleanup by OrderId and buyer (never by PO number or SSCC alone).
     * No Mongo multi-collection transaction is assumed: if a delete fails, repeat this
     * idempotent operation after fixing the error; the Order is deleted last.
     */
    public synchronized Map<String, Object> purge(String buyer, String orderId, String confirmation) {
        assertAvailable(buyer, orderId);
        PackingOrder entity = template.findById(orderId, PackingOrder.class);
        String required = "DELETE " + entity.getOrderName();
        if (!required.equals(confirmation)) {
            throw new IllegalArgumentException("Type '" + required + "' to confirm deletion of this Order and all related data.");
        }
        // Never remove active PDA reservations while the physical device may still be scanning.
        if (template.exists(captureQuery(orderId).addCriteria(Criteria.where("stopped").is(false)), RfidPdaCapture.class)) {
            throw new IllegalStateException("Stop active RFID PDA capture(s) before deleting this Order.");
        }
        audit.log(RequestActor.current(), "TEST_PURGE_STARTED", "LULULEMON_ORDER", orderId,
                "Test-only hard purge requested for Order " + entity.getOrderName(), null, null);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Target target : targets(orderId)) {
            if (template.collectionExists(target.collection())) {
                counts.put(target.label(), template.remove(target.query(), target.collection()).getDeletedCount());
            } else {
                counts.put(target.label(), 0L);
            }
        }
        // Parent Order last, so a partial error retains the Order for an idempotent retry.
        Query orderQuery = Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON));
        counts.put("Order", template.remove(orderQuery, PackingOrder.class).getDeletedCount());
        audit.log(RequestActor.current(), "TEST_PURGE_FINISHED", "LULULEMON_ORDER", orderId,
                "Test-only cleanup of Order " + entity.getOrderName() + ": " + counts, null, null);
        return Map.of("orderId", orderId, "orderName", entity.getOrderName(), "deleted", counts);
    }

    private record Target(String label, String collection, Query query) { }

    private java.util.List<Target> targets(String orderId) {
        java.util.List<Target> targets = new java.util.ArrayList<>();
        add(targets, "RFID sessions", RfidScanSession.class, ownerQuery(orderId));
        add(targets, "PDA captures", RfidPdaCapture.class, captureQuery(orderId));
        add(targets, "PO Pick claims", PoPickClaim.class, ownerQuery(orderId));
        add(targets, "PO Pick history", PoPickEvent.class, buyerKeyQuery(orderId));
        add(targets, "Checking history", CartonCheckingRecord.class, ownerQuery(orderId));
        // Retired print requests were stored in a buyer+Order-scoped collection.
        targets.add(new Target("Legacy Print handoffs", "print_handoffs", ownerQuery(orderId)));
        add(targets, "Weight results", WeighingResult.class, ownerQuery(orderId));
        add(targets, "Weighing assignments", WeighingOrder.class, ownerQuery(orderId));
        add(targets, "Weight profiles", WeightProfile.class, ownerQuery(orderId));
        add(targets, "Shipping schedules", ShippingSchedule.class, ownerQuery(orderId));
        add(targets, "Scan history", ScanEvent.class, ownerQuery(orderId));
        add(targets, "Carton items", CartonItem.class, ownerQuery(orderId));
        add(targets, "Cartons", Carton.class, ownerQuery(orderId));
        add(targets, "Purchase Orders", PurchaseOrder.class, ownerQuery(orderId));
        add(targets, "Shipments", Shipment.class, ownerQuery(orderId));
        add(targets, "Packing list lines", PackingListLine.class, ownerQuery(orderId));
        add(targets, "Packing allocation lines", PackingAllocationLine.class, ownerQuery(orderId));
        add(targets, "Carton operations", CartonScanTransaction.class, ownerQuery(orderId));
        return targets;
    }

    private void add(java.util.List<Target> targets, String label, Class<?> type, Query query) {
        targets.add(new Target(label, template.getCollectionName(type), query));
    }

    private Query ownerQuery(String orderId) {
        // buyerCode remains supported for legacy documents saved before buyer_key existed.
        return Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId));
    }

    private Query buyerKeyQuery(String orderId) {
        return Query.query(Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId));
    }

    private Query captureQuery(String orderId) {
        return Query.query(Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON)
                .and("order").is(orderId));
    }
}
