package com.smolcumentdb.storage;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory collection of BSON documents.
 */
public class InMemoryCollection {

    private final CopyOnWriteArrayList<BsonDocument> documents = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Write operations (synchronized for multi-step atomicity)
    // -------------------------------------------------------------------------

    public synchronized List<BsonDocument> insertMany(List<BsonDocument> docs) {
        List<BsonDocument> inserted = new ArrayList<>(docs.size());
        for (BsonDocument doc : docs) {
            BsonDocument copy = doc.clone();
            if (!copy.containsKey("_id")) {
                copy.put("_id", new BsonObjectId(new ObjectId()));
            }
            documents.add(copy);
            inserted.add(copy);
        }
        return inserted;
    }

    /**
     * Replaces documents matching {@code filter} using the update spec.
     * Returns the number of modified documents.
     */
    public synchronized long update(BsonDocument filter, BsonDocument update,
                                    boolean multi, com.smolcumentdb.query.FilterEvaluator evaluator) {
        long modified = 0;
        for (int i = 0; i < documents.size(); i++) {
            BsonDocument doc = documents.get(i);
            if (evaluator.matches(doc, filter)) {
                BsonDocument updated = applyUpdate(doc.clone(), update);
                documents.set(i, updated);
                modified++;
                if (!multi) break;
            }
        }
        return modified;
    }

    /**
     * Deletes documents matching {@code filter}.
     * Returns the number of deleted documents.
     */
    public synchronized long delete(BsonDocument filter, boolean multi,
                                    com.smolcumentdb.query.FilterEvaluator evaluator) {
        long deleted = 0;
        List<BsonDocument> toRemove = new ArrayList<>();
        for (BsonDocument doc : documents) {
            if (evaluator.matches(doc, filter)) {
                toRemove.add(doc);
                deleted++;
                if (!multi) break;
            }
        }
        documents.removeAll(toRemove);
        return deleted;
    }

    public void drop() {
        documents.clear();
    }

    // -------------------------------------------------------------------------
    // Read operations (snapshot - safe without lock due to CopyOnWriteArrayList)
    // -------------------------------------------------------------------------

    public List<BsonDocument> find(BsonDocument filter, com.smolcumentdb.query.FilterEvaluator evaluator) {
        List<BsonDocument> result = new ArrayList<>();
        for (BsonDocument doc : documents) {
            if (evaluator.matches(doc, filter)) {
                result.add(doc.clone());
            }
        }
        return result;
    }

    /** Returns a snapshot of all documents (for aggregation etc.). */
    public List<BsonDocument> all() {
        List<BsonDocument> copy = new ArrayList<>(documents.size());
        for (BsonDocument doc : documents) {
            copy.add(doc.clone());
        }
        return copy;
    }

    public long count(BsonDocument filter, com.smolcumentdb.query.FilterEvaluator evaluator) {
        if (filter == null || filter.isEmpty()) return documents.size();
        long count = 0;
        for (BsonDocument doc : documents) {
            if (evaluator.matches(doc, filter)) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Update operator application
    // -------------------------------------------------------------------------

    private BsonDocument applyUpdate(BsonDocument doc, BsonDocument update) {
        // If the update document has no known operator keys, treat as a full replacement.
        boolean hasOperator = update.keySet().stream().anyMatch(k -> k.startsWith("$"));
        if (!hasOperator) {
            // Full replacement - preserve _id
            BsonDocument replacement = update.clone();
            if (!replacement.containsKey("_id") && doc.containsKey("_id")) {
                replacement.put("_id", doc.get("_id"));
            }
            return replacement;
        }

        // Apply operators
        if (update.containsKey("$set")) {
            BsonDocument set = update.getDocument("$set");
            set.forEach(doc::put);
        }
        if (update.containsKey("$unset")) {
            update.getDocument("$unset").keySet().forEach(doc::remove);
        }
        if (update.containsKey("$inc")) {
            update.getDocument("$inc").forEach((key, val) -> {
                if (doc.isNumber(key)) {
                    doc.put(key, new org.bson.BsonInt64(doc.getNumber(key).longValue()
                            + val.asNumber().longValue()));
                } else {
                    doc.put(key, val);
                }
            });
        }
        if (update.containsKey("$push")) {
            update.getDocument("$push").forEach((key, val) -> {
                if (!doc.isArray(key)) {
                    doc.put(key, new BsonArray());
                }
                doc.getArray(key).add(val);
            });
        }
        if (update.containsKey("$addToSet")) {
            update.getDocument("$addToSet").forEach((key, val) -> {
                if (!doc.isArray(key)) {
                    doc.put(key, new BsonArray());
                }
                BsonArray arr = doc.getArray(key);
                if (!arr.contains(val)) {
                    arr.add(val);
                }
            });
        }
        if (update.containsKey("$pull")) {
            update.getDocument("$pull").forEach((key, val) -> {
                if (doc.isArray(key)) {
                    BsonArray arr = doc.getArray(key);
                    arr.removeIf(elem -> elem.equals(val));
                }
            });
        }
        if (update.containsKey("$rename")) {
            update.getDocument("$rename").forEach((oldKey, newKeyVal) -> {
                String newKey = newKeyVal.asString().getValue();
                if (doc.containsKey(oldKey)) {
                    doc.put(newKey, doc.get(oldKey));
                    doc.remove(oldKey);
                }
            });
        }
        return doc;
    }
}
