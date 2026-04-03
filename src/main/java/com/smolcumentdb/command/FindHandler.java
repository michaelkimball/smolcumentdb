package com.smolcumentdb.command;

import com.smolcumentdb.query.FilterEvaluator;
import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles the MongoDB {@code find} and cursor commands.
 *
 * <p>Maintains a server-side cursor registry so that {@code getMore} can page
 * through large result sets.
 */
public class FindHandler {

    // Cursor registry shared with GetMoreHandler and AggregateHandler
    public static final ConcurrentHashMap<Long, List<BsonDocument>> CURSOR_REGISTRY
            = new ConcurrentHashMap<>();
    public static final AtomicLong CURSOR_ID_SEQ = new AtomicLong(1);

    private final InMemoryStorage storage;
    private final FilterEvaluator filter;

    public FindHandler(InMemoryStorage storage) {
        this.storage = storage;
        this.filter  = new FilterEvaluator();
    }

    public BsonDocument handle(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("find").getValue();

        BsonDocument filterSpec = command.containsKey("filter")
                ? command.getDocument("filter") : new BsonDocument();
        BsonDocument sort = command.containsKey("sort")
                ? command.getDocument("sort") : null;
        BsonDocument projection = command.containsKey("projection")
                ? command.getDocument("projection") : null;
        int limit     = command.containsKey("limit") ? command.getNumber("limit").intValue() : 0;
        int skip      = command.containsKey("skip")  ? command.getNumber("skip").intValue()  : 0;
        int batchSize = command.containsKey("batchSize") ? command.getNumber("batchSize").intValue() : 101;

        InMemoryCollection col = storage.getCollection(db, collection);
        List<BsonDocument> results = col.find(filterSpec, filter);

        // Sort
        if (sort != null && !sort.isEmpty()) {
            results = applySortAndProject(results, sort);
        }

        // Skip / limit
        if (skip > 0) results = results.subList(Math.min(skip, results.size()), results.size());
        if (limit > 0) results = results.subList(0, Math.min(limit, results.size()));

        // Projection
        if (projection != null && !projection.isEmpty()) {
            results = applyProjection(results, projection);
        }

        // Batch the first page
        int effectiveBatchSize = batchSize <= 0 ? results.size() : batchSize;
        List<BsonDocument> firstBatch;
        long cursorId = 0;

        if (results.size() > effectiveBatchSize) {
            firstBatch = new ArrayList<>(results.subList(0, effectiveBatchSize));
            List<BsonDocument> remaining = new ArrayList<>(results.subList(effectiveBatchSize, results.size()));
            cursorId = CURSOR_ID_SEQ.getAndIncrement();
            CURSOR_REGISTRY.put(cursorId, remaining);
        } else {
            firstBatch = results;
        }

        return buildCursorResponse(cursorId, db + "." + collection, firstBatch);
    }

    static BsonDocument buildCursorResponse(long cursorId, String namespace, List<BsonDocument> batch) {
        BsonArray bsonBatch = new BsonArray();
        for (BsonDocument d : batch) bsonBatch.add(d);

        BsonDocument cursor = new BsonDocument();
        cursor.put("id", new BsonInt64(cursorId));
        cursor.put("ns", new BsonString(namespace));
        cursor.put("firstBatch", bsonBatch);

        BsonDocument response = new BsonDocument();
        response.put("cursor", cursor);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<BsonDocument> applySortAndProject(List<BsonDocument> docs, BsonDocument sort) {
        FilterEvaluator fe = this.filter;
        List<BsonDocument> sorted = new ArrayList<>(docs);
        sorted.sort((a, b) -> {
            for (Map.Entry<String, BsonValue> entry : sort.entrySet()) {
                String key = entry.getKey();
                int direction = entry.getValue().asNumber().intValue();
                BsonValue av = fe.getNestedValue(a, key);
                BsonValue bv = fe.getNestedValue(b, key);
                int cmp = compare(av, bv);
                if (cmp != 0) return direction * cmp;
            }
            return 0;
        });
        return sorted;
    }

    private List<BsonDocument> applyProjection(List<BsonDocument> docs, BsonDocument projection) {
        boolean inclusionMode = projection.entrySet().stream()
                .filter(e -> !e.getKey().equals("_id"))
                .anyMatch(e -> e.getValue().asNumber().intValue() == 1);

        List<BsonDocument> result = new ArrayList<>(docs.size());
        for (BsonDocument doc : docs) {
            BsonDocument out;
            if (inclusionMode) {
                out = new BsonDocument();
                for (Map.Entry<String, BsonValue> entry : projection.entrySet()) {
                    if (entry.getValue().asNumber().intValue() == 1) {
                        BsonValue v = filter.getNestedValue(doc, entry.getKey());
                        if (v != null) out.put(entry.getKey(), v);
                    }
                }
                // _id included by default unless excluded
                if (!projection.containsKey("_id") && doc.containsKey("_id")) {
                    out.put("_id", doc.get("_id"));
                }
                if (projection.containsKey("_id") && projection.getNumber("_id").intValue() == 0) {
                    out.remove("_id");
                }
            } else {
                out = doc.clone();
                for (Map.Entry<String, BsonValue> entry : projection.entrySet()) {
                    if (entry.getValue().asNumber().intValue() == 0) {
                        out.remove(entry.getKey());
                    }
                }
            }
            result.add(out);
        }
        return result;
    }

    private static int compare(BsonValue a, BsonValue b) {
        if (a == null || a instanceof BsonNull) return (b == null || b instanceof BsonNull) ? 0 : -1;
        if (b == null || b instanceof BsonNull) return 1;
        if (a.isNumber() && b.isNumber())
            return Double.compare(a.asNumber().doubleValue(), b.asNumber().doubleValue());
        if (a.isString() && b.isString())
            return a.asString().getValue().compareTo(b.asString().getValue());
        if (a.isDateTime() && b.isDateTime())
            return Long.compare(a.asDateTime().getValue(), b.asDateTime().getValue());
        return a.toString().compareTo(b.toString());
    }
}
