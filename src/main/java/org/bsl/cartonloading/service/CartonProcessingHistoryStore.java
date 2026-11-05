package org.bsl.cartonloading.service;

import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.model.CartonProcessingHistory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** Reusable, buyer-neutral projection store; no periodic polling or collection scans. */
@Service
public class CartonProcessingHistoryStore {
    private final MongoTemplate mongo;
    public CartonProcessingHistoryStore(MongoTemplate mongo) { this.mongo = mongo; }

    @PostConstruct
    public void ensureIndexes() {
        var ops = mongo.indexOps(CartonProcessingHistory.class);
        ops.ensureIndex(new Index().on("buyerCode", Sort.Direction.ASC)
                .on("orderId", Sort.Direction.ASC).on("factoryCode", Sort.Direction.ASC)
                .on("weightTime", Sort.Direction.DESC).on("_id", Sort.Direction.DESC)
                .named("history_scope_completed_desc"));
        ops.ensureIndex(new Index().on("buyerCode", Sort.Direction.ASC)
                .on("orderId", Sort.Direction.ASC).on("weightTime", Sort.Direction.DESC)
                .on("_id", Sort.Direction.DESC).named("history_order_completed_desc"));
        ops.ensureIndex(new Index().on("buyerCode", Sort.Direction.ASC)
                .on("orderId", Sort.Direction.ASC).on("poNumber", Sort.Direction.ASC)
                .on("weightTime", Sort.Direction.DESC).named("history_order_po_completed"));
        ops.ensureIndex(new Index().on("buyerCode", Sort.Direction.ASC)
                .on("sscc18", Sort.Direction.ASC).named("history_buyer_sscc"));
    }

    /** Deterministic _id makes writes safely retryable after application/network failures. */
    public CartonProcessingHistory save(CartonProcessingHistory snapshot) {
        snapshot.setId(CartonProcessingHistory.key(snapshot.getBuyerCode(), snapshot.getCartonId()));
        if (!"PASS".equals(snapshot.getStatus()) && !"FAILED".equals(snapshot.getStatus()))
            throw new IllegalArgumentException("History only accepts completed PASS/FAILED outcomes");
        if (snapshot.getWeightTime() == null) throw new IllegalArgumentException("Weight completion timestamp required");
        return mongo.save(snapshot);
    }

    /** Reopen/Recall removes only the current finished projection; immutable attempts stay intact. */
    public void removeCurrent(String buyerCode, String cartonId) {
        mongo.remove(Query.query(Criteria.where("_id").is(CartonProcessingHistory.key(buyerCode, cartonId))
                .and("buyerCode").is(buyerCode)), CartonProcessingHistory.class);
    }
}
