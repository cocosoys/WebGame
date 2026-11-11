package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.control.ControlClient;
import com.github.cocosoys.mc.webgame.control.ControlMessage;
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
 * 执行面上报驱动（READY → 推流就绪，帧经管控通道上行后转发；STOPPED/FAILED → 关闭会话）。</p>
 */
public final class CloudSessionManager implements InstanceRegistry.Listener, ControlClient.Listener {

    private final JavaPlugin plugin;
    private final WebGameConfig config;
    private final WsGateway gateway;
    private final ControlClient control;
    private final InstanceRegistry registry;
    private final BanStore banStore;

    private final Map<String, CloudSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicLong pingCounter = new AtomicLong();

    /** 执行后端（v0.5 定案：默认 local 插件内 spawn；remote 预留 S5）。 */
    private volatile ExecutorBackend backend;
    /** 镜像仓库（local 执行面选择镜像用）。 */
    private volatile CloudImageManager imageManager;

    private int taskId = -1;

    public CloudSessionManager(JavaPlugin plugin, WebGameConfig config, WsGateway gateway,
                               ControlClient control, InstanceRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.gateway = gateway;
        this.control = control;
        this.registry = registry;
        this.banStore = new BanStore(plugin);
    }

    /** 云游戏封禁库（管理指令 /soyshttp cloudban / cloudunban 读写）。 */
    public BanStore getBanStore() {
        return banStore;
    }

    /** 装配执行后端（local=插件内 spawn；remote=管控契约，S5 预留）。 */
    public void setBackend(ExecutorBackend backend) {
        this.backend = backend;
        if (backend != null) {
            plugin.getLogger().info("WebGame 云游戏执行后端已装配：" + backend.type());
        }
    }

    /** 装配镜像仓库（local 执行面按会话 clientId / 默认镜像选择）。 */
    public void setImageManager(CloudImageManager imageManager) {
        this.imageManager = imageManager;
    }

    JavaPlugin plugin() {
        return plugin;
    }

    /** 供会话访问配置。 */
    WebGameConfig config() {
        return config;
    }

    /** 公开配置访问（管理指令/容量计算用）。 */
    public WebGameConfig configOf() {
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
        // 回收全部实例：本地执行后端统一清理（进程树 + 编码器 + 注入器），
        // 管控契约路径逐实例下发 KILL
        if (backend != null) {
            try {
                backend.closeAll();
            } catch (Throwable ignored) {
            }
        }
        for (InstanceHandle h : registry.all()) {
            if (backend != null) {
                backend.kill(h.getInstanceId());
            } else {
                sendKill(h);
            }
        }
    }

    // ===== 会话创建/销毁 =====

    /**
     * 尝试创建会话：先做设备限流（复用 WsGateway 的进入游戏名额），
     * 再查同名在线玩家，随后登记实例黑盒并下发 SPAWN。失败返回 null
     * （调用方应拒绝连接）。ctx 必须持有：下行发送用 ctx.writeAndFlush。
     *
     * @param reqWidth  浏览器请求的容器宽度（0 = 服务端 config）
     * @param reqHeight 浏览器请求的容器高度（0 = 服务端 config）
     * @param cores     设备型号核数（1-4；0 = 服务端默认 2）
     * @param xmx       设备型号 JVM 堆（空 = 服务端 config）
     * @param fps       设备型号帧率（0 = 服务端 config）
     * @param profileId 设备型号 id（预置 id / custom；仅记录）
     * @param clientId  客户端版本条目 id（cloud.clients；空 = 服务端默认 clients[0]）
     */
    public CloudSession create(io.netty.channel.ChannelHandlerContext ctx, String username, String deviceIp,
                               int reqWidth, int reqHeight, int cores, String xmx, int fps, String profileId,
                               String clientId) {
        // 封禁检查：WebGame 云游戏封禁库（指令 cloudban 维护）
        if (banStore.isBanned(username)) {
            plugin.getLogger().info("Cloud 封禁拒绝 user=" + username + " ip=" + deviceIp
                    + " reason=" + banStore.getBan(username).getReason());
            return null;
        }
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
        // 同名云会话去重：同 user 已有活跃/悬挂会话则拒绝新建（防多实例同 UUID 互踢、
        // display 争抢导致的 SPAWN 循环；同 sid 重连走 resume 不受影响）
        for (CloudSession s : sessions.values()) {
            if (!s.isClosed() && s.getUsername().equalsIgnoreCase(username)) {
                gateway.releaseDeviceSlot(deviceIp);
                plugin.getLogger().info("Cloud 同名会话拒绝 user=" + username + " 已有活跃会话 "
                        + s.getId());
                return null;
            }
        }
        String id = "c" + sessionSeq.incrementAndGet();
        CloudSession session = new CloudSession(plugin, this, id, username, deviceIp, ctx,
                reqWidth, reqHeight, cores, xmx, fps, profileId, clientId);
        sessions.put(id, session);
        // 登记实例黑盒 + 下发 SPAWN（执行面异步起客户端 + 采集推流）
        String instanceId = "i" + id.substring(1);
        InstanceHandle handle = registry.register(instanceId, username, deviceIp);
        session.setInstance(handle);
        sendSpawn(handle);
        plugin.getLogger().info("Cloud 会话创建 id=" + id + " user=" + username + " ip=" + deviceIp
                + " instance=" + instanceId + " cores=" + session.getCores()
                + " xmx=" + session.getXmx() + " profile=" + session.getProfileId()
                + " client=" + (clientId == null || clientId.isEmpty() ? "(default)" : clientId));
        return session;
    }

    /** 当前已用云游戏实例数（含悬挂但仍占资源的会话）。 */
    public int usedInstances() {
        int n = 0;
        for (CloudSession s : sessions.values()) {
            if (!s.isClosed()) {
                n++;
            }
        }
        return n;
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
     * 则重新启动），READY 后经 {@link #onInstanceReady} 进入推流就绪态——
     * 避免"执行面实例已清理、会话仍残留"时给浏览器一个永远等不到帧的会话。
     *
     * <p>返回 null 表示无可恢复会话（调用方应走 create）。同名在线检查仅对
     * "新建"生效；恢复路径的目标会话就是该用户自己的旧会话。</p>
     */
    public CloudSession resume(io.netty.channel.ChannelHandlerContext ctx, String username,
                               String sessionId, String deviceIp) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        // 封禁检查：已封禁用户不可恢复既有会话（同 create 规则）
        if (banStore.isBanned(username)) {
            plugin.getLogger().info("Cloud 封禁拒绝恢复 user=" + username + " session=" + sessionId);
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

    /** 会话关闭回调（由 CloudSession.close 触发）：释放设备名额、回收实例。 */
    void onSessionClosed(CloudSession session) {
        sessions.remove(session.getId(), session);
        gateway.releaseDeviceSlot(session.getDeviceIp());
        InstanceHandle h = session.getInstance();
        if (h != null) {
            registry.remove(h.getInstanceId());
            if (backend != null) {
                backend.kill(h.getInstanceId());
            } else {
                sendKill(h);
            }
        }
        plugin.getLogger().info("Cloud 会话结束 id=" + session.getId() + " user=" + session.getUsername());
    }

    public CloudSession get(String id) {
        return sessions.get(id);
    }

    public int size() {
        return sessions.size();
    }

    // ===== 管理指令支撑（cloudstatus / cloudkick / cloudban / cloudunban）=====

    /** 全部会话（含悬挂中未关闭的）。 */
    public java.util.List<CloudSession> allSessions() {
        return new java.util.ArrayList<>(sessions.values());
    }

    /** 按用户名（忽略大小写）查找第一个未关闭会话。 */
    public CloudSession findSessionByUser(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        for (CloudSession s : sessions.values()) {
            if (!s.isClosed() && s.getUsername().equalsIgnoreCase(username)) {
                return s;
            }
        }
        return null;
    }

    /** 执行面控制客户端是否已连接（容器机器整体运行状态）。 */
    public boolean isControlConnected() {
        // 本地执行后端：执行面即本插件进程，恒在
        return backend != null || control.isConnected();
    }

    /**
     * 踢出指定用户的云游戏会话：下发错误帧 + 关闭会话（释放设备名额 + KILL 实例）。
     *
     * @return 是否存在并踢出了会话（无会话返回 false）
     */
    public boolean kickUser(String username, String reason) {
        CloudSession s = findSessionByUser(username);
        if (s == null) {
            return false;
        }
        String text = (reason == null || reason.trim().isEmpty())
                ? "你已被管理员踢出" : "你已被踢出：" + reason.trim();
        s.sendError(text);
        s.close();
        plugin.getLogger().info("Cloud 管理员踢出 user=" + username + " session=" + s.getId());
        return true;
    }

    /**
     * 封禁用户：写入 WebGame 封禁库 + 踢出现有会话 + 同步 Bukkit NAME BanList
     * （拦 Eagler 回环登录 / 容器内同名 MC 进服）。
     */
    public void ban(String username, String reason) {
        banStore.ban(username, reason);
        kickUser(username, reason == null ? "已被管理员封禁" : "封禁：" + reason);
        try {
            Bukkit.getBanList(org.bukkit.BanList.Type.NAME)
                    .addBan(username, reason == null ? "WebGame 管理员封禁" : reason,
                            null, "WebGame");
        } catch (Throwable t) {
            plugin.getLogger().warning("同步 Bukkit 封禁失败 user=" + username + ": " + t);
        }
        plugin.getLogger().info("Cloud 管理员封禁 user=" + username);
    }

    /**
     * 解封用户：移除 WebGame 封禁库记录 + 同步解除 Bukkit NAME ban。
     *
     * @return 是否存在 WebGame 封禁记录（无记录返回 false，但仍尝试解 Bukkit ban）
     */
    public boolean unban(String username) {
        boolean removed = banStore.unban(username);
        try {
            Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(username);
        } catch (Throwable t) {
            plugin.getLogger().warning("同步解除 Bukkit 封禁失败 user=" + username + ": " + t);
        }
        if (removed) {
            plugin.getLogger().info("Cloud 管理员解封 user=" + username);
        }
        return removed;
    }

    /** 是否已被 WebGame 云游戏封禁。 */
    public boolean isBanned(String username) {
        return banStore.isBanned(username);
    }

    // ===== 实例黑盒事件（InstanceRegistry.Listener） =====

    @Override
    public void onInstanceReady(InstanceHandle h) {
        CloudSession s = findSession(h);
        if (s == null || s.isClosed()) {
            return;
        }
        // 路线 C：不再下发 KasmVNC 端点 URL——推流走同一 WS 信令通道，
        // 就绪后执行面 FFmpeg 推流帧（0x87/0x88）经 onVideoConfig/onVideoFrame 直达浏览器。
        s.sendReady();
    }

    @Override
    public void onVideoConfig(InstanceHandle h, byte[] data) {
        CloudSession s = findSession(h);
        if (s != null && !s.isClosed()) {
            s.sendConfig(data);
        }
    }

    @Override
    public void onVideoFrame(InstanceHandle h, byte[] data) {
        CloudSession s = findSession(h);
        if (s != null && !s.isClosed()) {
            s.sendVideo(data);
        }
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

    /**
     * S2 反代鉴权已随 KasmVNC 反代一并移除（路线 C 推流走同端口 WS，无独立采集端点）。
     */

    // ===== 管控指令 =====

    private void sendSpawn(InstanceHandle h) {
        // 本地执行后端（v0.5 默认）：插件内 spawn（GameInstance + EncoderProcess + 注入器）
        if (backend != null) {
            localSpawn(h);
            return;
        }
        spawnAttempt(h, 1);
    }

    /**
     * 本地执行面 spawn：按会话 clientId / 默认镜像选择镜像，把会话级参数
     * （xmx/分辨率/fps）传给 {@link ExecutorBackend#spawn}。镜像缺失时直接
     * 报实例失败（浏览器收到明确错误而非无限等待）。
     */
    private void localSpawn(InstanceHandle h) {
        CloudSession s = findSession(h);
        if (s == null || s.isClosed()) {
            return;
        }
        CloudImage img = pickImage(s);
        if (img == null) {
            onInstanceFailed(h, "镜像仓库为空或未找到匹配镜像（请将镜像安装到 "
                    + config.getCloudImagesDir() + "）");
            return;
        }
        int w = s.getReqWidth() > 0 ? s.getReqWidth() : config.getCloudResolutionWidth();
        int rh = s.getReqHeight() > 0 ? s.getReqHeight() : config.getCloudResolutionHeight();
        backend.spawn(s, img, new ExecutorBackend.SpawnParams(
                s.getUsername(), s.getXmx(), w, rh, s.getFps()));
        plugin.getLogger().info("Cloud 本地执行面 spawn instance=" + h.getInstanceId()
                + " user=" + s.getUsername() + " image=" + img.getId()
                + " cores=" + s.getCores() + " xmx=" + s.getXmx()
                + " res=" + w + "x" + rh + " fps=" + s.getFps());
    }

    /** 镜像选择：会话 clientId（cloud.clients 注册表 id）命中优先，否则默认镜像。 */
    private CloudImage pickImage(CloudSession s) {
        CloudImageManager im = imageManager;
        if (im == null) {
            return null;
        }
        String cid = s.getClientId();
        if (cid != null && !cid.isEmpty()) {
            CloudImage hit = im.find(cid);
            if (hit != null) {
                return hit;
            }
        }
        return im.defaultImage();
    }

    // ===== ControlClient.Listener：建连/断连回调 =====

    @Override
    public void onConnected() {
        // 建连/重连成功：补发所有仍处于 SPAWNING 的实例 SPAWN。
        // WSL2 localhost 转发层对 idle 连接约 2 分钟静默掐断（半开），主服
        // writeAndFlush 会计为"假成功"；重连后 1s 窗口内补发成功率最高。
        int n = 0;
        for (InstanceHandle h : registry.all()) {
            if (ControlProtocol.ST_SPAWNING.equals(h.getState())) {
                sendSpawn(h);
                n++;
            }
        }
        if (n > 0) {
            plugin.getLogger().info("WebGame 管控连接已建立，补发 SPAWN x" + n);
        }
    }

    @Override
    public void onDisconnected(String reason) {
        plugin.getLogger().warning("WebGame 管控连接断开: " + reason);
    }

    @Override
    public void onMessage(ControlMessage msg) {
        // 上行消息统一由 InstanceRegistry 路由（实例帧/机器快照），此处不重复处理
    }

    /** 请求执行面上报状态（含机器快照：procs/mem/disk/load/ccPid），供管理员面板。 */
    public void requestStatus() {
        if (backend != null) {
            backend.reportStatus();
            return;
        }
        try {
            control.send(ControlProtocol.C_STATUS, new java.util.LinkedHashMap<String, String>());
        } catch (Exception e) {
            plugin.getLogger().warning("Cloud 请求状态失败: " + e.getMessage());
        }
    }

    /**
     * 转发浏览器输入事件到执行面（路线 C 输入隧道）。
     * payload 格式：[u8 idLen][instanceId utf8][Cloud 输入事件流（u16 count + events）]。
     */
    public void forwardInput(String instanceId, byte[] eventsBody) {
        if (instanceId == null || instanceId.isEmpty() || eventsBody == null || eventsBody.length == 0) {
            return;
        }
        // 本地执行后端：直接投递注入器（javaagent TCP 通道）
        if (backend != null) {
            backend.forwardInput(instanceId, eventsBody);
            return;
        }
        byte[] idBytes = instanceId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (idBytes.length > 255) {
            return;
        }
        byte[] payload = new byte[1 + idBytes.length + eventsBody.length];
        payload[0] = (byte) idBytes.length;
        System.arraycopy(idBytes, 0, payload, 1, idBytes.length);
        System.arraycopy(eventsBody, 0, payload, 1 + idBytes.length, eventsBody.length);
        try {
            control.sendBinary(ControlProtocol.C_INPUT, payload);
        } catch (Exception e) {
            plugin.getLogger().warning("Cloud 输入转发失败: " + e.getMessage());
        }
    }

    /**
     * SPAWN 下发（带持续重试）：执行面（WSL cc）可能正处重启/断连窗口（WSL 崩溃
     * 重启可能需数分钟），发送失败后按 2/4/6/…/30s 退避持续重试（会话存活期间
     * 最多 30 次，约 10 分钟），cc 恢复后实例即可启动；resume 路径复用同一机制（幂等）。
     */
    private void spawnAttempt(InstanceHandle h, int attempt) {
        if (attempt > 30) {
            CloudSession s = findSession(h);
            if (s != null && !s.isClosed()) {
                s.sendError("执行面长时间未就绪，实例启动失败，请重新连接");
            }
            return;
        }
        Map<String, String> f = buildSpawnFields(h);
        if (control.send(ControlProtocol.C_SPAWN, f)) {
            // 发送"成功"：8s 后检查是否被 cc ACK（SPAWNING→STARTING/FAILED）。
            // 半开连接下 writeAndFlush 可能"假成功"（入队即 true），无 ACK 视为丢失重发。
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                CloudSession s = findSession(h);
                if (s == null || s.isClosed()) {
                    return;
                }
                if (ControlProtocol.ST_SPAWNING.equals(h.getState())) {
                    spawnAttempt(h, attempt + 1);
                }
            }, 8 * 20L);
            return;
        }
        int delaySec = Math.min(attempt * 2, 30);
        if (attempt <= 3 || attempt % 5 == 0) {
            plugin.getLogger().warning("Cloud SPAWN 发送失败（执行面未连接） instance=" + h.getInstanceId()
                    + " user=" + h.getUsername() + "，" + delaySec + "s 后重试 (" + attempt + "/30)");
        }
        final int next = attempt + 1;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            CloudSession s = findSession(h);
            if (s != null && !s.isClosed()) {
                spawnAttempt(h, next);
            }
        }, delaySec * 20L);
    }

    private Map<String, String> buildSpawnFields(InstanceHandle h) {
        // 会话级设备型号参数（cores/xmx/fps），缺省用服务端 config
        CloudSession s = sessions.get(h.getInstanceId().startsWith("i")
                ? "c" + h.getInstanceId().substring(1) : "");
        int cores = s != null ? s.getCores() : 2;
        String xmx = s != null ? s.getXmx() : config.getClientXmx();
        int fps = s != null ? s.getFps() : config.getCloudFps();
        // 客户端版本条目：会话 ?client=xxx 指定；未命中/缺省用 clients[0]
        WebGameConfig.ClientSpec spec = null;
        if (s != null) {
            spec = config.findClientSpec(s.getClientId());
        }
        if (spec == null) {
            spec = config.defaultClientSpec();
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("instanceId", h.getInstanceId());
        f.put("username", h.getUsername());
        f.put("server", (spec != null && !spec.getServer().isEmpty())
                ? spec.getServer()
                : (config.getClientServer().isEmpty()
                        ? config.getLoopbackHost() : config.getClientServer()));
        int port = (spec != null && spec.getPort() > 0)
                ? spec.getPort() : config.getClientPort();
        f.put("port", String.valueOf(port > 0 ? port : Bukkit.getServer().getPort()));
        f.put("display", "");
        f.put("cores", String.valueOf(cores));
        f.put("xmx", xmx);
        f.put("scale", config.getCloudScale());
        f.put("fps", String.valueOf(fps));
        f.put("bitrate", String.valueOf(config.getCloudBitrateKbps()));
        f.put("clientDir", spec != null ? spec.getBaseDir() : config.getClientBaseDir());
        // 客户端版本信息（执行面据此选 launch 模板 + Java）
        f.put("versionId", spec != null ? spec.getVersion() : config.getClientVersion());
        f.put("javaHome", spec != null ? spec.getJavaHome() : config.getClientJavaPath());
        f.put("launchStyle", spec != null ? spec.getLaunchStyle() : "legacy");
        // 容器分辨率：浏览器请求（reqWidth/reqHeight）优先，否则用服务端 cloud.scale
        int rw = 0, rh = 0;
        if (s != null) {
            rw = s.getReqWidth();
            rh = s.getReqHeight();
        }
        f.put("width", String.valueOf(rw > 0 ? rw : config.getCloudResolutionWidth()));
        f.put("height", String.valueOf(rh > 0 ? rh : config.getCloudResolutionHeight()));
        return f;
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
