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

    private static final long AGENT_TIMEOUT_MS = 20_000L;
    private static final long STATUS_MIN_INTERVAL_MS = 2_000L;

    private final WebGame plugin;
    private final CloudClientService clients;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** clientId → sessionId（槽位占用表，一台机器一个槽）。 */
    private final Map<String, String> slots = new ConcurrentHashMap<>();
    private volatile BukkitTask watchdog;

    public CloudPlayService(WebGame plugin, CloudClientService clients) {
        this.plugin = plugin;
        this.clients = clients;
    }

    // ===== 对外 =====

    /** 开始一次会话：校验玩家名 → 找槽位 → 让代理准备客户端并启动游戏。 */
    public AjaxResult start(String clientId, String player, String token) {
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

        String busySession = slots.get(c.getId());
        if (busySession != null) {
            return AjaxResult.error("云游戏机占用中（会话 " + busySession + "），请稍后再试");
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
        sessions.put(sessionId, s);

        try {
            String body = "{\"sessionId\":\"" + sessionId + "\",\"player\":\"" + name + "\"}";
            String resp = http(s.agentBase + "/session/prepare", "POST", body, key, 120_000);
            if (!ok(resp)) {
                slots.remove(c.getId(), sessionId);
                sessions.remove(sessionId);
                return AjaxResult.error("会话准备失败：" + errorOf(resp));
            }
            s.state = Session.STATE_RUNNING;
            s.gameState = "running";
            s.lastPollAt = System.currentTimeMillis();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("clientId", c.getId());
            data.put("player", name);
            data.put("playUrl", playUrl(c.getId(), sessionId));
            data.put("liveUrl", liveUrl(c.getId(), key));
            return AjaxResult.success("云游戏会话已开始", data);
        } catch (Throwable t) {
            slots.remove(c.getId(), sessionId);
            sessions.remove(sessionId);
            LOG.log(Level.WARNING, "会话准备失败 " + sessionId + ": " + t);
            return AjaxResult.error("会话准备失败：" + t.getMessage());
        }
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
        for (Session s : sessions.values()) {
            if (Session.STATE_RUNNING.equals(s.state)) {
                refresh(s, false);
                // 游戏已退出：立刻清工作区并释放槽位，页面随后跳回选择页
                if ("stopped".equals(s.gameState)) {
                    finish(s, "game-stopped");
                }
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

    private String playUrl(String clientId, String sessionId) {
        return WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/" + clientId + "/play/?s=" + sessionId;
    }

    private String liveUrl(String clientId, String key) {
        try {
            return WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/" + clientId + "/live/?token="
                    + URLEncoder.encode(key, "UTF-8");
        } catch (Throwable t) {
            return WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/" + clientId + "/live/";
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

        public String getGameState() {
            return gameState;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }
}
