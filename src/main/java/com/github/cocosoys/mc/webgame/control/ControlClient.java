package com.github.cocosoys.mc.webgame.control;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管控契约客户端（插件侧）：连接控制客户端（执行面）的 TCP 服务端。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启动即连接，断线后指数退避自动重连（max 30s）；</li>
 *   <li>发送下行指令（SPAWN/KILL/STATUS/CONFIG/PING）；</li>
 *   <li>接收上行上报（SPAWN_ACK/READY/STOPPED/STATUS_RPT/PONG/ERROR），
 *       按 instanceId 路由到 {@link InstanceRegistry}。</li>
 * </ul>
 */
public final class ControlClient {

    /** 控制面与执行面的事件回调。 */
    public interface Listener {
        /** 连接建立（含重连成功）。 */
        void onConnected();

        /** 连接断开。 */
        void onDisconnected(String reason);

        /** 收到上行消息（实例无关的通用上报，含 ERROR）。 */
        void onMessage(ControlMessage msg);
    }

    private final JavaPlugin plugin;
    private final String host;
    private final int port;
    private final Listener listener;
    private final InstanceRegistry registry;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private EventLoopGroup group;
    private volatile Channel channel;
    private volatile boolean connected;

    public ControlClient(JavaPlugin plugin, String host, int port,
                         InstanceRegistry registry, Listener listener) {
        this.plugin = plugin;
        this.host = host;
        this.port = port;
        this.registry = registry;
        this.listener = listener;
    }

    /** 异步启动：建连接并保持重连（非阻塞）。 */
    public void start() {
        group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "WebGame-Control-IO");
            t.setDaemon(true);
            return t;
        });
        // 连接循环跑在独立线程：不能在 EventLoop 线程内做 connect().sync() 等阻塞操作
        Thread t = new Thread(this::connectLoop, "WebGame-Control-Connector");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped.set(true);
        Channel ch = channel;
        channel = null;
        if (ch != null) {
            ch.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    public boolean isConnected() {
        return connected && channel != null && channel.isActive();
    }

    /** 发送下行指令（线程安全）。返回是否发送成功。 */
    public boolean send(int type, Map<String, String> fields) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return false;
        }
        try {
            ch.writeAndFlush(Unpooled.wrappedBuffer(ControlProtocol.encode(type, fields)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean send(int type, String k1, String v1) {
        return send(type, java.util.Collections.singletonMap(k1, v1));
    }

    // ================= 连接循环 =================

    private void connectLoop() {
        long backoff = 1000L;
        while (!stopped.get()) {
            Channel ch = null;
            try {
                Bootstrap b = new Bootstrap();
                b.group(group)
                        .channel(NioSocketChannel.class)
                        .option(ChannelOption.TCP_NODELAY, true)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                        .handler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                        1 << 20, 0, 4, 1, 4));
                                ch.pipeline().addLast(new ControlFrameHandler());
                            }
                        });
                ChannelFuture f = b.connect(host, port).sync();
                ch = f.channel();
                channel = ch;
                connected = true;
                backoff = 1000L;
                plugin.getLogger().info("WebGame 管控连接已建立: " + host + ":" + port);
                if (listener != null) {
                    listener.onConnected();
                }
                ch.closeFuture().sync();
            } catch (Throwable t) {
                // 连接失败或断开：退避重连
                if (ch != null) {
                    try {
                        ch.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            connected = false;
            channel = null;
            if (listener != null) {
                listener.onDisconnected("连接断开");
            }
            if (stopped.get()) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 30000L);
        }
    }

    // ================= 帧处理器 =================

    private final class ControlFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            // LengthFieldBasedFrameDecoder 已剥 4 字节长度；帧剩余 = [u8 type][payload]
            byte[] body = new byte[frame.readableBytes()];
            frame.readBytes(body);
            ControlMessage msg = ControlProtocol.parse(body);
            if (msg == null) {
                return;
            }
            try {
                registry.onControlMessage(msg);
            } catch (Throwable t) {
                plugin.getLogger().warning("管控消息处理异常: " + t);
            }
            if (listener != null) {
                try {
                    listener.onMessage(msg);
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
