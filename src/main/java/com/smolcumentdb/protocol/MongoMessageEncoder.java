package com.smolcumentdb.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.bson.BsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

/**
 * Encodes a {@link BsonDocument} as a MongoDB OP_MSG response frame.
 */
public class MongoMessageEncoder extends MessageToByteEncoder<MongoResponse> {

    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();
    private static final EncoderContext ENCODER_CTX = EncoderContext.builder().build();

    @Override
    protected void encode(ChannelHandlerContext ctx, MongoResponse resp, ByteBuf out) {
        byte[] bsonBytes = toBsonBytes(resp.getDocument());

        // MsgHeader (16) + flagBits (4) + payloadType (1) + bson document
        int totalLength = 16 + 4 + 1 + bsonBytes.length;

        out.writeIntLE(totalLength);            // messageLength
        out.writeIntLE(resp.getRequestId());    // requestID (server-generated)
        out.writeIntLE(resp.getResponseTo());   // responseTo
        out.writeIntLE(Opcodes.OP_MSG);         // opCode

        out.writeIntLE(0);                       // flagBits = 0
        out.writeByte(0);                        // payloadType = 0 (single BSON doc)
        out.writeBytes(bsonBytes);
    }

    private static byte[] toBsonBytes(BsonDocument doc) {
        BasicOutputBuffer buf = new BasicOutputBuffer();
        org.bson.BsonBinaryWriter writer = new org.bson.BsonBinaryWriter(buf);
        CODEC.encode(writer, doc, ENCODER_CTX);
        writer.close();
        return buf.toByteArray();
    }
}
