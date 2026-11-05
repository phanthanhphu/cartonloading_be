package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Buyer-wide ALL_BP four-field identity. _id is a SHA-256 of canonical Buyer +
 * Master PO + PO + Packing Plan + SO (PTS). The MongoDB _id index atomically
 * reserves one identity across concurrent applications/Factories.
 *
 * Existing purchase_orders remain authoritative for historical ownership;
 * run the accompanying one-time backfill before production for full coverage
 * of historical PO values containing atypical whitespace.
 */
@Service
public class PoIdentityRegistryService {
    public static final int LOOKUP_BATCH = 400;
    private final MongoTemplate mongo;
    public PoIdentityRegistryService(MongoTemplate mongo) { this.mongo = mongo; }

    public record Input(String masterPo, String poNumber, String packingPlan, String salesOrderPts,
                        String factoryCode, String orderId) { }
    public record Conflict(String masterPo, String poNumber, String packingPlan, String salesOrderPts,
                           String existingFactory, String importedBy, LocalDateTime importedAt,
                           String existingOrderId) { }
    public record Reservation(String id, String token) { }

    public static String identityId(String buyer, String master, String po, String plan, String so) {
        String canonical = String.join("\u0000", norm(buyer), norm(master), norm(po), norm(plan), norm(so));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String norm(String raw) { return Objects.toString(TextNormalizer.key(raw), ""); }
    private static String display(String value) { return Objects.toString(TextNormalizer.trimToNull(value), "(blank)"); }
    private static String id(Input input) {
        return identityId(AppConstants.Buyer.LULULEMON, input.masterPo(), input.poNumber(), input.packingPlan(), input.salesOrderPts());
    }
    public static Input from(PurchaseOrder po) {
        return new Input(po.getMasterPo(), po.getPoNumber(), po.getPackingPlan(), po.getSalesOrderPts(), po.getFactoryCode(), po.getOrderId());
    }

    /** Read-only preflight. Never load entire Buyer data into memory. */
    public void preflight(Collection<Input> inputs, String excludedPoId) {
        if (inputs.isEmpty()) return;
        Map<String, Input> requested = new LinkedHashMap<>();
        for (Input input : inputs) requested.put(id(input), input);
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> conflictingKeys = new HashSet<>();
        List<Input> unique = new ArrayList<>(requested.values());
        for (int start = 0; start < unique.size(); start += LOOKUP_BATCH) {
            List<Input> batch = unique.subList(start, Math.min(start + LOOKUP_BATCH, unique.size()));
            Set<String> ids = batch.stream().map(PoIdentityRegistryService::id).collect(Collectors.toSet());
            List<Document> records = mongo.find(Query.query(Criteria.where("_id").in(ids)),
                    Document.class, AppConstants.Collection.PO_IDENTITY_REGISTRY);
            for (Document record : records) {
                if (excludedPoId != null && excludedPoId.equals(record.getString("poId"))) continue;
                // A previous delete or interrupted import may leave a COMMITTED key
                // without any purchase_orders document. Reconcile only proven orphans;
                // never release a live RESERVED claim from another concurrent import.
                if (releaseOrphanCommitted(record)) continue;
                if (conflictingKeys.add(record.getString("_id"))) conflicts.add(toConflict(record));
            }
            // Historical POs created before the registry existed. An indexed,
            // batched search by PO Number avoids scanning every stored PO.
            List<String> poNumbers = batch.stream().map(Input::poNumber)
                    .filter(Objects::nonNull).distinct().toList();
            if (poNumbers.isEmpty()) continue;
            Query legacy = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                    .and("poNumber").in(poNumbers));
            legacy.fields().include("_id").include("buyerCode").include("orderId")
                    .include("factoryCode").include("masterPo").include("poNumber")
                    .include("packingPlan").include("salesOrderPts")
                    .include("createdBy").include("createdAt");
            // Case-insensitive equality uses the matching collation-aware index.
            legacy.collation(org.springframework.data.mongodb.core.query.Collation.of("en")
                    .strength(org.springframework.data.mongodb.core.query.Collation.ComparisonLevel.secondary()));
            for (PurchaseOrder prior : mongo.find(legacy, PurchaseOrder.class)) {
                if (excludedPoId != null && excludedPoId.equals(prior.getId())) continue;
                String priorKey = id(from(prior));
                if (ids.contains(priorKey) && conflictingKeys.add(priorKey)) {
                    conflicts.add(new Conflict(display(prior.getMasterPo()), display(prior.getPoNumber()),
                            display(prior.getPackingPlan()), display(prior.getSalesOrderPts()),
                            display(prior.getFactoryCode()), display(prior.getCreatedBy()),
                            prior.getCreatedAt(), prior.getOrderId()));
                }
            }
        }
        if (!conflicts.isEmpty()) throw new PoImportConflictException(conflicts.stream().distinct().toList());
    }

    private static Conflict toConflict(Document doc) {
        return new Conflict(display(doc.getString("masterPo")), display(doc.getString("poNumber")),
                display(doc.getString("packingPlan")), display(doc.getString("salesOrderPts")),
                display(doc.getString("factoryCode")), display(doc.getString("importedBy")),
                asLocalDateTime(doc.get("importedAt")), doc.getString("orderId"));
    }

    private static LocalDateTime asLocalDateTime(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof java.util.Date date) return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        return null;
    }

    /** Reserve all keys *before* writing POs. Remove only this request's claims on conflict. */
    public List<Reservation> reserve(Collection<Input> inputs, String actor) {
        List<Reservation> reserved = new ArrayList<>();
        String token = UUID.randomUUID().toString();
        try {
            for (Input input : inputs) {
                Document doc = new Document("_id", id(input))
                        .append("buyerCode", AppConstants.Buyer.LULULEMON)
                        .append("masterPo", input.masterPo()).append("poNumber", input.poNumber())
                        .append("packingPlan", input.packingPlan()).append("salesOrderPts", input.salesOrderPts())
                        .append("factoryCode", input.factoryCode()).append("orderId", input.orderId())
                        .append("importedBy", actor).append("importedAt", new java.util.Date())
                        .append("reservationToken", token).append("state", AppConstants.PoIdentity.RESERVED);
                try {
                    mongo.insert(doc, AppConstants.Collection.PO_IDENTITY_REGISTRY);
                } catch (DuplicateKeyException duplicate) {
                    Document owner = mongo.findById(id(input), Document.class, AppConstants.Collection.PO_IDENTITY_REGISTRY);
                    if (owner != null && releaseOrphanCommitted(owner)) {
                        // Retry once after an atomic, ownership-constrained removal.
                        mongo.insert(doc, AppConstants.Collection.PO_IDENTITY_REGISTRY);
                    } else if (owner != null) {
                        throw new PoImportConflictException(List.of(toConflict(owner)));
                    } else throw duplicate;
                }
                reserved.add(new Reservation(id(input), token));
            }
        } catch (RuntimeException ex) {
            releaseAll(reserved);
            throw ex;
        }
        return reserved;
    }

    public void commit(Input input, String poId, List<Reservation> reservations) {
        String key = id(input);
        for (Reservation reservation : reservations) {
            if (!reservation.id().equals(key)) continue;
            mongo.updateFirst(Query.query(Criteria.where("_id").is(key)
                    .and("reservationToken").is(reservation.token())),
                    new Update().set("poId", poId).set("state", AppConstants.PoIdentity.COMMITTED),
                    AppConstants.Collection.PO_IDENTITY_REGISTRY);
            return;
        }
    }

    public void releaseAll(List<Reservation> reservations) {
        for (Reservation reservation : reservations) {
            mongo.remove(Query.query(Criteria.where("_id").is(reservation.id())
                    .and("reservationToken").is(reservation.token())
                    .and("state").is(AppConstants.PoIdentity.RESERVED)), AppConstants.Collection.PO_IDENTITY_REGISTRY);
        }
    }


    /**
     * Remove only COMMITTED registry entries whose referenced PO no longer exists.
     * RESERVED entries are never auto-cleared: they may represent active imports.
     * A matching purchase_orders document always wins over registry cleanup.
     */
    private boolean releaseOrphanCommitted(Document record) {
        if (!AppConstants.PoIdentity.COMMITTED.equals(record.getString("state"))) return false;
        String poId = record.getString("poId");
        if (poId == null || poId.isBlank()) return false;
        if (mongo.exists(Query.query(Criteria.where("_id").is(poId)), PurchaseOrder.class)) return false;
        // Remove only if no live PO with this business identity remains; preflight
        // additionally checks legacy purchase_orders for collisions after this step.
        Query owned = Query.query(Criteria.where("_id").is(record.getString("_id"))
                .and("poId").is(poId).and("state").is(AppConstants.PoIdentity.COMMITTED));
        mongo.remove(owned, AppConstants.Collection.PO_IDENTITY_REGISTRY);
        return true;
    }

    /** Order deletion may not steal a key still reserved by an active import. */
    public void assertNoReservedForOrder(String orderId) {
        Query active = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId).and("state").is(AppConstants.PoIdentity.RESERVED));
        if (mongo.exists(active, AppConstants.Collection.PO_IDENTITY_REGISTRY)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "This Order has pending ALL_BP imports. Finish or reconcile them before deleting.");
        }
    }

    /** Bulk cleanup includes stale registry keys for an Order whose POs were deleted. */
    public long releaseForDeletedOrder(String orderId) {
        Query owned = Query.query(Criteria.where("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(orderId).and("state").is(AppConstants.PoIdentity.COMMITTED));
        return mongo.remove(owned, AppConstants.Collection.PO_IDENTITY_REGISTRY).getDeletedCount();
    }

    /**
     * A deleted PO can leave a RESERVED claim if its original import crashed
     * after saveAll but before commit. Release ONLY the exact Order/key, and
     * only after the PO has actually disappeared from purchase_orders.
     */
    public void releaseAfterPoDelete(PurchaseOrder deleted) {
        String key = id(from(deleted));
        if (mongo.exists(Query.query(Criteria.where("_id").is(deleted.getId())), PurchaseOrder.class)) {
            throw new IllegalStateException("Cannot release PO identity while the PO still exists.");
        }
        Query matching = Query.query(Criteria.where("_id").is(key)
                .and("buyerCode").is(AppConstants.Buyer.LULULEMON)
                .and("orderId").is(deleted.getOrderId())
                .and("state").in(AppConstants.PoIdentity.COMMITTED, AppConstants.PoIdentity.RESERVED));
        mongo.remove(matching, AppConstants.Collection.PO_IDENTITY_REGISTRY);
    }

    /** Free ownership only after a successful authenticated PO delete or identity edit. */
    public void releaseCommitted(PurchaseOrder old) {
        String key = id(from(old));
        mongo.remove(Query.query(Criteria.where("_id").is(key)
                .and("poId").is(old.getId()).and("state").is(AppConstants.PoIdentity.COMMITTED)),
                AppConstants.Collection.PO_IDENTITY_REGISTRY);
    }
}
