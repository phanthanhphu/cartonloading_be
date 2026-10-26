package org.bsl.cartonloading.config;

import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

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
        factories.createIndex(Indexes.compoundIndex(Indexes.ascending("buyer_key"), Indexes.ascending("code")),
                new IndexOptions().name("factory_buyer_code_unique").unique(true));
        MongoCollection<Document> buyers = mongoTemplate.getCollection(AppConstants.Collection.BUYERS);
        buyers.createIndex(Indexes.ascending("buyerKey"), new IndexOptions().name("uq_buyer_key").unique(true));
        buyers.createIndex(Indexes.ascending("slug"), new IndexOptions().name("uq_buyer_slug").unique(true));
        buyers.createIndex(Indexes.compoundIndex(Indexes.ascending("active"), Indexes.ascending("sequence")),
                new IndexOptions().name("idx_buyer_active_sequence"));
    }

    private void createOrderAndPackingIndexes() {
        MongoCollection<Document> orders = mongoTemplate.getCollection(AppConstants.Collection.ORDERS);
        orders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("orderDate")),
                new IndexOptions().name("idx_order_buyer_date")
        );
        orders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("updatedAt")),
                new IndexOptions().name("idx_order_buyer_updated")
        );

        MongoCollection<Document> allocationLines = mongoTemplate.getCollection(AppConstants.Collection.PACKING_ALLOCATION_LINES);
        allocationLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_allocation_order_line")
        );
        allocationLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")),
                new IndexOptions().name("idx_allocation_order_po")
        );
        allocationLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")),
                new IndexOptions().name("idx_allocation_order_article")
        );

        MongoCollection<Document> packingListLines = mongoTemplate.getCollection(AppConstants.Collection.PACKING_LIST_LINES);
        packingListLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_packing_list_order_line")
        );
        packingListLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")),
                new IndexOptions().name("idx_packing_list_order_po")
        );
        packingListLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")),
                new IndexOptions().name("idx_packing_list_order_article")
        );

        MongoCollection<Document> scaleStations = mongoTemplate.getCollection(AppConstants.Collection.SCALE_STATIONS);
        scaleStations.createIndex(Indexes.ascending("stationCode"), new IndexOptions().name("uq_scale_station_code").unique(true));
        scaleStations.createIndex(Indexes.ascending("active"), new IndexOptions().name("idx_scale_station_active"));
    }

    private void createCartonOperationIndexes() {
        MongoCollection<Document> cartonOperations = mongoTemplate.getCollection(AppConstants.Collection.CARTON_OPERATIONS);
        cartonOperations.createIndex(
                Indexes.ascending("jobId"),
                new IndexOptions().name("uq_carton_operation_job_id").unique(true)
                        .partialFilterExpression(new Document("jobId", new Document("$type", "number")))
        );
        cartonOperations.createIndex(
                Indexes.ascending("stationCode"),
                new IndexOptions().name("uq_station_waiting_job").unique(true)
                        .partialFilterExpression(new Document("status", AppConstants.ProgressStatus.WAITING_WEIGHT))
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("scannedAt")),
                new IndexOptions().name("idx_carton_operation_order_scanned")
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_operation_line_status")
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions().name("uq_carton_operation_line_sequence").unique(true)
                        .partialFilterExpression(new Document("packingLineId", new Document("$type", "string")))
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("masterLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions().name("uq_carton_operation_master_sequence").unique(true)
                        .partialFilterExpression(new Document("masterLineId", new Document("$type", "string")))
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("orderCartonSequence")),
                new IndexOptions().name("idx_carton_operation_order_sequence")
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("factoryBarcode"),
                        Indexes.ascending("orderCartonSequence")
                ),
                new IndexOptions().name("idx_carton_operation_assignment_sequence")
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("itemKey")),
                new IndexOptions().name("uq_carton_operation_item_key").unique(true)
                        .partialFilterExpression(new Document("itemKey", new Document("$type", "string")))
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("poNumber"),
                        Indexes.ascending("articleNumber"),
                        Indexes.ascending("itemSequence")
                ),
                new IndexOptions().name("idx_carton_operation_business_sequence")
        );
        cartonOperations.createIndex(
                Indexes.ascending("factoryBarcode"),
                new IndexOptions().name("uq_carton_operation_factory_barcode").unique(true)
                        .partialFilterExpression(new Document("factoryBarcode", new Document("$type", "string")))
        );
        cartonOperations.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentId")),
                new IndexOptions().name("idx_carton_operation_buyer_shipment")
        );
    }

    private void createBarcodeAndShipmentIndexes() {
        MongoCollection<Document> factoryBarcodes = mongoTemplate.getCollection(AppConstants.Collection.FACTORY_BARCODES);
        factoryBarcodes.createIndex(Indexes.ascending("barcode"), new IndexOptions().name("uq_factory_barcode").unique(true));
        factoryBarcodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("year"), Indexes.ascending("factoryCode"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("uq_factory_barcode_sequence").unique(true)
        );
        factoryBarcodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_factory_barcode_status_created")
        );
        factoryBarcodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("batchId"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("idx_factory_barcode_batch_sequence")
        );
        factoryBarcodes.createIndex(
                Indexes.ascending("assignedCartonId"),
                new IndexOptions().name("uq_factory_barcode_assigned_carton").unique(true)
                        .partialFilterExpression(new Document("assignedCartonId", new Document("$type", "string")))
        );

        MongoCollection<Document> shipments = mongoTemplate.getCollection(AppConstants.Collection.SHIPMENTS);
        shipments.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentNo")),
                new IndexOptions().name("uq_shipment_buyer_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("shipmentNo", new Document("$type", "string")))
        );
        shipments.createIndex(
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

        purchaseOrders.createIndex(
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
            purchaseOrders.createIndex(
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

        purchaseOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_purchase_order_factory_status")
        );
        purchaseOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("sku")),
                new IndexOptions().name("idx_purchase_order_sku")
        );
    }

    private void createCartonIndexes() {
        MongoCollection<Document> cartons = mongoTemplate.getCollection(AppConstants.Collection.CARTONS);
        cartons.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.ascending("cartonNo")),
                new IndexOptions().name("uq_carton_buyer_po_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("poId", new Document("$type", "string"))
                                .append("cartonNo", new Document("$type", "number")))
        );
        cartons.createIndex(
                Indexes.ascending("sscc18"),
                new IndexOptions().name("uq_carton_sscc18").unique(true)
                        .partialFilterExpression(new Document("sscc18", new Document("$type", "string")))
        );
        cartons.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_buyer_factory_status")
        );
        cartons.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poId")),
                new IndexOptions().name("idx_carton_buyer_order_po")
        );

        MongoCollection<Document> cartonItems = mongoTemplate.getCollection(AppConstants.Collection.CARTON_ITEMS);
        cartonItems.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.ascending("itemNo")),
                new IndexOptions().name("uq_carton_item_buyer_carton_number").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("cartonId", new Document("$type", "string"))
                                .append("itemNo", new Document("$type", "number")))
        );
        cartonItems.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_item_buyer_po_status")
        );
    }

    private void createOperationalEventIndexes() {
        MongoCollection<Document> scanEvents = mongoTemplate.getCollection(AppConstants.Collection.SCAN_EVENTS);
        scanEvents.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_order_created")
        );
        scanEvents.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("poId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_po_created")
        );
        scanEvents.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_scan_event_buyer_carton_created")
        );
    }

    private void createWeighingIndexes() {
        MongoCollection<Document> weighingProfiles = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_PROFILES);
        weighingProfiles.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poId")),
                new IndexOptions().name("uq_weighing_profile_buyer_order_po").unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("orderId", new Document("$type", "string"))
                                .append("poId", new Document("$type", "string")))
        );

        MongoCollection<Document> weighingOrders = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_ORDERS);
        weighingOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_order_buyer_order_created")
        );
        weighingOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("factoryCode"), Indexes.ascending("status")),
                new IndexOptions().name("idx_weighing_order_buyer_factory_status")
        );

        MongoCollection<Document> weighingResults = mongoTemplate.getCollection(AppConstants.Collection.WEIGHING_RESULTS);
        weighingResults.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("weighingOrderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_result_buyer_order_created")
        );
        weighingResults.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("cartonId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_weighing_result_buyer_carton_created")
        );
    }
    private boolean hasIndex(MongoCollection<Document> collection, String indexName) {
        for (Document index : collection.listIndexes()) {
            if (indexName.equals(index.getString("name"))) return true;
        }
        return false;
    }

}
