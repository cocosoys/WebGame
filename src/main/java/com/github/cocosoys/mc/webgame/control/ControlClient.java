package com.github.cocosoys.mc.webgame.control;

import io.netty.bootstrap.ServerBootstrap;
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
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管控契约服务端（插件侧）：WSL 执行面控制客户端主动连接（TCP client）。
 *
 * <p>架构：WSL2 localhost 转发层对「Windows 发起」的连接写方向不稳定（实测
 * 写即断），而对「WSL 发起」的连接双向稳定（实测 144s/139 帧 100%）。
 * 故插件侧作为 TCP 服务端监听 {@code control.port}，执行面 cc 主动连入；
 * 插件接受连接后按需下发 SPAWN/KILL/STATUS/CONFIG，执行面回报
 * READY/STOPPED/STATUS/视频帧。断线由 cc 侧重连，插件侧保持监听。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>监听管控端口，接受执行面控制客户端连接（单连接，新连接顶旧）；</li>
 *   <li>发送下行指令（SPAWN/KILL/STATUS/CONFIG/PING）；</li>
 *   <li>接收上行上报（SPAWN_ACK/READY/STOPPED/STATUS_RPT/PONG/ERROR/视频帧），
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
    private volatile Listener listener;
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

    /** 异步启动：监听管控端口，接受执行面连接（非阻塞）。 */
    public void start() {
        group = new NioEventLoopGroup(1, r -> {
            Thread t = new Thread(r, "WebGame-Control-IO");
            t.setDaemon(true);
            return t;
        });
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 8)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(
                                    1 << 20, 0, 4, 1, 4));
                            ch.pipeline().addLast(new ControlFrameHandler());
                        }
                    });
            ChannelFuture f = b.bind(port).sync();
            plugin.getLogger().info("WebGame 管控服务端监听: 0.0.0.0:" + port
                    + "（等待执行面控制客户端连接）");
            f.channel().closeFuture().addListener(future ->
                    plugin.getLogger().warning("WebGame 管控服务端停止监听: " + port));
        } catch (Throwable t) {
            plugin.getLogger().warning("WebGame 管控服务端启动失败: " + t);
        }
    }

    /** 允许运行时替换监听者（建连/断连回调，如补发 SPAWN）。 */
    public void setListener(Listener listener) {
        this.listener = listener;
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

    /** 发送二进制下行帧（输入事件转发到执行面）。 */
    public boolean sendBinary(int type, byte[] payload) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return false;
        }
        try {
            ch.writeAndFlush(Unpooled.wrappedBuffer(ControlProtocol.encodeBinary(type, payload)));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean send(int type, String k1, String v1) {
        return send(type, java.util.Collections.singletonMap(k1, v1));
    }

    // ================= 管控心跳（保活 + 半开检测） =================
    // 主服每 10s 发 C_PING；cc 收到后回 S_PONG。若 45s 未收到任何上行帧
    // （PONG 或业务帧），判定连接半开/死（WSL2 localhost 转发可能产生
    // 单向死连接），主动断开触发重连，避免 SPAWN 等指令发到幽灵连接上静默丢失。

    private volatile long lastUp = 0L;

    void onAnyUp() {
        lastUp = System.currentTimeMillis();
    }

    private void heartbeat() {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return;
        }
        send(ControlProtocol.C_PING, "ts", String.valueOf(System.currentTimeMillis()));
        long idle = System.currentTimeMillis() - lastUp;
        if (idle > 45000L) {
            plugin.getLogger().warning("WebGame 管控连接 45s 无上行帧（疑似半开），主动断开重连");
            ch.close();
        }
    }

    // ================= 帧处理器 =================

    private final class ControlFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel newCh = ctx.channel();
            Channel old = channel;
            // 单连接模式：新连接顶掉旧连接（WSL 转发层可能残留半开连接）
            if (old != null && old != newCh) {
                try {
                    old.close();
                } catch (Throwable ignored) {
                }
            }
            channel = newCh;
            connected = true;
            plugin.getLogger().info("WebGame 执行面管控连接已建立: "
                    + ctx.channel().remoteAddress());
            if (listener != null) {
                try {
                    listener.onConnected();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (channel == ctx.channel()) {
                connected = false;
                channel = null;
                plugin.getLogger().warning("WebGame 执行面管控连接断开: "
                        + ctx.channel().remoteAddress() + "（等待重连）");
                if (listener != null) {
                    try {
                        listener.onDisconnected("连接断开");
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

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
            // 任何上行帧都视为连接存活（半开检测依据）
            onAnyUp();
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
