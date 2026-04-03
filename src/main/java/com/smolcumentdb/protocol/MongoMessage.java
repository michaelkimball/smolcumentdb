package com.smolcumentdb.protocol;

/**
 * Decoded representation of a MongoDB OP_MSG message.
 * The command document is extracted from the single Payload Type 0 section.
 * Document-sequence sections (Payload Type 1, e.g., "documents" for insert) are
 * merged into the command doc under their identifier key as a BsonArray.
 */
public final class MongoMessage {
    private final int requestId;
    private final int responseTo;
    private final org.bson.BsonDocument command;

    public MongoMessage(int requestId, int responseTo, org.bson.BsonDocument command) {
        this.requestId = requestId;
        this.responseTo = responseTo;
        this.command = command;
    }

    public int getRequestId() { return requestId; }
    public int getResponseTo() { return responseTo; }
    public org.bson.BsonDocument getCommand() { return command; }
}
