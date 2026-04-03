package com.smolcumentdb.command;

import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the MongoDB {@code insert} command.
 */
public class InsertHandler {

    private final InMemoryStorage storage;

    public InsertHandler(InMemoryStorage storage) {
        this.storage = storage;
    }

    public BsonDocument handle(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("insert").getValue();

        List<BsonDocument> docs = new ArrayList<>();
        if (command.isArray("documents")) {
            for (BsonValue v : command.getArray("documents")) {
                docs.add(v.asDocument());
            }
        }

        InMemoryCollection col = storage.getCollection(db, collection);
        List<BsonDocument> inserted = col.insertMany(docs);

        BsonDocument response = new BsonDocument();
        response.put("n", new BsonInt32(inserted.size()));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }
}
