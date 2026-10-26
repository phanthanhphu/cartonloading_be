package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class PoShippingPlanService {
    private final PackingOrderService orders;
    private final PurchaseOrderRepository pos;
    private final BuyerFactoryAccessService factoryAccess;
    private final FactoryCatalogService factories;
    private final MongoTemplate mongo;
    private final AuditLogService audit;

    public PoShippingPlanService(PackingOrderService orders, PurchaseOrderRepository pos,
                                 BuyerFactoryAccessService factoryAccess, FactoryCatalogService factories,
                                 MongoTemplate mongo, AuditLogService audit) {
        this.orders = orders;
        this.pos = pos;
        this.factoryAccess = factoryAccess;
        this.factories = factories;
        this.mongo = mongo;
        this.audit = audit;
    }

    /** One PO has at most one shipping date. No carton/RFID/weight state is modified. */
    public synchronized Map<String, Object> schedule(String buyer, String orderId, PoShippingPlanRequest request) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("PO shipping planning is available for LULULEMON only.");
        }
        orders.getEntity(AppConstants.Buyer.LULULEMON, orderId); // verifies Order ownership and Factory scope
        if (request == null || request.shippingDate() == null) {
            throw new IllegalArgumentException("Select a shipping date.");
        }
        List<String> rawIds = request.poIds();
        if (rawIds == null || rawIds.isEmpty() || rawIds.size() > 200) {
            throw new IllegalArgumentException("Select 1 to 200 POs per submission.");
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            if (rawId == null || rawId.isBlank() || !uniqueIds.add(rawId.trim())) {
                throw new IllegalArgumentException("PO selection contains an empty or duplicate PO.");
            }
        }

        // Validate all IDs and Factory permissions BEFORE modifying any PO.
        for (String poId : uniqueIds) {
            PurchaseOrder po = pos.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                    .orElseThrow(() -> new IllegalArgumentException("PO does not belong to this Order: " + poId));
            String factory = po.getFactoryCode();
            if (factory == null || factory.isBlank()) {
                throw new IllegalArgumentException("PO " + po.getPoNumber() + " has no assigned Factory.");
            }
            factories.requireActive(AppConstants.Buyer.LULULEMON, factory);
            factoryAccess.assertAssignedFactoryAccess(factory);
            if (po.getPlannedShippingDate() != null) {
                throw new IllegalArgumentException("PO " + po.getPoNumber() + " is already scheduled for " + po.getPlannedShippingDate() + ".");
            }
        }

        String actor = RequestActor.current();
        LocalDateTime now = LocalDateTime.now();
        Query eligible = Query.query(new Criteria().andOperator(
                Criteria.where("_id").in(uniqueIds),
                Criteria.where("orderId").is(orderId),
                Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON),
                Criteria.where("plannedShippingDate").is(null)
        ));
        Update fields = new Update()
                .set("plannedShippingDate", request.shippingDate())
                .set("shippingScheduledBy", actor)
                .set("shippingScheduledAt", now)
                .set("updatedBy", actor)
                .set("updatedAt", now);
        // Predicate prevents concurrent requests from silently overwriting a shipping date.
        long changed = mongo.updateMulti(eligible, fields, PurchaseOrder.class).getModifiedCount();
        if (changed != uniqueIds.size()) {
            // In a cross-server race some POs may have been saved: client must refresh both lists.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Some selected POs were scheduled concurrently. Refresh the lists and verify the saved dates.");
        }
        audit.log(actor, AppConstants.AuditAction.PO_SHIPPING_PLAN_CREATE,
                AppConstants.AuditResource.PO_SHIPPING_PLAN, orderId,
                "PO shipping date " + request.shippingDate() + " for " + changed + " logical PO(s): " + String.join(",", uniqueIds),
                null, null);
        return Map.of("shippingDate", request.shippingDate().toString(), "scheduledCount", changed);
    }
}
