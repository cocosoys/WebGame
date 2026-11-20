package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.platform.PlatformYaml;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.ConfigUtil;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.shttp.CloudClientPages;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.vo.CloudClientVo;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import com.github.cocosoys.mc.webgame.spring.support.CloudClientProbe;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 云游戏客户端管理实现。
 *
 * <p>元数据：{@code cloud-clients.yml}（与 config.yml 同目录）；条目可直接指向
 * 外部 URL 或任意本地文件，不提供上传。id 由服务端自增，删除后不复用。</p>
 */
public class CloudClientServiceImpl implements CloudClientService {

    private static final Logger LOG = Logger.getLogger(CloudClientServiceImpl.class.getName());

    private static final String META_FILE = "cloud-clients.yml";
    private static final String PAGE_PATH = "/cloud/";

    private final WebGame plugin;

    /** 按数字 id 排序、并发安全。 */
    private volatile Map<String, CloudClient> clients = new ConcurrentSkipListMap<>();
    private volatile String defaultId = "";
    private volatile boolean allowPlayerSelect = true;
    private volatile int nextId = 1;

    // ===== 运行态调度（90s 心跳 / TTL 判离线 / 写盘节流） =====

    /** 时间戳格式：与既有 YAML 里的时间列保持一致（便于人工比对）。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 默认心跳周期（秒）；可由 config.yml 的 cloud.heartbeat-seconds 覆盖。 */
    private static final int DEFAULT_HEARTBEAT_SECONDS = 90;
    /** 默认离线阈值（秒）：3 个心跳周期内没有任何成功探测才判 offline。 */
    private static final int DEFAULT_TTL_SECONDS = 270;
    /** 默认单次探测超时（毫秒）。 */
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 3000;
    /** 探测并发上限：避免 20 台设备同时探测把 Bukkit 的异步池占满。 */
    private static final int PROBE_CONCURRENCY = 2;
    /** 写盘最小间隔（秒）：状态没变化时不重复落盘。 */
    private static final int PERSIST_MIN_INTERVAL_SECONDS = 60;

    private volatile BukkitTask heartbeatTask;
    private volatile ExecutorService probePool;
    /** 一轮全量探测未结束时不再叠加新的一轮。 */
    private final AtomicBoolean probing = new AtomicBoolean(false);
    /** 有运行态变化待落盘。 */
    private volatile boolean dirty;
    /** 上一次落盘时刻（毫秒）。 */
    private volatile long lastPersistAt;

    private static final Comparator<String> ID_COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(toInt(a, 0), toInt(b, 0));
        return cmp != 0 ? cmp : String.valueOf(a).compareTo(String.valueOf(b));
    };

    public CloudClientServiceImpl(WebGame plugin) {
        this.plugin = plugin;
        load();
    }

    // ===== 查询 =====

    @Override
    public AjaxResult list() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("defaultId", defaultId);
            data.put("allowPlayerSelect", allowPlayerSelect);
            data.put("rows", toVos());
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("云游戏客户端查询失败：" + t.getMessage());
        }
    }

    // ===== 管理 =====

    @Override
    public AjaxResult create(Map<String, Object> body) {
        try {
            String path = str(body == null ? null : body.get("path")).trim();
            if (path.isEmpty()) {
                return AjaxResult.error("请填写客户端路径（URL 或本地文件）");
            }
            String id = String.valueOf(nextId++);
            CloudClient c = new CloudClient();
            c.setId(id);
            applyBody(c, body);
            c.setOrder(maxOrder() + 1);
            c.setSource(deriveSource(c));
            c.setMissing(!entryExists(c));
            clients.put(id, c);
            if (!isServableQuiet(defaultId)) {
                defaultId = id;
            }
            save();
            publishPages();
            return AjaxResult.success("已新增云游戏客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("新增失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult update(String id, Map<String, Object> body) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            Boolean enabled = toBool(body == null ? null : body.get("enabled"));
            if (Boolean.FALSE.equals(enabled) && id.equals(defaultId)) {
                return AjaxResult.error("默认客户端不可关闭，请先将其它客户端设为默认");
            }
            applyBody(c, body);
            if (c.getPath().isEmpty()) {
                return AjaxResult.error("请填写客户端路径（URL 或本地文件）");
            }
            c.setSource(deriveSource(c));
            c.setMissing(!entryExists(c));
            save();
            publishPages();
            return AjaxResult.success("已保存云游戏客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("保存失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setEnabled(String id, boolean enabled) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (!enabled && id.equals(defaultId)) {
                return AjaxResult.error("默认客户端不可关闭，请先将其它客户端设为默认");
            }
            c.setEnabled(enabled);
            save();
            publishPages();
            return AjaxResult.success((enabled ? "已启用 " : "已关闭 ") + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("操作失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setDefault(String id) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (c.isMissing()) {
                return AjaxResult.error("该客户端入口缺失，无法设为默认");
            }
            if (!c.isEnabled()) {
                return AjaxResult.error("该客户端已关闭，无法设为默认");
            }
            defaultId = id;
            save();
            publishPages();
            return AjaxResult.success("默认客户端已设为 " + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("设置默认客户端失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult delete(String id) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            clients.remove(id);
            if (id.equals(defaultId)) {
                defaultId = pickFallbackDefault();
            }
            save();
            publishPages();
            return AjaxResult.success("已删除 " + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("删除失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult reorder(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return AjaxResult.error("排序列表不能为空");
            }
            List<String> applied = new ArrayList<>();
            int next = 0;
            for (String id : ids) {
                CloudClient c = id == null ? null : clients.get(id);
                if (c == null || applied.contains(id)) {
                    continue;
                }
                c.setOrder(next++);
                applied.add(id);
            }
            for (CloudClient c : versions()) {
                if (!applied.contains(c.getId())) {
                    c.setOrder(next++);
                }
            }
            save();
            publishPages();
            Map<String, Object> data = new HashMap<>();
            data.put("rows", toVos());
            return AjaxResult.success("排序已保存", data);
        } catch (Throwable t) {
            return AjaxResult.error("排序保存失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setAllowPlayerSelect(boolean allow) {
        try {
            if (!allow && !isServableQuiet(defaultId)) {
                return AjaxResult.error("默认客户端不可用（已关闭或入口缺失），请先切换默认客户端");
            }
            allowPlayerSelect = allow;
            save();
            publishPages();
            return AjaxResult.success(allow
                    ? "已允许玩家自行选择客户端"
                    : "已禁止玩家自行选择客户端，/cloud/ 将直接进入默认客户端", null);
        } catch (Throwable t) {
            return AjaxResult.error("设置失败：" + t.getMessage());
        }
    }

    // ===== 供页面使用 =====

    @Override
    public List<CloudClient> versions() {
        List<CloudClient> list = new ArrayList<>(clients.values());
        list.sort((a, b) -> {
            int cmp = Integer.compare(a.getOrder(), b.getOrder());
            return cmp != 0 ? cmp : ID_COMPARATOR.compare(a.getId(), b.getId());
        });
        return list;
    }

    @Override
    public String defaultId() {
        return defaultId;
    }

    @Override
    public boolean allowPlayerSelect() {
        return allowPlayerSelect;
    }

    @Override
    public File resolveClientFile(CloudClient client) {
        if (client == null || CloudClient.isUrl(client.getPath())) {
            return null;
        }
        String path = client.getPath().trim();
        if (path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return file.isFile() ? file : null;
        }
        File inData = new File(plugin.getDataFolder(), path);
        if (inData.isFile()) {
            return inData;
        }
        File dataDir = plugin.getDataFolder();
        File serverRoot = dataDir.getParentFile() == null ? null : dataDir.getParentFile().getParentFile();
        if (serverRoot != null) {
            File inServer = new File(serverRoot, path);
            if (inServer.isFile()) {
                return inServer;
            }
        }
        return null;
    }

    @Override
    public void publishPages() {
        CloudClientPages pages = plugin.getCloudClientPages();
        if (pages == null) {
            return;
        }
        try {
            pages.publish();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "云游戏客户端页面发布失败: " + t);
        }
    }

    // ===== 运行态：上报 / 心跳 / 手动刷新 =====

    @Override
    public AjaxResult report(String cloudKey, Map<String, Object> body) {
        try {
            String ip = str(body == null ? null : body.get("ip")).trim();
            int port = toInt(body == null ? null : body.get("port"), 0);
            if (ip.isEmpty() || port <= 0 || port > 65535) {
                return AjaxResult.error("上报缺少有效的 ip/port");
            }
            String key = cloudKey == null ? "" : cloudKey.trim();
            String name = str(body.get("name")).trim();
            String version = str(body.get("version")).trim();
            String mcVersion = str(firstNonNull(body.get("mcVersion"), body.get("mc-version"))).trim();
            // 客户端自报 scheme：配了证书就是 https。缺省沿用条目原值，别把已有判断抹掉。
            Boolean reportedTls = toBool(firstNonNull(body.get("tls"), body.get("https")));

            CloudClient target = findByAddress(ip, port);
            boolean created = false;
            if (target == null) {
                // TOFU：首次上报直接登记（内网信任模型；之后以记录的密钥为准）
                String id = String.valueOf(nextId++);
                target = new CloudClient();
                target.setId(id);
                target.setName(name.isEmpty() ? (ip + ":" + port) : name);
                target.setEnabled(true);
                target.setOrder(maxOrder() + 1);
                target.setSource(CloudClient.SOURCE_URL);
                target.setMissing(false);
                target.setIp(ip);
                target.setPort(port);
                target.setTls(Boolean.TRUE.equals(reportedTls));
                target.setPath(target.pageUrl());
                target.setCloudKey(key);
                target.setMcVersion(mcVersion);
                clients.put(id, target);
                created = true;
                if (!isServableQuiet(defaultId)) {
                    defaultId = id;
                }
            } else {
                // 已登记设备：密钥必须一致，否则拒绝（只能由管理员 applyKey 覆盖）
                String recorded = target.getCloudKey();
                if (!recorded.isEmpty() && !recorded.equals(key)) {
                    return AjaxResult.error("共享密钥不匹配：该设备已登记，请由管理员在 ERP/指令中更新密钥");
                }
                if (recorded.isEmpty()) {
                    target.setCloudKey(key);
                }
                target.setIp(ip);
                target.setPort(port);
                if (reportedTls != null) {
                    target.setTls(reportedTls);
                    target.setPath(rewriteScheme(target.getPath(), reportedTls));
                    if (target.getPath().isEmpty()) {
                        target.setPath(target.pageUrl());
                    }
                }
                if (!name.isEmpty()) {
                    target.setName(name);
                }
                if (!mcVersion.isEmpty()) {
                    target.setMcVersion(mcVersion);
                }
            }
            if (!version.isEmpty()) {
                target.setVersion(version);
            }
            target.setLastReport(now());
            persistNow();
            final CloudClient probeTarget = target;
            // 上报后立刻验一次密钥（异步；失败也只会把状态标成 unauthorized，不影响登记）
            submitProbe(probeTarget, true);
            Map<String, Object> data = new HashMap<>();
            data.put("id", target.getId());
            data.put("created", created);
            return AjaxResult.success(created ? "设备已登记：" + target.getId() : "设备状态已更新：" + target.getId(), data);
        } catch (Throwable t) {
            return AjaxResult.error("上报失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult refresh(String id) {
        CloudClient c = id == null ? null : clients.get(id);
        if (c == null) {
            return AjaxResult.error("客户端不存在: " + id);
        }
        if (c.address().isEmpty()) {
            return AjaxResult.error("该条目未配置设备地址（ip/port），请先让客户端上报或手动填写");
        }
        CloudClientProbe.Result r = probeClient(c, c.getCloudKey());
        applyResult(c, r);
        persistNow();
        return AjaxResult.success(summary(c, r), findVo(id));
    }

    @Override
    public AjaxResult refreshAll() {
        List<CloudClient> targets = new ArrayList<>();
        for (CloudClient c : clients.values()) {
            if (!c.address().isEmpty()) {
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            return AjaxResult.error("没有可刷新的设备（全部条目都未配置 ip/port）");
        }
        ExecutorService pool = ensurePool();
        List<java.util.concurrent.Future<CloudClientProbe.Result>> futures = new ArrayList<>();
        for (CloudClient c : targets) {
            futures.add(pool.submit(() -> probeClient(c, c.getCloudKey())));
        }
        int ok = 0;
        int unauthorized = 0;
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            CloudClient c = targets.get(i);
            CloudClientProbe.Result r;
            try {
                r = futures.get(i).get(probeTimeoutMs() + 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                r = null;
            }
            if (r == null) {
                c.setLastCheck(now());
                c.setFailCount(c.getFailCount() + 1);
                if (shouldMarkOffline(c)) {
                    c.setStatus(CloudClient.STATUS_OFFLINE);
                }
                failed++;
                continue;
            }
            applyResult(c, r);
            if (!r.reachable) {
                failed++;
            } else if (!r.authorized) {
                unauthorized++;
            } else {
                ok++;
            }
        }
        persistNow();
        Map<String, Object> data = new HashMap<>();
        data.put("total", targets.size());
        data.put("online", ok);
        data.put("unauthorized", unauthorized);
        data.put("failed", failed);
        data.put("rows", toVos());
        return AjaxResult.success("刷新完成：在线 " + ok + " / 鉴权失败 " + unauthorized + " / 不可达 " + failed, data);
    }

    @Override
    public AjaxResult applyKey(String id, String key) {
        CloudClient c = id == null ? null : clients.get(id);
        if (c == null) {
            return AjaxResult.error("客户端不存在: " + id);
        }
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return AjaxResult.error("密钥不能为空");
        }
        if (c.address().isEmpty()) {
            return AjaxResult.error("该条目未配置设备地址（ip/port）");
        }
        CloudClientProbe.Result r = probeClient(c, k);
        if (!r.reachable) {
            return AjaxResult.error("设备不可达，未更新密钥：" + r.error);
        }
        if (!r.authorized) {
            return AjaxResult.error("该密钥被设备拒绝（HTTP " + r.httpStatus + "），未更新");
        }
        c.setCloudKey(k);
        applyResult(c, r);
        persistNow();
        return AjaxResult.success("密钥已更新并验证通过", findVo(id));
    }

    @Override
    public void startHeartbeat() {
        if (heartbeatTask != null) {
            return;
        }
        int period = heartbeatSeconds();
        ensurePool();
        // 首轮立即跑（服务器重启后立刻校准状态），之后每 period 秒一轮
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::runHeartbeatRound, 1L);
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::runHeartbeatRound, period * 20L, period * 20L);
        LOG.info("云游戏客户端心跳已启动：周期 " + period + "s，离线阈值 " + ttlSeconds() + "s");
    }

    @Override
    public void stopHeartbeat() {
        BukkitTask task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
            }
        }
        ExecutorService pool = probePool;
        probePool = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** 一轮全量探测（异步线程内执行；同一时刻只允许一轮）。 */
    private void runHeartbeatRound() {
        if (!probing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<CloudClient> targets = new ArrayList<>();
            for (CloudClient c : clients.values()) {
                if (!c.address().isEmpty()) {
                    targets.add(c);
                }
            }
            if (targets.isEmpty()) {
                return;
            }
            ExecutorService pool = ensurePool();
            List<java.util.concurrent.Future<CloudClientProbe.Result>> futures = new ArrayList<>();
            for (CloudClient c : targets) {
                futures.add(pool.submit(() -> probeClient(c, c.getCloudKey())));
            }
            for (int i = 0; i < futures.size(); i++) {
                CloudClient c = targets.get(i);
                CloudClientProbe.Result r;
                try {
                    r = futures.get(i).get(probeTimeoutMs() + 1000L, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (Throwable t) {
                    r = null;
                }
                if (r == null) {
                    c.setLastCheck(now());
                    c.setFailCount(c.getFailCount() + 1);
                    if (shouldMarkOffline(c)) {
                        c.setStatus(CloudClient.STATUS_OFFLINE);
                    }
                } else {
                    applyResult(c, r);
                }
            }
            dirty = true;
            persistIfDue(false);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "云游戏客户端心跳失败: " + t);
        } finally {
            probing.set(false);
        }
    }

    /** 单独的异步探测（上报后立即验证密钥用）。 */
    private void submitProbe(CloudClient c, boolean persistAfter) {
        try {
            ensurePool().submit(() -> {
                try {
                    CloudClientProbe.Result r = probeClient(c, c.getCloudKey());
                    applyResult(c, r);
                    dirty = true;
                    if (persistAfter) {
                        persistIfDue(false);
                    }
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "上报后探测失败: " + t);
                }
            });
        } catch (Throwable t) {
            LOG.log(Level.FINE, "提交探测任务失败: " + t);
        }
    }

    /**
     * 按条目记录的 scheme 探测；明文失败时自动回退试一次 HTTPS。
     *
     * <p>回退是必要的：设备一开证书，明文请求会直接断（同一端口先嗅探后握手），
     * 而管理员的既有条目里记的还是 {@code tls=false}。一次成功即把条目翻成 tls
     * （见 {@link #applyResult}），之后就走对的那条路。</p>
     */
    private CloudClientProbe.Result probeClient(CloudClient c, String key) {
        CloudClientProbe.Result r = CloudClientProbe.probe(
                c.getIp(), c.getPort(), key, probeTimeoutMs(), c.isTls());
        if (r.reachable || c.isTls()) {
            return r;
        }
        CloudClientProbe.Result tls = CloudClientProbe.probe(
                c.getIp(), c.getPort(), key, probeTimeoutMs(), true);
        return tls.reachable ? tls : r;
    }

    /** 把一次探测结果落到条目上（online / unauthorized / offline + TTL 判定）。 */
    private void applyResult(CloudClient c, CloudClientProbe.Result r) {
        c.setLastCheck(now());
        c.setLatencyMs(r.latencyMs);
        if (!r.reachable) {
            c.setFailCount(c.getFailCount() + 1);
            if (shouldMarkOffline(c)) {
                c.setStatus(CloudClient.STATUS_OFFLINE);
            }
            return;
        }
        // 探测发现的 scheme 与记录不一致（刚开证书 / 刚撤证书）：以能通的那个为准，
        // 入口地址同步换 scheme，玩家点"打开页面"不会被带到错协议上。
        if (r.tls != c.isTls()) {
            c.setTls(r.tls);
            c.setPath(rewriteScheme(c.getPath(), r.tls));
        }
        if (!r.authorized) {
            c.setStatus(CloudClient.STATUS_UNAUTHORIZED);
            c.setFailCount(c.getFailCount() + 1);
            return;
        }
        c.setStatus(CloudClient.STATUS_ONLINE);
        c.setLastOk(now());
        c.setFailCount(0);
        if (!r.version.isEmpty()) {
            c.setVersion(r.version);
        }
        if (r.uptimeSeconds > 0) {
            c.setUptimeSeconds(r.uptimeSeconds);
        }
    }

    /** 只替换 URL 的 scheme；自定义路径（非 http(s) 开头）原样保留。 */
    private static String rewriteScheme(String path, boolean tls) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        String p = path.trim();
        if (p.startsWith("https://")) {
            return tls ? p : "http://" + p.substring("https://".length());
        }
        if (p.startsWith("http://")) {
            return tls ? "https://" + p.substring("http://".length()) : p;
        }
        return path;
    }

    /** 离线判定：成功过→看 lastOk 距现在是否超过 TTL；从未成功→连续 3 次失败。 */
    private boolean shouldMarkOffline(CloudClient c) {
        String lastOk = c.getLastOk();
        if (lastOk == null || lastOk.isEmpty()) {
            return c.getFailCount() >= 3;
        }
        try {
            LocalDateTime t = LocalDateTime.parse(lastOk, TIME_FMT);
            long ageSeconds = java.time.Duration.between(t, LocalDateTime.now()).getSeconds();
            return ageSeconds > ttlSeconds();
        } catch (Throwable t) {
            return c.getFailCount() >= 3;
        }
    }

    private String summary(CloudClient c, CloudClientProbe.Result r) {
        if (!r.reachable) {
            return "不可达：" + r.error;
        }
        if (!r.authorized) {
            return "密钥被拒绝（HTTP " + r.httpStatus + "）";
        }
        return "在线，版本 " + (r.version.isEmpty() ? "未知" : r.version)
                + "，耗时 " + r.latencyMs + "ms";
    }

    private CloudClient findByAddress(String ip, int port) {
        for (CloudClient c : clients.values()) {
            if (c.getPort() == port && c.getIp().equalsIgnoreCase(ip)) {
                return c;
            }
        }
        return null;
    }

    private ExecutorService ensurePool() {
        ExecutorService pool = probePool;
        if (pool == null || pool.isShutdown()) {
            synchronized (this) {
                if (probePool == null || probePool.isShutdown()) {
                    probePool = Executors.newFixedThreadPool(PROBE_CONCURRENCY, r -> {
                        Thread t = new Thread(r, "WebGame-CloudProbe");
                        t.setDaemon(true);
                        return t;
                    });
                }
                pool = probePool;
            }
        }
        return pool;
    }

    private int heartbeatSeconds() {
        int v = plugin.getConfig().getInt("cloud.heartbeat-seconds", DEFAULT_HEARTBEAT_SECONDS);
        return v >= 15 ? v : DEFAULT_HEARTBEAT_SECONDS;
    }

    private int ttlSeconds() {
        int v = plugin.getConfig().getInt("cloud.ttl-seconds", DEFAULT_TTL_SECONDS);
        return v >= 30 ? v : DEFAULT_TTL_SECONDS;
    }

    private int probeTimeoutMs() {
        int v = plugin.getConfig().getInt("cloud.probe-timeout-ms", DEFAULT_PROBE_TIMEOUT_MS);
        if (v < 500) {
            return DEFAULT_PROBE_TIMEOUT_MS;
        }
        return Math.min(v, 15000);
    }

    /** 上报/手动刷新这类低频动作：立刻落盘（这类调用本来就少）。 */
    private void persistNow() {
        save();
        lastPersistAt = System.currentTimeMillis();
        dirty = false;
    }

    /** 心跳类高频动作：只有状态变化且距上次落盘超过节流窗口才写。 */
    private void persistIfDue(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !dirty) {
            return;
        }
        if (!force && now - lastPersistAt < PERSIST_MIN_INTERVAL_SECONDS * 1000L) {
            return;
        }
        save();
        lastPersistAt = now;
        dirty = false;
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /** 密钥掩码：保留前 4 后 4，中间固定 4 个星号。 */
    private static String maskKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    // ===== 元数据 =====

    private synchronized void load() {
        Map<String, CloudClient> loaded = new TreeMap<>(ID_COMPARATOR);
        String def = "";
        int storedNextId = 0;
        File meta = metaFile();
        if (meta.isFile()) {
            try {
                YamlConfiguration yaml = PlatformYaml.load(meta);
                String d = yaml.getString("default-id", "");
                if (d != null && !d.trim().isEmpty()) {
                    def = d.trim();
                }
                allowPlayerSelect = yaml.getBoolean("allow-player-select", true);
                storedNextId = Math.max(storedNextId, yaml.getInt("next-id", 0));
                ConfigurationSection section = yaml.getConfigurationSection("clients");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        if (!isValidId(key)) {
                            continue;
                        }
                        ConfigurationSection c = section.getConfigurationSection(key);
                        if (c == null) {
                            continue;
                        }
                        CloudClient client = new CloudClient();
                        client.setId(key);
                        client.setName(c.getString("name", ""));
                        client.setPath(c.getString("path", ""));
                        client.setMcVersion(c.getString("mc-version", ""));
                        client.setEnabled(c.getBoolean("enabled", true));
                        client.setOrder(c.getInt("order", -1));
                        client.setSource(c.getString("source", CloudClient.SOURCE_LOCAL));
                        // 运行态（可能不存在：老文件升级上来时全部为空/unknown）
                        client.setIp(c.getString("ip", ""));
                        client.setPort(c.getInt("port", 0));
                        client.setTls(c.getBoolean("tls", false));
                        client.setStatus(c.getString("status", CloudClient.STATUS_UNKNOWN));
                        client.setVersion(c.getString("version", ""));
                        client.setUptimeSeconds(c.getLong("uptime-s", 0));
                        client.setLastReport(c.getString("last-report", ""));
                        client.setLastOk(c.getString("last-ok", ""));
                        client.setLastCheck(c.getString("last-check", ""));
                        client.setFailCount(c.getInt("fail-count", 0));
                        client.setCloudKey(c.getString("cloud-key", ""));
                        client.setLatencyMs(c.getLong("latency-ms", 0));
                        loaded.put(key, client);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "读取 " + META_FILE + " 失败: " + t);
            }
        }

        int maxId = 0;
        for (CloudClient c : loaded.values()) {
            int n = toInt(c.getId(), 0);
            if (n > maxId) {
                maxId = n;
            }
        }
        nextId = Math.max(Math.max(storedNextId, maxId + 1), 1);

        for (CloudClient c : loaded.values()) {
            c.setMissing(!entryExists(c));
        }

        // 排序迁移：未指定顺序的条目按 id 顺序追加到已有序号之后
        int nextOrder = 0;
        for (CloudClient c : loaded.values()) {
            if (c.getOrder() >= 0 && c.getOrder() >= nextOrder) {
                nextOrder = c.getOrder() + 1;
            }
        }
        for (CloudClient c : loaded.values()) {
            if (c.getOrder() < 0) {
                c.setOrder(nextOrder++);
            }
        }

        if (!loaded.containsKey(def)) {
            def = pickFallbackDefault(loaded);
        }
        this.clients = new ConcurrentSkipListMap<>(loaded);
        this.defaultId = def;
        save();
    }

    private synchronized void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("default-id", defaultId);
            root.put("allow-player-select", allowPlayerSelect);
            root.put("next-id", nextId);
            Map<String, Object> items = new LinkedHashMap<>();
            for (CloudClient c : clients.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.getName());
                m.put("path", c.getPath());
                m.put("mc-version", c.getMcVersion());
                m.put("enabled", c.isEnabled());
                m.put("order", c.getOrder());
                m.put("source", c.getSource());
                // 运行态：必须一起写回，否则管理员在 ERP 改任意字段都会把状态抹掉
                m.put("ip", c.getIp());
                m.put("port", c.getPort());
                m.put("tls", c.isTls());
                m.put("status", c.getStatus());
                m.put("version", c.getVersion());
                m.put("uptime-s", c.getUptimeSeconds());
                m.put("last-report", c.getLastReport());
                m.put("last-ok", c.getLastOk());
                m.put("last-check", c.getLastCheck());
                m.put("fail-count", c.getFailCount());
                m.put("cloud-key", c.getCloudKey());
                m.put("latency-ms", c.getLatencyMs());
                items.put(c.getId(), m);
            }
            root.put("clients", items);
            // 原子落盘：先写临时文件再整体替换，避免半截 YAML 把整个配置写坏
            File target = metaFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            File tmp = new File(parent, META_FILE + ".tmp");
            PlatformYaml.save(ConfigUtil.toYaml(root), tmp);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailed) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "写入 " + META_FILE + " 失败: " + t);
        }
    }

    private File metaFile() {
        return new File(plugin.getDataFolder(), META_FILE);
    }

    // ===== 辅助 =====

    private List<CloudClientVo> toVos() {
        List<CloudClientVo> rows = new ArrayList<>();
        String prefix = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH;
        CloudClientPages pages = plugin.getCloudClientPages();
        for (CloudClient c : versions()) {
            CloudClientVo vo = new CloudClientVo();
            vo.setId(c.getId());
            vo.setName(c.getName());
            vo.setPath(c.getPath());
            vo.setMcVersion(c.getMcVersion());
            vo.setEnabled(c.isEnabled());
            vo.setOrder(c.getOrder());
            vo.setDefaultVersion(c.getId().equals(defaultId));
            vo.setSource(c.getSource());
            vo.setMissing(c.isMissing());
            vo.setPagePath(prefix + c.getId() + "/");
            String livePrefix = pages == null ? null : pages.livePrefixOf(c.getId());
            vo.setLivePath(livePrefix == null || livePrefix.isEmpty() ? "" : livePrefix + "/");
            vo.setIp(c.getIp());
            vo.setPort(c.getPort());
            vo.setTls(c.isTls());
            vo.setAddress(c.address());
            vo.setStatus(c.getStatus());
            vo.setVersion(c.getVersion());
            vo.setUptimeSeconds(c.getUptimeSeconds());
            vo.setLastReport(c.getLastReport());
            vo.setLastOk(c.getLastOk());
            vo.setLastCheck(c.getLastCheck());
            vo.setFailCount(c.getFailCount());
            vo.setLatencyMs(c.getLatencyMs());
            vo.setCloudKeyMasked(maskKey(c.getCloudKey()));
            rows.add(vo);
        }
        return rows;
    }

    private CloudClientVo findVo(String id) {
        for (CloudClientVo vo : toVos()) {
            if (vo.getId().equals(id)) {
                return vo;
            }
        }
        return null;
    }

    private boolean isServableQuiet(String id) {
        CloudClient c = id == null ? null : clients.get(id);
        return c != null && isServable(c);
    }

    /**
     * 是否可作为玩家入口：启用 + 入口存在 + 运行态未判离线/密钥被拒。
     *
     * <p>{@code unknown} 视为可用：新登记设备尚未探测过，不能一配置就被踢出默认。</p>
     */
    private boolean isServable(CloudClient c) {
        if (c == null || !c.isEnabled() || c.isMissing()) {
            return false;
        }
        String status = c.getStatus();
        return !CloudClient.STATUS_OFFLINE.equals(status)
                && !CloudClient.STATUS_UNAUTHORIZED.equals(status);
    }

    /** URL → url；其余 → local。 */
    private String deriveSource(CloudClient c) {
        return CloudClient.isUrl(c.getPath()) ? CloudClient.SOURCE_URL : CloudClient.SOURCE_LOCAL;
    }

    private boolean entryExists(CloudClient c) {
        return CloudClient.isUrl(c.getPath()) || resolveClientFile(c) != null;
    }

    private void applyBody(CloudClient c, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        String name = str(body.get("name")).trim();
        c.setName(name);
        String path = str(body.get("path")).trim();
        if (!path.isEmpty()) {
            c.setPath(path);
        }
        c.setMcVersion(str(body.get("mcVersion")).trim());
        Boolean enabled = toBool(body.get("enabled"));
        if (enabled != null) {
            c.setEnabled(enabled);
        }
        // 设备地址与协议允许管理员手填（客户端上报时也会覆盖成自报值）
        String oldAddress = c.address();
        String ip = str(body.get("ip")).trim();
        if (!ip.isEmpty()) {
            c.setIp(ip);
        }
        int port = toInt(body.get("port"), -1);
        if (port > 0 && port <= 65535) {
            c.setPort(port);
        }
        Boolean tls = toBool(body.get("tls"));
        if (tls != null) {
            c.setTls(tls);
        }
        // 入口地址是"自动拼出来的"（空，或者就指向旧 ip:port 根路径）时跟着一起换；
        // 管理员手填过业务路径的，只换 scheme。
        String newAddress = c.address();
        if (!newAddress.isEmpty() && !newAddress.equals(oldAddress)) {
            String auto = (c.isTls() ? "https://" : "http://") + oldAddress + "/";
            if (c.getPath().isEmpty() || c.getPath().equals(auto)
                    || c.getPath().equals("http://" + oldAddress + "/")
                    || c.getPath().equals("https://" + oldAddress + "/")) {
                c.setPath(c.pageUrl());
            }
        }
        if (tls != null) {
            c.setPath(rewriteScheme(c.getPath(), tls));
        }
    }

    private int maxOrder() {
        int max = -1;
        for (CloudClient c : clients.values()) {
            if (c.getOrder() > max) {
                max = c.getOrder();
            }
        }
        return max;
    }

    private String pickFallbackDefault() {
        return pickFallbackDefault(clients);
    }

    private static String pickFallbackDefault(Map<String, CloudClient> map) {
        CloudClient best = null;
        for (CloudClient c : map.values()) {
            if (!c.isEnabled() || c.isMissing()) {
                continue;
            }
            String status = c.getStatus();
            if (CloudClient.STATUS_OFFLINE.equals(status)
                    || CloudClient.STATUS_UNAUTHORIZED.equals(status)) {
                continue;
            }
            if (best == null || c.getOrder() < best.getOrder()
                    || (c.getOrder() == best.getOrder() && ID_COMPARATOR.compare(c.getId(), best.getId()) < 0)) {
                best = c;
            }
        }
        return best == null ? "" : best.getId();
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("\\d{1,9}");
    }

    private static Boolean toBool(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            String s = String.valueOf(o).trim();
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
