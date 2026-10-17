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

    /**
     * Removes only documents scoped to this single Order and buyer. Audit logs are retained.
     * Multi-collection Mongo deletes are not transactional without MongoDB replica set support:
     * run only on a disposable database after taking a backup.
     */
    public synchronized Map<String, Object> purge(String buyer, String orderId, String confirmation) {
        assertAvailable(buyer, orderId);
        PackingOrder entity = template.findById(orderId, PackingOrder.class);
        String required = "DELETE " + entity.getOrderName();
        if (!required.equals(confirmation)) {
            throw new IllegalArgumentException("Type '" + required + "' to confirm deletion of this Order and all related data.");
        }
        audit.log(RequestActor.current(), "TEST_PURGE_STARTED", "LULULEMON_ORDER", orderId,
                "Test-only hard purge requested for Order " + entity.getOrderName(), null, null);
        Map<String, Long> counts = new LinkedHashMap<>();
        // Delete dependent records first. Only the selected Order is touched.
        remove(counts, "RFID sessions", orderId, RfidScanSession.class);
        remove(counts, "Checking history", orderId, CartonCheckingRecord.class);
        // Preserve TEST purge for legacy documents without keeping the retired Print Request module.
        // Checking existence avoids creating print_handoffs in a fresh MongoDB database.
        removeLegacyPrintHandoffs(counts, orderId);
        remove(counts, "Weight results", orderId, WeighingResult.class);
        remove(counts, "Weighing assignments", orderId, WeighingOrder.class);
        remove(counts, "Weight profiles", orderId, WeightProfile.class);
        remove(counts, "Shipping schedules", orderId, ShippingSchedule.class);
        remove(counts, "Scan history", orderId, ScanEvent.class);
        remove(counts, "Carton items", orderId, CartonItem.class);
        remove(counts, "Cartons", orderId, Carton.class);
        remove(counts, "Purchase Orders", orderId, PurchaseOrder.class);
        remove(counts, "Shipments", orderId, Shipment.class);
        remove(counts, "Packing list lines", orderId, PackingListLine.class);
        remove(counts, "Packing allocation lines", orderId, PackingAllocationLine.class);
        remove(counts, "Carton operations", orderId, CartonScanTransaction.class);
        remove(counts, "Order", orderId, PackingOrder.class);
        audit.log(RequestActor.current(), "TEST_PURGE_FINISHED", "LULULEMON_ORDER", orderId,
                "Test-only cleanup of Order " + entity.getOrderName() + ": " + counts, null, null);
        return Map.of("orderId", orderId, "orderName", entity.getOrderName(), "deleted", counts);
    }

    private void removeLegacyPrintHandoffs(Map<String, Long> counts, String orderId) {
        final String legacyCollection = "print_handoffs";
        if (!template.collectionExists(legacyCollection)) {
            counts.put("Legacy Print handoffs", 0L);
            return;
        }
        Query query = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId));
        counts.put("Legacy Print handoffs", template.remove(query, legacyCollection).getDeletedCount());
    }

    private void remove(Map<String, Long> counts, String label, String orderId, Class<?> type) {
        Query query = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId));
        if (type == PackingOrder.class) {
            query = Query.query(Criteria.where("_id").is(orderId)
                    .and("buyerCode").is(AppConstants.Buyer.LULULEMON));
        }
        counts.put(label, template.remove(query, type).getDeletedCount());
    }
}
