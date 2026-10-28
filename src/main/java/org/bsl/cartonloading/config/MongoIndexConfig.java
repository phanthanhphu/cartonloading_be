package org.bsl.cartonloading.config;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Creates indexes for the current multi-buyer schema.
 *
 * This initializer is create-only except for targeted LULULEMON migrations:
 * prior PO-level and two-column unique indexes are superseded by the four-field
 * ALL_BP identity: Master PO + PO + Packing Plan + SO (PTS) within an Order.
 */
@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void createIndexes() {
        createCoreIndexes();
        createOrderAndPackingIndexes();
        createCartonOperationIndexes();
        createBarcodeAndShipmentIndexes();
        createPurchaseOrderIndexes();
        createCartonIndexes();
        createOperationalEventIndexes();
        createWeighingIndexes();
        System.out.println("Carton Loading multi-buyer indexes created successfully (create-only).");
    }

    private void createCoreIndexes() {
        MongoCollection<Document> factories = mongoTemplate.getCollection(AppConstants.Collection.FACTORIES);
        createIndexIfMissing(factories, Indexes.compoundIndex(Indexes.ascending("buyer_key"), Indexes.ascending("code")),
                new IndexOptions().name("factory_buyer_code_unique").unique(true));
        MongoCollection<Document> buyers = mongoTemplate.getCollection(AppConstants.Collection.BUYERS);
        createIndexIfMissing(buyers, Indexes.ascending("buyerKey"), new IndexOptions().name("uq_buyer_key").unique(true));
        createIndexIfMissing(buyers, Indexes.ascending("slug"), new IndexOptions().name("uq_buyer_slug").unique(true));
        createIndexIfMissing(buyers, Indexes.compoundIndex(Indexes.ascending("active"), Indexes.ascending("sequence")),
                new IndexOptions().name("idx_buyer_active_sequence"));
    }

    private void createOrderAndPackingIndexes() {
        MongoCollection<Document> orders = mongoTemplate.getCollection(AppConstants.Collection.ORDERS);
        createIndexIfMissing(orders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("orderDate")),
                new IndexOptions().name("idx_order_buyer_date")
        );
        createIndexIfMissing(orders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("updatedAt")),
                new IndexOptions().name("idx_order_buyer_updated")
        );

        MongoCollection<Document> allocationLines = mongoTemplate.getCollection(AppConstants.Collection.PACKING_ALLOCATION_LINES);
        createIndexIfMissing(allocationLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_allocation_order_line")
        );
        createIndexIfMissing(allocationLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")),
                new IndexOptions().name("idx_allocation_order_po")
        );
        createIndexIfMissing(allocationLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")),
                new IndexOptions().name("idx_allocation_order_article")
        );

        MongoCollection<Document> packingListLines = mongoTemplate.getCollection(AppConstants.Collection.PACKING_LIST_LINES);
        createIndexIfMissing(packingListLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_packing_list_order_line")
        );
        createIndexIfMissing(packingListLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")),
                new IndexOptions().name("idx_packing_list_order_po")
        );
        createIndexIfMissing(packingListLines, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")),
                new IndexOptions().name("idx_packing_list_order_article")
        );

        MongoCollection<Document> scaleStations = mongoTemplate.getCollection(AppConstants.Collection.SCALE_STATIONS);
        createIndexIfMissing(scaleStations, Indexes.ascending("stationCode"), new IndexOptions().name("uq_scale_station_code").unique(true));
        createIndexIfMissing(scaleStations, Indexes.ascending("active"), new IndexOptions().name("idx_scale_station_active"));
    }

    private void createCartonOperationIndexes() {
        MongoCollection<Document> cartonOperations = mongoTemplate.getCollection(AppConstants.Collection.CARTON_OPERATIONS);
        createIndexIfMissing(cartonOperations, 
                Indexes.ascending("jobId"),
                new IndexOptions().name("uq_carton_operation_job_id").unique(true)
                        .partialFilterExpression(new Document("jobId", new Document("$type", "number")))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.ascending("stationCode"),
                new IndexOptions().name("uq_station_waiting_job").unique(true)
                        .partialFilterExpression(new Document("status", AppConstants.ProgressStatus.WAITING_WEIGHT))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("scannedAt")),
                new IndexOptions().name("idx_carton_operation_order_scanned")
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_operation_line_status")
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions().name("uq_carton_operation_line_sequence").unique(true)
                        .partialFilterExpression(new Document("packingLineId", new Document("$type", "string")))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("masterLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions().name("uq_carton_operation_master_sequence").unique(true)
                        .partialFilterExpression(new Document("masterLineId", new Document("$type", "string")))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("orderCartonSequence")),
                new IndexOptions().name("idx_carton_operation_order_sequence")
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("factoryBarcode"),
                        Indexes.ascending("orderCartonSequence")
                ),
                new IndexOptions().name("idx_carton_operation_assignment_sequence")
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("itemKey")),
                new IndexOptions().name("uq_carton_operation_item_key").unique(true)
                        .partialFilterExpression(new Document("itemKey", new Document("$type", "string")))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("poNumber"),
                        Indexes.ascending("articleNumber"),
                        Indexes.ascending("itemSequence")
                ),
                new IndexOptions().name("idx_carton_operation_business_sequence")
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.ascending("factoryBarcode"),
                new IndexOptions().name("uq_carton_operation_factory_barcode").unique(true)
                        .partialFilterExpression(new Document("factoryBarcode", new Document("$type", "string")))
        );
        createIndexIfMissing(cartonOperations, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentId")),
                new IndexOptions().name("idx_carton_operation_buyer_shipment")
        );
    }

    private void createBarcodeAndShipmentIndexes() {
        MongoCollection<Document> factoryBarcodes = mongoTemplate.getCollection(AppConstants.Collection.FACTORY_BARCODES);
        createIndexIfMissing(factoryBarcodes, Indexes.ascending("barcode"), new IndexOptions().name("uq_factory_barcode").unique(true));
        createIndexIfMissing(factoryBarcodes, 
                Indexes.compoundIndex(Indexes.ascending("year"), Indexes.ascending("factoryCode"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("uq_factory_barcode_sequence").unique(true)
        );
        createIndexIfMissing(factoryBarcodes, 
                Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_factory_barcode_status_created")
        );
        createIndexIfMissing(factoryBarcodes, 
                Indexes.compoundIndex(Indexes.ascending("batchId"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("idx_factory_barcode_batch_sequence")
        );
        createIndexIfMissing(factoryBarcodes, 
                Indexes.ascending("assignedCartonId"),
                new IndexOptions().name("uq_factory_barcode_assigned_carton").unique(true)
                        .partialFilterExpression(new Document("assignedCartonId", new Document("$type", "string")))
        );

        MongoCollection<Document> shipments = mongoTemplate.getCollection(AppConstants.Collection.SHIPMENTS);
        createIndexIfMissing(shipments, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentNo")),
                new IndexOptions().name("uq_shipment_buyer_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("shipmentNo", new Document("$type", "string")))
        );
        createIndexIfMissing(shipments, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_shipment_buyer_created")
        );
    }

    private void createPurchaseOrderIndexes() {
        MongoCollection<Document> purchaseOrders = mongoTemplate.getCollection(AppConstants.Collection.PURCHASE_ORDERS);

        // PO is unique together with Master PO, not on its own. The old PO-only
        // index would incorrectly block different Master PO + PO combinations.
        String legacyPoUniqueIndex = "uq_purchase_order_buyer_order_number";
        if (hasIndex(purchaseOrders, legacyPoUniqueIndex)) {
            purchaseOrders.dropIndex(legacyPoUniqueIndex);
        }

        createIndexIfMissing(purchaseOrders, 
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("dcCode"),
                        Indexes.ascending("destination"),
                        Indexes.ascending("channel"),
                        Indexes.ascending("masterPo"),
                        Indexes.ascending("poNumber"),
                        Indexes.ascending("packingPlan"),
                        Indexes.ascending("salesOrderPts"),
                        Indexes.ascending("style"),
                        Indexes.ascending("description"),
                        Indexes.ascending("color")
                ),
                new IndexOptions().name("uq_purchase_order_lululemon_business_key").unique(true)
                        .partialFilterExpression(new Document("buyerCode", AppConstants.Buyer.LULULEMON)
                                .append("orderId", new Document("$type", "string"))
                                .append("poNumber", new Document("$type", "string")))
        );
        // Enforce the current four-column LULULEMON identity at database level.
        // IMPORTANT: create the replacement BEFORE removing the old 2-key index
        // so failed migrations never silently remove the existing constraint.
        // Do not mutate historical Carton / Shipping / Weight / RFID data.
        String oldPairIndex = "uq_lululemon_order_master_po_po";
        String fourKeyIndex = "uq_lululemon_order_master_po_po_plan_so_pts";
        try {
            createIndexIfMissing(purchaseOrders, 
                    Indexes.compoundIndex(
                            Indexes.ascending("buyerCode"), Indexes.ascending("orderId"),
                            Indexes.ascending("masterPo"), Indexes.ascending("poNumber"),
                            Indexes.ascending("packingPlan"), Indexes.ascending("salesOrderPts")),
                    new IndexOptions().name(fourKeyIndex).unique(true)
                            .collation(Collation.builder().locale("en")
                                    .collationStrength(CollationStrength.SECONDARY).build())
                            .partialFilterExpression(new Document("buyerCode", AppConstants.Buyer.LULULEMON)
                                    .append("orderId", new Document("$type", "string"))
                                    .append("masterPo", new Document("$type", "string"))
                                    .append("poNumber", new Document("$type", "string")))
            );
            if (hasIndex(purchaseOrders, oldPairIndex)) {
                purchaseOrders.dropIndex(oldPairIndex);
            }
        } catch (MongoException ex) {
            if (ex.getCode() != 11000) throw ex;
            System.err.println("WARNING: Existing LULULEMON POs violate the four-column unique key "
                    + "(Master PO, PO, Packing Plan, SO (PTS)). "
                    + "Index " + fourKeyIndex + " could not be created; review historical duplicates "
                    + "without deleting operational records. The former two-key index, if present, "
                    + "was preserved. Application upload validation remains active. "
                    + ex.getMessage());
        }

        createIndexIfMissing(purchaseOrders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_purchase_order_factory_status")
        );
        createIndexIfMissing(purchaseOrders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("sku")),
                new IndexOptions().name("idx_purchase_order_sku")
        );
    }

    private void createCartonIndexes() {
        MongoCollection<Document> cartons = mongoTemplate.getCollection(AppConstants.Collection.CARTONS);
        createIndexIfMissing(cartons, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.ascending("cartonNo")),
                new IndexOptions().name("uq_carton_buyer_po_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("poId", new Document("$type", "string"))
                                .append("cartonNo", new Document("$type", "number")))
        );
        createIndexIfMissing(cartons, 
                Indexes.ascending("sscc18"),
                new IndexOptions().name("uq_carton_sscc18").unique(true)
                        .partialFilterExpression(new Document("sscc18", new Document("$type", "string")))
        );
        createIndexIfMissing(cartons, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_buyer_factory_status")
        );
        createIndexIfMissing(cartons, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poId")),
                new IndexOptions().name("idx_carton_buyer_order_po")
        );

        MongoCollection<Document> cartonItems = mongoTemplate.getCollection(AppConstants.Collection.CARTON_ITEMS);
        createIndexIfMissing(cartonItems, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.ascending("itemNo")),
                new IndexOptions().name("uq_carton_item_buyer_carton_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("cartonId", new Document("$type", "string"))
                                .append("itemNo", new Document("$type", "number")))
        );
        createIndexIfMissing(cartonItems, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_item_buyer_po_status")
        );
    }

    private void createOperationalEventIndexes() {
        MongoCollection<Document> scanEvents = mongoTemplate.getCollection(AppConstants.Collection.SCAN_EVENTS);
        createIndexIfMissing(scanEvents, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_order_created")
        );
        createIndexIfMissing(scanEvents, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_po_created")
        );
        createIndexIfMissing(scanEvents, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_carton_created")
        );
    }

    private void createWeighingIndexes() {
        MongoCollection<Document> weighingProfiles = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_PROFILES);
        createIndexIfMissing(weighingProfiles, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poId")),
                new IndexOptions().name("uq_weighing_profile_buyer_order_po").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("orderId", new Document("$type", "string"))
                                .append("poId", new Document("$type", "string")))
        );

        MongoCollection<Document> weighingOrders = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_ORDERS);
        createIndexIfMissing(weighingOrders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_order_buyer_order_created")
        );
        createIndexIfMissing(weighingOrders, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_weighing_order_buyer_factory_status")
        );

        MongoCollection<Document> weighingResults = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_RESULTS);
        createIndexIfMissing(weighingResults, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("weighingOrderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_result_buyer_order_created")
        );
        createIndexIfMissing(weighingResults, 
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_result_buyer_carton_created")
        );
    }
    /**
     * Preserve existing database indexes whose keys AND operational options match,
     * even if older deployments used a different index name. MongoDB otherwise
     * throws IndexOptionsConflict (85) when creating the same index under a new name.
     *
     * No indexes or business records are removed here. In particular, an existing
     * non-unique or differently filtered index must never satisfy a unique index
     * request. All non-equivalent cases remain subject to MongoDB's validation.
     */
    private void createIndexIfMissing(MongoCollection<Document> collection, Bson keys, IndexOptions options) {
        BsonDocument expectedKeys = keys.toBsonDocument(Document.class, collection.getCodecRegistry());
        List<Document> currentIndexes;
        try {
            currentIndexes = collection.listIndexes().into(new ArrayList<>());
        } catch (MongoException ex) {
            // A fresh deployment may not have created this collection yet.
            if (ex.getCode() != 26) throw ex; // NamespaceNotFound
            collection.createIndex(keys, options);
            return;
        }

        // Check requested names first. Never mask a same-name specification conflict
        // just because a different-name equivalent happens to exist as well.
        for (Document existing : currentIndexes) {
            if (Objects.equals(options.getName(), existing.getString("name"))) {
                collection.createIndex(keys, options);
                return;
            }
        }

        for (Document existing : currentIndexes) {
            Document existingKeys = existing.get("key", Document.class);
            if (existingKeys == null || !expectedKeys.equals(
                    existingKeys.toBsonDocument(Document.class, collection.getCodecRegistry()))) {
                continue;
            }
            if (sameIndexOptions(collection, existing, options)) {
                System.out.println("Reusing MongoDB index '" + existing.getString("name")
                        + "' for '" + options.getName() + "' on collection '"
                        + collection.getNamespace().getCollectionName() + "' (same keys and options).");
                return;
            }
        }
        collection.createIndex(keys, options);
    }

    private boolean sameIndexOptions(MongoCollection<Document> collection, Document existing, IndexOptions wanted) {
        if (existing.getBoolean("unique", false) != wanted.isUnique()
                || existing.getBoolean("sparse", false) != wanted.isSparse()
                || existing.getBoolean("hidden", false) != wanted.isHidden()) {
            return false;
        }

        Document currentFilter = existing.get("partialFilterExpression", Document.class);
        Bson requestedFilter = wanted.getPartialFilterExpression();
        if ((currentFilter == null) != (requestedFilter == null)) return false;
        if (currentFilter != null && !currentFilter.toBsonDocument(Document.class, collection.getCodecRegistry())
                .equals(requestedFilter.toBsonDocument(Document.class, collection.getCodecRegistry()))) {
            return false;
        }

        // Do not assume two indexes with different collations are interchangeable.
        Document currentCollation = existing.get("collation", Document.class);
        if ((currentCollation == null) != (wanted.getCollation() == null)) return false;
        if (currentCollation != null && !currentCollation.toBsonDocument(Document.class, collection.getCodecRegistry())
                .equals(wanted.getCollation().asDocument())) {
            return false;
        }

        Number currentTtl = existing.get("expireAfterSeconds", Number.class);
        Long wantedTtl = wanted.getExpireAfter(TimeUnit.SECONDS);
        if (!Objects.equals(currentTtl == null ? null : currentTtl.longValue(), wantedTtl)) return false;

        // This application's indexes only configure name, keys, unique, partial
        // filter and (for one index) collation. Keep this guard conservative if
        // future indexes add additional options.
        return wanted.getWildcardProjection() == null
                && wanted.getStorageEngine() == null
                && wanted.getWeights() == null;
    }

    private boolean hasIndex(MongoCollection<Document> collection, String indexName) {
        try {
            for (Document index : collection.listIndexes()) {
                if (indexName.equals(index.getString("name"))) return true;
            }
            return false;
        } catch (MongoException ex) {
            if (ex.getCode() == 26) return false; // NamespaceNotFound on fresh databases
            throw ex;
        }
    }

}
