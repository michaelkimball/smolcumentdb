package com.smolcumentdb.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.bson.BsonArray;
import org.bson.BsonBinaryReader;
import org.bson.BsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.DecoderContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes a MongoDB wire protocol frame into a {@link MongoMessage}.
 *
 * <p>Supports OP_MSG (opcode 2013) and OP_QUERY (opcode 2004, legacy handshake only).
 */
public class MongoMessageDecoder extends LengthFieldBasedFrameDecoder {

    private static final int MAX_FRAME_LENGTH = 48_000_000;
    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();
    private static final DecoderContext DECODER_CTX = DecoderContext.builder().build();

    public MongoMessageDecoder() {
        super(MAX_FRAME_LENGTH, 0, 4, -4, 0);
    }

    /**
     * Override to read the 4-byte MongoDB frame length as little-endian.
     * The default implementation reads big-endian, which is wrong for MongoDB.
     */
    @Override
    protected long getUnadjustedFrameLength(ByteBuf buf, int offset, int length, java.nio.ByteOrder order) {
        // MongoDB wire protocol length field is always little-endian
        return buf.getUnsignedIntLE(offset);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) return null;
        try {
            frame.skipBytes(4); // messageLength
            int requestId  = frame.readIntLE();
            int responseTo = frame.readIntLE();
            int opCode     = frame.readIntLE();

            if (opCode == Opcodes.OP_MSG) {
                return decodeOpMsg(requestId, responseTo, frame);
            } else if (opCode == Opcodes.OP_QUERY) {
                return decodeOpQuery(requestId, responseTo, frame);
            }
            return null;
        } finally {
            frame.release();
        }
    }

    private MongoMessage decodeOpMsg(int requestId, int responseTo, ByteBuf frame) throws IOException {
        frame.skipBytes(4); // flagBits

        BsonDocument commandDoc = null;
        Map<String, BsonArray> sequences = new LinkedHashMap<>();

        while (frame.isReadable()) {
            int payloadType = frame.readByte() & 0xFF;
            if (payloadType == 0) {
                commandDoc = readBsonDocument(frame);
            } else if (payloadType == 1) {
                int seqSize = frame.readIntLE(); // includes its own 4 bytes
                byte[] seqPayload = new byte[seqSize - 4];
                frame.readBytes(seqPayload);

                ByteArrayInputStream bais = new ByteArrayInputStream(seqPayload);
                String identifier = readCStringFromStream(bais);

                BsonArray docs = new BsonArray();
                byte[] rest = bais.readAllBytes();
                int offset = 0;
                while (offset + 4 <= rest.length) {
                    int docLen = ByteBuffer.wrap(rest, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    if (offset + docLen > rest.length) break;
                    byte[] docBytes = new byte[docLen];
                    System.arraycopy(rest, offset, docBytes, 0, docLen);
                    docs.add(parseBsonBytes(docBytes));
                    offset += docLen;
                }
                sequences.put(identifier, docs);
            }
        }

        if (commandDoc == null) commandDoc = new BsonDocument();
        for (Map.Entry<String, BsonArray> entry : sequences.entrySet()) {
            commandDoc.put(entry.getKey(), entry.getValue());
        }
        return new MongoMessage(requestId, responseTo, commandDoc);
    }

    private MongoMessage decodeOpQuery(int requestId, int responseTo, ByteBuf frame) {
        frame.skipBytes(4); // flags
        drainCString(frame); // fullCollectionName
        frame.skipBytes(4);  // numberToSkip
        frame.skipBytes(4);  // numberToReturn
        BsonDocument query = readBsonDocument(frame);
        return new MongoMessage(requestId, responseTo, query);
    }

    private BsonDocument readBsonDocument(ByteBuf frame) {
        int docLen = frame.readIntLE();
        byte[] bytes = new byte[docLen];
        bytes[0] = (byte)  (docLen        & 0xFF);
        bytes[1] = (byte) ((docLen >>  8) & 0xFF);
        bytes[2] = (byte) ((docLen >> 16) & 0xFF);
        bytes[3] = (byte) ((docLen >> 24) & 0xFF);
        frame.readBytes(bytes, 4, docLen - 4);
        return parseBsonBytes(bytes);
    }

    private static BsonDocument parseBsonBytes(byte[] bytes) {
        return CODEC.decode(new BsonBinaryReader(ByteBuffer.wrap(bytes)), DECODER_CTX);
    }

    private static String readCStringFromStream(ByteArrayInputStream bais) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = bais.read()) > 0) {
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    private static void drainCString(ByteBuf frame) {
        while (frame.isReadable() && frame.readByte() != 0) {
            // consume until null terminator
        }
    }
}
