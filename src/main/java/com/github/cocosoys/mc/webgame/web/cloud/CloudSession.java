package com.github.cocosoys.mc.webgame.web.cloud;

import io.netty.channel.Channel;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个云游戏会话（路线 C 黑盒模型）：持有一条浏览器 WS 信令通道 + 一个实例黑盒。
 *
 * <p>生命周期：{@link CloudSessionManager#create} 建立并下发 SPAWN → 执行面起实例 →
 * READY 上报后本会话把 KasmVNC 采集端点 URL 下发给浏览器 → 浏览器跳转直连执行面
 * （v1 媒体/输入直连；S2 由插件 25574 反代）→ {@link #close()} 销毁（断线 / 心跳超时 /
 * 设备退出 / 实例停止）。</p>
 *
 * <p>发送线程安全：心跳调度与实例就绪回调均可调 {@link #sendBinary}。</p>
 */
public final class CloudSession {

    private final JavaPlugin plugin;
    private final CloudSessionManager manager;
    private final String id;
    private final String username;
    private final String deviceIp;
    private volatile io.netty.channel.ChannelHandlerContext ctx;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean detached = new AtomicBoolean(false);
    private volatile long detachedAt;

    /** 实例黑盒（经管控契约由执行面驱动；就绪后持有采集端点）。 */
    private volatile com.github.cocosoys.mc.webgame.control.InstanceHandle instance;
    private volatile long lastActivity = System.currentTimeMillis();

    /** 浏览器请求的容器分辨率（0 = 使用服务端 config cloud.scale）。 */
    private final int reqWidth;
    private final int reqHeight;

    CloudSession(JavaPlugin plugin, CloudSessionManager manager, String id,
                 String username, String deviceIp, io.netty.channel.ChannelHandlerContext ctx,
                 int reqWidth, int reqHeight) {
        this.plugin = plugin;
        this.manager = manager;
        this.id = id;
        this.username = username;
        this.deviceIp = deviceIp;
        this.ctx = ctx;
        this.reqWidth = Math.max(0, reqWidth);
        this.reqHeight = Math.max(0, reqHeight);
    }

    // ===== getters =====

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    /** 浏览器请求的容器宽度（0 = 使用服务端 config）。 */
    public int getReqWidth() {
        return reqWidth;
    }

    /** 浏览器请求的容器高度（0 = 使用服务端 config）。 */
    public int getReqHeight() {
        return reqHeight;
    }

    public Channel getChannel() {
        return ctx.channel();
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 上行活动（PONG / 输入）时更新失活时间戳。 */
    void touch() {
        lastActivity = System.currentTimeMillis();
    }

    // ===== 断线/恢复（重连机制）=====

    /** 是否处于"连接已断开、实例保留"的悬挂状态。 */
    public boolean isDetached() {
        return detached.get();
    }

    /** 距进入悬挂状态的时间（ms）；未悬挂时返回 0。 */
    public long getDetachedAt() {
        return detached.get() ? detachedAt : 0;
    }

    /**
     * 连接断开：进入悬挂状态（实例保留，宽限期内可凭 sessionId 恢复）。
     * 不主动关闭 channel——连接已断开，由 Netty 自行处理；仅当该连接仍是
     * 本会话当前绑定连接时才生效（旧连接被新连接取代后断开则忽略）。
     */
    void detachIfMine(io.netty.channel.ChannelHandlerContext ctx) {
        if (closed.get() || this.ctx != ctx) {
            return; // 已被新连接取代，忽略旧连接的断开
        }
        if (detached.compareAndSet(false, true)) {
            detachedAt = System.currentTimeMillis();
            plugin.getLogger().info("Cloud 会话悬挂 id=" + id + " user=" + username
                    + "（实例保留，宽限期内可恢复）");
        }
    }

    /** 重连恢复：换绑新通道，退出悬挂状态。 */
    void attach(io.netty.channel.ChannelHandlerContext newCtx) {
        this.ctx = newCtx;
        detached.set(false);
        detachedAt = 0;
        lastActivity = System.currentTimeMillis();
        plugin.getLogger().info("Cloud 会话恢复 id=" + id + " user=" + username);
    }

    /** 判断当前绑定连接是否就是给定连接（供旧连接断开时校验）。 */
    boolean isBoundTo(io.netty.channel.ChannelHandlerContext ctx) {
        return this.ctx == ctx;
    }

    /** 会话关联的实例黑盒。 */
    public com.github.cocosoys.mc.webgame.control.InstanceHandle getInstance() {
        return instance;
    }

    void setInstance(com.github.cocosoys.mc.webgame.control.InstanceHandle instance) {
        this.instance = instance;
    }

    // ===== 信令下行 =====

    /** 发送一条 cloud 协议二进制消息（自动封装 WS binary 帧）。
     *  必须用 ctx.writeAndFlush：从 handler 位置向上游传播，绕过 craftbukkit
     *  LengthFieldPrepender（channel.writeAndFlush 会从 pipeline 尾部传播，被加 VarInt 前缀）。 */
    void sendBinary(byte[] payload) {
        if (!closed.get() && ctx.channel().isActive()) {
            ctx.writeAndFlush(com.github.cocosoys.mc.webgame.web.ws.EaglerXProtocol.encodeFrame(
                    com.github.cocosoys.mc.webgame.web.ws.EaglerXProtocol.WS_BINARY, payload));
        }
    }

    /** 下发 KasmVNC 采集端点 URL（实例就绪后由管理器调用）。 */
    void sendKasmUrl(String url) {
        sendBinary(CloudProtocol.buildKasmUrl(url));
        plugin.getLogger().info("Cloud 下发采集端点 id=" + id + " user=" + username + " url=" + url);
    }

    void sendPing(long counter) {
        sendBinary(CloudProtocol.buildPing(counter));
    }

    void sendError(String msg) {
        sendBinary(CloudProtocol.buildError(msg));
    }

    // ===== 关闭 =====

    /** 关闭会话：通知管理器释放设备名额并下发 KILL（幂等）。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (ctx.channel().isActive()) {
            try {
                ctx.channel().close();
            } catch (Throwable ignored) {
            }
        }
        manager.onSessionClosed(this);
    }
}
