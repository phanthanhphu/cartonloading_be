package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.BuyerAccess;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Fail-closed Buyer removal safeguard for existing application-owned documents.
 * No delete cascade. Audit logs are intentionally not a blocker, because
 * creating a Buyer writes an audit entry even when nothing uses that Buyer.
 */
@Service
public class BuyerUsageService {
    private final MongoTemplate mongo;

    public BuyerUsageService(MongoTemplate mongo) { this.mongo = mongo; }

    private static final Map<String, String> SOURCES = sources();

    private static Map<String, String> sources() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put(AppConstants.Collection.USERS, "User access grants");
        s.put(AppConstants.Collection.FACTORIES, "Factories");
        s.put(AppConstants.Collection.DEPARTMENTS, "Departments");
        s.put(AppConstants.Collection.ORDERS, "Orders");
        s.put(AppConstants.Collection.PURCHASE_ORDERS, "Purchase Orders");
        s.put(AppConstants.Collection.PO_IDENTITY_REGISTRY, "PO identities");
        s.put(AppConstants.Collection.PACKING_ALLOCATION_LINES, "Packing allocations");
        s.put(AppConstants.Collection.PACKING_LIST_LINES, "Packing lists");
        s.put(AppConstants.Collection.CARTON_OPERATIONS, "Carton operations");
        s.put(AppConstants.Collection.FACTORY_BARCODES, "Factory barcodes");
        s.put(AppConstants.Collection.SHIPPING_SCHEDULES, "Shipping schedules");
        s.put(AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS, "Submitted shipping days");
        s.put(AppConstants.Collection.SHIPMENTS, "Shipments");
        s.put(AppConstants.Collection.CARTONS, "Cartons");
        s.put(AppConstants.Collection.CARTON_ITEMS, "Carton items");
        s.put(AppConstants.Collection.CARTON_PROCESSING_HISTORY, "Carton history");
        s.put(AppConstants.Collection.SCAN_EVENTS, "Scan events");
        s.put(AppConstants.Collection.CARTON_CHECKING_HISTORY, "Carton QC history");
        s.put(AppConstants.Collection.WEIGHING_ORDERS, "Weighing orders");
        s.put(AppConstants.Collection.WEIGHING_RESULTS, "Weighing results");
        s.put(AppConstants.Collection.WEIGHING_PROFILES, "Weight profiles");
        s.put(AppConstants.Collection.RFID_SCAN_SESSIONS, "RFID scan sessions");
        s.put(AppConstants.Collection.RFID_PDA_CAPTURES, "RFID captures");
        s.put(AppConstants.Collection.PO_PICK_CLAIMS, "PO picking");
        s.put(AppConstants.Collection.PO_PICK_HISTORY, "PO picking history");
        s.put(AppConstants.Collection.PACKING_IMPORT_BATCHES, "Import history");
        s.put(AppConstants.Collection.DEVICE_CONFIGURATIONS, "Device configurations");
        s.put(AppConstants.Collection.DEVICE_COMMAND_EVENTS, "Device commands");
        s.put(AppConstants.Collection.DEVICE_RUNTIME_SNAPSHOTS, "Device runtime");
        s.put("device_pc_agent_registrations", "PC agents");
        s.put("device_agent_registrations", "Scale agents");
        // Legacy collections still queried, where the project keeps historic records.
        s.put(AppConstants.LegacyCollection.SCALE_STATIONS, "Legacy scale stations");
        s.put(AppConstants.LegacyCollection.SCALE_PLC_DEVICES, "Legacy scales");
        s.put(AppConstants.LegacyCollection.RFID_PDA_DEVICES, "Legacy RFID devices");
        s.put(AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES, "Legacy conveyors");
        s.put(AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS, "Legacy device bindings");
        s.put(AppConstants.LegacyCollection.PACKING_IMPORT_BATCHES, "Legacy import history");
        return Collections.unmodifiableMap(s);
    }

    public List<String> findUsage(String buyerKey) {
        String key = BuyerAccess.normalize(buyerKey);
        if (key.isBlank()) throw new IllegalArgumentException("Invalid Buyer Key");
        String exact = "^" + Pattern.quote(key) + "$";
        Query query = new Query(new Criteria().orOperator(
                Criteria.where("buyerCode").regex(exact, "i"),
                Criteria.where("buyerKey").regex(exact, "i"),
                Criteria.where("buyer_key").regex(exact, "i"),
                Criteria.where("buyer").regex(exact, "i"),
                Criteria.where("buyerPermissions").regex(exact, "i"),
                Criteria.where("buyerTypePermissions.buyerCode").regex(exact, "i"),
                Criteria.where("buyerAccessPermissions." + key).exists(true)));
        List<String> uses = new ArrayList<>();
        for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
            if (mongo.exists(query, entry.getKey())) uses.add(entry.getValue());
        }
        return uses;
    }
}
