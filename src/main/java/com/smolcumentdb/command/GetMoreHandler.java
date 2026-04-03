package com.smolcumentdb.command;

import org.bson.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the MongoDB {@code getMore} command for cursor pagination.
 */
public class GetMoreHandler {

    public BsonDocument handle(BsonDocument command) {
        long cursorId      = command.getNumber("getMore").longValue();
        String collection  = command.getString("collection").getValue();
        int batchSize      = command.containsKey("batchSize")
                ? command.getNumber("batchSize").intValue() : 101;

        List<BsonDocument> remaining = FindHandler.CURSOR_REGISTRY.get(cursorId);

        if (remaining == null) {
            // Cursor not found or already exhausted
            BsonDocument error = new BsonDocument();
            error.put("ok", new BsonDouble(0.0));
            error.put("errmsg", new BsonString("cursor id " + cursorId + " not found"));
            error.put("code", new BsonInt32(43)); // CursorNotFound
            error.put("codeName", new BsonString("CursorNotFound"));
            return error;
        }

        int effectiveBatchSize = batchSize <= 0 ? remaining.size() : batchSize;
        List<BsonDocument> batch;
        long newCursorId;

        if (remaining.size() > effectiveBatchSize) {
            batch = new ArrayList<>(remaining.subList(0, effectiveBatchSize));
            remaining = new ArrayList<>(remaining.subList(effectiveBatchSize, remaining.size()));
            FindHandler.CURSOR_REGISTRY.put(cursorId, remaining);
            newCursorId = cursorId;
        } else {
            batch = remaining;
            FindHandler.CURSOR_REGISTRY.remove(cursorId);
            newCursorId = 0; // 0 = cursor exhausted
        }

        BsonArray bsonBatch = new BsonArray();
        for (BsonDocument d : batch) bsonBatch.add(d);

        BsonDocument cursor = new BsonDocument();
        cursor.put("id", new BsonInt64(newCursorId));
        cursor.put("ns", new BsonString(collection));
        cursor.put("nextBatch", bsonBatch);

        BsonDocument response = new BsonDocument();
        response.put("cursor", cursor);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }
}
