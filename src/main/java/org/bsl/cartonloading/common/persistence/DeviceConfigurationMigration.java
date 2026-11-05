package org.bsl.cartonloading.common.persistence;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One-time idempotent migration from legacy device/config collections to
 * generic collections. Never changes the RFID/weight processing rules.
 * Kept separate from runtime storage so it can be retired AFTER cutover.
 */
@Component
public class DeviceConfigurationMigration {
    private static final Logger log = LoggerFactory.getLogger(DeviceConfigurationMigration.class);
    private static final String TARGET = AppConstants.Collection.DEVICE_CONFIGURATIONS;
    private final MongoTemplate mongo;

    // This cleaned build always reads/writes the shared configuration store.
    @Value("${cartonloading.db.shared-devices.enabled:true}")
    private boolean sharedEnabled;
    @Value("${cartonloading.db.cleanup-legacy-collections.enabled:false}")
    private boolean cleanupLegacyCollections;
    @Value("${cartonloading.db.cleanup-legacy-collections.backup-confirmed:false}")
    private boolean backupConfirmed;

    public DeviceConfigurationMigration(MongoTemplate mongo) { this.mongo = mongo; }

    @PostConstruct
    public void startupMigration() {
        if (!sharedEnabled) {
            throw new IllegalStateException("This cleaned backend requires cartonloading.db.shared-devices.enabled=true; "
                    + "legacy-primary mode was removed. No data was deleted.");
        }
        // Read-only on source collections, insert-only on target: safe to restart.
        // Do not allow the service to come online with a partially copied configuration.
        var target = collection();
        target.createIndex(Indexes.compoundIndex(Indexes.ascending("buyerCode"),
                Indexes.ascending("deviceType"), Indexes.ascending("factoryCode")),
                new IndexOptions().name("idx_device_config_buyer_type_factory"));
        target.createIndex(Indexes.compoundIndex(Indexes.ascending("sourceCollection"),
                Indexes.ascending("config.stationCode")),
                new IndexOptions().name("uq_device_config_es_station")
                        .unique(true)
                        .partialFilterExpression(new Document("sourceCollection", AppConstants.LegacyCollection.SCALE_STATIONS)));
        for (String source : List.of(AppConstants.LegacyCollection.SCALE_STATIONS,
                AppConstants.LegacyCollection.SCALE_PLC_DEVICES,
                AppConstants.LegacyCollection.RFID_PDA_DEVICES,
                AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES,
                AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS)) {
            migrate(source);
        }
        // Buyer-agnostic names for three independent history/runtime collections.
        // These retain their own collections: they must NOT be embedded in device configuration.
        copyLegacyCollection(AppConstants.LegacyCollection.DEVICE_COMMAND_EVENTS,
                AppConstants.Collection.DEVICE_COMMAND_EVENTS);
        copyLegacyCollection(AppConstants.LegacyCollection.DEVICE_RUNTIME_SNAPSHOTS,
                AppConstants.Collection.DEVICE_RUNTIME_SNAPSHOTS);
        copyLegacyCollection(AppConstants.LegacyCollection.PACKING_IMPORT_BATCHES,
                AppConstants.Collection.PACKING_IMPORT_BATCHES);
        reconcileBeforeFirstCutover();
        if (cleanupLegacyCollections) cleanupLegacyAfterVerifiedCutover();
    }

    /**
     * Explicit, one-time, verified retirement of the OLD physical Mongo collections.
     * This is NOT part of normal startup and requires two operator approvals.
     * No polling or business data/logic is changed.
     */
    private void cleanupLegacyAfterVerifiedCutover() {
        if (!sharedEnabled || !backupConfirmed || !completed("__shared_device_cutover_v1__")) {
            throw new IllegalStateException("Legacy cleanup blocked: enable shared-devices, confirm a database backup, "
                    + "and complete the first shared-device cutover before cleanup");
        }
        // Validate every source record before removing any source collection.
        List<String> deviceSources = List.of(
                AppConstants.LegacyCollection.SCALE_STATIONS,
                AppConstants.LegacyCollection.SCALE_PLC_DEVICES,
                AppConstants.LegacyCollection.RFID_PDA_DEVICES,
                AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES,
                AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS);
        List<String> otherSources = List.of(
                AppConstants.LegacyCollection.DEVICE_COMMAND_EVENTS,
                AppConstants.LegacyCollection.DEVICE_RUNTIME_SNAPSHOTS,
                AppConstants.LegacyCollection.PACKING_IMPORT_BATCHES);
        for (String source : deviceSources) {
            if (!completed(source)) throw new IllegalStateException("Device migration incomplete: " + source);
            if (!mongo.collectionExists(source)) continue;
            for (Document old : mongo.getCollection(source).find().projection(new Document("_id", 1))) {
                Document row = collection().find(Filters.eq("_id", SharedDeviceConfigurationStore.key(source, old.get("_id")))).first();
                if (row == null || !(row.get("config") instanceof Document))
                    throw new IllegalStateException("Device migration incomplete for " + source + ": " + old.get("_id"));
            }
        }
        for (String source : otherSources) {
            if (!completed(source)) throw new IllegalStateException("Collection migration incomplete: " + source);
            if (!mongo.collectionExists(source)) continue;
            String targetName = switch (source) {
                case AppConstants.LegacyCollection.DEVICE_COMMAND_EVENTS -> AppConstants.Collection.DEVICE_COMMAND_EVENTS;
                case AppConstants.LegacyCollection.DEVICE_RUNTIME_SNAPSHOTS -> AppConstants.Collection.DEVICE_RUNTIME_SNAPSHOTS;
                case AppConstants.LegacyCollection.PACKING_IMPORT_BATCHES -> AppConstants.Collection.PACKING_IMPORT_BATCHES;
                default -> throw new IllegalStateException("Unknown legacy collection " + source);
            };
            MongoCollection<Document> target = mongo.getCollection(targetName);
            for (Document old : mongo.getCollection(source).find().projection(new Document("_id", 1))) {
                if (target.countDocuments(Filters.eq("_id", old.get("_id"))) != 1L)
                    throw new IllegalStateException("Collection migration incomplete: " + source + " / " + old.get("_id"));
            }
        }
        // All migrated IDs have a target. Drop only the eight allowlisted OLD collections.
        // Stop other Backend instances BEFORE enabling cleanup to prevent stale writes.
        for (String source : deviceSources) dropLegacyAfterVerification(source);
        for (String source : otherSources) dropLegacyAfterVerification(source);
    }

    private void dropLegacyAfterVerification(String source) {
        if (!mongo.collectionExists(source)) return;
        mongo.getCollection(source).drop();
        log.warn("Retired verified legacy MongoDB collection: {} (backup confirmed)", source);
    }

    /** One-time cutover reconciliation: legacy is authoritative until the first NEW-mode start.
     *  It repairs any stale mirrors from concurrent legacy-primary operations. After this
     *  marker exists, a NEW-mode restart does not read old data or resurrect deletions.
     */
    private void reconcileBeforeFirstCutover() {
        final String marker = "__shared_device_cutover_v1__";
        if (completed(marker)) return;
        for (String source : List.of(AppConstants.LegacyCollection.SCALE_STATIONS,
                AppConstants.LegacyCollection.SCALE_PLC_DEVICES, AppConstants.LegacyCollection.RFID_PDA_DEVICES,
                AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES,
                AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS)) {
            if (!mongo.collectionExists(source)) continue;
            java.util.Set<String> present = new java.util.HashSet<>();
            for (Document old : mongo.getCollection(source).find()) {
                Document copy = SharedDeviceConfigurationStore.record(source,old);
                String id = copy.getString("_id");
                present.add(id);
                collection().replaceOne(Filters.eq("_id",id),copy,
                        new ReplaceOptions().upsert(true));
            }
            for (Document row : collection().find(Filters.eq("sourceCollection",source))) {
                if (!present.contains(row.getString("_id"))) {
                    collection().updateOne(Filters.eq("_id",row.getString("_id")),
                            Updates.set("deleted",true));
                }
            }
            log.info("Reconciled {} device configurations for shared cutover",source);
        }
        markCompleted(marker);
        log.info("Shared device configuration cutover successfully reconciled; restart safe");
    }

    private void copyLegacyCollection(String source, String targetName) {
        if (completed(source)) return;
        if (!mongo.collectionExists(source)) {
            markCompleted(source);
            return;
        }
        MongoCollection<Document> old = mongo.getCollection(source);
        MongoCollection<Document> target = mongo.getCollection(targetName);
        java.util.List<com.mongodb.client.model.WriteModel<Document>> writes = new ArrayList<>(300);
        java.util.List<Object> ids = new ArrayList<>(300);
        long scanned = 0;
        for (Document data : old.find().batchSize(300)) {
            Object id = data.get("_id");
            if (id == null) throw new IllegalStateException("Legacy collection missing _id: " + source);
            Document fields = new Document(data);
            fields.remove("_id");
            // Never overwrite a record which the new code has already updated.
            writes.add(new com.mongodb.client.model.UpdateOneModel<>(Filters.eq("_id", id),
                    new Document("$setOnInsert", fields), new UpdateOptions().upsert(true)));
            ids.add(id);
            scanned++;
            if (writes.size() >= 300) {
                flushCopy(target, writes, ids, source);
            }
        }
        flushCopy(target, writes, ids, source);
        markCompleted(source);
        log.info("Legacy collection {} -> {}, checked {} records, original retained", source,targetName,scanned);
    }

    private void flushCopy(MongoCollection<Document> target,
                           java.util.List<com.mongodb.client.model.WriteModel<Document>> writes,
                           java.util.List<Object> ids, String source) {
        if (writes.isEmpty()) return;
        target.bulkWrite(writes, new com.mongodb.client.model.BulkWriteOptions().ordered(false));
        long found = target.countDocuments(Filters.in("_id", ids));
        if (found != ids.size()) {
            throw new IllegalStateException("Legacy migration verification failed: " + source
                    + ", expected=" + ids.size() + ", found=" + found);
        }
        writes.clear(); ids.clear();
    }

    private MongoCollection<Document> collection() {
        return mongo.getCollection(TARGET);
    }

    // Markers live in the already-required shared collection (no extra Collection).
    // They prevent deleted old rows from being resurrected on the next startup.
    private static String markerId(String source) { return "__migration_completed__:" + source; }
    private boolean completed(String source) {
        return collection().countDocuments(Filters.eq("_id", markerId(source))) == 1L;
    }
    private void markCompleted(String source) {
        collection().updateOne(Filters.eq("_id", markerId(source)),
                Updates.combine(Updates.set("recordType", "MIGRATION_MARKER"),
                        Updates.set("completedAt", java.util.Date.from(java.time.Instant.now()))),
                new UpdateOptions().upsert(true));
    }

    public synchronized void migrate(String source) {
        SharedDeviceConfigurationStore.type(source); // validate legacy source before reading
        if (completed(source)) return;
        if (!mongo.collectionExists(source)) {
            markCompleted(source);
            return;
        }
        MongoCollection<Document> target = collection();
        long originals = 0;
        for (Document old : mongo.getCollection(source).find()) {
            Document copy = SharedDeviceConfigurationStore.record(source, old);
            // setOnInsert means a more recently edited config in the new store wins.
            target.updateOne(Filters.eq("_id", copy.getString("_id")),
                    Updates.combine(Updates.setOnInsert("buyerCode", copy.get("buyerCode")),
                            Updates.setOnInsert("deviceType", copy.get("deviceType")),
                            Updates.setOnInsert("factoryCode", copy.get("factoryCode")),
                            Updates.setOnInsert("sourceCollection", source),
                            Updates.setOnInsert("originalId", copy.get("originalId")),
                            Updates.setOnInsert("config", copy.get("config"))),
                    new UpdateOptions().upsert(true));
            originals++;
        }
        // Verify each original ID has a migrated counterpart, not just row totals.
        for (Document old : mongo.getCollection(source)
                .find()
                .projection(new Document("_id", 1))) {
            if (target.countDocuments(Filters.eq("_id", SharedDeviceConfigurationStore.key(source, old.get("_id")))) != 1L) {
                throw new IllegalStateException("Device migration verification failed: " + source
                        + " / " + old.get("_id"));
            }
        }
        long total = target.countDocuments(Filters.eq("sourceCollection", source));
        markCompleted(source);
        log.info("Device configuration migration: source={}, migratedSource={}, targetRows={}, legacyKept=true",
                source, originals, total);
    }

}
