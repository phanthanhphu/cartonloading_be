package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaCapture;
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

import java.util.Map;
import java.util.Objects;

/** Admin-only hard purge for disposable TEST data. NEVER enable in production. */
@Service
public class LululemonTestCleanupService {
    private final MongoTemplate template;
    private final AuditLogService audit;
    private final Environment environment;
    private final LululemonOrderCascadeDeleteService cascadeDelete;

    @Value("${lululemon.test-cleanup.enabled:false}")
    private boolean enabled;

    public LululemonTestCleanupService(MongoTemplate template, AuditLogService audit,
                                       Environment environment, LululemonOrderCascadeDeleteService cascadeDelete) {
        this.template = template;
        this.audit = audit;
        this.environment = environment;
        this.cascadeDelete = cascadeDelete;
    }

    /** Feature status can be checked safely without enabling the destructive endpoint. */
    public boolean isTestCleanupEnabled() {
        return enabled && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    public Map<String, Boolean> status(String buyer, String orderId) {
        if (!isTestCleanupEnabled()) return Map.of("enabled", false);
        assertAvailable(buyer, orderId);
        return Map.of("enabled", true);
    }

    public void assertAvailable(String buyer, String orderId) {
        if (!isTestCleanupEnabled()) {
            throw new IllegalStateException("TEST cleanup is disabled. Use an isolated test database.");
        }
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("TEST cleanup is limited to LULULEMON.");
        }
        OrderDocument entity = template.findById(orderId, OrderDocument.class);
        if (entity == null || !Objects.equals(entity.getBuyerCode(), AppConstants.Buyer.LULULEMON)) {
            throw new IllegalArgumentException("Lululemon Order not found.");
        }
    }

    /** Read-only preview from the SAME target inventory and queries as actual deletion. */
    public Map<String, Object> preview(String buyer, String orderId) {
        assertAvailable(buyer, orderId);
        OrderDocument entity = template.findById(orderId, OrderDocument.class);
        Map<String, Long> counts = cascadeDelete.previewCounts(orderId);
        boolean activeCapture = template.exists(Query.query(Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON)
                .and("order").is(orderId).and("stopped").is(false)), RfidPdaCapture.class);
        return Map.of("orderId", orderId, "orderName", entity.getOrderName(),
                "counts", counts, "activeCapture", activeCapture);
    }

    /**
     * TEST-ONLY Delete All delegates to the same guarded cascade as production
     * Order removal. This prevents Report or Shipping Day records being left out
     * of the test cleanup when the workflow evolves.
     */
    public synchronized Map<String, Object> purge(String buyer, String orderId, String confirmation) {
        assertAvailable(buyer, orderId);
        OrderDocument entity = template.findById(orderId, OrderDocument.class);
        // The shared deletion enforces Admin, the exact typed confirmation,
        // active PDA + PO identity protection, the deletion fence and parent-last order.
        Map<String, Long> deleted = cascadeDelete.deleteAll(orderId, entity.getOrderName(), confirmation);
        audit.logForFactory(RequestActor.current(), "TEST_PURGE_FINISHED", "LULULEMON_ORDER", orderId,
                "Test-only cleanup of Order " + entity.getOrderName() + ": " + deleted,
                AppConstants.Buyer.LULULEMON, entity.getFactoryCode());
        return Map.of("orderId", orderId, "orderName", entity.getOrderName(), "deleted", deleted);
    }
}
