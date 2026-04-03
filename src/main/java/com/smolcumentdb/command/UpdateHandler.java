package com.smolcumentdb.command;

import com.smolcumentdb.query.FilterEvaluator;
import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

import java.util.List;

/**
 * Handles the MongoDB {@code update} command.
 *
 * <p>Supports single and multi-document updates, upsert.
 * Update operators: $set, $unset, $inc, $push, $pull, $addToSet, $rename.
 */
public class UpdateHandler {

    private final InMemoryStorage storage;
    private final FilterEvaluator filter;

    public UpdateHandler(InMemoryStorage storage) {
        this.storage = storage;
        this.filter  = new FilterEvaluator();
    }

    public BsonDocument handle(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("update").getValue();
        InMemoryCollection col = storage.getCollection(db, collection);

        BsonArray updates = command.getArray("updates");
        long totalModified = 0;
        long totalMatched = 0;
        long totalUpserted = 0;

        for (BsonValue updateVal : updates) {
            BsonDocument updateSpec = updateVal.asDocument();
            BsonDocument q      = updateSpec.containsKey("q") ? updateSpec.getDocument("q") : new BsonDocument();
            BsonDocument u      = updateSpec.containsKey("u") ? updateSpec.getDocument("u") : new BsonDocument();
            boolean multi       = updateSpec.containsKey("multi") && updateSpec.getBoolean("multi").getValue();
            boolean upsert      = updateSpec.containsKey("upsert") && updateSpec.getBoolean("upsert").getValue();

            List<BsonDocument> matched = col.find(q, filter);
            totalMatched += matched.size();

            if (matched.isEmpty() && upsert) {
                // Insert a new document derived from the filter + update
                BsonDocument newDoc = deriveUpsertDocument(q, u);
                col.insertMany(java.util.List.of(newDoc));
                totalUpserted++;
            } else {
                totalModified += col.update(q, u, multi, filter);
            }
        }

        BsonDocument response = new BsonDocument();
        response.put("n",         new BsonInt32((int) totalMatched));
        response.put("nModified", new BsonInt32((int) totalModified));
        if (totalUpserted > 0) {
            response.put("upserted", new BsonArray()); // ids omitted for brevity
        }
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    private BsonDocument deriveUpsertDocument(BsonDocument filter, BsonDocument update) {
        BsonDocument doc = new BsonDocument();
        // Copy simple equality conditions from filter
        for (String key : filter.keySet()) {
            BsonValue val = filter.get(key);
            if (!key.startsWith("$") && !val.isDocument()) {
                doc.put(key, val);
            }
        }
        // Apply update operators
        boolean hasOp = update.keySet().stream().anyMatch(k -> k.startsWith("$"));
        if (hasOp && update.containsKey("$set")) {
            update.getDocument("$set").forEach(doc::put);
        } else if (!hasOp) {
            // Full replacement
            update.forEach(doc::put);
        }
        return doc;
    }
}
