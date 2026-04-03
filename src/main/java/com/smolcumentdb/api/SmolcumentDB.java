package com.smolcumentdb.api;

import com.smolcumentdb.server.SmolcumentDBServer;
import com.smolcumentdb.storage.InMemoryStorage;

/**
 * Main entry point for SmolcumentDB.
 *
 * <pre>{@code
 * // Start on a random port
 * SmolcumentDB db = SmolcumentDB.start();
 * MongoClient client = MongoClients.create(db.getConnectionString());
 *
 * // Or build with options
 * SmolcumentDB db = SmolcumentDB.builder().port(27017).start();
 *
 * // Teardown
 * db.stop();
 * }</pre>
 */
public class SmolcumentDB {

    private final SmolcumentDBServer server;
    private final InMemoryStorage storage;

    private SmolcumentDB(SmolcumentDBServer server, InMemoryStorage storage) {
        this.server  = server;
        this.storage = storage;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** Starts a server on a random available port and returns the instance. */
    public static SmolcumentDB start() {
        return builder().start();
    }

    /** Returns a fluent builder for configuring the server before starting. */
    public static SmolcumentDBBuilder builder() {
        return new SmolcumentDBBuilder();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Stops the server. After calling this, the instance cannot be reused. */
    public void stop() {
        server.stop();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns a MongoDB connection string pointing at the embedded server,
     * e.g. {@code "mongodb://127.0.0.1:54321"}.
     */
    public String getConnectionString() {
        return "mongodb://127.0.0.1:" + server.getBoundPort();
    }

    /** Returns the TCP port the server is listening on. */
    public int getPort() {
        return server.getBoundPort();
    }

    /**
     * Returns the underlying {@link InMemoryStorage} so tests can inspect or
     * pre-populate data directly without going through the wire protocol.
     */
    public InMemoryStorage getStorage() {
        return storage;
    }

    // -------------------------------------------------------------------------
    // Package-private factory used by SmolcumentDBBuilder
    // -------------------------------------------------------------------------

    static SmolcumentDB create(SmolcumentDBServer server, InMemoryStorage storage) {
        return new SmolcumentDB(server, storage);
    }
}
