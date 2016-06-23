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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.AbstractChannelPoolHandler;
import io.netty.channel.pool.AbstractChannelPoolMap;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.eclipse.californium.elements.Connector;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.RawDataChannel;
import org.eclipse.californium.elements.UDPConnector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TCP client connection is used by CoapEndpoint when instantiated by the CoapClient. Per RFC the client can both
 * send and receive messages, but cannot accept new incoming connections.
 */
public class TcpClientConnector implements Connector {

    private final static Logger LOGGER = Logger.getLogger(UDPConnector.class.getName());

    private final int numberOfThreads;
    private final int connectionIdleTimeoutSeconds;
    private final int connectTimeoutMillis;
    private EventLoopGroup workerGroup;
    private RawDataChannel rawDataChannel;
    private AbstractChannelPoolMap<SocketAddress, ChannelPool> poolMap;

    public TcpClientConnector(int numberOfThreads, int connectTimeoutMillis, int idleTimeout) {
        this.numberOfThreads = numberOfThreads;
        this.connectionIdleTimeoutSeconds = idleTimeout;
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    @Override
    public void start() throws IOException {
        if (rawDataChannel == null) {
            throw new IllegalStateException("Cannot start without message handler.");
        }

        if (workerGroup != null) {
            throw new IllegalStateException("Connector already started");
        }

        workerGroup = new NioEventLoopGroup(numberOfThreads);
        poolMap = new AbstractChannelPoolMap<SocketAddress, ChannelPool>() {
            @Override
            protected ChannelPool newPool(SocketAddress key) {
                Bootstrap bootstrap = new Bootstrap()
                        .group(workerGroup)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.SO_KEEPALIVE, true)
                        .option(ChannelOption.AUTO_READ, true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                        .remoteAddress(key);

                // We multiplex over the same TCP connection, so don't acquire more than one connection per endpoint.
                // TODO: But perhaps we could make it a configurable property.
                return new FixedChannelPool(bootstrap, new MyChannelPoolHandler(key), 1);
            }
        };
    }

    @Override
    public void stop() {
        workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        workerGroup = null;
    }

    @Override
    public void destroy() {
        stop();
    }

    @Override
    public void send(final RawData msg) {
        final ChannelPool channelPool = poolMap.get(new InetSocketAddress(msg.getAddress(), msg.getPort()));
        Future<Channel> acquire = channelPool.acquire();
        acquire.addListener(new GenericFutureListener<Future<Channel>>() {
            @Override
            public void operationComplete(Future<Channel> future) throws Exception {
                if (future.isSuccess()) {
                    Channel channel = future.getNow();
                    try {
                        channel.writeAndFlush(Unpooled.wrappedBuffer(msg.getBytes()));
                    } finally {
                        channelPool.release(channel);
                    }
                } else {
                    LOGGER.log(Level.WARNING, "Unable to open connection to " + msg.getAddress(), future.cause());
                }
            }
        });
    }

    @Override
    public void setRawDataReceiver(RawDataChannel messageHandler) {
        if (rawDataChannel != null) {
            throw new IllegalStateException("Raw data channel already set.");
        }

        this.rawDataChannel = messageHandler;
    }

    @Override
    public InetSocketAddress getAddress() {
        // Client TCP connector doesn't really have an address it binds to.
        return new InetSocketAddress(0);
    }

    private class MyChannelPoolHandler extends AbstractChannelPoolHandler {

        private final SocketAddress key;

        MyChannelPoolHandler(SocketAddress key) {
            this.key = key;
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
            // Handler order:
            // 1. Generate Idle events
            // 2. Remove (and close) endpoint pools when idle
            // 3. Stream-to-message decoder
            // 4. Hand-off decoded messages to CoAP stack
            ch.pipeline().addLast(new IdleStateHandler(0, 0, connectionIdleTimeoutSeconds));
            ch.pipeline().addLast(new ChannelDuplexHandler() {
                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof IdleStateEvent) {
                        // TODO: Small risk of race condition here if pool is being removed while new data is being
                        // sent or received. The race would cause a send to fail.
                        poolMap.remove(key);
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