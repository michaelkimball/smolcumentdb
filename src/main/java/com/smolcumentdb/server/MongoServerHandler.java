package com.smolcumentdb.server;

import com.smolcumentdb.command.CommandDispatcher;
import com.smolcumentdb.protocol.MongoMessage;
import com.smolcumentdb.protocol.MongoResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.bson.BsonDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler. Receives decoded {@link MongoMessage} objects,
 * dispatches to {@link CommandDispatcher}, and writes back a {@link MongoResponse}.
 *
 * <p>One instance per channel (not sharable) to keep connection state isolated.
 */
public class MongoServerHandler extends SimpleChannelInboundHandler<MongoMessage> {

    private static final Logger LOG = LoggerFactory.getLogger(MongoServerHandler.class);

    private final CommandDispatcher dispatcher;

    public MongoServerHandler(CommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MongoMessage msg) {
        BsonDocument result = dispatcher.dispatch(msg.getCommand());
        ctx.writeAndFlush(new MongoResponse(msg.getRequestId(), result));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Channel error - closing connection", cause);
        ctx.close();
    }
}
