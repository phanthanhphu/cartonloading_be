package org.bsl.cartonloading.config;

import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import jakarta.annotation.PostConstruct;

@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void createIndexes() {
        MongoCollection<Document> buyers = mongoTemplate.getCollection("buyers");
        buyers.createIndex(
                Indexes.ascending("buyerKey"),
                new IndexOptions().name("uq_buyer_key").unique(true)
        );
        buyers.createIndex(
                Indexes.ascending("slug"),
                new IndexOptions().name("uq_buyer_slug").unique(true)
        );
        buyers.createIndex(Indexes.compoundIndex(Indexes.ascending("active"), Indexes.ascending("sequence")));

        MongoCollection<Document> packingOrders = mongoTemplate.getCollection("packing_orders");
        try {
            packingOrders.dropIndex("uq_packing_order_buyer_order_no");
            System.out.println("Dropped legacy packing order number index");
        } catch (Exception ignored) {
            // The legacy index may not exist on a new database.
        }
        packingOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("orderDate")),
                new IndexOptions().name("idx_packing_order_buyer_date")
        );
        packingOrders.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("updatedAt")));

        MongoCollection<Document> allocationLines = mongoTemplate.getCollection("packing_allocation_lines");
        allocationLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_allocation_order_line")
        );
        allocationLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")));
        allocationLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")));

        MongoCollection<Document> packingListLines = mongoTemplate.getCollection("packing_list_lines");
        packingListLines.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("lineNo")),
                new IndexOptions().name("idx_packing_list_order_line")
        );
        packingListLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber")));
        packingListLines.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("articleNumber")));

        MongoCollection<Document> scaleStations = mongoTemplate.getCollection("scale_stations");
        scaleStations.createIndex(
                Indexes.ascending("stationCode"),
                new IndexOptions().name("uq_scale_station_code").unique(true)
        );
        scaleStations.createIndex(Indexes.ascending("active"));

        MongoCollection<Document> cartonTransactions = mongoTemplate.getCollection("carton_scan_transactions");
        // Migrate legacy non-partial unique indexes so PLANNED cartons may keep jobId/packingLineId null.
        for (String indexName : new String[]{"uq_carton_job_id", "uq_carton_line_sequence", "uq_wsp_master_carton_sequence"}) {
            try {
                cartonTransactions.dropIndex(indexName);
            } catch (Exception ignored) {
                // New database or index already migrated.
            }
        }
        cartonTransactions.createIndex(
                Indexes.ascending("jobId"),
                new IndexOptions()
                        .name("uq_carton_job_id")
                        .unique(true)
                        .partialFilterExpression(new Document("jobId", new Document("$type", "number")))
        );
        cartonTransactions.createIndex(
                Indexes.ascending("stationCode"),
                new IndexOptions()
                        .name("uq_station_waiting_job")
                        .unique(true)
                        .partialFilterExpression(new Document("status", "WAITING_WEIGHT"))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("scannedAt")),
                new IndexOptions().name("idx_carton_order_scanned")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("status")),
                new IndexOptions().name("idx_carton_line_status")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("packingLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions()
                        .name("uq_carton_line_sequence")
                        .unique(true)
                        .partialFilterExpression(new Document("packingLineId", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("masterLineId"), Indexes.ascending("cartonSequence")),
                new IndexOptions()
                        .name("uq_wsp_master_carton_sequence")
                        .unique(true)
                        .partialFilterExpression(new Document("masterLineId", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("orderCartonSequence")),
                new IndexOptions().name("idx_carton_order_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("factoryBarcode"),
                        Indexes.ascending("orderCartonSequence")
                ),
                new IndexOptions().name("idx_carton_assignment_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("itemKey")),
                new IndexOptions()
                        .name("uq_carton_item_key")
                        .unique(true)
                        .partialFilterExpression(new Document("itemKey", new Document("$type", "string")))
        );
        cartonTransactions.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poNumber"), Indexes.ascending("articleNumber"), Indexes.ascending("itemSequence")),
                new IndexOptions().name("idx_carton_business_sequence")
        );
        cartonTransactions.createIndex(
                Indexes.ascending("factoryBarcode"),
                new IndexOptions()
                        .name("uq_carton_factory_barcode")
                        .unique(true)
                        .partialFilterExpression(new Document("factoryBarcode", new Document("$type", "string")))
        );

        MongoCollection<Document> factoryBarcodes = mongoTemplate.getCollection("factory_barcodes");
        cartonTransactions.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentId")),
                new IndexOptions().name("idx_carton_buyer_shipment"));
        MongoCollection<Document> shipments = mongoTemplate.getCollection("carton_shipments");
        shipments.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("shipmentNo")),
                new IndexOptions().name("uq_shipment_buyer_number").unique(true));
        shipments.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.descending("createdAt")));
        factoryBarcodes.createIndex(
                Indexes.ascending("barcode"),
                new IndexOptions().name("uq_factory_barcode").unique(true)
        );
        factoryBarcodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("year"), Indexes.ascending("factoryCode"), Indexes.ascending("runningNumber")),
                new IndexOptions().name("uq_factory_barcode_sequence").unique(true)
        );
        factoryBarcodes.createIndex(Indexes.compoundIndex(Indexes.ascending("status"), Indexes.descending("createdAt")));
        factoryBarcodes.createIndex(Indexes.compoundIndex(Indexes.ascending("batchId"), Indexes.ascending("runningNumber")));
        factoryBarcodes.createIndex(
                Indexes.ascending("assignedCartonId"),
                new IndexOptions()
                        .name("uq_factory_barcode_assigned_carton")
                        .unique(true)
                        .partialFilterExpression(new Document("assignedCartonId", new Document("$type", "string")))
        );

        // LULULEMON module: Order -> PO -> Carton -> Item.
        // A PO number is unique only inside one buyer/order. The previous global
        // uq_lululemon_po index prevented the same PO number from being used in
        // another Order and caused E11000 during order-scoped ALL_BP imports.
        MongoCollection<Document> lululemonPos = mongoTemplate.getCollection("lululemon_pos");
        try {
            lululemonPos.dropIndex("uq_lululemon_po");
            System.out.println("Dropped legacy global LULULEMON PO index");
        } catch (MongoCommandException ex) {
            // Mongo error code 27 = IndexNotFound and 26 = NamespaceNotFound
            // (brand-new database). Any other failure, such as insufficient
            // privileges, must stop startup so the application cannot silently
            // continue with the wrong uniqueness rule.
            if (ex.getErrorCode() != 27 && ex.getErrorCode() != 26) throw ex;
        }
        lululemonPos.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("buyerCode"),
                        Indexes.ascending("orderId"),
                        Indexes.ascending("poNumber")
                ),
                new IndexOptions()
                        .name("uq_lululemon_order_po")
                        .unique(true)
                        .partialFilterExpression(new Document("buyerCode", new Document("$type", "string"))
                                .append("orderId", new Document("$type", "string"))
                                .append("poNumber", new Document("$type", "string")))
        );
        lululemonPos.createIndex(Indexes.compoundIndex(Indexes.ascending("orderId"), Indexes.ascending("factoryCode"), Indexes.ascending("status")));
        lululemonPos.createIndex(Indexes.compoundIndex(Indexes.ascending("orderId"), Indexes.ascending("sku")));

        MongoCollection<Document> lululemonCartons = mongoTemplate.getCollection("lululemon_cartons");
        lululemonCartons.createIndex(
                Indexes.compoundIndex(Indexes.ascending("poId"), Indexes.ascending("cartonNo")),
                new IndexOptions().name("uq_lululemon_po_carton_no").unique(true)
        );
        lululemonCartons.createIndex(
                Indexes.ascending("sscc18"),
                new IndexOptions()
                        .name("uq_lululemon_sscc18")
                        .unique(true)
                        .partialFilterExpression(new Document("sscc18", new Document("$type", "string")))
        );
        lululemonCartons.createIndex(Indexes.compoundIndex(Indexes.ascending("factoryCode"), Indexes.ascending("status")));

        MongoCollection<Document> lululemonItems = mongoTemplate.getCollection("lululemon_carton_items");
        lululemonItems.createIndex(
                Indexes.compoundIndex(Indexes.ascending("cartonId"), Indexes.ascending("itemNo")),
                new IndexOptions().name("uq_lululemon_carton_item_no").unique(true)
        );
        lululemonItems.createIndex(Indexes.compoundIndex(Indexes.ascending("poId"), Indexes.ascending("status")));

        MongoCollection<Document> lululemonWeightProfiles = mongoTemplate.getCollection("lululemon_weight_profiles");
        lululemonWeightProfiles.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.ascending("poId")),
                new IndexOptions().name("uq_lululemon_weight_profile_po").unique(true)
        );

        MongoCollection<Document> lululemonWeighingOrders = mongoTemplate.getCollection("lululemon_weighing_orders");
        lululemonWeighingOrders.createIndex(
                Indexes.compoundIndex(Indexes.ascending("buyerCode"), Indexes.ascending("orderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_lululemon_weighing_order")
        );
        lululemonWeighingOrders.createIndex(Indexes.compoundIndex(Indexes.ascending("factoryCode"), Indexes.ascending("status")));

        MongoCollection<Document> lululemonWeightEvents = mongoTemplate.getCollection("lululemon_weight_events");
        lululemonWeightEvents.createIndex(
                Indexes.compoundIndex(Indexes.ascending("weighingOrderId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_lululemon_weight_event_order")
        );
        lululemonWeightEvents.createIndex(
                Indexes.compoundIndex(Indexes.ascending("cartonId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_lululemon_weight_event_carton")
        );

        System.out.println("Carton Loading indexes created successfully.");
    }
}
