package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaCapture;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.AuditLogService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Explicit, Admin-only Order removal for LULULEMON. Nothing is selected by
 * PO number or Factory alone: EVERY removal is scoped to Buyer and Order ID.
 *
 * MongoDB installations without replica-set transactions cannot make a
 * multi-collection delete atomic. Order is removed last; if a step fails,
 * retrying the same confirmed deletion completes the remaining work.
 * Audit logs are deliberately retained as accountability records.
 */
@Service
public class LululemonOrderCascadeDeleteService {
    private final MongoTemplate mongo;
    private final PoIdentityRegistryService registry;
    private final AuditLogService audit;

    public LululemonOrderCascadeDeleteService(MongoTemplate mongo, PoIdentityRegistryService registry,
                                                AuditLogService audit) {
        this.mongo = mongo;
        this.registry = registry;
        this.audit = audit;
    }

    /**
     * Single inventory for BOTH Order deletion and the test-only Delete All button.
     * Keep preview, deletion, retry and Report-facing persisted data in sync.
     * The PO identity registry is handled separately, after the Purchase Orders.
     */
    private record Target(String label, String collection, String buyerField, String orderField) { }

    private static final List<Target> TARGETS = List.of(
            new Target("RFID sessions", AppConstants.Collection.RFID_SCAN_SESSIONS, "buyerCode", "orderId"),
            new Target("PDA captures", AppConstants.Collection.RFID_PDA_CAPTURES, "buyer_key", "order"),
            new Target("PO Pick claims", AppConstants.Collection.PO_PICK_CLAIMS, "buyerCode", "orderId"),
            new Target("PO Pick history", AppConstants.Collection.PO_PICK_HISTORY, "buyer_key", "orderId"),
            new Target("Checking history", AppConstants.Collection.CARTON_CHECKING_HISTORY, "buyerCode", "orderId"),
            new Target("Legacy Print handoffs", "print_handoffs", "buyerCode_or_buyer_key", "orderId"),
            new Target("Completed carton history", AppConstants.Collection.CARTON_PROCESSING_HISTORY, "buyerCode", "orderId"),
            new Target("Weight results", AppConstants.Collection.WEIGHING_RESULTS, "buyerCode", "orderId"),
            new Target("Weighing assignments", AppConstants.Collection.WEIGHING_ORDERS, "buyerCode", "orderId"),
            new Target("Weight profiles", AppConstants.Collection.WEIGHING_PROFILES, "buyerCode", "orderId"),
            new Target("Shipping schedules", AppConstants.Collection.SHIPPING_SCHEDULES, "buyerCode", "orderId"),
            // Shipping Day Submit/Reopen records are consumed by Management Report.
            // They are NOT stored in shipping_schedules and must be deleted separately.
            new Target("Shipping day submissions (Report)", AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS, "buyerCode", "orderId"),
            new Target("Scan history", AppConstants.Collection.SCAN_EVENTS, "buyerCode", "orderId"),
            new Target("Carton items", AppConstants.Collection.CARTON_ITEMS, "buyerCode", "orderId"),
            new Target("Cartons", AppConstants.Collection.CARTONS, "buyerCode", "orderId"),
            new Target("Purchase Orders", AppConstants.Collection.PURCHASE_ORDERS, "buyerCode", "orderId"),
            new Target("ALL_BP import batches", AppConstants.Collection.PACKING_IMPORT_BATCHES, "buyerCode", "orderId"),
            // Historical data created with the shared ES import collections.
            new Target("Shipments", AppConstants.Collection.SHIPMENTS, "buyerCode", "orderId"),
            new Target("Packing list lines", AppConstants.Collection.PACKING_LIST_LINES, "buyerCode", "orderId"),
            new Target("Packing allocation lines", AppConstants.Collection.PACKING_ALLOCATION_LINES, "buyerCode", "orderId"),
            new Target("Carton operations", AppConstants.Collection.CARTON_OPERATIONS, "buyerCode", "orderId")
    );

    private static final String REGISTRY_LABEL = "PO identity registry";

    private Query scoped(String buyerField, String orderField, String orderId) {
        Criteria owner = "buyerCode_or_buyer_key".equals(buyerField)
                ? new Criteria().orOperator(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                    Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON))
                : Criteria.where(buyerField).is(AppConstants.Buyer.LULULEMON);
        return Query.query(new Criteria().andOperator(owner, Criteria.where(orderField).is(orderId)));
    }

    /** Read-only preview of the EXACT buyer + order predicates used by Delete All. */
    public Map<String, Long> previewCounts(String orderId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            counts.put(target.label(), mongo.collectionExists(target.collection())
                    ? mongo.count(scoped(target.buyerField(), target.orderField(), orderId), target.collection()) : 0L);
        }
        counts.put(REGISTRY_LABEL, mongo.collectionExists(AppConstants.Collection.PO_IDENTITY_REGISTRY)
                ? mongo.count(scoped("buyerCode", "orderId", orderId), AppConstants.Collection.PO_IDENTITY_REGISTRY) : 0L);
        return counts;
    }

    public boolean hasOrderData(String orderId) {
        for (Target target : TARGETS) {
            if (mongo.collectionExists(target.collection())
                    && mongo.exists(scoped(target.buyerField(), target.orderField(), orderId), target.collection())) {
                return true;
            }
        }
        return mongo.collectionExists(AppConstants.Collection.PO_IDENTITY_REGISTRY)
                && mongo.exists(scoped("buyerCode", "orderId", orderId), AppConstants.Collection.PO_IDENTITY_REGISTRY);
    }

    /**
     * Perform all Order-owned deletions through ONE path, whether triggered by
     * the regular Admin delete or the test-only Delete All button.
     * Return actual deleted counts for the test-only result/diagnostics.
     */
    public synchronized Map<String, Long> deleteAll(String orderId, String expectedName, String confirmation) {
        if (!RequestActor.isAdmin()) {
            throw new AccessDeniedException("Only Admin can permanently delete an Order containing operational data.");
        }
        OrderDocument order = mongo.findOne(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)), OrderDocument.class);
        if (order == null) throw new IllegalArgumentException("LULULEMON Order not found.");
        String required = AppConstants.OrderDeletion.CONFIRM_PREFIX + order.getOrderName();
        if (!required.equals(confirmation) || !order.getOrderName().equals(expectedName)) {
            throw new IllegalArgumentException("Type '" + required + "' to confirm deletion of the Order and all its operational data.");
        }
        if (mongo.exists(Query.query(Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON)
                        .and("order").is(orderId).and("stopped").is(false)), RfidPdaCapture.class)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Stop all active RFID PDA captures for this Order before deletion.");
        }
        registry.assertNoReservedForOrder(orderId);
        audit.log(RequestActor.current(), "ORDER_CASCADE_DELETE_STARTED", AppConstants.AuditResource.ORDER,
                orderId, "Permanent LULULEMON Order deletion started: " + order.getOrderName(), null, null);
        // Persist a deletion fence BEFORE removing dependents: a failed operation
        // leaves the Order available to retry without permitting new imports.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)),
                new Update().set("deletionState", AppConstants.OrderDeletion.DELETING), OrderDocument.class);
        registry.assertNoReservedForOrder(orderId);
        Map<String, Long> deleted = new LinkedHashMap<>();
        for (Target target : TARGETS) {
            long removed = mongo.collectionExists(target.collection())
                    ? mongo.remove(scoped(target.buyerField(), target.orderField(), orderId), target.collection()).getDeletedCount()
                    : 0L;
            deleted.put(target.label(), removed);
        }
        // Fail closed rather than removing the parent if ANY related documents remain.
        // This detects incomplete deletes, e.g. a concurrent writer ignoring the fence.
        for (Target target : TARGETS) {
            if (mongo.collectionExists(target.collection())
                    && mongo.exists(scoped(target.buyerField(), target.orderField(), orderId), target.collection())) {
                throw new IllegalStateException("Order deletion incomplete in " + target.collection() + ". Retry after resolving the writer.");
            }
        }
        // Never steal an identity still RESERVED by an ongoing import.
        registry.assertNoReservedForOrder(orderId);
        deleted.put(REGISTRY_LABEL, registry.releaseForDeletedOrder(orderId));
        if (mongo.collectionExists(AppConstants.Collection.PO_IDENTITY_REGISTRY)
                && mongo.exists(scoped("buyerCode", "orderId", orderId), AppConstants.Collection.PO_IDENTITY_REGISTRY)) {
            throw new IllegalStateException("Order deletion incomplete in PO identity registry. Reconcile its reservations and retry.");
        }
        // Parent last: a failure leaves an Order with its deletion fence for a safe retry.
        deleted.put("Order", mongo.remove(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)), OrderDocument.class).getDeletedCount());
        return deleted;
    }
}
