package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.es.model.PackingOrder;
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

    private record Target(String collection, String buyerField, String orderField) { }

    private static final List<Target> TARGETS = List.of(
            new Target(AppConstants.Collection.RFID_SCAN_SESSIONS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.RFID_PDA_CAPTURES, "buyer_key", "order"),
            new Target(AppConstants.Collection.PO_PICK_CLAIMS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.PO_PICK_HISTORY, "buyer_key", "orderId"),
            new Target(AppConstants.Collection.CARTON_CHECKING_HISTORY, "buyerCode", "orderId"),
            new Target("print_handoffs", "buyerCode_or_buyer_key", "orderId"),
            new Target(AppConstants.Collection.CARTON_PROCESSING_HISTORY, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.WEIGHING_RESULTS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.WEIGHING_ORDERS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.WEIGHING_PROFILES, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.SHIPPING_SCHEDULES, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.SCAN_EVENTS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.CARTON_ITEMS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.CARTONS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.PURCHASE_ORDERS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.PACKING_IMPORT_BATCHES, "buyerCode", "orderId"),
            // These may exist for historic Orders using the shared ES imports.
            new Target(AppConstants.Collection.SHIPMENTS, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.PACKING_LIST_LINES, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.PACKING_ALLOCATION_LINES, "buyerCode", "orderId"),
            new Target(AppConstants.Collection.CARTON_OPERATIONS, "buyerCode", "orderId")
    );

    private Query scoped(String buyerField, String orderField, String orderId) {
        Criteria owner = "buyerCode_or_buyer_key".equals(buyerField)
                ? new Criteria().orOperator(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                    Criteria.where("buyer_key").is(AppConstants.Buyer.LULULEMON))
                : Criteria.where(buyerField).is(AppConstants.Buyer.LULULEMON);
        return Query.query(new Criteria().andOperator(owner, Criteria.where(orderField).is(orderId)));
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

    public synchronized void deleteAll(String orderId, String expectedName, String confirmation) {
        if (!RequestActor.isAdmin()) {
            throw new AccessDeniedException("Only Admin can permanently delete an Order containing operational data.");
        }
        PackingOrder order = mongo.findOne(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)), PackingOrder.class);
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
        // An audit record survives an interrupted partial cleanup.
        audit.log(RequestActor.current(), "ORDER_CASCADE_DELETE_STARTED", AppConstants.AuditResource.ORDER,
                orderId, "Permanent LULULEMON Order deletion started: " + order.getOrderName(), null, null);
        // A persisted deletion fence prevents new Workflow imports from beginning.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)),
                new Update().set("deletionState", AppConstants.OrderDeletion.DELETING), PackingOrder.class);
        // Recheck after placing the fence. A competing importer may have reserved
        // an identity while the Order was still available.
        registry.assertNoReservedForOrder(orderId);
        for (Target target : TARGETS) {
            if (mongo.collectionExists(target.collection())) {
                mongo.remove(scoped(target.buyerField(), target.orderField(), orderId), target.collection());
            }
        }
        // Release registry only after all dependent POs have been removed.
        registry.releaseForDeletedOrder(orderId);
        // Parent last: a partial failure leaves an Order to retry deletion against.
        mongo.remove(Query.query(Criteria.where("_id").is(orderId)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)), PackingOrder.class);
    }
}
