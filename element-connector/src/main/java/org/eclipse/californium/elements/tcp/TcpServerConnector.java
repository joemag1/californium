/*******************************************************************************
 * Copyright (c) 2015 Institute for Pervasive Computing, ETH Zurich and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 *
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Martin Lanter - architect and initial implementation
 *    Joe Magerramov (Amazon AWS) - CoAP over TCP support
 ******************************************************************************/
package org.eclipse.californium.elements.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.UDPConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP server connection is used by CoapEndpoint when instantiated by the CoapServer. Per RFC the server can both
 * send and receive messages, but cannot initiated new outgoing connections.
 */
public class TcpServerConnector implements Connector {

    private final static Logger LOGGER = Logger.getLogger(UDPConnector.class.getName());

    private final int numberOfThreads;
    private final InetSocketAddress localAddress;
    private final int connectionIdleTimeoutSeconds;
    private final ConcurrentMap<SocketAddress, Channel> activeChannels = new ConcurrentHashMap<>();

    private RawDataChannel rawDataChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public TcpServerConnector(InetSocketAddress localAddress, int idleTimeout, int numberOfThreads) {
        this.numberOfThreads = numberOfThreads;
        this.connectionIdleTimeoutSeconds = idleTimeout;
        this.localAddress = localAddress;
    }

    @Override
    public void start() throws IOException {
        if (rawDataChannel == null) {
            throw new IllegalStateException("Cannot start without message handler.");
        }
        if (bossGroup != null) {
            throw new IllegalStateException("Connector already started");
        }
        if (workerGroup != null) {
            throw new IllegalStateException("Connector already started");
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(numberOfThreads);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 100)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelRegistry());

        // Start the server.
        bootstrap.bind(localAddress).syncUninterruptibly();
    }

    @Override
    public void stop() {
        bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();

        workerGroup = null;
        bossGroup = null;
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void send(RawData msg) {
        Channel channel = activeChannels.get(msg.getInetSocketAddress());
        if (channel == null) {
            // TODO: Is it worth allowing opening a new connection when in server mode?
            LOGGER.log(Level.WARNING,
                    "Attempting to send message to an address without an active connection {0}", msg.getAddress());
            return;
        }

        channel.writeAndFlush(Unpooled.wrappedBuffer(msg.getBytes()));
    }

    @Override
    public void setRawDataReceiver(RawDataChannel messageHandler) {
        if (rawDataChannel != null) {
            throw new IllegalStateException("RawDataChannel alrady set");
        }

        this.rawDataChannel = messageHandler;
    }

    @Override
    public InetSocketAddress getAddress() {
        return localAddress;
    }

    private class ChannelRegistry extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            // Handler order:
            // 0. Register/unregister new channel: all messages can only be sent over open connections.
            // 1. Generate Idle events
            // 2. Close idle channels.
            // 3. Stream-to-message decoder
            // 4. Hand-off decoded messages to CoAP stack
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    activeChannels.put(ctx.channel().remoteAddress(), ctx.channel());
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    activeChannels.remove(ctx.channel().remoteAddress());
                }
            });
            ch.pipeline().addLast(new IdleStateHandler(0, 0, connectionIdleTimeoutSeconds));
            ch.pipeline().addLast(new ChannelDuplexHandler() {
                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof IdleStateEvent) {
                        ctx.close();
                    }
                }
            });
            ch.pipeline().addLast(new DatagramFramer());
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    rawDataChannel.receiveData((RawData) msg);
                }
            });
        }
    }

    @Override
    public boolean isTcp() {
        return true;
    }
}