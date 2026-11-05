package org.bsl.cartonloading.common.persistence;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runtime access to ONE shared device configuration collection for ES and LULULEMON.
 * Original source names are stable type discriminators ONLY, not collections used
 * for operational reads/writes. Migration happens before this bean is created.
 */
@Component
public class SharedDeviceConfigurationStore {
    private static final String TARGET = AppConstants.Collection.DEVICE_CONFIGURATIONS;
    private final MongoTemplate mongo;

    public SharedDeviceConfigurationStore(MongoTemplate mongo, DeviceConfigurationMigration migration) {
        this.mongo = mongo; // injecting migration ensures backfill/cutover first
    }

    public static String key(String sourceCollection, Object originalId) {
        return sourceCollection + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(originalId).getBytes(StandardCharsets.UTF_8));
    }

    private static String buyer(String source) {
        return AppConstants.LegacyCollection.SCALE_STATIONS.equals(source)
                ? AppConstants.Buyer.ENGELBERT_STRAUSS : AppConstants.Buyer.LULULEMON;
    }
    static String type(String source) {
        return switch (source) {
            case AppConstants.LegacyCollection.SCALE_STATIONS -> "SCALE_STATION";
            case AppConstants.LegacyCollection.SCALE_PLC_DEVICES -> "SCALE";
            case AppConstants.LegacyCollection.RFID_PDA_DEVICES -> "RFID";
            case AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES -> "CONVEYOR";
            case AppConstants.LegacyCollection.STATION_DEVICE_BINDINGS -> "STATION_BINDING";
            default -> throw new IllegalArgumentException("Unsupported device type: " + source);
        };
    }

    static Document record(String source, Document raw) {
        Object id = raw.get("_id");
        if (id == null) throw new IllegalStateException("Device config has no _id: " + source);
        return new Document("_id", key(source, id))
                .append("buyerCode", buyer(source))
                .append("deviceType", type(source))
                .append("factoryCode", raw.getString("factoryCode"))
                .append("sourceCollection", source)
                .append("originalId", String.valueOf(id))
                .append("config", raw);
    }

    private MongoCollection<Document> collection() { return mongo.getCollection(TARGET); }

    public <T> List<T> list(String source, Class<T> type) {
        List<T> rows = new ArrayList<>();
        for (Document row : collection().find(Filters.and(Filters.eq("sourceCollection", source),
                Filters.ne("deleted", true)))) {
            rows.add(fromRow(row, type));
        }
        return rows;
    }

    public <T> Optional<T> find(String source, Object id, Class<T> type) {
        if (id == null) return Optional.empty();
        Document row = collection().find(Filters.and(Filters.eq("_id", key(source, id)),
                Filters.ne("deleted", true))).first();
        return row == null ? Optional.empty() : Optional.of(fromRow(row, type));
    }

    private <T> T fromRow(Document row, Class<T> type) {
        Document content = row.get("config", Document.class);
        if (content == null) throw new IllegalStateException("Missing device config content: " + row.get("_id"));
        return mongo.getConverter().read(type, content);
    }

    private Document toDocument(Object entity) {
        Document doc = new Document();
        mongo.getConverter().write(entity, doc);
        return doc;
    }

    public <T> T save(String source, T entity, Function<T, String> getId,
                      java.util.function.BiConsumer<T, String> setId, boolean insertOnly) {
        return saveShared(source, entity, getId, setId, insertOnly);
    }

    private <T> T saveShared(String source, T entity, Function<T, String> getId,
                      java.util.function.BiConsumer<T, String> setId, boolean insertOnly) {
        String id = getId.apply(entity);
        if (id == null || id.isBlank()) {
            id = new ObjectId().toHexString();
            setId.accept(entity, id);
        }
        Document data = toDocument(entity);
        // Type-specific mapping preserves @Field aliases and the original ID type.
        if (!data.containsKey("_id")) data.put("_id", id);
        Document row = record(source, data);
        if (insertOnly) {
            Document previous = collection().find(Filters.eq("_id", row.get("_id"))).first();
            if (previous != null && Boolean.TRUE.equals(previous.getBoolean("deleted"))) {
                // A removed station/reader may be re-created later with the same identifier.
                var result = collection().replaceOne(Filters.and(Filters.eq("_id",row.get("_id")),
                        Filters.eq("deleted", true)), row);
                if (result.getModifiedCount() != 1L)
                    throw new org.springframework.dao.DuplicateKeyException("Device identifier changed concurrently");
            } else {
                try {
                    collection().insertOne(row); // same uniqueness semantics as MongoRepository.insert
                } catch (com.mongodb.MongoWriteException ex) {
                    if (ex.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY)
                        throw new org.springframework.dao.DuplicateKeyException("Device already exists", ex);
                    throw ex;
                }
            }
        } else {
            collection().replaceOne(Filters.eq("_id", row.get("_id")), row,
                    new ReplaceOptions().upsert(true));
        }
        return entity;
    }

    /**
     * Save a Conveyor configuration only if the version opened by the editor is still current.
     * A regular upsert would let an older browser/tab restore an obsolete endpoint or Unit ID.
     * MongoDB checks the revision and replaces the document in ONE atomic operation.
     * Legacy Conveyor documents without revision are treated as version 0.
     */
    public ConveyorPlcDevice saveConveyorIfCurrent(
            ConveyorPlcDevice entity, long expectedRevision) {
        String source = AppConstants.LegacyCollection.CONVEYOR_PLC_DEVICES;
        Document data = toDocument(entity);
        if (!data.containsKey("_id")) data.put("_id", entity.getDeviceCode());
        Document row = record(source, data);
        Object key = row.get("_id");
        var condition = Filters.and(Filters.eq("_id", key),
                Filters.eq("sourceCollection", source), Filters.ne("deleted", true),
                expectedRevision == 0
                    ? Filters.or(Filters.exists("config.revision", false), Filters.eq("config.revision", 0L))
                    : Filters.eq("config.revision", expectedRevision));
        if (collection().replaceOne(condition, row).getMatchedCount() == 1L) return entity;
        // Creation may proceed only if no record (including a tombstone) uses the same key.
        if (expectedRevision == 0L) {
            try {
                collection().insertOne(row);
                return entity;
            } catch (com.mongodb.MongoWriteException ex) {
                if (ex.getError().getCategory() != com.mongodb.ErrorCategory.DUPLICATE_KEY) throw ex;
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,
                "Conveyor configuration was changed by another session. Your edits were NOT saved. Close and reopen Edit to review the latest settings.");
    }

    /** Reuses Mongo's atomic compare-and-set for simulator and physical PLC events.
     *  Only the paths are namespaced; query predicates and updates are untouched.
     */
    public ScalePlcDevice findAndModifyScale(
            org.springframework.data.mongodb.core.query.Query query,
            org.springframework.data.mongodb.core.query.Update update) {
        org.springframework.data.mongodb.core.query.Query scoped = scaleQuery(query);
        var updated = mongo.findAndModify(scoped, scaleUpdate(update),
                org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
                ScaleConfigurationRecord.class, TARGET);
        return updated == null ? null : updated.getConfig();
    }

    public void updateScale(org.springframework.data.mongodb.core.query.Query query,
                            org.springframework.data.mongodb.core.query.Update update) {
        mongo.updateFirst(scaleQuery(query), scaleUpdate(update), ScaleConfigurationRecord.class, TARGET);
    }

    private org.springframework.data.mongodb.core.query.Query scaleQuery(
            org.springframework.data.mongodb.core.query.Query legacy) {
        Document rewritten = rewriteFields(legacy.getQueryObject(), true);
        rewritten.put("sourceCollection", AppConstants.LegacyCollection.SCALE_PLC_DEVICES);
        rewritten.put("deleted", new Document("$ne", true));
        return new org.springframework.data.mongodb.core.query.BasicQuery(rewritten);
    }

    private org.springframework.data.mongodb.core.query.Update scaleUpdate(
            org.springframework.data.mongodb.core.query.Update legacy) {
        Document result = new Document();
        legacy.getUpdateObject().forEach((op, val) -> {
            if (!(val instanceof Document fields))
                throw new IllegalArgumentException("Unsupported scale update operator: " + op);
            result.put(op, rewriteFields(fields, false));
        });
        return new org.springframework.data.mongodb.core.query.BasicUpdate(result);
    }

    @SuppressWarnings("unchecked")
    private Document rewriteFields(Document original, boolean criteria) {
        Document result = new Document();
        original.forEach((field, val) -> {
            if (criteria && ("$or".equals(field) || "$and".equals(field) || "$nor".equals(field))) {
                java.util.List<Document> alternatives = new ArrayList<>();
                for (Object branch : (java.util.List<Object>)val)
                    alternatives.add(rewriteFields((Document)branch, true));
                result.put(field, alternatives);
            } else if (field.startsWith("$")) {
                result.put(field, val);
            } else if ("_id".equals(field)) {
                if (!(val instanceof String)) throw new IllegalArgumentException("Scale station ID must be a String");
                result.put(field, key(AppConstants.LegacyCollection.SCALE_PLC_DEVICES, val));
            } else {
                result.put("config." + field, val);
            }
        });
        return result;
    }

    public void delete(String source, String originalId) {
        // Keep tombstone so no deleted device is restored by a later migration.
        if (originalId != null) {
            collection().updateOne(Filters.eq("_id", key(source, originalId)), Updates.set("deleted", true));
        }
    }
}
