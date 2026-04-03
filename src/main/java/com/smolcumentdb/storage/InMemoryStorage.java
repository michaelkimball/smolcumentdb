package com.smolcumentdb.storage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry of all in-memory databases and their collections.
 */
public class InMemoryStorage {

    private static final InMemoryStorage INSTANCE = new InMemoryStorage();

    // dbName → collectionName → InMemoryCollection
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, InMemoryCollection>> databases
            = new ConcurrentHashMap<>();

    private InMemoryStorage() {}

    public static InMemoryStorage getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------

    public InMemoryCollection getCollection(String db, String collection) {
        return databases
                .computeIfAbsent(db, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(collection, k -> new InMemoryCollection());
    }

    public boolean collectionExists(String db, String collection) {
        ConcurrentHashMap<String, InMemoryCollection> dbMap = databases.get(db);
        return dbMap != null && dbMap.containsKey(collection);
    }

    public Set<String> listCollections(String db) {
        ConcurrentHashMap<String, InMemoryCollection> dbMap = databases.get(db);
        return dbMap == null ? Set.of() : dbMap.keySet();
    }

    public Set<String> listDatabases() {
        return databases.keySet();
    }

    public void dropCollection(String db, String collection) {
        ConcurrentHashMap<String, InMemoryCollection> dbMap = databases.get(db);
        if (dbMap != null) {
            dbMap.remove(collection);
        }
    }

    public void dropDatabase(String db) {
        databases.remove(db);
    }

    /** Wipes all data — useful for test teardown. */
    public void reset() {
        databases.clear();
    }
}
