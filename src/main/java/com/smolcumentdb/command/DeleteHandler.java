package com.smolcumentdb.command;

import com.smolcumentdb.query.FilterEvaluator;
import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

/**
 * Handles the MongoDB {@code delete} command.
 */
public class DeleteHandler {

    private final InMemoryStorage storage;
    private final FilterEvaluator filter;

    public DeleteHandler(InMemoryStorage storage) {
        this.storage = storage;
        this.filter  = new FilterEvaluator();
    }

    public BsonDocument handle(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("delete").getValue();
        InMemoryCollection col = storage.getCollection(db, collection);

        BsonArray deletes = command.getArray("deletes");
        long totalDeleted = 0;

        for (BsonValue deleteVal : deletes) {
            BsonDocument deleteSpec = deleteVal.asDocument();
            BsonDocument q   = deleteSpec.containsKey("q") ? deleteSpec.getDocument("q") : new BsonDocument();
            int limit        = deleteSpec.containsKey("limit") ? deleteSpec.getNumber("limit").intValue() : 0;
            boolean multi    = limit != 1; // limit=1 means delete one, 0 means delete all

            totalDeleted += col.delete(q, multi, filter);
        }

        BsonDocument response = new BsonDocument();
        response.put("n",  new BsonInt32((int) totalDeleted));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }
}
