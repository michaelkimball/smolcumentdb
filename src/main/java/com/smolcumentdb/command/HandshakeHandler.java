package com.smolcumentdb.command;

import org.bson.*;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the MongoDB connection handshake commands:
 * hello, isMaster, ismaster, buildInfo, getParameter, ping, endSessions.
 *
 * <p>Responds with wire version 17 (MongoDB 6.0), which satisfies the MongoDB
 * Java Driver 4.x requirement of maxWireVersion >= 6.
 */
public class HandshakeHandler {

    private static final int MIN_WIRE_VERSION = 0;
    private static final int MAX_WIRE_VERSION = 17;
    private static final AtomicInteger CONNECTION_ID_SEQ = new AtomicInteger(1);

    private final int connectionId = CONNECTION_ID_SEQ.getAndIncrement();

    public BsonDocument handle(BsonDocument command) {
        BsonDocument response = new BsonDocument();
        response.put("ismaster", BsonBoolean.TRUE);
        response.put("isWritablePrimary", BsonBoolean.TRUE);
        response.put("helloOk", BsonBoolean.TRUE);
        response.put("minWireVersion", new BsonInt32(MIN_WIRE_VERSION));
        response.put("maxWireVersion", new BsonInt32(MAX_WIRE_VERSION));
        response.put("maxBsonObjectSize", new BsonInt32(16_777_216));
        response.put("maxMessageSizeBytes", new BsonInt32(48_000_000));
        response.put("maxWriteBatchSize", new BsonInt32(100_000));
        response.put("localTime", new BsonDateTime(new Date().getTime()));
        response.put("logicalSessionTimeoutMinutes", new BsonInt32(30));
        response.put("connectionId", new BsonInt32(connectionId));
        response.put("readOnly", BsonBoolean.FALSE);
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public static BsonDocument buildInfo() {
        BsonDocument response = new BsonDocument();
        response.put("version", new BsonString("6.0.0"));
        response.put("versionArray", new BsonArray(java.util.List.of(
                new BsonInt32(6), new BsonInt32(0), new BsonInt32(0), new BsonInt32(0))));
        response.put("gitVersion", new BsonString("smolcumentdb"));
        response.put("sysInfo", new BsonString("deprecated"));
        response.put("storageEngines", new BsonArray(java.util.List.of(new BsonString("smolcumentdb"))));
        response.put("bits", new BsonInt32(64));
        response.put("debug", BsonBoolean.FALSE);
        response.put("maxBsonObjectSize", new BsonInt32(16_777_216));
        response.put("ok", new BsonDouble(1.0));
        return response;
    }

    public static BsonDocument ping() {
        BsonDocument r = new BsonDocument();
        r.put("ok", new BsonDouble(1.0));
        return r;
    }

    public static BsonDocument getParameter(BsonDocument command) {
        BsonDocument r = new BsonDocument();
        // Return a sensible value for any requested parameter
        if (command.containsKey("featureCompatibilityVersion")) {
            BsonDocument fcv = new BsonDocument("version", new BsonString("6.0"));
            r.put("featureCompatibilityVersion", fcv);
        }
        r.put("ok", new BsonDouble(1.0));
        return r;
    }
}
