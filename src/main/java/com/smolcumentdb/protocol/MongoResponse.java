package com.smolcumentdb.protocol;

import org.bson.BsonDocument;

/**
 * An OP_MSG response to be written back to the client.
 */
public final class MongoResponse {
    private final int requestId;   // server-side message ID (we use 0)
    private final int responseTo;  // must mirror the client's requestID
    private final BsonDocument document;

    public MongoResponse(int responseTo, BsonDocument document) {
        this.requestId = 0;
        this.responseTo = responseTo;
        this.document = document;
    }

    public int getRequestId()  { return requestId; }
    public int getResponseTo() { return responseTo; }
    public BsonDocument getDocument() { return document; }
}
