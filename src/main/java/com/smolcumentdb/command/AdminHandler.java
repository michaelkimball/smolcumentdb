package com.smolcumentdb.command;

import com.smolcumentdb.query.FilterEvaluator;
import com.smolcumentdb.storage.InMemoryCollection;
import com.smolcumentdb.storage.InMemoryStorage;
import org.bson.*;

import java.util.Set;

/**
 * Handles MongoDB administrative commands:
 * listDatabases, listCollections, drop, dropDatabase, createCollection,
 * createIndexes, count, countDocuments, killCursors, endSessions.
 */
public class AdminHandler {

    private final InMemoryStorage storage;
    private final FilterEvaluator filter;

    public AdminHandler(InMemoryStorage storage) {
        this.storage = storage;
        this.filter  = new FilterEvaluator();
    }

    public BsonDocument listDatabases(BsonDocument command) {
        BsonArray databases = new BsonArray();
        for (String dbName : storage.listDatabases()) {
            BsonDocument dbInfo = new BsonDocument();
            dbInfo.put("name", new BsonString(dbName));
            dbInfo.put("sizeOnDisk", new BsonInt64(0));
            dbInfo.put("empty", BsonBoolean.FALSE);
            databases.add(dbInfo);
        }
        BsonDocument response = new BsonDocument();
        response.put("databases", databases);
        response.put("totalSize", new BsonInt64(0));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument listCollections(BsonDocument command) {
        String db = command.getString("$db").getValue();
        BsonArray collections = new BsonArray();
        for (String colName : storage.listCollections(db)) {
            BsonDocument col = new BsonDocument();
            col.put("name", new BsonString(colName));
            col.put("type", new BsonString("collection"));
            col.put("options", new BsonDocument());
            col.put("idIndex", new BsonDocument("v", new BsonInt32(2))
                    .append("key", new BsonDocument("_id", new BsonInt32(1)))
                    .append("name", new BsonString("_id_")));
            collections.add(col);
        }

        // Return as a cursor
        BsonDocument cursor = new BsonDocument();
        cursor.put("id", new BsonInt64(0));
        cursor.put("ns", new BsonString(db + ".$cmd.listCollections"));
        cursor.put("firstBatch", collections);

        BsonDocument response = new BsonDocument();
        response.put("cursor", cursor);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument drop(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("drop").getValue();
        storage.dropCollection(db, collection);
        BsonDocument response = new BsonDocument();
        response.put("ns", new BsonString(db + "." + collection));
        response.put("nIndexesWas", new BsonInt32(1));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument dropDatabase(BsonDocument command) {
        String db = command.getString("$db").getValue();
        storage.dropDatabase(db);
        BsonDocument response = new BsonDocument();
        response.put("dropped", new BsonString(db));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument createCollection(BsonDocument command) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString("create").getValue();
        // Calling getCollection lazily creates it
        storage.getCollection(db, collection);
        BsonDocument response = new BsonDocument();
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    /** No-op - indexes are not enforced in-memory but we acknowledge the command. */
    public BsonDocument createIndexes(BsonDocument command) {
        BsonDocument response = new BsonDocument();
        response.put("numIndexesBefore", new BsonInt32(1));
        response.put("numIndexesAfter", new BsonInt32(1));
        response.put("createdCollectionAutomatically", BsonBoolean.FALSE);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument count(BsonDocument command, String commandKey) {
        String db         = command.getString("$db").getValue();
        String collection = command.getString(commandKey).getValue();
        BsonDocument query = command.containsKey("query") ? command.getDocument("query") : new BsonDocument();
        InMemoryCollection col = storage.getCollection(db, collection);
        long n = col.count(query, filter);
        BsonDocument response = new BsonDocument();
        response.put("n",  new BsonInt32((int) n));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public BsonDocument killCursors(BsonDocument command) {
        BsonArray cursorIds = command.getArray("cursors", new BsonArray());
        for (BsonValue id : cursorIds) {
            FindHandler.CURSOR_REGISTRY.remove(id.asNumber().longValue());
        }
        BsonDocument response = new BsonDocument();
        response.put("cursorsKilled", cursorIds);
        response.put("cursorsNotFound", new BsonArray());
        response.put("cursorsAlive", new BsonArray());
        response.put("cursorsUnknown", new BsonArray());
        response.put("ok", new BsonDouble(1.0));
        return response;
    }
}
