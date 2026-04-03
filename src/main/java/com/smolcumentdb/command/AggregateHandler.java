package com.smolcumentdb.command;

import com.smolcumentdb.aggregation.AggregationPipeline;
import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the MongoDB {@code aggregate} command.
 */
public class AggregateHandler {

    private final InMemoryStorage storage;
    private final AggregationPipeline pipeline;

    public AggregateHandler(InMemoryStorage storage) {
        this.storage  = storage;
        this.pipeline = new AggregationPipeline();
    }

    public BsonDocument handle(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("aggregate").getValue();
        BsonArray stages  = command.getArray("pipeline");

        InMemoryCollection col = storage.getCollection(db, collection);
        List<BsonDocument> input = col.all();

        List<BsonDocument> results = pipeline.execute(input, stages);

        int batchSize = command.containsKey("cursor") && command.getDocument("cursor").containsKey("batchSize")
                ? command.getDocument("cursor").getNumber("batchSize").intValue()
                : results.size();
        if (batchSize <= 0) batchSize = results.size();

        long cursorId = 0;
        List<BsonDocument> firstBatch;

        if (results.size() > batchSize) {
            firstBatch = new ArrayList<>(results.subList(0, batchSize));
            List<BsonDocument> remaining = new ArrayList<>(results.subList(batchSize, results.size()));
            cursorId = FindHandler.CURSOR_ID_SEQ.getAndIncrement();
            FindHandler.CURSOR_REGISTRY.put(cursorId, remaining);
        } else {
            firstBatch = results;
        }

        BsonArray bsonBatch = new BsonArray();
        for (BsonDocument d : firstBatch) bsonBatch.add(d);

        BsonDocument cursor = new BsonDocument();
        cursor.put("id", new BsonInt64(cursorId));
        cursor.put("ns", new BsonString(db + "." + collection));
        cursor.put("firstBatch", bsonBatch);

        BsonDocument response = new BsonDocument();
        response.put("cursor", cursor);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }
}
