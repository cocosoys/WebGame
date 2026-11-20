package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 云游戏“点击即玩”会话服务：一台云主机同一时刻只服务一个玩家（capacity = 1）。
 *
 * <p>职责边界：本类只管会话（占位、释放、看门狗）与"跟云主机上的会话代理说话"，
 * 不碰页面渲染逻辑（页面在 {@code CloudClientPages}）。代理的契约只有三条：
 * {@code /session/prepare}、{@code /session/end}、{@code /session/status}。</p>
 *
 * <p><b>为什么看门狗必须存在</b>：玩家在 MC 里退出后，stream-server 会 stop 游戏并
 * 自动解除桌面锁，但浏览器还连着——只有把会话判死、页面跳回选择页，才不会把
 * 一台已解锁的 Windows 桌面留在玩家面前。</p>
 */
public final class CloudPlayService {

    private static final Logger LOG = Logger.getLogger(CloudPlayService.class.getName());

    private static final Pattern PLAYER_RE = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Pattern STATE_RE = Pattern.compile("\"state\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern OK_RE = Pattern.compile("\"ok\"\\s*:\\s*(true|false)");
    private static final Pattern ERROR_RE = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SESSION_RE = Pattern.compile("\"session\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PLAYER_RE_JSON = Pattern.compile("\"player\"\\s*:\\s*\"([^\"]*)\"");

    private static final long AGENT_TIMEOUT_MS = 20_000L;
    private static final long STATUS_MIN_INTERVAL_MS = 2_000L;

    private final WebGame plugin;
    private final CloudClientService clients;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** clientId → sessionId（槽位占用表，一台机器一个槽）。 */
    private final Map<String, String> slots = new ConcurrentHashMap<>();
    /** clientId → 更新开始时间：包更新是几十秒到几分钟的事，绝不阻塞玩家那次请求。 */
    private final Map<String, Long> updatingClients = new ConcurrentHashMap<>();
    /** clientId → 最近一次更新失败原因（失败后要停下，不然每次点击都重下一遍 200MB）。 */
    private final Map<String, String> updateErrors = new ConcurrentHashMap<>();
    private volatile BukkitTask watchdog;

    /** 一次包更新最多等多久（毫秒）；超时就允许下一次点击重新发起。 */
    private static final long UPDATE_MAX_MS = 15 * 60 * 1000L;

    public CloudPlayService(WebGame plugin, CloudClientService clients) {
        this.plugin = plugin;
        this.clients = clients;
    }

    // ===== 对外 =====

    /** 开始一次会话：校验玩家名 → 找槽位 → 让代理准备客户端并启动游戏。 */
    public AjaxResult start(String clientId, String player, String token) {
        return start(clientId, player, token, "");
    }

    /**
     * 开始一次会话（带门牌别名）。
     *
     * @param alias 玩家进来的门牌（{@code /cloud/<alias>}）；空串表示走内部命名空间路径
     */
    public AjaxResult start(String clientId, String player, String token, String alias) {
        String name = player == null ? "" : player.trim();
        if (!PLAYER_RE.matcher(name).matches()) {
            return AjaxResult.error("游戏名称不合法：3–16 位字母/数字/下划线");
        }
        CloudClient c = findClient(clientId);
        if (c == null) {
            return AjaxResult.error("云游戏客户端不存在");
        }
        if (!c.isEnabled() || c.isMissing()) {
            return AjaxResult.error("该云游戏客户端当前不可用");
        }
        if (!CloudClient.isUrl(c.getPath())) {
            return AjaxResult.error("该客户端不是云游戏设备（本地页面不起会话）");
        }
        String key = token == null ? "" : token.trim();
        if (key.isEmpty() || !key.equals(c.getCloudKey())) {
            return AjaxResult.error("令牌不正确");
        }
        if (c.getIp().isEmpty() || c.getPort() <= 0) {
            return AjaxResult.error("该客户端尚未上报设备地址");
        }
        // 别名校验：门牌必须真的属于这台机器，否则玩家能借 A 的名字进 B 的机器
        String gate = com.github.cocosoys.mc.webgame.shttp.CloudClientPages.normalizeAlias(alias);
        if (!gate.isEmpty()) {
            CloudClient owner = plugin.getCloudClientPages() == null
                    ? null : plugin.getCloudClientPages().resolveAlias(gate);
            if (owner == null) {
                return AjaxResult.error("入口 /cloud/" + gate + " 不存在");
            }
            if (!owner.getId().equals(c.getId())) {
                return AjaxResult.error("入口 /cloud/" + gate + " 与客户端不匹配");
            }
        }

        String busySession = slots.get(c.getId());
        if (busySession != null) {
            return AjaxResult.error("云游戏机占用中（会话 " + busySession + "），请稍后再试");
        }

        // 客户端包版本：只在"真的不一样"时才动文件，而且更新走后台，不占用玩家的这次请求
        String versionCheck = ensureClientVersion(c);
        if ("updating".equals(versionCheck)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("updating", true);
            data.put("retrySeconds", 20);
            data.put("clientId", c.getId());
            return AjaxResult.success("正在更新云游戏客户端，完成后会自动继续", data);
        }
        if (versionCheck.startsWith("err:")) {
            return AjaxResult.error(versionCheck.substring(4));
        }

        String sessionId = "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        // 先占位再准备：两个请求同时进来时，第二个必须在准备之前就被拒掉
        slots.put(c.getId(), sessionId);
        Session s = new Session();
        s.sessionId = sessionId;
        s.clientId = c.getId();
        s.player = name;
        s.token = key;
        s.agentBase = agentBase(c);
        s.state = Session.STATE_PREPARING;
        s.createdAt = System.currentTimeMillis();
        s.lastHeartbeatAt = s.createdAt;
        sessions.put(sessionId, s);

        try {
            // 客户端版本是唯一的更新触发器：代理只在"我这儿版本不对"时才去下载解包，
            // 从不主动更新（包由 WebGame 托管，地址与 sha256 一并下发，代理据此校验）
            String wantVersion = "";
            String pkgUrl = "";
            String pkgSha = "";
            if (plugin.getCloudClientPages() != null) {
                wantVersion = plugin.getCloudClientPages().clientPackageVersion();
                if (clientUpdateEnabled() && !wantVersion.isEmpty()) {
                    pkgUrl = plugin.getCloudClientPages().clientPackageUrl();
                    pkgSha = plugin.getCloudClientPages().clientPackageSha();
                }
            }
            String body = "{\"sessionId\":\"" + sessionId + "\",\"player\":\"" + name + "\""
                    + ",\"clientVersion\":\"" + jsonEscape(wantVersion) + "\""
                    + ",\"clientUrl\":\"" + jsonEscape(pkgUrl) + "\""
                    + ",\"clientSha256\":\"" + jsonEscape(pkgSha) + "\"}";
            String resp = http(s.agentBase + "/session/prepare", "POST", body, key, 120_000);
            if (!ok(resp)) {
                slots.remove(c.getId(), sessionId);
                sessions.remove(sessionId);
                return AjaxResult.error("会话准备失败：" + errorOf(resp));
            }
            s.state = Session.STATE_RUNNING;
            s.gameState = "running";
            s.lastPollAt = System.currentTimeMillis();
            // 开闸：直播前缀只在会话期存在；开不出来就别把玩家放进包装页
            boolean opened = true;
            try {
                opened = plugin.getCloudClientPages() != null && plugin.getCloudClientPages().openLive(c.getId());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "开闸失败 " + s.sessionId + ": " + t);
                opened = false;
            }
            if (!opened) {
                finish(s, "live-gate-failed");
                return AjaxResult.error("直播通道不可用（同端口转播未启用，检查 cloud.same-port-relay）");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("clientId", c.getId());
            data.put("player", name);
            data.put("playUrl", playUrl(c.getId(), sessionId, gate));
            data.put("liveUrl", liveUrl(c.getId(), key, gate));
            return AjaxResult.success("云游戏会话已开始", data);
        } catch (Throwable t) {
            slots.remove(c.getId(), sessionId);
            sessions.remove(sessionId);
            LOG.log(Level.WARNING, "会话准备失败 " + sessionId + ": " + t);
            return AjaxResult.error("会话准备失败：" + t.getMessage());
        }
    }

    /**
     * 客户端包版本闸门。
     *
     * <p>返回三种结果：{@code "ok"}（版本一致或该功能关闭）、{@code "updating"}（后台正在下载/解包）、
     * {@code "err:原因"}（做不到，必须告诉玩家）。注意：<b>更新不能同步阻塞玩家的请求</b>——
     * SOYS 的 HTTP 管线撑不住几十秒不响应的请求（实测 30 秒级会被直接断掉），
     * 所以这里只负责"发起"，下载放在后台任务里跑。</p>
     */
    private String ensureClientVersion(CloudClient c) {
        if (!clientUpdateEnabled()) {
            return "ok";
        }
        String want = plugin.getCloudClientPages() == null ? "" : plugin.getCloudClientPages().clientPackageVersion();
        if (want.isEmpty()) {
            return "ok"; // 没配版本号 = 不校验
        }
        Long started = updatingClients.get(c.getId());
        if (started != null) {
            if (System.currentTimeMillis() - started < UPDATE_MAX_MS) {
                return "updating";
            }
            updatingClients.remove(c.getId());
        }
        String err = updateErrors.get(c.getId());
        if (err != null) {
            return "err:" + err;
        }
        String local = verifyClientVersion(c, want);
        if (local == null) {
            // 代理没回话：不拦人，进到 prepare 才知道真相；真有问题那里会报
            return "ok";
        }
        if (local.equals(want)) {
            return "ok";
        }
        String url = plugin.getCloudClientPages() == null ? "" : plugin.getCloudClientPages().clientPackageUrl();
        if (url.isEmpty()) {
            return "err:客户端版本不符（期望 " + want + "，实际 " + (local.isEmpty() ? "未知" : local)
                    + "），且 config.yml 没配 cloud.client-update.base-url";
        }
        startAsyncUpdate(c, want, url);
        return "updating";
    }

    /** 读代理的本地版本；请求失败返回 null（与"版本是空串"区分开）。 */
    private String verifyClientVersion(CloudClient c, String want) {
        try {
            String resp = http(agentBase(c) + "/client/verify?version=" + URLEncoder.encode(want, "UTF-8"),
                    "GET", null, c.getCloudKey(), 8_000L);
            Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"").matcher(resp);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "版本校验失败 " + c.getId() + ": " + t.getMessage());
            return null;
        }
    }

    /** 后台更新：下载 → 校验 → 解包 → 换目录，全程不碰玩家的请求线程。 */
    private void startAsyncUpdate(CloudClient c, String want, String url) {
        final String clientId = c.getId();
        updatingClients.put(clientId, System.currentTimeMillis());
        String sha = plugin.getCloudClientPages() == null ? "" : plugin.getCloudClientPages().clientPackageSha();
        final String token = c.getCloudKey();
        final String base = agentBase(c);
        final String body = "{\"url\":\"" + jsonEscape(url) + "\",\"sha256\":\"" + jsonEscape(sha)
                + "\",\"version\":\"" + jsonEscape(want) + "\"}";
        LOG.info("客户端 " + clientId + " 版本不符，开始后台更新到 " + want + "：" + url);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String resp = http(base + "/client/redeploy", "POST", body, token, 900_000L);
                if (!ok(resp)) {
                    throw new RuntimeException(errorOf(resp));
                }
                updateErrors.remove(clientId);
                LOG.info("客户端 " + clientId + " 更新完成：" + resp);
            } catch (Throwable t) {
                String msg = "客户端包更新失败：" + t.getMessage();
                updateErrors.put(clientId, msg);
                LOG.log(Level.WARNING, "客户端 " + clientId + " " + msg);
            } finally {
                updatingClients.remove(clientId);
            }
        });
    }

    /** 当前该客户端是否正在更新（ERP/状态展示可用）。 */
    public boolean isUpdating(String clientId) {
        return updatingClients.containsKey(clientId);
    }

    /** 会话状态：浏览器侧的包装页靠它决定"什么时候跳回选择页"。 */
    public AjaxResult status(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("state", "ended");
            data.put("reason", "not-found");
            return AjaxResult.success("会话不存在", data);
        }
        refresh(s, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", s.state);
        data.put("gameState", s.gameState);
        data.put("player", s.player);
        data.put("clientId", s.clientId);
        return AjaxResult.success("ok", data);
    }

    /** 主动结束会话（玩家点“结束”或看门狗判定游戏已退出）。 */
    public AjaxResult end(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null) {
            return AjaxResult.success("会话已结束", null);
        }
        finish(s, "requested");
        return AjaxResult.success("会话已结束", null);
    }

    /**
     * 浏览器心跳：包装页每 10 秒（{@code cloud.heartbeat.interval-seconds}）打一次。
     *
     * <p>玩家直接关掉标签页后，JS 不会发任何"再见"——只有靠"这个会话再没人吭声"来判定。
     * 心跳与状态轮询共用同一个页面，页面被节流时两者一起变慢，因此判离阈值必须留足余量。</p>
     */
    public AjaxResult heartbeat(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (s == null) {
            data.put("state", Session.STATE_ENDED);
            data.put("reason", "not-found");
            return AjaxResult.success("会话不存在", data);
        }
        s.lastHeartbeatAt = System.currentTimeMillis();
        s.heartbeatSeen = true;
        data.put("state", s.state);
        data.put("gameState", s.gameState);
        data.put("player", s.player);
        data.put("clientId", s.clientId);
        return AjaxResult.success("ok", data);
    }

    public void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::watch, 100L, 100L);
    }

    public void stopWatchdog() {
        BukkitTask t = watchdog;
        watchdog = null;
        if (t != null) {
            try {
                t.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 某客户端是否正被占用（ERP/选择页展示用）。 */
    public boolean isBusy(String clientId) {
        return slots.containsKey(clientId);
    }

    // ===== 内部 =====

    private void watch() {
        long now = System.currentTimeMillis();
        long timeoutMs = heartbeatTimeoutSeconds() * 1000L;
        boolean hbOn = heartbeatEnabled();
        for (Session s : sessions.values()) {
            if (!Session.STATE_RUNNING.equals(s.state)) {
                continue;
            }
            refresh(s, false);
            // 游戏已退出：立刻清工作区并释放槽位，页面随后跳回选择页
            if ("stopped".equals(s.gameState)) {
                finish(s, "game-stopped");
                continue;
            }
            // 玩家关掉浏览器：只有"浏览器真的发过心跳"之后才开始计时，
            // 否则刚恢复/刚开始的会话会在页面还没加载时被自己判死。
            if (hbOn && s.heartbeatSeen && now - s.lastHeartbeatAt > timeoutMs) {
                LOG.info("会话 " + s.sessionId + " 已 " + ((now - s.lastHeartbeatAt) / 1000)
                        + " 秒没有心跳，判定玩家已离开");
                finish(s, "browser-left");
            }
        }
    }

    /**
     * 启动对齐：插件重启后内存里的槽位表是空的，但云主机上可能还跑着上一轮的会话。
     * <p>有会话就恢复占位并重新开闸（玩家刷新页面能接着看），没有就清干净；会话在、
     * 游戏已经停了则顺手让代理收尾——否则会出现"机器被占着但页面显示空闲"。</p>
     */
    public void reconcileFromAgents() {
        LOG.info("启动对齐：开始核对 " + clients.versions().size() + " 个云客户端");
        for (CloudClient c : clients.versions()) {
            if (!c.isEnabled() || c.isMissing() || !CloudClient.isUrl(c.getPath())) {
                continue;
            }
            if (c.getIp().isEmpty() || c.getPort() <= 0 || c.getCloudKey().isEmpty()) {
                continue;
            }
            String base = agentBase(c);
            String active = "";
            String gameState = "";
            String player = "";
            try {
                String resp = http(base + "/health", "GET", null, c.getCloudKey(), 5_000L);
                active = fieldOf(resp, SESSION_RE);
                gameState = stateOf(resp);
                player = fieldOf(resp, PLAYER_RE_JSON);
            } catch (Throwable t) {
                LOG.log(Level.FINE, "启动对齐查询 " + c.getId() + " 失败: " + t.getMessage());
                continue;
            }
            try {
                if (active == null || active.isEmpty()) {
                    slots.remove(c.getId());
                    if (plugin.getCloudClientPages() != null) {
                        plugin.getCloudClientPages().closeLive(c.getId());
                    }
                    continue;
                }
                if ("stopped".equals(gameState) || "unknown".equals(gameState)) {
                    // 会话记录还在但游戏已经没了：让代理把残留工作区收掉，别把机器白占着
                    LOG.info("启动对齐：客户端 " + c.getId() + " 残留会话 " + active
                            + "（游戏状态 " + gameState + "），通知代理收尾");
                    try {
                        http(base + "/session/end", "POST", "{}", c.getCloudKey(), 60_000L);
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "启动对齐收尾失败 " + c.getId() + ": " + t.getMessage());
                    }
                    slots.remove(c.getId());
                    if (plugin.getCloudClientPages() != null) {
                        plugin.getCloudClientPages().closeLive(c.getId());
                    }
                    continue;
                }
                Session s = new Session();
                s.sessionId = active;
                s.clientId = c.getId();
                s.player = player == null || player.isEmpty() ? "" : player;
                s.token = c.getCloudKey();
                s.agentBase = base;
                s.state = Session.STATE_RUNNING;
                s.gameState = gameState;
                s.createdAt = System.currentTimeMillis();
                s.lastHeartbeatAt = s.createdAt;
                s.heartbeatSeen = false; // 页面还没连上来，先不按心跳计时
                s.restored = true;
                sessions.put(active, s);
                slots.put(c.getId(), active);
                if (plugin.getCloudClientPages() != null) {
                    try {
                        plugin.getCloudClientPages().openLive(c.getId());
                    } catch (Throwable t) {
                        LOG.log(Level.FINE, "启动对齐开闸失败 " + c.getId() + ": " + t.getMessage());
                    }
                }
                LOG.info("启动对齐：客户端 " + c.getId() + " 恢复会话 " + active
                        + "（玩家 " + s.player + "，游戏 " + gameState + "）");
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "启动对齐处理 " + c.getId() + " 失败: " + t);
            }
        }
    }

    private void refresh(Session s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - s.lastPollAt < STATUS_MIN_INTERVAL_MS) {
            return;
        }
        s.lastPollAt = now;
        try {
            String resp = http(s.agentBase + "/session/status", "GET", null, s.token, AGENT_TIMEOUT_MS);
            String st = stateOf(resp);
            if (st != null && !st.isEmpty()) {
                s.gameState = st;
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "会话状态查询失败 " + s.sessionId + ": " + t.getMessage());
        }
    }

    private void finish(Session s, String reason) {
        if (s == null || Session.STATE_ENDED.equals(s.state)) {
            return;
        }
        s.state = Session.STATE_ENDED;
        s.endReason = reason;
        slots.remove(s.clientId, s.sessionId);
        // 关闸先于收尾：即使代理清理慢，桌面通道也已经对所有人 404 了
        try {
            if (plugin.getCloudClientPages() != null) {
                plugin.getCloudClientPages().closeLive(s.clientId);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "关闸失败 " + s.sessionId + ": " + t.getMessage());
        }
        try {
            http(s.agentBase + "/session/end", "POST", "{}", s.token, 60_000);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "会话收尾失败 " + s.sessionId + ": " + t.getMessage());
        }
    }

    private String agentBase(CloudClient c) {
        int port = 8090;
        try {
            port = plugin.getConfig().getInt("cloud.session-agent-port", 8090);
        } catch (Throwable ignored) {
        }
        return "http://" + c.getIp() + ":" + port;
    }

    // ===== 配置（改 config.yml 即生效，不需要重启） =====

    public boolean heartbeatEnabled() {
        try {
            return plugin.getConfig().getBoolean("cloud.heartbeat.enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 页面心跳间隔（秒）：下发给包装页 JS。 */
    public int heartbeatIntervalSeconds() {
        int v = 10;
        try {
            v = plugin.getConfig().getInt("cloud.heartbeat.interval-seconds", 10);
        } catch (Throwable ignored) {
        }
        return v < 3 ? 3 : (v > 120 ? 120 : v);
    }

    /** 判离阈值（秒）：超过这么久没心跳就当玩家关了浏览器。 */
    public int heartbeatTimeoutSeconds() {
        int v = 30;
        try {
            v = plugin.getConfig().getInt("cloud.heartbeat.timeout-seconds", 30);
        } catch (Throwable ignored) {
        }
        // 必须大于心跳间隔，否则一次抖动就误杀
        int min = heartbeatIntervalSeconds() * 2;
        return v < min ? min : v;
    }

    /** 期望的云游戏客户端版本（prepare 时发给代理做比对，空 = 不比对）。 */
    public String clientVersion() {
        try {
            return plugin.getConfig().getString("cloud.client-update.version", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean clientUpdateEnabled() {
        try {
            return plugin.getConfig().getBoolean("cloud.client-update.enabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "");
    }

    private static String fieldOf(String resp, Pattern p) {
        Matcher m = p.matcher(resp == null ? "" : resp);
        return m.find() ? m.group(1) : "";
    }

    /** 包装页地址：走门牌时是 {@code /cloud/<别名>/play}，否则是内部命名空间路径。 */
    private String playUrl(String clientId, String sessionId, String alias) {
        String base = alias == null || alias.isEmpty()
                ? WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/" + clientId
                : "/cloud/" + alias;
        return base + "/play/?s=" + sessionId;
    }

    private String liveUrl(String clientId, String key, String alias) {
        String base = alias == null || alias.isEmpty()
                ? WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/" + clientId
                : "/cloud/" + alias;
        try {
            return base + "/live/?token=" + URLEncoder.encode(key, "UTF-8");
        } catch (Throwable t) {
            return base + "/live/";
        }
    }

    private CloudClient findClient(String clientId) {
        if (clientId == null) {
            return null;
        }
        for (CloudClient c : clients.versions()) {
            if (c.getId().equals(clientId)) {
                return c;
            }
        }
        return null;
    }

    private static boolean ok(String resp) {
        Matcher m = OK_RE.matcher(resp == null ? "" : resp);
        return m.find() && "true".equals(m.group(1));
    }

    private static String errorOf(String resp) {
        if (resp == null || resp.isEmpty()) {
            return "代理无响应";
        }
        Matcher m = ERROR_RE.matcher(resp);
        return m.find() ? m.group(1) : resp;
    }

    private static String stateOf(String resp) {
        if (resp == null) {
            return null;
        }
        Matcher m = STATE_RE.matcher(resp);
        return m.find() ? m.group(1) : null;
    }

    /** 最小 HTTP 客户端：只给会话代理用，不引入额外依赖。 */
    private static String http(String url, String method, String body, String key, long timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout((int) Math.min(timeoutMs, 15_000L));
            conn.setReadTimeout((int) timeoutMs);
            conn.setRequestProperty("X-Cloud-Key", key == null ? "" : key);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (code >= 400 && (text == null || text.isEmpty())) {
                throw new IllegalException("HTTP " + code);
            }
            return text;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        is.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 内部异常：只为了让 4xx 的响应带上状态码，不对外暴露。 */
    private static final class IllegalException extends Exception {
        IllegalException(String message) {
            super(message);
        }
    }

    /** 一条会话。 */
    public static final class Session {
        public static final String STATE_PREPARING = "preparing";
        public static final String STATE_RUNNING = "running";
        public static final String STATE_ENDED = "ended";

        private String sessionId;
        private String clientId;
        private String player;
        private String token;
        private String agentBase;
        private String state = STATE_PREPARING;
        private String gameState = "";
        private String endReason = "";
        private long createdAt;
        private long lastPollAt;
        /** 最近一次浏览器心跳；0 = 还没收到过。 */
        private long lastHeartbeatAt;
        /** 浏览器是否已经发过心跳：没发过就不按心跳超时判离（页面还没加载完）。 */
        private boolean heartbeatSeen;
        /** 该会话是插件重启后从代理 /health 恢复出来的。 */
        private boolean restored;

        public String getSessionId() {
            return sessionId;
        }

        public String getClientId() {
            return clientId;
        }

        public String getPlayer() {
            return player;
        }

        public String getState() {
            return state;
        }

        public boolean isRestored() {
            return restored;
        }

        public long getLastHeartbeatAt() {
            return lastHeartbeatAt;
        }

        public String getEndReason() {
            return endReason;
        }

        public String getGameState() {
            return gameState;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }
}
