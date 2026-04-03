package com.smolcumentdb.server;

import com.smolcumentdb.command.CommandDispatcher;
import com.smolcumentdb.protocol.MongoMessageDecoder;
import com.smolcumentdb.protocol.MongoMessageEncoder;
import com.smolcumentdb.storage.InMemoryStorage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Netty-based TCP server that speaks the MongoDB Wire Protocol.
 */
public class SmolcumentDBServer {

    private static final Logger LOG = LoggerFactory.getLogger(SmolcumentDBServer.class);

    private final String host;
    private final int port;
    private final InMemoryStorage storage;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /** Actual bound port - equals {@code port} unless 0 was requested (random). */
    private int boundPort;

    public SmolcumentDBServer(String host, int port, InMemoryStorage storage) {
        this.host    = host;
        this.port    = port;
        this.storage = storage;
    }

    public void start() throws InterruptedException {
        CommandDispatcher dispatcher = new CommandDispatcher(storage);

        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new MongoMessageDecoder(),     // inbound:  bytes   → MongoMessage
                                new MongoMessageEncoder(),     // outbound: MongoResponse → bytes
                                new MongoServerHandler(dispatcher) // business logic
                        );
                    }
                });

        ChannelFuture future = bootstrap.bind(host, port).sync();
        serverChannel = future.channel();
        boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
        LOG.info("SmolcumentDB listening on {}:{}", host, boundPort);
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup  != null) bossGroup.shutdownGracefully();
        LOG.info("SmolcumentDB stopped");
    }

    public int getBoundPort() {
        return boundPort;
    }
}
