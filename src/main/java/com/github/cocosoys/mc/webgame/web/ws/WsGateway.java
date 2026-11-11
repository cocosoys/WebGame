package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebGame 同端口 WS 隧道网关（M2）。
 *
 * <p>与 SOYSHTTPOverMC 的嗅探器共存于同一 Spigot 监听端口：
 * 本网关把 {@code ParentInjector} 插入父 Channel pipeline 最前（SOYS 之前），
 * 子连接先经 {@link WsSnifferHandler} 分流——仅 {@code Upgrade: websocket} 且路径为
 * {@code /eagler} 的连接被 WebGame 接管（EaglerX 握手 + MC 回环），其余流量放行
 * 给 SOYSHTTPOverMC 与 Spigot MC 解码器，互不干扰。</p>
 */
public final class WsGateway {

    private final JavaPlugin plugin;
    private final String wsPath;
    private final String loopbackHost;
    private final int loopbackPort;
    private final boolean allowV3;
    private final boolean allowV4;
    private final int minMcProtocol;
    private final int maxMcProtocol;
    private final String serverBrand;
    private final String serverVersion;
    private final int maxConnectionsPerDevice;

    private final NioEventLoopGroup loopbackGroup;
    private final List<Channel> installedParents = new ArrayList<>();
    private final Map<Channel, WsFrameHandler> sessions = new ConcurrentHashMap<>();
    /** 设备（来源 IP）当前活跃游戏会话计数，用于 max-connections-per-device 限制。 */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> deviceSlots = new ConcurrentHashMap<>();

    private static final AtomicInteger GROUP_SEQ = new AtomicInteger();

    public WsGateway(JavaPlugin plugin, String wsPath, String loopbackHost, int loopbackPort,
                     boolean allowV3, boolean allowV4, int minMcProtocol, int maxMcProtocol,
                     String serverBrand, String serverVersion, int maxConnectionsPerDevice) {
        this.plugin = plugin;
        this.wsPath = wsPath;
        this.loopbackHost = loopbackHost;
        this.loopbackPort = loopbackPort;
        this.allowV3 = allowV3;
        this.allowV4 = allowV4;
        this.minMcProtocol = minMcProtocol;
        this.maxMcProtocol = maxMcProtocol;
        this.serverBrand = serverBrand;
        this.serverVersion = serverVersion;
        this.maxConnectionsPerDevice = Math.max(1, maxConnectionsPerDevice);
        // 多会话共享的 IO 线程池：4 线程可支撑多人同时进服/下 chunk
        this.loopbackGroup = new NioEventLoopGroup(4, r -> {
            Thread t = new Thread(r, "WebGame-MC-Loopback-" + GROUP_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void install() {
        try {
            Object serverConnection = getServerConnection();
            if (serverConnection == null) {
                plugin.getLogger().warning("无法获取 ServerConnection，WebGame WS 隧道安装失败");
                return;
            }
            List<ChannelFuture> futures = findChannelFutureList(serverConnection);
            if (futures == null) {
                plugin.getLogger().warning("无法获取监听 channel 列表，WebGame WS 隧道安装失败");
                return;
            }
            int n = 0;
            for (ChannelFuture cf : futures) {
                if (cf == null) {
                    continue;
                }
                Channel parent = cf.channel();
                if (parent == null || !parent.isActive()) {
                    continue;
                }
                ChannelPipeline pipe = parent.pipeline();
                if (pipe.get("webgame-ws-parent") == null) {
                    pipe.addFirst("webgame-ws-parent", new WsParentInjector(this));
                    installedParents.add(parent);
                    n++;
                }
            }
            if (n == 0) {
                plugin.getLogger().warning("未找到活跃监听端口，WebGame WS 隧道未生效");
            } else {
                plugin.getLogger().info("WebGame WS 隧道已安装：端口 " + loopbackPort + "，路径 " + wsPath
                        + "，共 " + n + " 个监听 channel");
            }
        } catch (Throwable t) {
            plugin.getLogger().severe("WebGame WS 隧道安装异常: " + t);
        }
    }

    public void uninstall() {
        for (Channel parent : installedParents) {
            try {
                ChannelPipeline pipe = parent.pipeline();
                io.netty.channel.ChannelHandler h = pipe.get("webgame-ws-parent");
                if (h != null) {
                    pipe.remove(h);
                }
            } catch (Throwable ignored) {
            }
        }
        installedParents.clear();
        for (Channel ch : sessions.keySet()) {
            try {
                ch.close();
            } catch (Throwable ignored) {
            }
        }
        sessions.clear();
        loopbackGroup.shutdownGracefully();
    }

    void removeSession(WsFrameHandler handler) {
        sessions.values().remove(handler);
    }

    /** 同一设备（来源 IP）最多同时进入游戏的浏览器会话数。 */
    public int getMaxConnectionsPerDevice() {
        return maxConnectionsPerDevice;
    }

    /**
     * 为指定来源 IP 尝试占用一个"进入游戏"名额。
     * 已占用数达到上限时返回 false（拒绝进服）。
     */
    public boolean tryAcquireDeviceSlot(String ip) {
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        java.util.concurrent.atomic.AtomicInteger n = deviceSlots.computeIfAbsent(ip,
                k -> new java.util.concurrent.atomic.AtomicInteger());
        int cur;
        while ((cur = n.get()) < maxConnectionsPerDevice) {
            if (n.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
        return false;
    }

    /** 释放该来源 IP 的一个"进入游戏"名额。 */
    public void releaseDeviceSlot(String ip) {
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }
        java.util.concurrent.atomic.AtomicInteger n = deviceSlots.get(ip);
        if (n != null) {
            if (n.decrementAndGet() <= 0) {
                deviceSlots.remove(ip, n);
            }
        }
    }

    // ===== 父 Channel 注入器 =====

    private static final class WsParentInjector extends ChannelInboundHandlerAdapter {
        private final WsGateway gateway;

        WsParentInjector(WsGateway gateway) {
            this.gateway = gateway;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel) {
                Channel child = (Channel) msg;
                // 先让下游（SOYSHTTPOverMC 的 ParentInjector）注入它的 http-over-mc-sniffer，
                // 再把我们的嗅探器插到它之前，确保首包分流优先到达 WebGame。
                ctx.fireChannelRead(msg);
                ChannelPipeline cp = child.pipeline();
                if (cp.get("webgame-ws-sniffer") == null) {
                    if (cp.get("http-over-mc-sniffer") != null) {
                        cp.addBefore("http-over-mc-sniffer", "webgame-ws-sniffer",
                                new WsSnifferHandler(gateway));
                    } else {
                        cp.addFirst("webgame-ws-sniffer", new WsSnifferHandler(gateway));
                    }
                }
                return;
            }
            ctx.fireChannelRead(msg);
        }
    }

    // ===== getters =====

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public String getWsPath() {
        return wsPath;
    }

    public String getLoopbackHost() {
        return loopbackHost;
    }

    public int getLoopbackPort() {
        return loopbackPort;
    }

    public boolean isAllowV3() {
        return allowV3;
    }

    public boolean isAllowV4() {
        return allowV4;
    }

    public int getMinMcProtocol() {
        return minMcProtocol;
    }

    public int getMaxMcProtocol() {
        return maxMcProtocol;
    }

    public String getServerBrand() {
        return serverBrand;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public NioEventLoopGroup getLoopbackGroup() {
        return loopbackGroup;
    }

    // ===== 反射：ServerConnection → 监听 channel 列表（与 SOYS 一致） =====

    private Object getServerConnection() {
        try {
            Object craftServer = Bukkit.getServer();
            Method getServer = craftServer.getClass().getMethod("getServer");
            Object mcServer = getServer.invoke(craftServer);
            Method getServerConnection = mcServer.getClass().getMethod("getServerConnection");
            return getServerConnection.invoke(mcServer);
        } catch (Throwable t) {
            try {
                Object craftServer = Bukkit.getServer();
                Method getServer = craftServer.getClass().getMethod("getServer");
                Object mcServer = getServer.invoke(craftServer);
                Object sc = findServerConnectionField(mcServer);
                if (sc != null) {
                    return sc;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    private static Object findServerConnectionField(Object mcServer) {
        if (mcServer == null) {
            return null;
        }
        Class<?> clazz = mcServer.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(mcServer);
                    if (val != null && val.getClass().getSimpleName().contains("ServerConnection")) {
                        return val;
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> findChannelFutureList(Object serverConnection) {
        if (serverConnection == null) {
            return null;
        }
        Class<?> clazz = serverConnection.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(serverConnection);
                    if (val == null) {
                        continue;
                    }
                    if (val instanceof List) {
                        List<?> list = (List<?>) val;
                        if (!list.isEmpty()) {
                            Object first = list.get(0);
                            if (first instanceof ChannelFuture) {
                                return (List<ChannelFuture>) list;
                            }
                            if (first != null) {
                                String cn = first.getClass().getName();
                                if (cn.contains("ChannelFuture") || cn.contains("ChannelPromise") || cn.contains("Promise")) {
                                    try {
                                        first.getClass().getMethod("channel");
                                        return (List<ChannelFuture>) list;
                                    } catch (NoSuchMethodException ignored) {
                                    }
                                }
                            }
                        }
                    }
                    if (val instanceof Map) {
                        List<ChannelFuture> result = new ArrayList<>();
                        for (Object v : ((Map<?, ?>) val).values()) {
                            if (v instanceof ChannelFuture) {
                                result.add((ChannelFuture) v);
                            }
                        }
                        if (!result.isEmpty()) {
                            return result;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
