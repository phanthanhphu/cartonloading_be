package org.bsl.cartonloading.buyers.lululemon.service;

import java.time.LocalDateTime;
import java.util.*;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickClaim;
import org.bsl.cartonloading.buyers.lululemon.model.PoPickEvent;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** New picking workflow; deliberately does not modify existing carton/scan services. */
@Service
public class PoPickingService {
    private final PurchaseOrderRepository pos;
    private final CartonRepository cartons;
    private final PackingOrderService orders;
    private final BuyerFactoryAccessService access;
    private final MongoTemplate mongo;

    public PoPickingService(PurchaseOrderRepository pos, CartonRepository cartons,
            PackingOrderService orders, BuyerFactoryAccessService access, MongoTemplate mongo) {
        this.pos = pos; this.cartons = cartons; this.orders = orders; this.access = access; this.mongo = mongo;
    }

    public record PickRow(String buyerKey, String orderId, String poNumber, String factoryCode,
                          String pickedBy, LocalDateTime pickedAt, String pickStatus, int lines) {}

    private void verifyBuyer(String buyer, String orderId) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("Only LULULEMON is supported");
        }
        orders.getEntity(AppConstants.Buyer.LULULEMON, orderId);
        if (!access.currentUser().canAccessBuyer(AppConstants.Buyer.LULULEMON)) {
            throw new AccessDeniedException("Buyer access denied");
        }
    }

    private String claimId(String orderId, String poNumber) {
        // URL-safe stable ID; includes buyer and order, no ambiguous separators.
        return "LULULEMON:" + Base64.getUrlEncoder().withoutPadding().encodeToString(orderId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(poNumber.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String factory(String value) {
        String normalized = User.normalizeFactoryCode(value);
        if (!normalized.matches("F[1-7]")) throw new IllegalArgumentException("Factory must be F1..F7");
        return normalized;
    }

    private List<PurchaseOrder> lines(String orderId, String poNumber) {
        String normalized = poNumber == null ? "" : poNumber.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("PO number is required");
        List<PurchaseOrder> found = pos.findAllByOrderIdAndBuyerCodeAndPoNumber(orderId, AppConstants.Buyer.LULULEMON, normalized);
        if (found.isEmpty()) throw new IllegalArgumentException("PO not found: " + normalized);
        return found;
    }

    private void requirePacking(User user) {
        if (!user.isAdminRole() && !user.canAssignBarcode()) {
            throw new AccessDeniedException("Packing permission required");
        }
    }

    public List<PickRow> list(String buyer, String orderId) {
        verifyBuyer(buyer, orderId);
        Map<String, List<PurchaseOrder>> grouped = new TreeMap<>();
        for (PurchaseOrder po : pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)) {
            grouped.computeIfAbsent(po.getPoNumber(), key -> new ArrayList<>()).add(po);
        }
        List<PickRow> response = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String number = entry.getKey();
            PoPickClaim claim = mongo.findById(claimId(orderId, number), PoPickClaim.class);
            PurchaseOrder first = entry.getValue().stream()
                    .filter(row -> row.getPickedBy() != null && !row.getPickedBy().isBlank())
                    .findFirst().orElse(entry.getValue().get(0));
            // A historical PO may have a pickedBy value but no claim document.
            // Never display such a PO as available in the shared pool.
            boolean legacyOwner = first.getPickedBy() != null && !first.getPickedBy().isBlank();
            response.add(new PickRow("LULULEMON", orderId, number,
                    claim == null ? first.getFactoryCode() : claim.getFactoryCode(),
                    claim == null ? first.getPickedBy() : claim.getPickedBy(),
                    claim == null ? first.getPickedAt() : claim.getPickedAt(),
                    claim == null ? (legacyOwner ? "LEGACY_PICKED" : "UNPICKED") : claim.getState(), entry.getValue().size()));
        }
        return response;
    }

    /** Immutable audit history, scoped to buyer and parent Order. */
    public List<PoPickEvent> history(String buyer, String orderId, String poNumber) {
        verifyBuyer(buyer, orderId);
        return mongo.find(Query.query(Criteria.where("buyer_key").is("LULULEMON")
                        .and("orderId").is(orderId).and("poNumber").is(poNumber))
                        .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")),
                PoPickEvent.class);
    }

    private void audit(String orderId, String poNumber, String factory, String actor, String action) {
        audit(orderId, poNumber, factory, actor, action, null, null);
    }

    private void audit(String orderId, String poNumber, String factory, String actor, String action,
                       String previousPickedBy, String reason) {
        PoPickEvent event = new PoPickEvent();
        event.setOrderId(orderId);
        event.setPoNumber(poNumber);
        event.setFactoryCode(factory);
        event.setActor(actor);
        event.setAction(action);
        event.setPreviousPickedBy(previousPickedBy);
        event.setReason(reason);
        event.setCreatedAt(LocalDateTime.now());
        mongo.insert(event);
    }

    public synchronized PickRow pick(String buyer, String orderId, String poNumber, String targetFactory) {
        verifyBuyer(buyer, orderId);
        User user = access.currentUser();
        requirePacking(user);
        String selectedFactory = factory(targetFactory);
        if (!user.isAdminRole() && !user.getFactoryPermissions().contains(selectedFactory)) {
            throw new AccessDeniedException("Packing user is not assigned to " + selectedFactory);
        }
        List<PurchaseOrder> rows = lines(orderId, poNumber);
        String original = null;
        for (PurchaseOrder row : rows) {
            String current = row.getFactoryCode();
            if (current != null && !current.isBlank() && !selectedFactory.equalsIgnoreCase(current)) {
                throw new IllegalArgumentException("PO is already scoped to Factory " + current);
            }
            if (row.getPickedBy() != null && !row.getPickedBy().isBlank()) {
                throw new IllegalArgumentException("PO already picked by " + row.getPickedBy());
            }
            if (original == null && current != null && !current.isBlank()) original = current;
        }
        PoPickClaim claim = new PoPickClaim();
        claim.setId(claimId(orderId, poNumber.trim()));
        claim.setOrderId(orderId);
        claim.setPoNumber(poNumber.trim());
        claim.setFactoryCode(selectedFactory);
        claim.setOriginalFactoryCode(original);
        claim.setPickedBy(user.getEmail());
        claim.setPickedAt(LocalDateTime.now());
        try {
            // Atomic unique _id insertion: only one of concurrent Packing requests wins.
            mongo.insert(claim);
        } catch (DuplicateKeyException ex) {
            throw new IllegalArgumentException("PO already picked by another Packing. Refresh the list.");
        }
        try {
            List<String> ids = rows.stream().map(PurchaseOrder::getId).toList();
            Query q = Query.query(Criteria.where("_id").in(ids).and("buyerCode").is("LULULEMON").and("orderId").is(orderId));
            mongo.updateMulti(q, new Update().set("buyer_key", "LULULEMON").set("factoryCode", selectedFactory)
                .set("pickedBy", user.getEmail()).set("pickedAt", claim.getPickedAt()), PurchaseOrder.class);
        } catch (RuntimeException ex) {
            mongo.remove(Query.query(Criteria.where("_id").is(claim.getId())), PoPickClaim.class);
            throw ex;
        }
        audit(orderId, poNumber.trim(), selectedFactory, user.getEmail(), "PICK");
        return new PickRow("LULULEMON", orderId, poNumber.trim(), selectedFactory,
                user.getEmail(), claim.getPickedAt(), "ACTIVE", rows.size());
    }

    /** Any authorized Packing user may release a PO claimed by another Packing.
     *  Existing operational-data checks remain in force; no carton/weight data is deleted.
     *  Cross-Packing release requires an explanation for the audit log.
     */
    public synchronized PickRow unpick(String buyer, String orderId, String poNumber, String reason) {
        verifyBuyer(buyer, orderId);
        User user = access.currentUser();
        requirePacking(user);
        List<PurchaseOrder> rows = lines(orderId, poNumber);
        String id = claimId(orderId, poNumber.trim());
        PoPickClaim claim = mongo.findById(id, PoPickClaim.class);
        if (claim == null) throw new IllegalArgumentException("PO has no active pick");
        if (!"ACTIVE".equals(claim.getState())) {
            throw new IllegalArgumentException("PO is being modified. Refresh and retry");
        }
        String actorEmail = user.getEmail();
        boolean otherPacking = !actorEmail.equalsIgnoreCase(claim.getPickedBy());
        String trimmedReason = reason == null ? "" : reason.trim();
        if (otherPacking && trimmedReason.isEmpty()) {
            throw new IllegalArgumentException("A reason is required when unpicking another Packing's PO");
        }
        // Keep the existing protections: SKU assignment, generated cartons or
        // a progressed PO status mean the PO has operational dependencies.
        if (rows.stream().anyMatch(po -> (po.getSku() != null && !po.getSku().isBlank())
                || !cartons.findByPoIdOrderByCartonNoAsc(po.getId()).isEmpty()
                || !AppConstants.PurchaseOrderStatus.NOT_STARTED.equals(po.getStatus()))) {
            throw new IllegalArgumentException("Cannot unpick: PO already has SKU assignment, cartons, Packing or Weighing activity");
        }
        var locked = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("state").is("ACTIVE")),
                new Update().set("state", "RELEASING"), PoPickClaim.class);
        if (locked == null) throw new IllegalArgumentException("PO is being modified. Refresh and retry");
        try {
            Query q = Query.query(Criteria.where("_id").in(rows.stream().map(PurchaseOrder::getId).toList())
                    .and("buyerCode").is("LULULEMON").and("orderId").is(orderId));
            Update u = new Update().set("factoryCode", claim.getOriginalFactoryCode())
                    .unset("pickedBy").unset("pickedAt");
            mongo.updateMulti(q, u, PurchaseOrder.class);
            mongo.remove(Query.query(Criteria.where("_id").is(id).and("state").is("RELEASING")), PoPickClaim.class);
        } catch (RuntimeException ex) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("state", "ACTIVE"), PoPickClaim.class);
            throw ex;
        }
        audit(orderId, poNumber.trim(), claim.getFactoryCode(), actorEmail, "UNPICK",
                claim.getPickedBy(), trimmedReason.isEmpty() ? null : trimmedReason);
        return new PickRow("LULULEMON", orderId, poNumber.trim(), claim.getOriginalFactoryCode(), null, null, "UNPICKED", rows.size());
    }
}
