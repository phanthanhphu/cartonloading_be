package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed guard against deleting a Factory referenced by application data.
 * Audit logs are deliberately excluded: creating a Factory writes an audit event
 * and that event alone must not make a newly-created unused Factory undeletable.
 * No cascading deletes or document rewrites are permitted here.
 */
@Service
public class FactoryUsageService {
    private final MongoTemplate mongo;

    public FactoryUsageService(MongoTemplate mongo) { this.mongo = mongo; }

    private static final Map<String, String> SOURCES = sources();

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(AppConstants.Collection.USERS, "Users / Factory assignments");
        sources.put(AppConstants.Collection.DEPARTMENTS, "Departments");
        sources.put(AppConstants.Collection.ORDERS, "Orders");
        sources.put(AppConstants.Collection.PURCHASE_ORDERS, "Purchase Orders");
        sources.put(AppConstants.Collection.SHIPPING_SCHEDULES, "Shipping Schedules");
        sources.put(AppConstants.Collection.CARTONS, "Cartons");
        sources.put(AppConstants.Collection.CARTON_PROCESSING_HISTORY, "Carton History");
        sources.put(AppConstants.Collection.CARTON_ITEMS, "Carton Items");
        sources.put(AppConstants.Collection.SCAN_EVENTS, "Scan Events");
        sources.put(AppConstants.Collection.WEIGHING_ORDERS, "Weighing Orders");
        sources.put(AppConstants.Collection.WEIGHING_RESULTS, "Weighing Results");
        sources.put(AppConstants.Collection.WEIGHING_PROFILES, "Weight Profiles");
        sources.put(AppConstants.Collection.CARTON_CHECKING_HISTORY, "QC History");
        sources.put(AppConstants.Collection.RFID_SCAN_SESSIONS, "RFID Sessions");
        sources.put(AppConstants.Collection.RFID_PDA_CAPTURES, "RFID Captures");
        sources.put(AppConstants.Collection.PO_PICK_CLAIMS, "PO Picking");
        sources.put(AppConstants.Collection.PO_PICK_HISTORY, "PO Pick History");
        sources.put(AppConstants.Collection.PACKING_IMPORT_BATCHES, "Import History");
        sources.put(AppConstants.Collection.DEVICE_CONFIGURATIONS, "Device Configurations");
        sources.put(AppConstants.Collection.DEVICE_COMMAND_EVENTS, "Device Commands");
        sources.put(AppConstants.Collection.DEVICE_RUNTIME_SNAPSHOTS, "Device Runtime");
        // Legacy device collections may still exist before their migration is complete.
        sources.put(AppConstants.LegacyCollection.SCALE_STATIONS, "Legacy Scale Stations");
        sources.put(AppConstants.LegacyCollection.SCALE_PLC_DEVICES, "Legacy Scale Devices");
        sources.put(AppConstants.LegacyCollection.RFID_PDA_DEVICES, "Legacy RFID Devices");
        sources.put(AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES, "Legacy Conveyors");
        sources.put(AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS, "Legacy Device Bindings");
        sources.put(AppConstants.LegacyCollection.PACKING_IMPORT_BATCHES, "Legacy Import History");
        return java.util.Collections.unmodifiableMap(sources);
    }

    public List<String> findUsage(String factoryCode) {
        // Mongo matches scalar values as well as elements of arrays for .is(code).
        String exactFactory = "^" + java.util.regex.Pattern.quote(factoryCode) + "$";
        Criteria[] fields = {
                Criteria.where("factoryCode").regex(exactFactory, "i"),
                Criteria.where("factoryCodes").regex(exactFactory, "i"),
                Criteria.where("factory").regex(exactFactory, "i"),
                Criteria.where("factoryPermissions").regex(exactFactory, "i"),
                Criteria.where("weighingFactoryPermissions").regex(exactFactory, "i"),
                Criteria.where("factory_code").regex(exactFactory, "i"),
                Criteria.where("assignedFactories").regex(exactFactory, "i")
        };
        Query query = new Query(new Criteria().orOperator(fields));
        List<String> usedBy = new ArrayList<>();
        // Use only known application collections, rather than scanning user/import payloads.
        // Explicitly do not query FACTORIES or AUDIT_LOGS.
        for (Map.Entry<String, String> entry : SOURCES.entrySet()) {
            if (mongo.exists(query, entry.getKey())) usedBy.add(entry.getValue());
        }
        return usedBy;
    }
}
