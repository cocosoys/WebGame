package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.control.ControlClient;
import com.github.cocosoys.mc.webgame.control.ControlProtocol;
import com.github.cocosoys.mc.webgame.control.InstanceHandle;
import com.github.cocosoys.mc.webgame.control.InstanceRegistry;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 云游戏会话管理器（路线 C 黑盒模型）：会话注册表 + 设备限流（复用
 * {@link WsGateway} 的 max-connections-per-device 名额）+ 心跳/失活调度
 * + 实例黑盒路由（经管控契约驱动执行面）。
 *
 * <p>调度：Bukkit 同步 tick 每 1 秒运行——下发心跳（间隔可配）、检查失活超时
 * （默认 15s 无上行则销毁）。实例生命周期由 {@link InstanceRegistry} 依据
 * 执行面上报驱动（READY → 下发 KasmVNC 端点；STOPPED/FAILED → 关闭会话）。</p>
 */
public final class CloudSessionManager implements InstanceRegistry.Listener {

    private final JavaPlugin plugin;
    private final WebGameConfig config;
    private final WsGateway gateway;
    private final ControlClient control;
    private final InstanceRegistry registry;

    private final Map<String, CloudSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicLong pingCounter = new AtomicLong();

    private int taskId = -1;

    public CloudSessionManager(JavaPlugin plugin, WebGameConfig config, WsGateway gateway,
                               ControlClient control, InstanceRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.gateway = gateway;
        this.control = control;
        this.registry = registry;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    /** 供会话访问配置。 */
    WebGameConfig config() {
        return config;
    }

    /** 启动心跳/失活调度。 */
    public void install() {
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                20L, 20L).getTaskId();
    }

    public void uninstall() {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (CloudSession s : sessions.values()) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        sessions.clear();
        for (InstanceHandle h : registry.all()) {
            sendKill(h);
        }
    }

    // ===== 会话创建/销毁 =====

    /**
     * 尝试创建会话：先做设备限流（复用 WsGateway 的进入游戏名额），
     * 再查同名在线玩家，随后登记实例黑盒并下发 SPAWN。失败返回 null
     * （调用方应拒绝连接）。ctx 必须持有：下行发送用 ctx.writeAndFlush。
     */
    public CloudSession create(io.netty.channel.ChannelHandlerContext ctx, String username, String deviceIp) {
        if (!gateway.tryAcquireDeviceSlot(deviceIp)) {
            plugin.getLogger().info("Cloud 设备限制拒绝 user=" + username + " ip=" + deviceIp
                    + " 超过每设备上限 " + gateway.getMaxConnectionsPerDevice());
            return null;
        }
        // 同名冲突：服务器已有同名在线玩家则拒绝（与 EaglerX 通道同规则）
        if (isNameOnline(username)) {
            gateway.releaseDeviceSlot(deviceIp);
            plugin.getLogger().info("Cloud 同名拒绝 user=" + username + " 该玩家已在线");
            return null;
        }
        String id = "c" + sessionSeq.incrementAndGet();
        CloudSession session = new CloudSession(plugin, this, id, username, deviceIp, ctx);
        sessions.put(id, session);
        // 登记实例黑盒 + 下发 SPAWN（执行面异步起 Xvnc + Forge 客户端）
        String instanceId = "i" + id.substring(1);
        InstanceHandle handle = registry.register(instanceId, username, deviceIp);
        session.setInstance(handle);
        sendSpawn(handle);
        plugin.getLogger().info("Cloud 会话创建 id=" + id + " user=" + username + " ip=" + deviceIp
                + " instance=" + instanceId);
        return session;
    }

    private boolean isNameOnline(String username) {
        try {
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(username)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 尝试恢复会话（重连机制）：凭 sessionId 复用已存在（含悬挂）会话的实例，
     * 换绑新连接并**幂等重发 SPAWN**（实例活着由执行面复用回 READY；实例已死
     * 则重新启动），READY 后经 {@link #onInstanceReady} 下发 KASM_URL——
     * 避免"执行面实例已清理、会话仍残留"时直接下发死端点导致 Bad Gateway。
     *
     * <p>返回 null 表示无可恢复会话（调用方应走 create）。同名在线检查仅对
     * "新建"生效；恢复路径的目标会话就是该用户自己的旧会话。</p>
     */
    public CloudSession resume(io.netty.channel.ChannelHandlerContext ctx, String username,
                               String sessionId, String deviceIp) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        CloudSession s = sessions.get(sessionId);
        if (s == null || s.isClosed()) {
            return null;
        }
        if (!s.getUsername().equalsIgnoreCase(username)) {
            return null; // sessionId 与用户名不匹配，拒绝越权恢复
        }
        // 竞态兜底：旧连接尚未 detach（活跃）时，新连接直接换绑取代（刷新场景），
        // 不销毁实例——旧连接随后断开会被 detachIfMine 忽略。
        s.attach(ctx);
        // 幂等重发 SPAWN（执行面复用或重启实例）；不直接下发旧端点——
        // 旧端点可能已随执行面清理失效。READY 由 onInstanceReady 统一下发。
        InstanceHandle h = s.getInstance();
        if (h != null && !s.isClosed()) {
            sendSpawn(h);
        }
        return s;
    }

    /** 会话关闭回调（由 CloudSession.close 触发）：释放设备名额、下发 KILL。 */
    void onSessionClosed(CloudSession session) {
        sessions.remove(session.getId(), session);
        gateway.releaseDeviceSlot(session.getDeviceIp());
        InstanceHandle h = session.getInstance();
        if (h != null) {
            registry.remove(h.getInstanceId());
            sendKill(h);
        }
        plugin.getLogger().info("Cloud 会话结束 id=" + session.getId() + " user=" + session.getUsername());
    }

    public CloudSession get(String id) {
        return sessions.get(id);
    }

    public int size() {
        return sessions.size();
    }

    // ===== 实例黑盒事件（InstanceRegistry.Listener） =====

    @Override
    public void onInstanceReady(InstanceHandle h) {
        CloudSession s = findSession(h);
        if (s == null || s.isClosed()) {
            return;
        }
        String url = buildKasmUrl(h);
        s.sendKasmUrl(url);
    }

    @Override
    public void onInstanceStopped(InstanceHandle h, String reason) {
        CloudSession s = findSession(h);
        if (s != null && !s.isClosed()) {
            s.sendError("游戏实例已退出");
            s.close();
        }
    }

    @Override
    public void onInstanceFailed(InstanceHandle h, String reason) {
        CloudSession s = findSession(h);
        if (s != null && !s.isClosed()) {
            s.sendError(reason == null ? "游戏实例启动失败" : reason);
            s.close();
        }
    }

    /** 依据实例 id 反查会话（会话 id = c<seq>，实例 id = i<seq>）。
     *  额外校验用户名匹配：执行面实例编号跨服务器重启会复用（旧实例的
     *  READY/STOPPED/FAILED 事件可能迟到），防止旧实例事件误匹配同名序号的新会话。 */
    private CloudSession findSession(InstanceHandle h) {
        String seq = h.getInstanceId().startsWith("i") ? h.getInstanceId().substring(1) : null;
        if (seq == null) {
            return null;
        }
        CloudSession s = sessions.get("c" + seq);
        if (s == null || s.isClosed()) {
            return null;
        }
        if (!s.getUsername().equalsIgnoreCase(h.getUsername())) {
            return null;
        }
        return s;
    }

    /** 构建 KasmVNC 采集端点 URL（S2：插件 25574 反代，浏览器只访问同端口）。 */
    private String buildKasmUrl(InstanceHandle h) {
        CloudSession s = findSession(h);
        String token = s == null ? "" : s.getId();
        String host = config.getPublicHost();
        int port = Bukkit.getServer().getPort();
        int kasmPort = h.getKasmPort() > 0 ? h.getKasmPort() : 8542;
        return "http://" + host + ":" + port + "/kasm/" + token + "/" + kasmPort
                + "/vnc.html?host=" + host + "&port=" + port
                + "&path=kasm/" + token + "/" + kasmPort + "/websockify"
                + "&autoconnect=1&resize=scale&reconnect=1";
    }

    /**
     * S2 反代鉴权：token 必须是活跃云游戏会话 id，且 kasmPort 必须等于
     * 该会话实例上报的采集端口（防 SSRF 与越权访问执行面其它端口）。
     */
    public boolean authorizeKasm(String token, int kasmPort) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        CloudSession s = sessions.get(token);
        if (s == null || s.isClosed()) {
            return false;
        }
        InstanceHandle h = s.getInstance();
        return h != null && h.getKasmPort() == kasmPort;
    }

    // ===== 管控指令 =====

    private void sendSpawn(InstanceHandle h) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("instanceId", h.getInstanceId());
        f.put("username", h.getUsername());
        f.put("server", config.getClientServer().isEmpty()
                ? config.getLoopbackHost() : config.getClientServer());
        f.put("port", String.valueOf(config.getClientPort() > 0
                ? config.getClientPort() : Bukkit.getServer().getPort()));
        f.put("display", "");
        f.put("xmx", config.getClientXmx());
        f.put("scale", config.getCloudScale());
        f.put("fps", String.valueOf(config.getCloudFps()));
        f.put("bitrate", String.valueOf(config.getCloudBitrateKbps()));
        f.put("clientDir", config.getClientBaseDir());
        boolean sent = control.send(ControlProtocol.C_SPAWN, f);
        if (!sent) {
            plugin.getLogger().warning("Cloud SPAWN 发送失败（执行面未连接） instance=" + h.getInstanceId()
                    + " user=" + h.getUsername());
        }
    }

    private void sendKill(InstanceHandle h) {
        control.send(ControlProtocol.C_KILL, "instanceId", h.getInstanceId());
    }

    // ===== 心跳与失活 =====

    private void tick() {
        long now = System.currentTimeMillis();
        int pingInterval = config.getCloudPingIntervalSeconds() * 1000;
        int idleTimeout = config.getCloudIdleTimeoutSeconds() * 1000;
        int graceMs = config.getCloudResumeGraceSeconds() * 1000;
        for (CloudSession s : sessions.values()) {
            if (s.isClosed()) {
                continue;
            }
            // 悬挂会话：宽限期结束仍未恢复 → 真正销毁（释放名额 + KILL 实例）
            if (s.isDetached() && now - s.getDetachedAt() > graceMs) {
                plugin.getLogger().info("Cloud 悬挂超宽限期关闭 id=" + s.getId() + " user=" + s.getUsername());
                s.close();
                continue;
            }
            if (now - s.getLastActivity() > idleTimeout) {
                plugin.getLogger().info("Cloud 失活超时关闭 id=" + s.getId() + " user=" + s.getUsername());
                s.sendError("连接失活，已断开");
                s.close();
                continue;
            }
            if (now - s.getLastActivity() > pingInterval) {
                // 距上次上行超过一个心跳间隔，主动下发 ping 探测
                s.sendPing(pingCounter.incrementAndGet());
            }
        }
    }
}
