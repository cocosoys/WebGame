package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** clientId → 最近一次被池挑中/起会话的时刻（"最久未用"策略用）。 */
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();
    /** 玩家名 → 最近一次上报的 MC|Brand（只用于日志审计，不参与判定）。 */
    private final Map<String, String> brands = new ConcurrentHashMap<>();
    /**
     * 「唯一入口」入场票据：ticket → 签发时刻。
     *
     * <p>价值不在"防伪造"（票据随页面下发，本身就是页面能力），而在于
     * <b>把"选哪台机器"从浏览器手里收回服务端</b>：页面只带票据，不暴露任何客户端的
     * cloud-key；服务层按序挑一台在线空闲的机器，用该机器自己的密钥去调代理。</p>
     */
    private final Map<String, Long> entryTickets = new ConcurrentHashMap<>();
    /** 票据有效期（毫秒）：页面从加载到点"进入"通常几秒，留 3 分钟足够重试。 */
    private static final long ENTRY_TICKET_TTL_MS = 180_000L;
    private volatile BukkitTask watchdog;
    /** 主线程巡检：玩家名在本服上线 = "连服务器"阶段真的过了。 */
    private volatile BukkitTask stageTask;

    /** 一次包更新最多等多久（毫秒）；超时就允许下一次点击重新发起。 */
    private static final long UPDATE_MAX_MS = 15 * 60 * 1000L;

    public CloudPlayService(WebGame plugin, CloudClientService clients) {
        this.plugin = plugin;
        this.clients = clients;
    }

    // ===== 对外 =====

    /** 开始一次会话：校验玩家名 → 找槽位 → 让代理准备客户端并启动游戏。 */
    public AjaxResult start(String clientId, String player, String token) {
        return start(clientId, player, token, "", "");
    }

    /**
     * 开始一次会话（带门牌别名）。
     *
     * @param alias 玩家进来的门牌（{@code /cloud/<alias>}）；空串表示走内部命名空间路径
     */
    public AjaxResult start(String clientId, String player, String token, String alias) {
        return start(clientId, player, token, alias, "");
    }

    /**
     * 开始一次会话（门牌别名 + 池）。
     *
     * @param alias 玩家进来的门牌（{@code /cloud/<alias>}）；空串 = 内部命名空间路径
     * @param pool  池名（{@code /cloud/pool/<池>}）；非空时忽略 clientId，由服务端挑一台空闲机器
     */
    public AjaxResult start(String clientId, String player, String token, String alias, String pool) {
        return start(clientId, player, token, alias, pool, "");
    }

    /**
     * 开始一次会话（门牌别名 + 池 + 唯一入口票据）。
     *
     * @param alias  玩家进来的门牌（{@code /cloud/<alias>}）；空串 = 内部命名空间路径
     * @param pool   池名（{@code /cloud/pool/<池>}）；非空时忽略 clientId，由服务端挑一台空闲机器
     * @param ticket 唯一入口票据（{@code /cloud/auto/} 下发）；非空时忽略 clientId/alias/pool，
     *               由服务端在"在线 + 空闲"的机器里按排序挑一台，并用该机自己的密钥调代理
     */
    public AjaxResult start(String clientId, String player, String token, String alias, String pool,
                            String ticket) {
        String name = player == null ? "" : player.trim();
        if (!PLAYER_RE.matcher(name).matches()) {
            return AjaxResult.error("游戏名称不合法：3–16 位字母/数字/下划线");
        }
        String tk = ticket == null ? "" : ticket.trim();
        boolean byTicket = !tk.isEmpty();
        if (byTicket) {
            if (!validEntryTicket(tk)) {
                return AjaxResult.error("入场票据已过期，请刷新入口页重试");
            }
            CloudClient picked = pickAuto();
            if (picked == null) {
                return AjaxResult.error("当前没有在线且空闲的云主机，请稍后再试");
            }
            // 票据模式下"客户端"由服务端决定：下游所有校验以挑中的机器为准
            clientId = picked.getId();
            token = picked.getCloudKey();
            alias = "";
            pool = "";
        }
        String key = token == null ? "" : token.trim();
        if (key.isEmpty()) {
            return AjaxResult.error("令牌不正确");
        }
        String wantPool = pool == null ? "" : com.github.cocosoys.mc.webgame.shttp.CloudClientPages
                .normalizeAlias(pool);
        boolean byPool = !wantPool.isEmpty();
        // 别名校验：门牌必须真的属于这台机器，否则玩家能借 A 的名字进 B 的机器
        String gate = com.github.cocosoys.mc.webgame.shttp.CloudClientPages.normalizeAlias(alias);

        CloudClient c;
        if (byPool) {
            if (!gate.isEmpty()) {
                return AjaxResult.error("池入口不能同时指定门牌");
            }
            Pick pick = pickPool(wantPool, key);
            if (pick.client == null) {
                return AjaxResult.error(pick.reason);
            }
            c = pick.client;
        } else {
            c = findClient(clientId);
            if (c == null && !gate.isEmpty() && plugin.getCloudClientPages() != null) {
                // 玩家是从 /cloud/<门牌> 点进来的：clientId 为空时用门牌反查机器
                c = plugin.getCloudClientPages().resolveAlias(gate);
            }
            if (c == null) {
                return AjaxResult.error("云游戏客户端不存在");
            }
            if (!c.isEnabled() || c.isMissing()) {
                return AjaxResult.error("该云游戏客户端当前不可用");
            }
            if (!CloudClient.isUrl(c.getPath())) {
                return AjaxResult.error("该客户端不是云游戏设备（本地页面不起会话）");
            }
            if (!key.equals(c.getCloudKey())) {
                return AjaxResult.error("令牌不正确");
            }
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
        }
        // 公共兜底：池挑出来的机器也要再验一次（可能在挑选与起会话之间被管理员关了）
        if (!c.isEnabled() || c.isMissing()) {
            return AjaxResult.error("该云游戏客户端当前不可用");
        }
        if (!CloudClient.isUrl(c.getPath())) {
            return AjaxResult.error("该客户端不是云游戏设备（本地页面不起会话）");
        }
        if (!key.equals(c.getCloudKey())) {
            return AjaxResult.error("令牌不正确");
        }
        if (c.getIp().isEmpty() || c.getPort() <= 0) {
            return AjaxResult.error("该客户端尚未上报设备地址");
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
        s.stage = Session.STAGE_WORKSPACE;
        s.pool = wantPool;
        s.liveSeg = randomSeg();
        s.createdAt = System.currentTimeMillis();
        s.lastHeartbeatAt = s.createdAt;
        sessions.put(sessionId, s);
        lastUsed.put(c.getId(), s.createdAt);

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
            // prepare 返回 = 工作区已分配、game/start 已发出；下一步等进程真的起来
            s.stage = Session.STAGE_JVM;
            s.lastPollAt = System.currentTimeMillis();
            // 启动兜底从 game/start 已发出开始计时：即使代理一直报 unknown，
            // 也不能让会话无限期占着机器（后来真的 running 时不会再覆盖它）
            s.armAt = s.lastPollAt;
            // 开闸：只注册本会话专属的随机前缀；开不出来就别把玩家放进包装页
            boolean opened = true;
            try {
                opened = plugin.getCloudClientPages() != null
                        && plugin.getCloudClientPages().openLive(c.getId(), s.liveSeg);
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
            data.put("clientName", c.getName());
            data.put("player", name);
            data.put("pool", wantPool);
            // 唯一入口的包装页停在 /cloud/auto/play/ 上；池入口停在池名上
            data.put("playUrl", byTicket
                    ? autoPlayUrl(sessionId)
                    : (byPool ? poolPlayUrl(wantPool, sessionId) : playUrl(c.getId(), sessionId, gate)));
            data.put("liveUrl", liveUrlOf(s));
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

    /** 最近一次更新失败原因（空 = 没有失败记录）。 */
    public String updateErrorOf(String clientId) {
        String err = clientId == null ? null : updateErrors.get(clientId);
        return err == null ? "" : err;
    }

    /**
     * ERP「下发更新」：不等会话、立刻把版本号推给指定机器。
     *
     * <p>与玩家点击触发的更新是同一条链路（verify → 下载 → sha256 → 解包 → 换目录），
     * 区别只在于发起者。三件事必须在这里挡住：机器正被占用（换目录会把正在跑的游戏拽掉）、
     * 已经在更新、以及没配包地址。</p>
     */
    public AjaxResult pushUpdate(String clientId) {
        CloudClient c = findClient(clientId);
        if (c == null) {
            return AjaxResult.error("云游戏客户端不存在: " + clientId);
        }
        if (!CloudClient.isUrl(c.getPath())) {
            return AjaxResult.error("本地页面条目没有客户端包，不需要下发更新");
        }
        if (c.getIp().isEmpty() || c.getPort() <= 0 || c.getCloudKey().isEmpty()) {
            return AjaxResult.error("该机器尚未上报设备地址/密钥，无法下发");
        }
        if (isBusy(clientId)) {
            return AjaxResult.error("该机器正被会话占用，请先结束会话再下发更新");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (updatingClients.containsKey(clientId)) {
            data.put("updating", true);
            return AjaxResult.success("该机器正在更新中，请稍候", data);
        }
        String want = plugin.getCloudClientPages() == null
                ? "" : plugin.getCloudClientPages().clientPackageVersion();
        String url = plugin.getCloudClientPages() == null
                ? "" : plugin.getCloudClientPages().clientPackageUrl();
        if (want.isEmpty()) {
            return AjaxResult.error("config.yml 未配置 cloud.client-update.version");
        }
        if (url.isEmpty()) {
            return AjaxResult.error("config.yml 未配置 cloud.client-update.base-url（代理拿不到包地址）");
        }
        // 手动下发是"我就是要现在更新"：先清掉上次的失败记录，否则 ensureClientVersion 会一直拦着
        updateErrors.remove(clientId);
        startAsyncUpdate(c, want, url);
        data.put("updating", true);
        data.put("version", want);
        data.put("url", url);
        return AjaxResult.success("已开始向 " + c.getName() + " 下发版本 " + want, data);
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
        return statusData(s, "ok");
    }

    /** 会话状态载荷（状态/心跳共用一个形状，页面只写一套解析）。 */
    private AjaxResult statusData(Session s, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", s.state);
        data.put("gameState", s.gameState);
        data.put("player", s.player);
        data.put("clientId", s.clientId);
        data.put("pool", s.pool);
        data.put("stage", s.stage);
        data.put("stageIndex", stageIndex(s.stage));
        // 进服判定的结果：页面靠 joined + revealInMs 决定什么时候揭遮罩，
        // joinIp/joinBrand 只是给玩家和管理员看的审计信息，不参与判定。
        data.put("joined", s.joined);
        data.put("joinIp", s.joinIp);
        data.put("joinBrand", s.joinBrand);
        data.put("minHoldSeconds", minHoldSeconds());
        long hold = minHoldSeconds() * 1000L;
        data.put("revealInMs", s.joined
                ? Math.max(0L, s.joinedAt + hold - System.currentTimeMillis())
                : -1L);
        // 画面地址只给"正在跑"的会话：页面拿到后才设 iframe src（地址含会话专属随机段，
        // 令牌在片段里；拿不到就保持空白，不会退化成任何人都能打开的固定地址）
        if (Session.STATE_RUNNING.equals(s.state)) {
            data.put("liveUrl", liveUrlOf(s));
        }
        return AjaxResult.success(msg, data);
    }

    /** 阶段序号：1 工作区 / 2 JVM / 3 连服 / 4 画面（页面用它画四步清单）。 */
    private static int stageIndex(String stage) {
        if (Session.STAGE_WORKSPACE.equals(stage)) {
            return 1;
        }
        if (Session.STAGE_JVM.equals(stage)) {
            return 2;
        }
        if (Session.STAGE_JOIN.equals(stage)) {
            return 3;
        }
        if (Session.STAGE_SCREEN.equals(stage)) {
            return 4;
        }
        return 0;
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
        return statusData(s, "ok");
    }

    public void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::watch, 100L, 100L);
        // 玩家上线事件读的是 Bukkit 状态，必须留在主线程：每 2 秒扫一遍"连服务器"阶段
        stageTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::scanJoins, 40L, 40L);
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
        BukkitTask st = stageTask;
        stageTask = null;
        if (st != null) {
            try {
                st.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 某客户端是否正被占用（ERP/选择页展示用）。 */
    public boolean isBusy(String clientId) {
        return slots.containsKey(clientId);
    }

    /**
     * 当前会话快照（含已结束但还没被清理的记录）。
     *
     * <p>ERP 的「会话监控」直接合并这份数据，不再依赖页面心跳表：云游戏会话的真相
     * 只有这里一处（槽位、阶段、进服判定都在本对象里）。</p>
     */
    public List<Session> snapshotSessions() {
        return new ArrayList<>(sessions.values());
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
            // 启动兜底：进程起来后 startup-timeout 秒还没看到「可信进服」，就别再占着机器
            if (!s.joined && s.armAt > 0 && now - s.armAt > startupTimeoutSeconds() * 1000L) {
                LOG.warning("会话 " + s.sessionId + " 游戏进程已起 " + ((now - s.armAt) / 1000)
                        + " 秒仍未见云机进服（云机 " + s.clientId + "），按超时结束并释放槽位");
                finish(s, "join-timeout");
                continue;
            }
            // 进服后 join-window 秒内页面仍没回执「画面已载入」：阶段按进服推进，
            // 不再把第 4 格悬着（画面本身在 iframe 里，页面自己会揭遮罩）
            if (s.joined && !Session.STAGE_SCREEN.equals(s.stage)
                    && now - s.joinedAt > joinWindowSeconds() * 1000L) {
                s.stage = Session.STAGE_SCREEN;
                s.screenAt = now;
                LOG.info("会话 " + s.sessionId + " 进服后 " + joinWindowSeconds()
                        + " 秒未收到画面回执，按进服判定推进到 screen");
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
                // 从代理恢复出来的会话只知道「进程还活着」，不知道玩家在不在服里。
                // 阶段最多给到 jvm；join 必须由进服事件的 IP 判定或兜底巡检补上。
                s.stage = gameUp(gameState) ? Session.STAGE_JVM : Session.STAGE_WORKSPACE;
                s.armAt = gameUp(gameState) ? System.currentTimeMillis() : 0L;
                s.pool = c.getPool();
                s.liveSeg = randomSeg();
                s.createdAt = System.currentTimeMillis();
                s.lastHeartbeatAt = s.createdAt;
                s.heartbeatSeen = false; // 页面还没连上来，先不按心跳计时
                s.restored = true;
                sessions.put(active, s);
                slots.put(c.getId(), active);
                if (plugin.getCloudClientPages() != null) {
                    try {
                        plugin.getCloudClientPages().openLive(c.getId(), s.liveSeg);
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

    /**
     * 「玩家真的进来了」的唯一判定入口（由 {@code PlayerJoinEvent} 调用，主线程）。
     *
     * <p>判据是<b>来源 IP</b>：这台云主机的 {@code cloud-clients.yml} 里 {@code ip} 就是云机自己的
     * 地址，云游戏客户端一定从它出网；eagler（WS 隧道）永远从回环 {@code 127.0.0.1} 进服，
     * 玩家自己电脑上的原生客户端则是玩家自己的地址。名字只做审计——eagler 可以随便填一个名字
     * 冒名顶替，IP 不行。回环地址永远不判成云机。</p>
     */
    public void onPlayerJoin(String playerName, String rawAddress, String brand) {
        String ip = normalizeIp(rawAddress);
        Session s = matchSession(playerName, ip);
        if (s == null) {
            LOG.fine("[进服审计] " + playerName + " 从 " + ip + " 进服（品牌 " + brand
                    + "），当前没有云游戏会话，忽略");
            return;
        }
        boolean fromCloud = isCloudAddress(s, ip);
        LOG.info("[进服审计] 玩家 " + playerName + "（会话 " + s.sessionId + "，云机 " + s.clientId
                + "）来源 " + ip + " 品牌 " + (brand.isEmpty() ? "未知" : brand)
                + " → " + (fromCloud ? "云游戏客户端" : "非云机来源（回环/玩家本机），不判进服"));
        if (!fromCloud) {
            return;
        }
        markJoined(s, playerName, ip, brand, "事件");
    }

    /** 玩家退出：只做审计与记录，会话收尾仍然交给「游戏进程已停」那条硬判据。 */
    public void onPlayerQuit(String playerName, String rawAddress) {
        String ip = normalizeIp(rawAddress);
        brands.remove(playerName);
        for (Session s : sessions.values()) {
            if (s.joined && s.joinName.equals(playerName) && sameHost(s.joinIp, ip)) {
                s.leftAt = System.currentTimeMillis();
                LOG.info("[进服审计] 玩家 " + playerName + " 离开服务器（会话 " + s.sessionId
                        + "，在服 " + ((s.leftAt - s.joinedAt) / 1000) + " 秒）");
                return;
            }
        }
    }

    /** 客户端品牌（{@code MC|Brand}）只进日志，不参与任何判定。 */
    public void noteBrand(String playerName, String brand) {
        if (playerName == null || brand == null || brand.isEmpty()) {
            return;
        }
        brands.put(playerName, brand);
        for (Session s : sessions.values()) {
            if (s.joined && s.joinName.equals(playerName)) {
                s.joinBrand = brand;
                LOG.info("[进服审计] 会话 " + s.sessionId + " 玩家 " + playerName
                        + " 的客户端品牌：" + brand);
                return;
            }
        }
    }

    /** 已记录的品牌（进服事件早于品牌包时，用它补上审计字段）。 */
    public String brandOf(String playerName) {
        String v = playerName == null ? null : brands.get(playerName);
        return v == null ? "" : v;
    }

    /** 阶段 4：页面确认画面真的载入过（{@code POST /cloud/session/screen}）。 */
    public AjaxResult screen(String sessionId) {
        Session s = sessionId == null ? null : sessions.get(sessionId);
        if (s == null) {
            return AjaxResult.success("会话不存在", null);
        }
        if (!s.joined) {
            return AjaxResult.error("玩家还没进服，画面阶段暂不确认");
        }
        if (!Session.STAGE_SCREEN.equals(s.stage)) {
            s.stage = Session.STAGE_SCREEN;
            s.screenAt = System.currentTimeMillis();
            LOG.info("会话 " + s.sessionId + " 画面已载入（进服后 "
                    + ((s.screenAt - s.joinedAt) / 1000) + " 秒）");
        }
        return AjaxResult.success("画面已就绪", null);
    }

    /** 记一次可信进服：阶段推到 join，并留时间戳给页面算揭示倒计时。 */
    private void markJoined(Session s, String playerName, String ip, String brand, String source) {
        if (s.joined) {
            return;
        }
        s.joined = true;
        s.joinedAt = System.currentTimeMillis();
        s.joinName = playerName == null ? "" : playerName;
        s.joinIp = ip == null ? "" : ip;
        s.joinBrand = brand == null ? "" : brand;
        if (!Session.STAGE_SCREEN.equals(s.stage)) {
            s.stage = Session.STAGE_JOIN;
        }
        LOG.info("会话 " + s.sessionId + " 玩家已进服（" + source + "，云机 " + s.clientId
                + "，来源 " + s.joinIp + "），" + minHoldSeconds() + " 秒后交由页面揭示画面");
    }

    /**
     * 主线程兜底巡检：事件漏掉（插件中途启用、会话从代理恢复）时，用在线玩家表补一次。
     * <p>规则与事件路径完全一致：只有来源等于该会话云机 IP 才算数，回环永远不算。</p>
     */
    private void scanJoins() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String ip = normalizeIp(p.getAddress() == null || p.getAddress().getAddress() == null
                    ? "" : p.getAddress().getAddress().getHostAddress());
            Session s = matchSession(p.getName(), ip);
            if (s == null || s.joined || !isCloudAddress(s, ip)) {
                continue;
            }
            LOG.info("[进服审计] 巡检补记：" + p.getName() + " 来源 " + ip
                    + " 与会话 " + s.sessionId + " 的云机地址一致");
            markJoined(s, p.getName(), ip, brandOf(p.getName()), "巡检");
        }
    }

    /**
     * 找到这次进服属于哪个会话：先按来源 IP 与会话云机地址配对，再按会话玩家名配对。
     * <p>返回 null 表示与本插件无关的普通进服。</p>
     */
    private Session matchSession(String playerName, String ip) {
        for (Session s : sessions.values()) {
            if (Session.STATE_ENDED.equals(s.state)) {
                continue;
            }
            String cloudIp = clientIpOf(s);
            if (!cloudIp.isEmpty() && !isLoopback(ip) && sameHost(cloudIp, ip)) {
                return s;
            }
        }
        for (Session s : sessions.values()) {
            if (Session.STATE_ENDED.equals(s.state)) {
                continue;
            }
            if (s.player != null && !s.player.isEmpty() && s.player.equalsIgnoreCase(playerName)) {
                return s;
            }
        }
        return null;
    }

    /** 这次进服的地址是否就是该会话那台云机的地址（回环一律否）。 */
    private boolean isCloudAddress(Session s, String ip) {
        String cloudIp = clientIpOf(s);
        return !cloudIp.isEmpty() && !isLoopback(ip) && sameHost(cloudIp, ip);
    }

    private String clientIpOf(Session s) {
        CloudClient c = findClient(s.clientId);
        return c == null ? "" : normalizeIp(c.getIp());
    }

    /** 归一化地址：去掉 {@code /}、端口与 IPv6 方括号，IPv4 映射地址折回 IPv4。 */
    static String normalizeIp(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.isEmpty()) {
            return "";
        }
        if (v.startsWith("/")) {
            v = v.substring(1);
        }
        int pct = v.indexOf('%');
        if (pct > 0) {
            v = v.substring(0, pct);
        }
        if (v.startsWith("[") && v.contains("]")) {
            v = v.substring(1, v.indexOf(']'));
        } else if (v.indexOf(':') == v.lastIndexOf(':') && v.contains(":")) {
            // host:port（IPv4 只有一个冒号）
            v = v.substring(0, v.indexOf(':'));
        }
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        if ("::1".equals(lower) || "0:0:0:0:0:0:0:1".equals(lower)) {
            return "127.0.0.1";
        }
        if (lower.startsWith("::ffff:")) {
            v = v.substring(7);
        }
        return v;
    }

    private static boolean isLoopback(String ip) {
        String v = normalizeIp(ip);
        return v.isEmpty() || "127.0.0.1".equals(v) || v.startsWith("127.");
    }

    private static boolean sameHost(String a, String b) {
        String x = normalizeIp(a);
        String y = normalizeIp(b);
        return !x.isEmpty() && x.equalsIgnoreCase(y);
    }

    /**
     * 池选机：同池里挑一台"在线、健康、空闲、不在更新、密钥对得上"的机器。
     *
     * <p>策略来自 {@code cloud.pool.strategy}：{@code order}（默认，按 ERP 排序取第一台）、
     * {@code latency}（延迟最低）、{@code recent}（最久没被用过的先上，做轮转）。
     * 无论哪种策略，"在线/空闲"都是硬门槛——宁可拒绝，也不把玩家塞进一台正在被别人用的机器。</p>
     */
    private Pick pickPool(String pool, String key) {
        List<CloudClient> candidates = new ArrayList<>();
        int offline = 0;
        int degraded = 0;
        int busy = 0;
        int keyMismatch = 0;
        int noAddr = 0;
        int updating = 0;
        int unauthorized = 0;
        for (CloudClient c : clients.versions()) {
            if (!c.isEnabled() || c.isMissing() || !CloudClient.isUrl(c.getPath())) {
                continue;
            }
            if (!pool.equals(c.getPool())) {
                continue;
            }
            if (!key.equals(c.getCloudKey())) {
                keyMismatch++;
                continue;
            }
            if (c.getIp().isEmpty() || c.getPort() <= 0) {
                noAddr++;
                continue;
            }
            String status = c.getStatus();
            if (CloudClient.STATUS_OFFLINE.equals(status)) {
                offline++;
                continue;
            }
            if (CloudClient.STATUS_DEGRADED.equals(status)) {
                degraded++;
                continue;
            }
            if (CloudClient.STATUS_UNAUTHORIZED.equals(status)) {
                unauthorized++;
                continue;
            }
            if (slots.containsKey(c.getId())) {
                busy++;
                continue;
            }
            if (updatingClients.containsKey(c.getId())) {
                updating++;
                continue;
            }
            candidates.add(c);
        }
        if (candidates.isEmpty()) {
            List<String> why = new ArrayList<>();
            if (busy > 0) {
                why.add(busy + " 台占用中");
            }
            if (offline > 0) {
                why.add(offline + " 台离线");
            }
            if (degraded > 0) {
                why.add(degraded + " 台健康检查不通过");
            }
            if (updating > 0) {
                why.add(updating + " 台正在更新客户端");
            }
            if (unauthorized > 0) {
                why.add(unauthorized + " 台密钥被拒");
            }
            if (keyMismatch > 0) {
                why.add(keyMismatch + " 台口令与当前入口不一致");
            }
            if (noAddr > 0) {
                why.add(noAddr + " 台未上报地址");
            }
            return new Pick(null, "池 " + pool + " 暂时没有可用机器"
                    + (why.isEmpty() ? "" : "（" + join(why) + "）"));
        }
        String strategy = poolStrategy();
        if ("latency".equals(strategy)) {
            candidates.sort((a, b) -> {
                int cmp = Long.compare(latencyOf(a), latencyOf(b));
                return cmp != 0 ? cmp : orderOf(a, b);
            });
        } else if ("recent".equals(strategy)) {
            candidates.sort((a, b) -> {
                int cmp = Long.compare(lastUsedOf(a.getId()), lastUsedOf(b.getId()));
                return cmp != 0 ? cmp : orderOf(a, b);
            });
        } else {
            candidates.sort(this::orderOf);
        }
        return new Pick(candidates.get(0), "");
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * 唯一入口选机：不限池、不限口令，在"启用 + 有地址 + 没离线/降级/密钥被拒 + 空闲 + 不在更新"
     * 的机器里按 {@code cloud.pool.strategy} 挑一台。
     *
     * <p>与 {@link #pickPool} 的差别只有一条：池是"玩家已经声明了哪组机器"，唯一入口是
     * "整个集群里随便给一台"。因此这里不比较令牌——挑中之后用该机自己的 cloud-key 调代理，
     * 浏览器永远拿不到设备的密钥。</p>
     */
    private CloudClient pickAuto() {
        List<CloudClient> candidates = new ArrayList<>();
        for (CloudClient c : clients.versions()) {
            if (!c.isEnabled() || c.isMissing() || !CloudClient.isUrl(c.getPath())) {
                continue;
            }
            if (c.getIp().isEmpty() || c.getPort() <= 0 || c.getCloudKey().isEmpty()) {
                continue;
            }
            String status = c.getStatus();
            if (CloudClient.STATUS_OFFLINE.equals(status)
                    || CloudClient.STATUS_DEGRADED.equals(status)
                    || CloudClient.STATUS_UNAUTHORIZED.equals(status)) {
                continue;
            }
            if (slots.containsKey(c.getId()) || updatingClients.containsKey(c.getId())) {
                continue;
            }
            candidates.add(c);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if ("recent".equals(poolStrategy())) {
            candidates.sort((a, b) -> {
                int cmp = Long.compare(lastUsedOf(a.getId()), lastUsedOf(b.getId()));
                return cmp != 0 ? cmp : orderOf(a, b);
            });
        } else if ("latency".equals(poolStrategy())) {
            candidates.sort((a, b) -> {
                int cmp = Long.compare(latencyOf(a), latencyOf(b));
                return cmp != 0 ? cmp : orderOf(a, b);
            });
        } else {
            candidates.sort(this::orderOf);
        }
        return candidates.get(0);
    }

    /** 签发一张唯一入口票据：180 秒有效、只可消费一次（重放无效）。 */
    public String mintEntryTicket() {
        pruneEntryTickets();
        String t = "t-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        entryTickets.put(t, System.currentTimeMillis());
        return t;
    }

    /** 校验并<b>消费</b>一张唯一入口票据：票据只能用一次（用掉即作废，重放无效）。 */
    private boolean validEntryTicket(String ticket) {
        pruneEntryTickets();
        Long at = entryTickets.remove(ticket);
        return at != null && System.currentTimeMillis() - at <= ENTRY_TICKET_TTL_MS;
    }

    private void pruneEntryTickets() {
        long now = System.currentTimeMillis();
        entryTickets.entrySet().removeIf(e -> now - e.getValue() > ENTRY_TICKET_TTL_MS);
    }

    /** 未上报延迟的机器排最后（0 不是"最快"，是"不知道"）。 */
    private static long latencyOf(CloudClient c) {
        long v = c.getLatencyMs();
        return v <= 0 ? Long.MAX_VALUE : v;
    }

    private long lastUsedOf(String clientId) {
        Long v = lastUsed.get(clientId);
        return v == null ? 0L : v;
    }

    private int orderOf(CloudClient a, CloudClient b) {
        int cmp = Integer.compare(a.getOrder(), b.getOrder());
        if (cmp != 0) {
            return cmp;
        }
        try {
            return Integer.compare(Integer.parseInt(a.getId()), Integer.parseInt(b.getId()));
        } catch (Throwable ignored) {
            return a.getId().compareTo(b.getId());
        }
    }

    /** 池策略：order（默认）/ latency / recent。 */
    private String poolStrategy() {
        String v = "order";
        try {
            v = plugin.getConfig().getString("cloud.pool.strategy", "order");
        } catch (Throwable ignored) {
        }
        v = v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"latency".equals(v) && !"recent".equals(v)) {
            v = "order";
        }
        return v;
    }

    /** 池包装页地址：地址栏停在池名上，具体机器只在 iframe 里出现。 */
    private String poolPlayUrl(String pool, String sessionId) {
        return "/cloud/pool/" + pool + "/play/?s=" + sessionId;
    }

    /** 进程是否真的起来了（cloud-stream 的 game.state）。 */
    private static boolean gameUp(String state) {
        if (state == null) {
            return false;
        }
        String s = state.toLowerCase(java.util.Locale.ROOT);
        return s.contains("running") || s.contains("starting");
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
            // 进程起来（starting/running）只说明「JVM 起来了」，不代表玩家进了服务器。
            // 阶段 3（join）必须由 PlayerJoinEvent 的 IP 判定推进，这里最多推进到 jvm。
            if (gameUp(s.gameState) && Session.STAGE_WORKSPACE.equals(s.stage)) {
                s.stage = Session.STAGE_JVM;
            }
            if (gameUp(s.gameState) && s.armAt == 0) {
                s.armAt = now; // 启动兜底从「进程真的起来」开始计时，而不是从点进页面开始
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

    /**
     * 启动兜底（秒）：游戏进程起来之后，最多等这么久还没看到「云机进服」。
     * <p>实测进服 15–120 秒，180 秒是留足余量的兜底；超时就结束会话释放机器，
     * 避免一台跑着没人玩的 Windows 桌面把槽位占死。</p>
     */
    public int startupTimeoutSeconds() {
        int v = 180;
        try {
            v = plugin.getConfig().getInt("cloud.reveal.startup-timeout-seconds", 180);
        } catch (Throwable ignored) {
        }
        return v < 30 ? 30 : (v > 900 ? 900 : v);
    }

    /**
     * 进服窗口（秒）：玩家已进服后，再给页面这么久来上报「画面已载入」。
     * <p>超时就按进服判定把阶段推进到 screen——玩家已经在游戏里了，没理由让他继续盯遮罩。</p>
     */
    public int joinWindowSeconds() {
        int v = 30;
        try {
            v = plugin.getConfig().getInt("cloud.reveal.join-window-seconds", 30);
        } catch (Throwable ignored) {
        }
        return v < 5 ? 5 : (v > 300 ? 300 : v);
    }

    /** 揭幕下限（秒）：进服后页面至少再压这么久才揭开遮罩，避免画面闪一下又黑屏。 */
    public int minHoldSeconds() {
        int v = 15;
        try {
            v = plugin.getConfig().getInt("cloud.reveal.min-hold-seconds", 15);
        } catch (Throwable ignored) {
        }
        return v < 0 ? 0 : (v > 120 ? 120 : v);
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

    /** 唯一入口包装页地址：{@code /cloud/auto/play/?s=<会话>}（地址栏停在"自动匹配"上）。 */
    private static String autoPlayUrl(String sessionId) {
        return "/cloud/auto/play/?s=" + sessionId;
    }

    /** 会话专属随机段（直播前缀里的一段，猜测成本从"知道有会话"变成"知道这一次的 20 位随机值"）。 */
    private static String randomSeg() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * 会话的直播地址：{@code /web/plugins/WebGame/cloud/<id>/live/<seg>/#token=<t>}。
     *
     * <p>令牌放在 URL 片段里：片段不会被浏览器发到服务端，不进 SOYS 转播日志/访问日志，
     * 也不随 Referer 外泄；selkies 前端本身就从 {@code location.hash} 读它。</p>
     * <p>只在同端口转播可用、且会话带有随机段时返回；关掉转播则退回设备直连地址
     * （直接连设备时片段同样有效）。</p>
     */
    private String liveUrlOf(Session s) {
        CloudClient c = findClient(s.clientId);
        if (c == null) {
            return "";
        }
        String key = c.getCloudKey() == null ? "" : c.getCloudKey();
        boolean relay = true;
        try {
            relay = plugin.getConfig().getBoolean("cloud.same-port-relay", true);
        } catch (Throwable ignored) {
        }
        if (relay && !s.liveSeg.isEmpty() && plugin.getCloudClientPages() != null) {
            return appendToken(plugin.getCloudClientPages().livePathOf(s.clientId, s.liveSeg), key);
        }
        String base = c.getPath() == null ? "" : c.getPath().trim();
        if (base.isEmpty() || !CloudClient.isUrl(base)) {
            return "";
        }
        return appendToken(base, key);
    }

    /** 补 {@code #token=}；已经是带片段的地址就不重复追加。 */
    private static String appendToken(String url, String token) {
        if (url == null || url.isEmpty() || token == null || token.isEmpty() || url.contains("token=")) {
            return url;
        }
        try {
            return url + "#token=" + URLEncoder.encode(token, "UTF-8");
        } catch (Throwable t) {
            return url;
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

    /** 池选机结果：{@code client == null} 时 {@code reason} 一定非空。 */
    private static final class Pick {
        final CloudClient client;
        final String reason;

        Pick(CloudClient client, String reason) {
            this.client = client;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** 一条会话。 */
    public static final class Session {
        public static final String STATE_PREPARING = "preparing";
        public static final String STATE_RUNNING = "running";
        public static final String STATE_ENDED = "ended";

        /** 阶段：分配工作区（prepare 进行中）。 */
        public static final String STAGE_WORKSPACE = "workspace";
        /** 阶段：工作区就绪，等游戏进程起来。 */
        public static final String STAGE_JVM = "jvm";
        /** 阶段：进程已起，等玩家登录本服。 */
        public static final String STAGE_JOIN = "join";
        /** 阶段：玩家已进服（画面由包装页自己确认）。 */
        public static final String STAGE_SCREEN = "screen";
        private String sessionId;
        private String clientId;
        private String player;
        private String token;
        private String agentBase;
        private String state = STATE_PREPARING;
        /** 阶段（见 STAGE_*）；只前进不后退。 */
        private String stage = STAGE_WORKSPACE;
        /** 该会话是从哪个池挑出来的（空 = 直接指定机器）。 */
        private String pool = "";
        /** 本会话专属的直播前缀随机段（{@code /cloud/<id>/live/<seg>/}）；空 = 未经同端口转播。 */
        private String liveSeg = "";
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
        /** 是否已确认「玩家真的进了本服」（只由来源 IP 判定，名字不算数）。 */
        private boolean joined;
        /** 确认进服的时刻；0 = 还没进服。 */
        private long joinedAt;
        /** 进服时用的名字 / 来源地址 / 客户端品牌（审计用）。 */
        private String joinName = "";
        private String joinIp = "";
        private String joinBrand = "";
        /** 第一次观察到游戏进程起来的时刻：启动兜底从这里开始计时。 */
        private long armAt;
        /** 页面回执「画面已载入」的时刻。 */
        private long screenAt;
        /** 玩家离开服务器的时刻（只做审计，会话收尾仍看进程状态）。 */
        private long leftAt;

        public boolean isJoined() {
            return joined;
        }

        public long getJoinedAt() {
            return joinedAt;
        }

        public String getJoinIp() {
            return joinIp;
        }

        public String getJoinBrand() {
            return joinBrand;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getClientId() {
            return clientId;
        }

        /** 阶段（workspace/jvm/join/screen）；ERP 会话列表按它显示进度。 */
        public String getStage() {
            return stage;
        }

        /** 会话从哪个池挑出来的（空 = 直接指定机器）。 */
        public String getPool() {
            return pool == null ? "" : pool;
        }

        /** 是否已确认玩家真的进服（来源 IP 判定）。 */
        public boolean isJoinedConfirmed() {
            return joined;
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
