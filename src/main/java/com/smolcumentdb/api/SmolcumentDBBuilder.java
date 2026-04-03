package com.smolcumentdb.api;

import com.smolcumentdb.server.SmolcumentDBServer;
import com.smolcumentdb.storage.InMemoryStorage;

/**
 * Fluent builder for {@link SmolcumentDB}.
 *
 * <pre>{@code
 * SmolcumentDB db = SmolcumentDB.builder()
 *         .host("127.0.0.1")
 *         .port(0)          // 0 = pick a random free port
 *         .start();
 * }</pre>
 */
public class SmolcumentDBBuilder {

    private String host = "127.0.0.1";
    private int    port = 0; // 0 = random

    SmolcumentDBBuilder() {}

    public SmolcumentDBBuilder host(String host) {
        this.host = host;
        return this;
    }

    /**
     * Sets the TCP port. Use {@code 0} (the default) to let the OS pick a
     * random available port — recommended for tests to avoid conflicts.
     */
    public SmolcumentDBBuilder port(int port) {
        this.port = port;
        return this;
    }

    /**
     * Starts the embedded server and returns a ready-to-use {@link SmolcumentDB} instance.
     *
     * @throws RuntimeException if the server fails to bind
     */
    public SmolcumentDB start() {
        InMemoryStorage storage = InMemoryStorage.getInstance();
        SmolcumentDBServer server = new SmolcumentDBServer(host, port, storage);
        try {
            server.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SmolcumentDB interrupted while starting", e);
        }
        return SmolcumentDB.create(server, storage);
    }
}
