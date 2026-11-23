package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.soyshttpovermc.web.upstream.UpstreamTarget;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 云游戏客户端页面热登记。
 *
 * <p>每个启用条目登记到 {@code /cloud/<id>/}（磁盘目录懒读）；{@code /cloud/} 登记为
 * 统一选择页。所有登记条目都打上 {@link #TAG}，变更时 {@code unregisterByTag} 精确撤销
 * 后再重建，不影响 dist 与 eagler 客户端页面。</p>
 */
public final class CloudClientPages {

    private static final Logger LOG = Logger.getLogger(CloudClientPages.class.getName());

    /** 本类登记条目的专用 tag（与 dist / eagler 客户端页面隔离）。 */
    public static final String TAG = "webgame-cloud-client-pages";

    private static final String INDEX_FILE = "index.html";
    /** 云游戏客户端固定挂载路径（相对插件页面根）。 */
    private static final String PAGE_PATH = "/cloud/";
    /**
     * 同端口转播子路径：{@code /cloud/<id>/} 是跳转页，{@code /cloud/<id>/live/} 才是
     * 上游代理前缀。两者必须分开——页面注册 key 与 upstream 前缀共用同一路径会互相抢命中。
     */
    private static final String LIVE_SEGMENT = "/live";
    /** 会话包装页子路径：{@code /cloud/<id>/play/}，里面 iframe 载直播页、轮询会话状态。 */
    private static final String PLAY_SEGMENT = "/play/";
    /** 插件 API 前缀（页面里用 fetch 直接打会话端点）。 */
    private static final String API_PREFIX = "/api/plugins/WebGame";
    /** 根路径门牌前缀：{@code /cloud/<别名>}（不经 /web/plugins 命名空间）。 */
    private static final String ROOT_CLOUD_PATH = "/cloud/";
    /** 别名长度上限（够写"测试服务器"或"test"，太长没人愿意敲）。 */
    private static final int ALIAS_MAX_LEN = 24;
    /** 别名保留字：与我们的固定子路径/常用入口冲突。 */
    private static final java.util.Set<String> ALIAS_RESERVED = new java.util.HashSet<>(java.util.Arrays.asList(
            "session", "live", "play", "index", "index.html", "favicon.ico", "pool", "auto", ".", ".."));
    /** 池入口前缀：{@code /cloud/pool/<池名>}。<code>pool</code> 因此被列为别名保留字。 */
    private static final String POOL_SEGMENT = "pool";
    /**
     * 唯一入口前缀：{@code /cloud/auto/}（{@code /cloud/} 根目录顶部也会给一张入口卡）。
     * <p>与池入口的区别：池是管理员已经分好的一组机器，唯一入口是"整个集群里给我一台空闲的"，
     * 且浏览器不带任何设备密钥（服务端用票据挑机）。<code>auto</code> 因此也是别名保留字。</p>
     */
    private static final String AUTO_SEGMENT = "auto";

    private final WebGame plugin;
    private final CloudClientService cloudClientService;
    private final com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService playService;

    private volatile SoysHttpOverMcApi api;

    /**
     * 已生效的同端口转播基址（id → {@code /web/plugins/WebGame/cloud/&lt;id&gt;/live}）。
     *
     * <p>注意这不是"能直接打开的直播地址"：真正的直播前缀是<b>每会话</b>的
     * {@code <基址>/<seg>/}（见 {@link #livePathOf(String, String)}），没有会话时
     * 基址上只有一张门禁页。ERP 列表用它展示"这台机器走同端口接入"，不是给玩家点的链接。</p>
     */
    private volatile java.util.Map<String, String> livePrefixes = java.util.Collections.emptyMap();

    /** 别名（已规范化）→ 客户端 id；publish 时重建，供会话层校验"别名属于谁"。 */
    private volatile java.util.Map<String, String> aliasToClient = java.util.Collections.emptyMap();

    /** 客户端包摘要缓存（按 mtime + size 命中，避免每次 publish 重算几十 MB 的 sha256）。 */
    private long cachedPkgMtime = -1;
    private long cachedPkgSize = -1;
    private String cachedPkgSha = "";
    private int cachedPkgFiles = 0;

    public CloudClientPages(WebGame plugin, CloudClientService cloudClientService,
                            com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService playService) {
        this.plugin = plugin;
        this.cloudClientService = cloudClientService;
        this.playService = playService;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
    }

    /** 页面扩展是否就绪（启动对齐要等它，否则开闸没有落点）。 */
    public boolean isAttached() {
        return api != null && api.getWebPage() != null;
    }

    /** 某条目当前生效的同端口转播前缀（未转播返回 null）。 */
    public String livePrefixOf(String id) {
        if (id == null) {
            return null;
        }
        return livePrefixes.get(id);
    }

    /** 同端口转播开关（{@code cloud.same-port-relay}，默认开启）。 */
    private boolean relayEnabled() {
        try {
            return plugin.getConfig().getBoolean("cloud.same-port-relay", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 幂等重建：先撤销本 tag 全部条目，再按当前客户端列表重新登记。 */
    public synchronized void publish() {
        SoysHttpOverMcApi a = api;
        if (a == null) {
            return;
        }
        WebPageApi pages = a.getWebPage();
        if (pages == null) {
            return;
        }
        try {
            pages.unregisterByTag(TAG);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "unregister cloud client pages failed: " + t);
        }
        // 同端口转播前缀与页面同生命周期：先全撤，再按当前客户端重建
        try {
            a.unregisterUpstreamByTag(TAG);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "unregister cloud upstreams failed: " + t);
        }

        String rel = PAGE_PATH.substring(1);
        boolean allowSelect = cloudClientService.allowPlayerSelect();
        String defaultId = cloudClientService.defaultId();
        boolean relay = relayEnabled();
        java.util.Map<String, String> live = new java.util.LinkedHashMap<>();
        // 别名表先建好：全局唯一（同一别名只归一台机器），重复的按顺序先到先得并记警告
        java.util.Map<String, String> aliasMap = buildAliasMap();
        for (CloudClient c : cloudClientService.versions()) {
            if (!c.isEnabled() || c.isMissing()) {
                continue;
            }
            // 禁止自选时只开放默认客户端页面，其余页面 404
            if (!allowSelect && !c.getId().equals(defaultId)) {
                continue;
            }
            String id = c.getId();
            String entry = PAGE_PATH + id + "/";
            if (CloudClient.isUrl(c.getPath())) {
                // 远程客户端（cloud-stream）：默认走 SOYS 同端口转播（前缀 + 反代 + wss 隧道），
                // 外网用户只需要 MC 端口，不必再为 .128:8080 单独做端口映射。
                // 若 SOYS 侧不支持 upstream（老 jar），自动退回原来的绝对地址跳转。
                String livePrefix = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + id + LIVE_SEGMENT;
                String token = c.getCloudKey();
                // 直播前缀默认不开：只有在会话进行中才注册 upstream（openLive）。
                // 否则任何人拿到地址就能直接看/操作 .128 的桌面（游戏未跑时那就是一台解锁的 Windows）。
                String liveUrl;
                try {
                    a.unregisterUpstreamByTag(liveTag(id));
                } catch (Throwable ignored) {
                }
                liveUrl = relay ? appendToken(livePrefix + "/", token) : withToken(c.getPath().trim(), token);
                if (relay) {
                    live.put(id, livePrefix);
                }
                // 入口页不再直接跳直播：先收游戏名 → 起会话 → 才进直播（锁桌面那条链路的前置条件）
                registerPage(pages, entry, buildEntryPage(c, token, ""));
                registerPage(pages, PAGE_PATH + id + PLAY_SEGMENT,
                        buildPlayPage(c, liveUrl, token, WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH, ""));
                if (relay) {
                    // 命名空间下的直播前缀也要有门禁页（会话说"没开闸"时别落 SPA 兜底）
                    // 路径里已经带了 /web/plugins/WebGame 前缀，必须用 proxy 登记（不补前缀）
                    byte[] gate = buildLiveGatePage(c,
                            WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + id).getBytes(StandardCharsets.UTF_8);
                    registerProxyPage(pages, livePrefix, gate);
                    registerProxyPage(pages, livePrefix + "/", gate);
                }
                // 根路径门牌：/cloud/<别名> 直接就是同一套入口+包装页，地址栏全程不暴露内部 id
                if (relay) {
                    for (String alias : normalizedAliases(c)) {
                        if (!aliasMap.containsKey(alias) || !aliasMap.get(alias).equals(id)) {
                            continue;
                        }
                        registerAliasPages(pages, c, alias, token, liveUrl);
                    }
                }
                continue;
            }
            File file = cloudClientService.resolveClientFile(c);
            if (file == null) {
                continue;
            }
            if (INDEX_FILE.equalsIgnoreCase(file.getName())) {
                try {
                    Set<WebRegistry.Entry> entries = pages.registerDirectory(
                            plugin, "/" + rel + id, file.getParentFile());
                    tag(entries);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "登记云游戏客户端目录失败 " + id + ": " + t);
                }
            } else {
                try {
                    registerPage(pages, entry, new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "登记云游戏客户端页面失败 " + id + ": " + t);
                }
            }
        }

        // 默认客户端缺失时仍展示选择页（空状态），避免跳转到无效地址
        String entryHtml = (!allowSelect && !defaultId.isEmpty())
                ? buildRedirectPage(defaultEntry(defaultId))
                : buildSelectionPage();
        registerPage(pages, PAGE_PATH, entryHtml);
        // 根路径门牌目录：/cloud 与 /cloud/ 都登记（浏览器只发其一，另一边 404 会让人以为服务坏了）
        String dirHtml = buildRootDirectoryPage(aliasMap);
        byte[] dirBytes = dirHtml.getBytes(StandardCharsets.UTF_8);
        registerProxyPage(pages, ROOT_CLOUD_PATH, dirBytes);
        registerProxyPage(pages, ROOT_CLOUD_PATH.substring(0, ROOT_CLOUD_PATH.length() - 1), dirBytes);
        // 池入口：/cloud/pool/<池名> —— 一个名字对应多台机器，点进去由服务端挑一台空闲的
        registerPoolPages(pages, allowSelect);
        // 唯一入口：/cloud/auto/ —— 整张短地址，玩家只填游戏名，机器由服务端挑，
        // 浏览器手里没有（也不需要）任何设备的 cloud-key
        registerAutoPages(pages);
        livePrefixes = live;
        aliasToClient = aliasMap;
        // 客户端更新包：目录登记 + manifest（代理按 version/sha256 决定要不要下载）
        publishClientPackage(pages);
    }

    /**
     * 客户端更新包托管：{@code plugins/WebGame/packages/} 整个目录登记成可下载目录，
     * 同时把 {@code manifest.json}（version + 文件数 + sha256）放到同路径下。
     *
     * <p>代理只认这份 manifest：版本号来自 config.yml，sha256 由磁盘上的 zip 现算，
     * 所以"把新包丢进目录 + 改 version"就是一次发布，不需要重启插件。</p>
     */
    private void publishClientPackage(WebPageApi pages) {
        File dir = new File(plugin.getDataFolder(), "packages");
        if (!dir.isDirectory()) {
            return;
        }
        String rel = "/cloud/client";
        try {
            Set<WebRegistry.Entry> entries = pages.registerDirectory(plugin, rel, dir);
            tag(entries);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记客户端更新包目录失败: " + t);
        }
        try {
            registerPage(pages, rel + "/manifest.json", buildClientManifest(dir));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记 manifest 失败: " + t);
        }
    }

    /** manifest.json：版本 + 大小 + 文件数 + sha256 + 下载地址。 */
    private String buildClientManifest(File dir) {
        String version = "";
        String pkg = "cloud-client.zip";
        String target = "";
        boolean enabled = true;
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
            enabled = cfg.getBoolean("cloud.client-update.enabled", true);
            version = cfg.getString("cloud.client-update.version", "");
            pkg = cfg.getString("cloud.client-update.package", pkg);
            target = cfg.getString("cloud.client-update.target-dir", "");
        } catch (Throwable ignored) {
        }
        version = version == null ? "" : version.trim();
        pkg = pkg == null || pkg.trim().isEmpty() ? "cloud-client.zip" : pkg.trim();
        File zip = new File(dir, pkg);
        long size = zip.isFile() ? zip.length() : 0;
        long mtime = zip.isFile() ? zip.lastModified() : 0;
        String sha = "";
        int files = 0;
        if (zip.isFile()) {
            // 同一个文件不重复算：zip 动辄几十 MB，manifest 又会被反复重建
            if (mtime == cachedPkgMtime && size == cachedPkgSize && !cachedPkgSha.isEmpty()) {
                sha = cachedPkgSha;
                files = cachedPkgFiles;
            } else {
                try {
                    sha = sha256(zip);
                    files = countZipEntries(zip);
                    cachedPkgMtime = mtime;
                    cachedPkgSize = size;
                    cachedPkgSha = sha;
                    cachedPkgFiles = files;
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "计算客户端包摘要失败 " + zip + ": " + t);
                }
            }
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"enabled\":").append(enabled)
                .append(",\"version\":\"").append(jsonStr(version))
                .append("\",\"package\":\"").append(jsonStr(pkg))
                .append("\",\"size\":").append(size)
                .append(",\"files\":").append(files)
                .append(",\"sha256\":\"").append(sha)
                .append("\",\"targetDir\":\"").append(jsonStr(target))
                .append("\",\"url\":\"").append(jsonStr(WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/client/" + pkg))
                .append("\"}");
        return sb.toString();
    }

    private String clientUrlFor(String pkg) {
        String base = "";
        try {
            base = plugin.getConfig().getString("cloud.client-update.base-url", "").trim();
        } catch (Throwable ignored) {
        }
        if (base.isEmpty()) {
            return "";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/client/" + pkg;
    }

    private static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** zip 里的文件条数（不含目录项）；用于 manifest 里给人看的"文件数"。 */
    private static int countZipEntries(File zip) throws Exception {
        int count = 0;
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (!e.isDirectory()) {
                    count++;
                }
            }
        } finally {
            zf.close();
        }
        return count;
    }

    private static String jsonStr(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 撤销本类登记的全部页面（插件停用时调用）。 */
    public synchronized void unpublish() {
        SoysHttpOverMcApi a = api;
        if (a == null) {
            return;
        }
        try {
            WebPageApi pages = a.getWebPage();
            if (pages != null) {
                pages.unregisterByTag(TAG);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "unpublish cloud client pages failed: " + t);
        }
        try {
            for (CloudClient c : cloudClientService.versions()) {
                a.unregisterUpstreamByTag(liveTag(c.getId()));
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "unpublish cloud upstreams failed: " + t);
        }
        livePrefixes = java.util.Collections.emptyMap();
    }

    /**
     * 开一次会话的直播通道：只注册<b>该会话专属</b>的随机前缀
     * {@code /web/plugins/WebGame/cloud/<id>/live/<seg>/}。
     *
     * <p>这是防"绕开入口页"的关键一环——页面只是体验，真正的门在这里：</p>
     * <ul>
     *   <li>没有进行中的会话，这个随机前缀根本不存在，猜测 {@code /cloud/<id>/live/} 只会拿到 403 门禁页；</li>
     *   <li>前缀里带每会话随机的 {@code seg}，别人无法从"已知有会话"推出别人的画面地址；</li>
     *   <li>即使拿到地址，selkies 仍要求令牌（由包装页从会话状态里取，放 URL 片段 {@code #token=}，
     *       不进请求行、不进代理日志）。</li>
     * </ul>
     *
     * @param clientId 目标云机
     * @param seg      会话专属随机段（由 {@code CloudPlayService} 生成并随会话保存）
     * @return true 表示已开闸；false 表示同端口转播不可用（老 jar / 关闭开关）
     */
    public synchronized boolean openLive(String clientId, String seg) {
        SoysHttpOverMcApi a = api;
        if (a == null || !relayEnabled()) {
            return false;
        }
        CloudClient c = clientOf(clientId);
        if (c == null || !CloudClient.isUrl(c.getPath())) {
            return false;
        }
        String livePrefix = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + clientId + LIVE_SEGMENT
                + "/" + seg;
        boolean ok = false;
        try {
            ok = registerLiveUpstream(a, livePrefix, c, clientId, liveTag(clientId));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "会话直播前缀注册失败 " + livePrefix + ": " + t);
        }
        return ok;
    }

    /** 会话专属直播前缀（供会话状态里的 {@code liveUrl} 拼装）。 */
    public String livePathOf(String clientId, String seg) {
        return WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + clientId + LIVE_SEGMENT + "/" + seg + "/";
    }

    /** 会话结束时"关闸"：撤销该客户端所有前缀（命名空间 + 各别名，共用同一 tag）。 */
    public synchronized void closeLive(String clientId) {
        SoysHttpOverMcApi a = api;
        if (a == null) {
            return;
        }
        try {
            a.unregisterUpstreamByTag(liveTag(clientId));
        } catch (Throwable t) {
            LOG.log(Level.FINE, "close cloud live upstream failed " + clientId + ": " + t);
        }
    }

    /** 别名（已规范化）归属哪个客户端；未登记/未启用返回 null。 */
    public CloudClient resolveAlias(String alias) {
        String key = normalizeAlias(alias);
        if (key.isEmpty()) {
            return null;
        }
        String clientId = aliasToClient.get(key);
        return clientId == null ? null : clientOf(clientId);
    }

    /** 某客户端的别名列表（规范化后，已排除冲突/保留字/非法项）。 */
    public List<String> aliasesOf(String clientId) {
        java.util.Map<String, String> map = aliasToClient;
        List<String> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            if (!e.getValue().equals(clientId)) {
                continue;
            }
            // 只回规范形（小写），大小写变体是同一个门牌的别名，不重复展示
            if (e.getKey().equals(e.getKey().toLowerCase(java.util.Locale.ROOT))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private boolean isAliasOwner(String alias, String clientId) {
        String owner = aliasToClient.get(alias);
        return owner == null || owner.equals(clientId);
    }

    /**
     * 建别名表：规范化 → 去重 → 全局唯一。
     * <p>同一别名被两台机器登记时按 {@code order} 先到先得，后到的记警告并丢弃——
     * 宁可少一块门牌，也不能出现"点 A 名字进了 B 机器"。</p>
     */
    private java.util.Map<String, String> buildAliasMap() {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (CloudClient c : cloudClientService.versions()) {
            if (!c.isEnabled() || c.isMissing()) {
                continue;
            }
            for (String alias : normalizedAliases(c)) {
                String owner = map.get(alias);
                if (owner == null) {
                    map.put(alias, c.getId());
                } else if (!owner.equals(c.getId())) {
                    LOG.warning("别名冲突：/cloud/" + alias + " 已属于客户端 " + owner
                            + "，忽略客户端 " + c.getId() + " 的同名登记");
                    continue;
                }
                // 大小写等价写法一并入表，让 /cloud/TEST、/cloud/Test 也能过"别名归属"校验
                for (String v : aliasVariants(alias)) {
                    if (!map.containsKey(v)) {
                        map.put(v, c.getId());
                    }
                }
            }
        }
        return map;
    }

    /** 客户端声明的别名：规范化 + 过滤非法/保留字/重复。 */
    private List<String> normalizedAliases(CloudClient c) {
        List<String> out = new ArrayList<>();
        List<String> raw = c.getAliases();
        if (raw == null) {
            return out;
        }
        for (String a : raw) {
            String n = normalizeAlias(a);
            if (n.isEmpty() || ALIAS_RESERVED.contains(n) || out.contains(n)) {
                if (!n.isEmpty() && ALIAS_RESERVED.contains(n)) {
                    LOG.warning("别名 " + a + " 是保留字，已忽略（客户端 " + c.getId() + "）");
                }
                continue;
            }
            out.add(n);
        }
        return out;
    }

    /**
     * 规范化别名：去空白、去首尾斜杠、ASCII 转小写（中文原样保留）。
     * <p>ASCII 不区分大小写是为了"Test / test / TEST 都是同一块门牌"；
     * 中文没有大小写，不做额外变换，避免 NFC/NFD 之类反而制造两个 key。</p>
     */
    public static boolean isReservedAlias(String normalized) {
        return normalized != null && ALIAS_RESERVED.contains(normalized);
    }

    public static String normalizeAlias(String alias) {
        if (alias == null) {
            return "";
        }
        String s = alias.trim();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > ALIAS_MAX_LEN) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#' || ch == '%' || ch == '\\' || Character.isWhitespace(ch)) {
                return "";
            }
            if ((ch >= 'A' && ch <= 'Z')) {
                ch = (char) (ch + 32);
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /** 期望的客户端包版本（config.yml 的 cloud.client-update.version；空 = 不比对）。 */
    public String clientPackageVersion() {
        try {
            String v = plugin.getConfig().getString("cloud.client-update.version", "");
            return v == null ? "" : v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 客户端包下载地址（给云主机代理用；base-url 没配就返回空，代理会跳过更新）。 */
    public String clientPackageUrl() {
        String pkg = "cloud-client.zip";
        try {
            String p = plugin.getConfig().getString("cloud.client-update.package", pkg);
            if (p != null && !p.trim().isEmpty()) {
                pkg = p.trim();
            }
        } catch (Throwable ignored) {
        }
        return clientUrlFor(pkg);
    }

    /** 当前包 sha256（与 manifest 同源；包不存在返回空串）。 */
    public String clientPackageSha() {
        File dir = new File(plugin.getDataFolder(), "packages");
        String pkg = "cloud-client.zip";
        try {
            String p = plugin.getConfig().getString("cloud.client-update.package", pkg);
            if (p != null && !p.trim().isEmpty()) {
                pkg = p.trim();
            }
        } catch (Throwable ignored) {
        }
        File zip = new File(dir, pkg);
        if (!zip.isFile()) {
            return "";
        }
        if (zip.lastModified() == cachedPkgMtime && zip.length() == cachedPkgSize && !cachedPkgSha.isEmpty()) {
            return cachedPkgSha;
        }
        try {
            cachedPkgMtime = zip.lastModified();
            cachedPkgSize = zip.length();
            cachedPkgSha = sha256(zip);
            cachedPkgFiles = countZipEntries(zip);
            return cachedPkgSha;
        } catch (Throwable t) {
            LOG.log(Level.FINE, "计算客户端包摘要失败: " + t);
            return "";
        }
    }

    /** 登记某别名的入口页与包装页（根路径，无 /web/plugins 前缀）。 */
    private void registerAliasPages(WebPageApi pages, CloudClient c, String alias, String token, String liveUrl) {
        byte[] entry = buildEntryPage(c, token, alias).getBytes(StandardCharsets.UTF_8);
        byte[] play = buildPlayPage(c, appendToken(ROOT_CLOUD_PATH + alias + LIVE_SEGMENT + "/", token),
                token, ROOT_CLOUD_PATH, alias).getBytes(StandardCharsets.UTF_8);
        // 注册表是精确匹配、不折叠大小写，所以 ASCII 别名要把 test / TEST / Test 三种写法都登记上
        for (String v : aliasVariants(alias)) {
            String base = ROOT_CLOUD_PATH + v;
            // 带/不带尾斜杠都登记：人手动敲地址时十有八九不带斜杠
            registerProxyPage(pages, base, entry);
            registerProxyPage(pages, base + "/", entry);
            registerProxyPage(pages, base + "/play", play);
            registerProxyPage(pages, base + "/play/", play);
            // 直播前缀的门禁页：没会话时不该落 SPA 兜底（语义含糊），给一页明确的"请从入口进入"
            byte[] gate = buildLiveGatePage(c, base).getBytes(StandardCharsets.UTF_8);
            registerProxyPage(pages, base + LIVE_SEGMENT, gate);
            registerProxyPage(pages, base + LIVE_SEGMENT + "/", gate);
        }
    }

    /**
     * 无会话访问 {@code live} 的提示页（403 语义）。
     *
     * <p>直播前缀只在会话期由 upstream 注册，会话一结束就撤掉；撤掉之后如果什么都不登记，
     * 请求会落到 WebGame SPA 兜底——页面看着"正常"，实际是在骗人。这里登记一页静态的
     * 门禁页：告诉玩家通道没开、给出从哪个入口重进的链接。</p>
     *
     * <p>说明：SOYS 的注册页接口只出口 200，拿不到真正的 403 状态码；页面上按 403 语义
     * 标注，HTTP 状态仍是 200。</p>
     */
    private String buildLiveGatePage(CloudClient c, String entryPath) {
        String name = escape(c.getName());
        String entry = escape(entryPath);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>403 · 直播通道未开启</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:560px;padding:40px 24px}\n"
                + ".code{font-size:64px;font-weight:700;letter-spacing:4px;color:#ff8b8b;margin:0 0 8px}\n"
                + "h1{margin:0 0 12px;font-size:22px;font-weight:600}\n"
                + ".sub{margin:0 0 26px;color:#9aa6bd;font-size:14px;line-height:1.9}\n"
                + ".btn{display:inline-block;padding:12px 20px;border-radius:12px;text-decoration:none;"
                + "background:linear-gradient(135deg,#4f8cff,#2f6ad9);color:#fff;font-size:15px;font-weight:600}\n"
                + ".hint{margin-top:22px;font-size:12px;color:#6f7a90;line-height:1.8}\n"
                + "code{font-family:ui-monospace,Consolas,monospace;color:#7fa7ff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<p class=\"code\">403</p>\n"
                + "<h1>直播通道未开启 · " + name + "</h1>\n"
                + "<p class=\"sub\">这个地址是云游戏的<strong>画面通道</strong>，只在会话进行中开放。" + "\n"
                + "现在没有进行中的会话，所以它拒绝访问——这是设计行为，不是故障。</p>\n"
                + "<a class=\"btn\" href=\"" + entry + "\">请从入口进入</a>\n"
                + "<p class=\"hint\">入口要先填游戏名、起一次会话，画面通道才会打开：<code>" + entry + "</code></p>\n"
                + "</div>\n</body>\n</html>\n";
    }

    private static String urlToken(String token) {
        try {
            return java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Throwable t) {
            return token;
        }
    }

    /**
     * ASCII 别名的大小写等价写法：小写（规范形）、全大写、首字母大写。
     * <p>中文别名原样返回——中文没有大小写，也不做 NFC/NFD 变换。</p>
     */
    public static List<String> aliasVariants(String alias) {
        List<String> out = new ArrayList<>();
        if (alias == null || alias.isEmpty()) {
            return out;
        }
        out.add(alias);
        boolean ascii = true;
        for (int i = 0; i < alias.length(); i++) {
            if (alias.charAt(i) > 127) {
                ascii = false;
                break;
            }
        }
        if (!ascii) {
            return out;
        }
        String upper = alias.toUpperCase(java.util.Locale.ROOT);
        if (!out.contains(upper)) {
            out.add(upper);
        }
        String cap = alias.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + alias.substring(1);
        if (!out.contains(cap)) {
            out.add(cap);
        }
        return out;
    }

    /**
     * 池成员：{@code pool} 非空的远程客户端按池名分组（只有启用且入口是 URL 的才算）。
     * <p>池名在写入时已规范化（小写 ASCII / 中文原样），这里不再重复处理。</p>
     */
    private java.util.Map<String, List<CloudClient>> poolMembers() {
        java.util.Map<String, List<CloudClient>> pools = new java.util.LinkedHashMap<>();
        for (CloudClient c : cloudClientService.versions()) {
            if (!c.isEnabled() || c.isMissing() || !CloudClient.isUrl(c.getPath())) {
                continue;
            }
            String pool = c.getPool();
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            List<CloudClient> list = pools.get(pool);
            if (list == null) {
                list = new ArrayList<>();
                pools.put(pool, list);
            }
            list.add(c);
        }
        return pools;
    }

    /**
     * 池的入口令牌：取池内出现次数最多的非空密钥。
     *
     * <p>池是"一组共享同一份 cloud-stream 口令的机器"；令牌写在页面里只是为了让浏览器能
     * 通过服务端的鉴权，真正的门在会话层（只挑密钥等于这个令牌的机器）。密钥各不相同的池
     * 能用的机器就只剩一半——与其偷偷降级，不如让 ERP 的池列一眼看出来配错了。</p>
     */
    private String poolKey(List<CloudClient> members) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (CloudClient c : members) {
            String k = c.getCloudKey();
            if (k.isEmpty()) {
                continue;
            }
            Integer n = counts.get(k);
            counts.put(k, n == null ? 1 : n + 1);
        }
        String best = "";
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** 登记每个池的入口页与包装页（{@code /cloud/pool/<池名>}）。 */
    private void registerPoolPages(WebPageApi pages, boolean allowSelect) {
        if (!allowSelect || !relayEnabled()) {
            return;
        }
        for (java.util.Map.Entry<String, List<CloudClient>> e : poolMembers().entrySet()) {
            String pool = e.getKey();
            String token = poolKey(e.getValue());
            if (token.isEmpty()) {
                LOG.warning("池 " + pool + " 内没有可用的共享密钥，跳过入口登记");
                continue;
            }
            byte[] entry = buildPoolEntryPage(pool, e.getValue(), token).getBytes(StandardCharsets.UTF_8);
            byte[] play = buildPoolPlayPage(pool, token).getBytes(StandardCharsets.UTF_8);
            for (String v : aliasVariants(pool)) {
                String base = ROOT_CLOUD_PATH + POOL_SEGMENT + "/" + v;
                registerProxyPage(pages, base, entry);
                registerProxyPage(pages, base + "/", entry);
                registerProxyPage(pages, base + "/play", play);
                registerProxyPage(pages, base + "/play/", play);
            }
        }
    }

    /**
     * 池入口页：玩家看到的是一组机器（只读），提交时只报池名——
     * 具体挑哪台由服务端按策略决定，玩家不需要也不应该关心。
     */
    private String buildPoolEntryPage(String pool, List<CloudClient> members, String token) {
        StringBuilder list = new StringBuilder(256);
        for (CloudClient c : members) {
            list.append("<span class=\"pill\">").append(escape(c.getName())).append("</span>");
        }
        String tok = js(token);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>池 " + escape(pool) + " · 进入云游戏</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:520px;padding:40px 24px}\n"
                + "h1{margin:0 0 6px;font-size:24px;font-weight:600}\n"
                + ".sub{margin:0 0 18px;color:#8f9bb3;font-size:13px;line-height:1.7}\n"
                + ".pill{display:inline-block;margin:0 6px 6px 0;padding:2px 8px;border-radius:6px;"
                + "background:rgba(255,255,255,.08);color:#9aa6bd;font-size:12px}\n"
                + ".field{margin:18px 0 8px;font-size:13px;color:#b9c3d6}\n"
                + "input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid rgba(255,255,255,.16);"
                + "background:rgba(255,255,255,.05);color:#e8edf7;font-size:16px;outline:none}\n"
                + "input:focus{border-color:#4f8cff;background:rgba(79,140,255,.10)}\n"
                + ".tip{margin:8px 0 0;font-size:12px;color:#7c879c}\n"
                + "button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;cursor:pointer;"
                + "background:linear-gradient(135deg,#4f8cff,#2f6ad9);color:#fff;font-size:16px;font-weight:600}\n"
                + "button:disabled{opacity:.55;cursor:not-allowed}\n"
                + ".msg{margin-top:14px;font-size:13px;min-height:20px}\n"
                + ".msg.err{color:#ff8b8b}.msg.ok{color:#9aa6bd}\n"
                + ".wait{margin-top:16px;display:none}\n"
                + ".track{height:8px;border-radius:6px;background:rgba(255,255,255,.08);overflow:hidden}\n"
                + ".track>i{display:block;height:100%;width:0;border-radius:6px;"
                + "background:linear-gradient(90deg,#4f8cff,#67c23a);transition:width .12s linear}\n"
                + ".pct{margin:8px 0 0;font-size:12px;color:#9aa6bd}\n"
                + ".back{display:inline-block;margin-top:18px;font-size:13px;color:#7c879c;text-decoration:none}\n"
                + ".back:hover{color:#4f8cff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>池 " + escape(pool) + "</h1>\n"
                + "<p class=\"sub\">这组机器共 " + members.size() + " 台，点进入后自动挑一台<strong>空闲且在线</strong>的；"
                + "全部占用时会被拒，不会排队也不会抢别人的机器。</p>\n"
                + "<div class=\"sub\">" + list + "</div>\n"
                + "<form id=\"f\" autocomplete=\"off\">\n"
                + "<div class=\"field\">游戏名称</div>\n"
                + "<input id=\"p\" maxlength=\"16\" placeholder=\"3–16 位字母、数字或下划线\" "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">\n"
                + "<p class=\"tip\">该名称会作为客户端登录名（--username）使用，请与你在服务器中的名字保持一致。</p>\n"
                + "<button id=\"b\" type=\"submit\">进入云游戏</button>\n"
                + "<div class=\"msg\" id=\"m\"></div>\n"
                + "<div class=\"wait\" id=\"wait\"><div class=\"track\"><i id=\"fill\"></i></div>\n"
                + "<p class=\"pct\"><span id=\"pc\">0</span>% · <span id=\"st\">正在分配工作区…</span></p></div>\n"
                + "</form>\n"
                + "<a class=\"back\" href=\"" + escape(ROOT_CLOUD_PATH) + "\">← 返回门牌目录</a>\n"
                + "</div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var POOL=" + js(pool) + ";\n"
                + "var TOKEN=" + tok + ";\n"
                + "var P=" + progressConfig() + ";\n"
                + progressJs()
                + "var RE=/^[A-Za-z0-9_]{3,16}$/;\n"
                + "var f=document.getElementById('f'),p=document.getElementById('p'),b=document.getElementById('b'),"
                + "m=document.getElementById('m'),wait=document.getElementById('wait'),fill=document.getElementById('fill'),"
                + "pc=document.getElementById('pc'),st=document.getElementById('st');\n"
                + "function say(t,err){m.textContent=t;m.className='msg '+(err?'err':'ok');}\n"
                + "var pr=null,attempts=0;\n"
                + "function attempt(v){\n"
                + "  if(!pr){pr=startProgress(fill,pc);}\n"
                + "  fetch(API+'/cloud/session/start',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({pool:POOL,player:v,token:TOKEN})})\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    var d=(j&&j.data)||{};\n"
                + "    if(j&&j.code===200&&d.playUrl){pr.finish();st.textContent='已分配 '+(d.clientName||'云主机')+'，正在连接画面…';"
                + "location.replace(d.playUrl);return;}\n"
                + "    if(j&&j.code===200&&d.updating&&attempts<40){attempts++;"
                + "st.textContent='正在更新云游戏客户端包，完成后会自动继续…';say('',false);"
                + "setTimeout(function(){attempt(v);},(d.retrySeconds||20)*1000);return;}\n"
                + "    pr.stop();b.disabled=false;wait.style.display='none';"
                + "say((j&&j.msg)||'暂时没有可用的机器，请稍后重试',true);\n"
                + "  })\n"
                + "  .catch(function(){pr.stop();b.disabled=false;wait.style.display='none';"
                + "say('网络异常，请稍后重试',true);});\n"
                + "}\n"
                + "f.addEventListener('submit',function(e){\n"
                + "  e.preventDefault();\n"
                + "  var v=(p.value||'').trim();\n"
                + "  if(!v){say('请先输入游戏名称',true);p.focus();return;}\n"
                + "  if(!RE.test(v)){say('游戏名称需为 3–16 位字母、数字或下划线',true);p.focus();return;}\n"
                + "  b.disabled=true;say('',false);wait.style.display='block';attempts=0;"
                + "st.textContent='正在分配工作区…';attempt(v);\n"
                + "});\n"
                + "</script>\n</body>\n</html>\n";
    }

    /**
     * 唯一入口页登记：{@code /cloud/auto/} 与 {@code /cloud/auto/play/}。
     *
     * <p>根路径登记（无 {@code /plugins} 前缀），与门牌目录同一层：玩家只需要记住
     * {@code https://<服务器>:<端口>/cloud/auto/} 一个地址。</p>
     */
    private void registerAutoPages(WebPageApi pages) {
        byte[] entry = buildAutoEntryPage().getBytes(StandardCharsets.UTF_8);
        byte[] play = buildAutoPlayPage().getBytes(StandardCharsets.UTF_8);
        String base = ROOT_CLOUD_PATH + AUTO_SEGMENT;
        registerProxyPage(pages, base, entry);
        registerProxyPage(pages, base + "/", entry);
        registerProxyPage(pages, base + PLAY_SEGMENT.substring(0, PLAY_SEGMENT.length() - 1), play);
        registerProxyPage(pages, base + PLAY_SEGMENT, play);
    }

    /**
     * 唯一入口页：{@code /cloud/auto/}。
     *
     * <p>玩家只填游戏名，机器由服务端挑（{@code pickAuto()}）。页面本身<b>不持有任何设备密钥</b>：
     * 提交前向 {@code /cloud/entry-ticket} 要一张 180 秒票据，服务端认票挑机，再用挑中那台机器
     * 自己的 cloud-key 去调它的代理。这样"唯一入口"不会把集群里所有机器的密钥发到浏览器。</p>
     *
     * <p>票据每次提交都重新取（重试更新客户端包时可能已过去几十秒），过期只会让这一次提交被拒，
     * 玩家看到的仍是可重试的提示，不会卡死。</p>
     */
    private String buildAutoEntryPage() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>进入云游戏 · 自动匹配</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:520px;padding:40px 24px}\n"
                + "h1{margin:0 0 6px;font-size:24px;font-weight:600}\n"
                + ".sub{margin:0 0 18px;color:#8f9bb3;font-size:13px;line-height:1.7}\n"
                + ".pill{display:inline-block;margin:0 6px 6px 0;padding:2px 8px;border-radius:6px;"
                + "background:rgba(255,255,255,.08);color:#9aa6bd;font-size:12px}\n"
                + ".field{margin:18px 0 8px;font-size:13px;color:#b9c3d6}\n"
                + "input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid rgba(255,255,255,.16);"
                + "background:rgba(255,255,255,.05);color:#e8edf7;font-size:16px;outline:none}\n"
                + "input:focus{border-color:#4f8cff;background:rgba(79,140,255,.10)}\n"
                + ".tip{margin:8px 0 0;font-size:12px;color:#7c879c}\n"
                + "button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;cursor:pointer;"
                + "background:linear-gradient(135deg,#4f8cff,#2f6ad9);color:#fff;font-size:16px;font-weight:600}\n"
                + "button:disabled{opacity:.55;cursor:not-allowed}\n"
                + ".msg{margin-top:14px;font-size:13px;min-height:20px}\n"
                + ".msg.err{color:#ff8b8b}.msg.ok{color:#9aa6bd}\n"
                + ".wait{margin-top:16px;display:none}\n"
                + ".track{height:8px;border-radius:6px;background:rgba(255,255,255,.08);overflow:hidden}\n"
                + ".track>i{display:block;height:100%;width:0;border-radius:6px;"
                + "background:linear-gradient(90deg,#4f8cff,#67c23a);transition:width .12s linear}\n"
                + ".pct{margin:8px 0 0;font-size:12px;color:#9aa6bd}\n"
                + ".back{display:inline-block;margin-top:18px;font-size:13px;color:#7c879c;text-decoration:none}\n"
                + ".back:hover{color:#4f8cff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>进入云游戏</h1>\n"
                + "<p class=\"sub\">不用选机器：提交后在<strong>全部在线且空闲</strong>的云主机里给你挑一台。"
                + "全部占用时会被拒，不会排队、也不会抢别人的机器。</p>\n"
                + "<form id=\"f\" autocomplete=\"off\">\n"
                + "<div class=\"field\">游戏名称</div>\n"
                + "<input id=\"p\" maxlength=\"16\" placeholder=\"3–16 位字母、数字或下划线\" "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">\n"
                + "<p class=\"tip\">该名称会作为客户端登录名（--username）使用，请与你在服务器中的名字保持一致。</p>\n"
                + "<button id=\"b\" type=\"submit\">进入云游戏</button>\n"
                + "<div class=\"msg\" id=\"m\"></div>\n"
                + "<div class=\"wait\" id=\"wait\"><div class=\"track\"><i id=\"fill\"></i></div>\n"
                + "<p class=\"pct\"><span id=\"pc\">0</span>% · <span id=\"st\">正在分配工作区…</span></p></div>\n"
                + "</form>\n"
                + "<a class=\"back\" href=\"" + escape(ROOT_CLOUD_PATH) + "\">← 返回门牌目录</a>\n"
                + "</div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var P=" + progressConfig() + ";\n"
                + progressJs()
                + "var RE=/^[A-Za-z0-9_]{3,16}$/;\n"
                + "var f=document.getElementById('f'),p=document.getElementById('p'),b=document.getElementById('b'),"
                + "m=document.getElementById('m'),wait=document.getElementById('wait'),fill=document.getElementById('fill'),"
                + "pc=document.getElementById('pc'),st=document.getElementById('st');\n"
                + "function say(t,err){m.textContent=t;m.className='msg '+(err?'err':'ok');}\n"
                + "var pr=null,attempts=0;\n"
                + "function attempt(v){\n"
                + "  if(!pr){pr=startProgress(fill,pc);}\n"
                + "  // 每次都重新签票：重试可能已过去几十秒，拿旧票会被服务端当过期拒掉\n"
                + "  fetch(API+'/cloud/entry-ticket')\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(t){\n"
                + "    var td=(t&&t.data)||{};\n"
                + "    if(!(t&&t.code===200&&td.ticket)){throw new Error((t&&t.msg)||'无法获取入场票据');}\n"
                + "    return fetch(API+'/cloud/session/start',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({player:v,ticket:td.ticket})});\n"
                + "  })\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    var d=(j&&j.data)||{};\n"
                + "    if(j&&j.code===200&&d.playUrl){pr.finish();st.textContent='已分配 '+(d.clientName||'云主机')+'，正在连接画面…';"
                + "location.replace(d.playUrl);return;}\n"
                + "    if(j&&j.code===200&&d.updating&&attempts<40){attempts++;"
                + "st.textContent='正在更新云游戏客户端包，完成后会自动继续…';say('',false);"
                + "setTimeout(function(){attempt(v);},(d.retrySeconds||20)*1000);return;}\n"
                + "    pr.stop();b.disabled=false;wait.style.display='none';"
                + "say((j&&j.msg)||'暂时没有可用的机器，请稍后重试',true);\n"
                + "  })\n"
                + "  .catch(function(e){pr.stop();b.disabled=false;wait.style.display='none';"
                + "say((e&&e.message)||'网络异常，请稍后重试',true);});\n"
                + "}\n"
                + "f.addEventListener('submit',function(e){\n"
                + "  e.preventDefault();\n"
                + "  var v=(p.value||'').trim();\n"
                + "  if(!v){say('请先输入游戏名称',true);p.focus();return;}\n"
                + "  if(!RE.test(v)){say('游戏名称需为 3–16 位字母、数字或下划线',true);p.focus();return;}\n"
                + "  b.disabled=true;say('',false);wait.style.display='block';attempts=0;"
                + "st.textContent='正在挑选空闲云主机…';attempt(v);\n"
                + "});\n"
                + "</script>\n</body>\n</html>\n";
    }

    /**
     * 唯一入口包装页：{@code /cloud/auto/play/?s=<会话>}。
     *
     * <p>与池包装页同一套逻辑（{@code /cloud/pool/<池>/play/?s=}）：地址栏停在入口名上，
     * iframe 的画面地址由会话状态下发 —— 里面带的是<b>会话专属随机段</b>，别人拿到页面地址
     * 也猜不出直播前缀。结束/超时一律 {@code location.replace} 回入口页，不留一个可操作的桌面。</p>
     */
    private String buildAutoPlayPage() {
        return buildPlayPage("自动匹配", "", "", ROOT_CLOUD_PATH,
                "<span class=\"pill\">/cloud/auto</span>", true);
    }

    /** 根路径登记（无 /plugins 前缀）；返回的 Entry 手工打 tag 以便随发布整体撤销。 */
    private WebRegistry.Entry registerProxyPage(WebPageApi pages, String path, byte[] content) {
        try {
            WebRegistry.Entry e = pages.registerProxyPage(plugin, path, content);
            tag(e);
            return e;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记根路径页面失败 " + path + ": " + t);
            return null;
        }
    }

    /** 每个客户端独立的 upstream tag，便于"只撤这一台的直播"而保留页面。 */
    private static String liveTag(String id) {
        return TAG + "-live-" + (id == null ? "" : id);
    }

    private CloudClient clientOf(String id) {
        if (id == null) {
            return null;
        }
        for (CloudClient c : cloudClientService.versions()) {
            if (c.getId().equals(id)) {
                return c;
            }
        }
        return null;
    }

    // ===== 内部 =====

    /** 登记 {@code <entry>index.html} 与目录别名 {@code <entry>}。 */
    private void registerPage(WebPageApi pages, String entry, String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        try {
            WebRegistry.Entry e = pages.registerPage(plugin, entry + "index.html", bytes, TAG);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记页面失败 " + entry + ": " + t);
        }
        try {
            WebRegistry.Entry e = pages.registerPage(plugin, entry, bytes, TAG);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记目录别名失败 " + entry + ": " + t);
        }
    }

    private void tag(Set<WebRegistry.Entry> entries) {
        if (entries == null) {
            return;
        }
        for (WebRegistry.Entry e : entries) {
            tag(e);
        }
    }

    private void tag(WebRegistry.Entry entry) {
        if (entry != null && entry.tags != null && !entry.tags.contains(TAG)) {
            entry.tags.add(TAG);
        }
    }

    private String defaultEntry(String id) {
        return WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + id + "/";
    }

    /** 取某条目的共享密钥（= cloud-stream 的 auth_token），用于给跳转地址补 token。 */
    private String keyOf(String id) {
        for (CloudClient c : cloudClientService.versions()) {
            if (c.getId().equals(id)) {
                return c.getCloudKey();
            }
        }
        return "";
    }

    /**
     * 给远程客户端地址补令牌，<b>放在 URL 片段</b>（{@code #token=}）而不是查询串。
     *
     * <p>selkies 前端（{@code selkies-core.js}）优先从 {@code location.hash} 读 token，
     * 读不到才看查询串；WebSocket 走 {@code selkies.token.<b64url>} 子协议。片段有三个好处：
     * 不会被浏览器发到服务端、不进 SOYS 转播日志与访问日志、不随 Referer 泄露给第三方。
     * （查询串形态 {@code ?token=} 会同时出现在这三个地方，是之前"令牌进 URL"问题的来源。）</p>
     */
    private static String withToken(String url, String token) {
        if (url == null || url.isEmpty() || token == null || token.isEmpty()) {
            return url;
        }
        if (!CloudClient.isUrl(url) || url.contains("token=")) {
            return url;
        }
        return appendToken(url, token);
    }

    /**
     * 无条件补 {@code #token=}（不区分绝对/相对地址）。
     * <p>同端口转播的跳转目标是本地相对前缀，{@link #withToken} 会因"不是 URL"而跳过它，
     * 所以此处单列一条路径。</p>
     */
    private static String appendToken(String url, String token) {
        if (url == null || url.isEmpty() || token == null || token.isEmpty() || url.contains("token=")) {
            return url;
        }
        try {
            return url + "#token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    // ===== 同端口转播 =====

    /**
     * 把一条远程客户端的 {@code <livePrefix>} 前缀注册给 SOYS 的 upstream 转播。
     *
     * <p>换算规则只有一条：上游路径 = 请求路径 − 本地前缀，前缀只做剥离、不参与上游拼接。
     * 因此浏览器在 {@code <livePrefix>/api/websockets} 上发起的 WS，到上游就是
     * {@code /api/websockets}，这正是 selkies 的原始路径；令牌走 URL 片段
     * {@code #token=}（前端自己发起请求时放 {@code Authorization} 头或
     * {@code selkies.token.*} 子协议）。</p>
     *
     * @return true 表示注册成功（调用方应把跳转页指向本地前缀）；false 表示该 SOYS 版本
     *         不支持 upstream（老 jar），调用方退回绝对地址跳转
     */
    private boolean registerLiveUpstream(SoysHttpOverMcApi a, String livePrefix, CloudClient c, String id) {
        return registerLiveUpstream(a, livePrefix, c, id, TAG);
    }

    private boolean registerLiveUpstream(SoysHttpOverMcApi a, String livePrefix, CloudClient c, String id, String tag) {
        String base = c.getPath() == null ? "" : c.getPath().trim();
        if (base.isEmpty()) {
            return false;
        }
        try {
            // 内网自签证书必须放宽校验，否则 TLS 握手直接失败；公网目标保持严格校验
            boolean insecure = isPrivateHost(base);
            a.registerUpstream(livePrefix, UpstreamTarget.of(base, insecure), tag);
            LOG.info("云客户端 " + id + " 已注册同端口转播前缀 " + livePrefix + " -> " + base
                    + (insecure ? "（内网自签，跳过证书校验）" : ""));
            return true;
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "云客户端 " + id + " 注册同端口转播失败，回退直连地址: " + t);
            return false;
        }
    }

    /**
     * 目标是否属于内网/本机（据此决定是否跳过证书校验）。
     * <p>只认字面地址：10/8、172.16-31/12、192.168/16、127/8、localhost、[::1]、169.254/16。</p>
     */
    private static boolean isPrivateHost(String url) {
        String host;
        try {
            host = new java.net.URI(url).getHost();
        } catch (Throwable t) {
            return false;
        }
        if (host == null || host.isEmpty()) {
            return false;
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        if (host.equals("localhost") || host.equals("::1") || host.equals("[::1]")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 10 || a == 127) {
                return true;
            }
            if (a == 192 && b == 168) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            return a == 169 && b == 254;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildSelectionPage() {
        String prefix = WebGameConfig.WEB_PLUGIN_PREFIX;
        String defaultId = cloudClientService.defaultId();

        // 默认客户端强制置顶，其余按管理员拖拽顺序
        List<CloudClient> ordered = new ArrayList<>();
        CloudClient defaultClient = null;
        for (CloudClient c : cloudClientService.versions()) {
            if (!c.isEnabled() || c.isMissing()) {
                continue;
            }
            if (c.getId().equals(defaultId)) {
                defaultClient = c;
            } else {
                ordered.add(c);
            }
        }
        if (defaultClient != null) {
            ordered.add(0, defaultClient);
        }

        StringBuilder cards = new StringBuilder(1024);
        int count = 0;
        // 唯一入口置顶（/cloud/auto）：玩家可以不选机器，直接让服务端挑一台空闲的
        count++;
        cards.append("<a class=\"card card-default\" href=\"").append(ROOT_CLOUD_PATH).append(AUTO_SEGMENT).append("\">")
                .append("<div class=\"card-head\"><span class=\"card-name\">直接进入</span>")
                .append("<span class=\"badge\">自动匹配</span></div>")
                .append("<div class=\"card-meta\"><span>不用选机器</span><span>/cloud/auto</span></div></a>");
        for (CloudClient c : ordered) {
            count++;
            String id = escape(c.getId());
            String name = escape(c.getName());
            String mc = escape(c.getMcVersion());
            boolean isDefault = c.getId().equals(defaultId);
            cards.append("<a class=\"card").append(isDefault ? " card-default" : "")
                    .append("\" href=\"").append(prefix).append(PAGE_PATH).append(id).append("/\">")
                    .append("<div class=\"card-head\"><span class=\"card-name\">").append(name).append("</span>");
            if (isDefault) {
                cards.append("<span class=\"badge\">默认</span>");
            }
            cards.append("</div><div class=\"card-meta\">");
            if (!mc.isEmpty()) {
                cards.append("<span>MC ").append(mc).append("</span>");
            }
            cards.append("<span>ID ").append(id).append("</span>");
            cards.append("</div></a>");
        }
        if (count == 0) {
            cards.append("<div class=\"empty\">暂无可用的云游戏客户端，请联系管理员。</div>");
        }

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>选择云游戏客户端</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:860px;padding:48px 24px}\n"
                + "h1{margin:0 0 8px;font-size:28px;font-weight:600;letter-spacing:1px}\n"
                + ".sub{margin:0 0 32px;color:#8f9bb3;font-size:14px}\n"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:16px}\n"
                + ".card{display:block;padding:20px;border-radius:14px;text-decoration:none;color:inherit;"
                + "background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.09);"
                + "transition:transform .15s ease,border-color .15s ease,background .15s ease}\n"
                + ".card:hover{transform:translateY(-3px);border-color:#4f8cff;background:rgba(79,140,255,.12)}\n"
                + ".card-default{border-color:rgba(103,194,58,.55)}\n"
                + ".card-head{display:flex;align-items:center;justify-content:space-between;gap:8px}\n"
                + ".card-name{font-size:17px;font-weight:600}\n"
                + ".badge{font-size:12px;padding:2px 8px;border-radius:999px;background:#67c23a;color:#08130a}\n"
                + ".card-meta{margin-top:14px;display:flex;flex-wrap:wrap;gap:8px;color:#9aa6bd;font-size:12px}\n"
                + ".card-meta span{padding:2px 8px;border-radius:6px;background:rgba(255,255,255,.06)}\n"
                + ".empty{padding:32px;text-align:center;color:#8f9bb3;border:1px dashed rgba(255,255,255,.15);border-radius:14px}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>选择云游戏客户端</h1>\n"
                + "<p class=\"sub\">选择一个客户端开始游戏</p>\n"
                + "<div class=\"grid\">" + cards + "</div>\n"
                + "</div>\n</body>\n</html>\n";
    }

    /**
     * 跳转页：{@code /cloud/} 的严格模式入口与远程 URL 客户端共用。
     */
    private String buildRedirectPage(String target) {
        String href = escape(target);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<meta http-equiv=\"refresh\" content=\"0;url=" + href + "\">\n"
                + "<title>正在进入游戏</title>\n"
                + "<script>location.replace(" + js(target) + ");</script>\n"
                + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#0d1220;color:#8f9bb3;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC',"
                + "'Microsoft YaHei',sans-serif;font-size:14px}a{color:#4f8cff}</style>\n"
                + "</head>\n<body>\n"
                + "<p>正在进入游戏，若未自动跳转请<a href=\"" + href + "\">点击此处</a>。</p>\n"
                + "</body>\n</html>\n";
    }

    /**
     * 根路径门牌目录：{@code /cloud} 与 {@code /cloud/}。
     *
     * <p>没有这块页面，别名就是"知道的人才能用"；有了它，一张短地址 {@code /cloud/} 就能把
     * 所有可用门牌列出来（后续"唯一入口、自动匹配"也会落在这里）。</p>
     */
    private String buildRootDirectoryPage(java.util.Map<String, String> aliasMap) {
        StringBuilder cards = new StringBuilder(1024);
        int count = 0;
        // 唯一入口置顶：玩家只填游戏名，机器由服务端挑（不需要知道任何门牌/池名）
        count++;
        cards.append("<a class=\"card card-main\" href=\"").append(ROOT_CLOUD_PATH).append(AUTO_SEGMENT).append("\">")
                .append("<div class=\"card-head\"><span class=\"card-name\">直接进入</span>")
                .append("<span class=\"badge\">自动匹配</span></div>")
                .append("<div class=\"card-url\">/cloud/auto</div>")
                .append("<div class=\"card-meta\"><span>不用选机器</span><span>挑一台空闲的</span></div></a>");
        for (java.util.Map.Entry<String, String> e : aliasMap.entrySet()) {
            CloudClient c = clientOf(e.getValue());
            if (c == null || !c.isEnabled() || c.isMissing()) {
                continue;
            }
            count++;
            String alias = e.getKey();
            String name = escape(c.getName());
            String mc = escape(c.getMcVersion());
            boolean online = CloudClient.STATUS_ONLINE.equals(c.getStatus());
            String base = ROOT_CLOUD_PATH + escape(alias);
            cards.append("<a class=\"card\" href=\"").append(base).append("\">")
                    .append("<div class=\"card-head\"><span class=\"card-name\">").append(name).append("</span>")
                    .append("<span class=\"dot").append(online ? "" : " off").append("\"></span></div>")
                    .append("<div class=\"card-url\">/cloud/").append(escape(alias)).append("</div>")
                    .append("<div class=\"card-meta\">");
            if (!mc.isEmpty()) {
                cards.append("<span>MC ").append(mc).append("</span>");
            }
            cards.append("<span>").append(online ? "在线" : escape(c.getStatus())).append("</span>")
                    .append("</div></a>");
        }
        // 池卡片：一个名字后面可能有好几台机器，玩家只认名字
        for (java.util.Map.Entry<String, List<CloudClient>> pe : poolMembers().entrySet()) {
            count++;
            String pool = pe.getKey();
            int members = pe.getValue().size();
            int healthy = 0;
            for (CloudClient m : pe.getValue()) {
                if (m.isHealthy()) {
                    healthy++;
                }
            }
            cards.append("<a class=\"card\" href=\"").append(ROOT_CLOUD_PATH).append(POOL_SEGMENT)
                    .append("/").append(escape(pool)).append("\">")
                    .append("<div class=\"card-head\"><span class=\"card-name\">池 ").append(escape(pool))
                    .append("</span><span class=\"dot").append(healthy > 0 ? "" : " off").append("\"></span></div>")
                    .append("<div class=\"card-url\">/cloud/pool/").append(escape(pool)).append("</div>")
                    .append("<div class=\"card-meta\"><span>").append(members).append(" 台</span>")
                    .append("<span>在线 ").append(healthy).append("</span></div></a>");
        }
        if (count == 0) {
            // 没配别名时退化成"按 id 进入"，避免页面空白让人以为服务坏了
            for (CloudClient c : cloudClientService.versions()) {
                if (!c.isEnabled() || c.isMissing() || !CloudClient.isUrl(c.getPath())) {
                    continue;
                }
                count++;
                cards.append("<a class=\"card\" href=\"")
                        .append(WebGameConfig.WEB_PLUGIN_PREFIX).append(PAGE_PATH).append(escape(c.getId()))
                        .append("/\">")
                        .append("<div class=\"card-head\"><span class=\"card-name\">").append(escape(c.getName()))
                        .append("</span></div>")
                        .append("<div class=\"card-url\">未配置别名，按 ID 进入</div></a>");
            }
        }
        if (count == 0) {
            cards.append("<div class=\"empty\">暂无可用的云游戏入口，请联系管理员。</div>");
        }
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>云游戏入口</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:820px;padding:44px 24px}\n"
                + "h1{margin:0 0 8px;font-size:26px;font-weight:600;letter-spacing:1px}\n"
                + ".sub{margin:0 0 28px;color:#8f9bb3;font-size:13px}\n"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:14px}\n"
                + ".card{display:block;padding:18px;border-radius:14px;text-decoration:none;color:inherit;"
                + "background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.09);"
                + "transition:transform .15s ease,border-color .15s ease,background .15s ease}\n"
                + ".card:hover{transform:translateY(-3px);border-color:#4f8cff;background:rgba(79,140,255,.12)}\n"
                + ".card-main{border-color:rgba(79,140,255,.55);background:rgba(79,140,255,.10)}\n"
                + ".badge{font-size:12px;padding:2px 8px;border-radius:999px;background:#4f8cff;color:#08122a}\n"
                + ".card-head{display:flex;align-items:center;justify-content:space-between;gap:8px}\n"
                + ".card-name{font-size:17px;font-weight:600}\n"
                + ".dot{width:8px;height:8px;border-radius:50%;background:#67c23a}\n"
                + ".dot.off{background:#7c879c}\n"
                + ".card-url{margin-top:6px;font-family:ui-monospace,Consolas,monospace;font-size:12px;color:#7fa7ff}\n"
                + ".card-meta{margin-top:12px;display:flex;flex-wrap:wrap;gap:8px;color:#9aa6bd;font-size:12px}\n"
                + ".card-meta span{padding:2px 8px;border-radius:6px;background:rgba(255,255,255,.06)}\n"
                + ".empty{padding:32px;text-align:center;color:#8f9bb3;border:1px dashed rgba(255,255,255,.15);"
                + "border-radius:14px}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>云游戏入口</h1>\n"
                + "<p class=\"sub\">选一个入口进入；首次进入需要填写你在服务器中的游戏名。</p>\n"
                + "<div class=\"grid\">" + cards + "</div>\n"
                + "</div>\n</body>\n</html>\n";
    }

    /**
     * 入口页：{@code /cloud/<id>/} 或 {@code /cloud/<别名>}。玩家必须先填游戏名，再点"进入云游戏"。
     *
     * <p>前端校验只是体验层（空格、长度、字符集），服务端 {@code /cloud/session/start}
     * 会再校验一次：绕过页面直接 POST 同样会被拒。</p>
     */
    private String buildEntryPage(CloudClient c, String token, String alias) {
        String name = escape(c.getName());
        String id = js(c.getId());
        String tok = js(token);
        String mc = escape(c.getMcVersion());
        String mcLine = mc.isEmpty() ? "" : "<span class=\"pill\">MC " + mc + "</span>";
        // 别名入口：地址栏已经是 /cloud/<别名>，返回链接也要回门牌目录，别把人甩到长 URL
        boolean byAlias = alias != null && !alias.isEmpty();
        String home = byAlias ? ROOT_CLOUD_PATH : WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH;
        String aliasLine = byAlias ? "<span class=\"pill\">/cloud/" + escape(alias) + "</span>" : "";
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + name + " · 进入云游戏</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:radial-gradient(circle at 50% 0%,#1f2a44 0%,#0d1220 60%,#070a12 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#e8edf7}\n"
                + ".wrap{width:100%;max-width:520px;padding:40px 24px}\n"
                + "h1{margin:0 0 6px;font-size:24px;font-weight:600}\n"
                + ".sub{margin:0 0 24px;color:#8f9bb3;font-size:13px;line-height:1.7}\n"
                + ".pill{display:inline-block;margin-right:8px;padding:2px 8px;border-radius:6px;"
                + "background:rgba(255,255,255,.08);color:#9aa6bd;font-size:12px}\n"
                + ".field{margin:18px 0 8px;font-size:13px;color:#b9c3d6}\n"
                + "input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid rgba(255,255,255,.16);"
                + "background:rgba(255,255,255,.05);color:#e8edf7;font-size:16px;outline:none}\n"
                + "input:focus{border-color:#4f8cff;background:rgba(79,140,255,.10)}\n"
                + ".tip{margin:8px 0 0;font-size:12px;color:#7c879c}\n"
                + "button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;cursor:pointer;"
                + "background:linear-gradient(135deg,#4f8cff,#2f6ad9);color:#fff;font-size:16px;font-weight:600}\n"
                + "button:disabled{opacity:.55;cursor:not-allowed}\n"
                + ".msg{margin-top:14px;font-size:13px;min-height:20px}\n"
                + ".msg.err{color:#ff8b8b}.msg.ok{color:#9aa6bd}\n"
                + ".wait{margin-top:16px;display:none}\n"
                + ".track{height:8px;border-radius:6px;background:rgba(255,255,255,.08);overflow:hidden}\n"
                + ".track>i{display:block;height:100%;width:0;border-radius:6px;"
                + "background:linear-gradient(90deg,#4f8cff,#67c23a);transition:width .12s linear}\n"
                + ".pct{margin:8px 0 0;font-size:12px;color:#9aa6bd}\n"
                + ".back{display:inline-block;margin-top:18px;font-size:13px;color:#7c879c;text-decoration:none}\n"
                + ".back:hover{color:#4f8cff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>" + name + "</h1>\n"
                + "<p class=\"sub\">" + aliasLine + "<span class=\"pill\">ID " + escape(c.getId()) + "</span>" + mcLine
                + "<br>进入前请填写你在服务器里的游戏名；云主机将按它启动客户端并自动连接本服。</p>\n"
                + "<form id=\"f\" autocomplete=\"off\">\n"
                + "<div class=\"field\">游戏名称</div>\n"
                + "<input id=\"p\" maxlength=\"16\" placeholder=\"3–16 位字母、数字或下划线\" "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">\n"
                + "<p class=\"tip\">该名称会作为客户端登录名（--username）使用，请与你在服务器中的名字保持一致。</p>\n"
                + "<button id=\"b\" type=\"submit\">进入云游戏</button>\n"
                + "<div class=\"msg\" id=\"m\"></div>\n"
                + "<div class=\"wait\" id=\"wait\"><div class=\"track\"><i id=\"fill\"></i></div>\n"
                + "<p class=\"pct\"><span id=\"pc\">0</span>% · <span id=\"st\">正在分配云主机…</span></p></div>\n"
                + "</form>\n"
                + "<a class=\"back\" href=\"" + escape(home) + "\">← 返回门牌目录</a>\n"
                + "</div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var CLIENT=" + id + ";\n"
                + "var TOKEN=" + tok + ";\n"
                + "var ALIAS=" + js(byAlias ? alias : "") + ";\n"
                + "var P=" + progressConfig() + ";\n"
                + progressJs()
                + "var RE=/^[A-Za-z0-9_]{3,16}$/;\n"
                + "var f=document.getElementById('f'),p=document.getElementById('p'),"
                + "b=document.getElementById('b'),m=document.getElementById('m'),"
                + "wait=document.getElementById('wait'),fill=document.getElementById('fill'),pc=document.getElementById('pc'),"
                + "st=document.getElementById('st');\n"
                + "function say(t,err){m.textContent=t;m.className='msg '+(err?'err':'ok');}\n"
                + "var pr=null,attempts=0;\n"
                + "function attempt(v){\n"
                + "  if(!pr){pr=startProgress(fill,pc);}\n"
                + "  fetch(API+'/cloud/session/start',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({clientId:CLIENT,player:v,token:TOKEN,alias:ALIAS})})\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    var d=(j&&j.data)||{};\n"
                + "    if(j&&j.code===200&&d.playUrl){pr.finish();st.textContent='已分配，正在连接画面…';"
                + "location.replace(d.playUrl);return;}\n"
                + "    // 客户端包在后台更新：进度条继续走，到点自己再点一次（不让人手动猜时间）\n"
                + "    if(j&&j.code===200&&d.updating&&attempts<40){attempts++;"
                + "st.textContent='正在更新云游戏客户端包，完成后会自动继续…';say('',false);"
                + "setTimeout(function(){attempt(v);},(d.retrySeconds||20)*1000);return;}\n"
                + "    pr.stop();b.disabled=false;wait.style.display='none';"
                + "say((j&&j.msg)||'开始失败，请稍后重试',true);\n"
                + "  })\n"
                + "  .catch(function(){pr.stop();b.disabled=false;wait.style.display='none';"
                + "say('网络异常，请稍后重试',true);});\n"
                + "}\n"
                + "f.addEventListener('submit',function(e){\n"
                + "  e.preventDefault();\n"
                + "  var v=(p.value||'').trim();\n"
                + "  if(!v){say('请先输入游戏名称',true);p.focus();return;}\n"
                + "  if(!RE.test(v)){say('游戏名称需为 3–16 位字母、数字或下划线',true);p.focus();return;}\n"
                + "  b.disabled=true;say('',false);wait.style.display='block';attempts=0;"
                + "st.textContent='正在分配工作区…';attempt(v);\n"
                + "});\n"
                + "</script>\n</body>\n</html>\n";
    }

    /**
     * 包装页：{@code /cloud/<id>/play/?s=<sessionId>}。
     *
     * <p>iframe 载直播画面，外层每 3 秒问一次会话状态；一旦会话结束（玩家在 MC 里退出、
     * 看门狗判定游戏已停、或点"结束游戏"），立刻 {@code location.replace} 回选择页——
     * 这样即使桌面被解锁，玩家也不会直面一个可操作的 Windows 桌面。</p>
     */
    private String buildPlayPage(CloudClient c, String liveUrl, String token, String homeUrl, String alias) {
        String aliasTag = alias == null || alias.isEmpty()
                ? "" : "<span class=\"pill\">/cloud/" + escape(alias) + "</span>";
        return buildPlayPage(c.getName(), liveUrl, token, homeUrl, aliasTag, false);
    }

    /**
     * 池包装页：{@code /cloud/pool/<池名>/play/?s=<会话>}。
     *
     * <p>地址栏停在池名上，iframe 的画面地址在拿到会话状态后才由前端拼出来
     * （{@code /web/plugins/WebGame/cloud/<实际机器 id>/live/}）——池是"自动挑一台"，
     * 挑中谁在起会话时才知道。</p>
     */
    private String buildPoolPlayPage(String pool, String token) {
        return buildPlayPage("池 " + pool, "", token, ROOT_CLOUD_PATH,
                "<span class=\"pill\">/cloud/pool/" + escape(pool) + "</span>", true);
    }

    private String buildPlayPage(String rawName, String liveUrl, String token, String homeUrl,
                                 String aliasTag, boolean dynamicLive) {
        String name = escape(rawName);
        // liveUrl/token/dynamicLive 保留在签名里是为了兼容既有调用点；
        // 实际画面地址一律由会话状态下发（见下方 poll() 的 d.liveUrl），页面不再持有令牌。
        String home = homeUrl;
        int hbSec = 10;
        int hbKeep = 30;
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
            hbSec = Math.max(3, cfg.getInt("cloud.heartbeat.interval-seconds", 10));
            hbKeep = Math.max(hbSec, cfg.getInt("cloud.heartbeat.timeout-seconds", 30));
        } catch (Throwable ignored) {
        }
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + name + " · 云游戏中</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "html,body{margin:0;height:100%;overflow:hidden;background:#070a12;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif}\n"
                + ".bar{height:40px;display:flex;align-items:center;gap:12px;padding:0 14px;"
                + "background:#101728;color:#c7d0e2;font-size:13px;border-bottom:1px solid rgba(255,255,255,.08)}\n"
                + ".dot{width:8px;height:8px;border-radius:50%;background:#67c23a}\n"
                + ".pill{padding:1px 8px;border-radius:999px;background:rgba(255,255,255,.08);"
                + "color:#9aa6bd;font-size:12px}\n"
                + ".grow{flex:1}\n"
                + ".btn{padding:6px 12px;border-radius:8px;border:1px solid rgba(255,255,255,.14);"
                + "background:transparent;color:#c7d0e2;font-size:13px;cursor:pointer}\n"
                + ".btn:hover{border-color:#4f8cff;color:#fff}\n"
                + "iframe{display:block;width:100%;height:calc(100% - 40px);border:0;background:#000}\n"
                + ".mask{position:fixed;inset:40px 0 0 0;display:flex;align-items:center;justify-content:center;"
                + "background:rgba(7,10,18,.94);color:#8f9bb3;font-size:14px;text-align:center;line-height:1.8;z-index:2}\n"
                + ".mw{width:min(420px,80vw)}\n"
                + ".mt{margin:0 0 14px;font-size:15px;color:#c7d0e2}\n"
                + ".track{height:9px;border-radius:6px;background:rgba(255,255,255,.08);overflow:hidden}\n"
                + ".track>i{display:block;height:100%;width:0;border-radius:6px;"
                + "background:linear-gradient(90deg,#4f8cff,#67c23a);transition:width .12s linear}\n"
                + ".mp{margin:10px 0 0;font-size:12px;color:#9aa6bd}\n"
                + ".mp b{color:#e8edf7;font-weight:600}\n"
                + ".steps{display:flex;gap:6px;margin:14px 0 6px}\n"
                + ".step{flex:1;padding:6px 4px;border-radius:9px;background:rgba(255,255,255,.04);"
                + "border:1px solid transparent;color:#6f7a90;font-size:11px;line-height:1.5;transition:all .2s}\n"
                + ".step b{display:block;font-size:10px;opacity:.75}\n"
                + ".step.active{border-color:#4f8cff;color:#dbe6ff;background:rgba(79,140,255,.12)}\n"
                + ".step.done{color:#a7dd85}\n"
                + ".now{margin:0;font-size:12px;color:#9aa6bd}\n"
                + ".note{margin:10px 0 0;font-size:12px;color:#6f7a90}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"bar\">\n"
                + "<span class=\"dot\"></span><span>" + name + "</span>" + aliasTag
                + "<span id=\"pl\" class=\"grow\"></span>\n"
                + "<button class=\"btn\" id=\"end\" type=\"button\">结束游戏</button>\n"
                + "</div>\n"
                + "<iframe id=\"v\" src=\"\" allow=\"autoplay; fullscreen; microphone; camera;"
                + " clipboard-read; clipboard-write; gamepad\" allowfullscreen></iframe>\n"
                + "<div class=\"mask\" id=\"mask\"><div class=\"mw\">\n"
                + "<p class=\"mt\" id=\"mt\">正在连接云主机…</p>\n"
                + "<div class=\"steps\">\n"
                + "<div class=\"step\" id=\"st1\"><b>1</b>分配工作区</div>\n"
                + "<div class=\"step\" id=\"st2\"><b>2</b>起 JVM</div>\n"
                + "<div class=\"step\" id=\"st3\"><b>3</b>连服务器</div>\n"
                + "<div class=\"step\" id=\"st4\"><b>4</b>画面就绪</div>\n"
                + "</div>\n"
                + "<p class=\"now\" id=\"now\">当前：正在分配工作区…</p>\n"
                + "<div class=\"track\" style=\"margin-top:10px\"><i id=\"fill\"></i></div>\n"
                + "<p class=\"mp\"><b><span id=\"pc\">0</span>%</b> · <span id=\"st\">首次冷启动通常 30–120 秒；玩家进服后自动揭开画面</span></p>\n"
                + "<p class=\"note\">关掉本页不会立刻释放机器：服务端约 " + hbKeep + " 秒收不到心跳才会收尾；"
                + "正常离开请点右上角「结束游戏」。</p>\n"
                + "</div></div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var HOME=" + js(home) + ";\n"
                + "var HB=" + hbSec + ";\n"
                + "var P=" + progressConfig() + ";\n"
                + progressJs()
                + "var q=new URLSearchParams(location.search),S=q.get('s')||'';\n"
                + "var mask=document.getElementById('mask'),pl=document.getElementById('pl'),"
                + "v=document.getElementById('v'),endBtn=document.getElementById('end'),"
                + "fill=document.getElementById('fill'),pc=document.getElementById('pc'),st=document.getElementById('st'),"
                + "mt=document.getElementById('mt'),nowEl=document.getElementById('now'),"
                + "steps=[document.getElementById('st1'),document.getElementById('st2'),"
                + "document.getElementById('st3'),document.getElementById('st4')];\n"
                + "var STAGE_TEXT={workspace:'正在分配工作区（拷贝客户端与可写区）…',"
                + "jvm:'正在启动游戏进程（JVM）…',join:'等待玩家进入服务器…',"
                + "screen:'玩家已进服，画面就绪'};\n"
                + "var STAGE_ORDER=['workspace','jvm','join','screen'];var lastStage='';\n"
                + "// 阶段由服务端按真实信号推进：prepare 返回=工作区已分配，游戏进程 running=JVM 起来了，"
                + "云机来源的玩家进服=连上了（事件判定，不靠名字）；第 4 格是本页确认画面载入。\n"
                + "function paintStage(stage){\n"
                + "  if(stage){lastStage=stage;}\n"
                + "  var idx=STAGE_ORDER.indexOf(lastStage)+1;\n"
                + "  for(var i=0;i<4;i++){\n"
                + "    var el=steps[i];if(!el)continue;\n"
                + "    var n=i+1,done=(idx>n)||(n===4&&shown);\n"
                + "    el.className='step'+(done?' done':'')+((!done&&n===idx)?' active':'');\n"
                + "  }\n"
                + "  if(stage&&STAGE_TEXT[stage]){nowEl.textContent='当前：'+STAGE_TEXT[stage];}\n"
                + "}\n"
                + "function goHome(){location.replace(HOME);}\n"
                + "function busy(on){mask.style.display=on?'flex':'none';}\n"
                + "var pr=startProgress(fill,pc),loaded=false,joined=false,readyAt=0,shown=false,liveSet=false;\n"
                + "// 揭开遮罩的三个条件缺一不可：①玩家真的进服（服务端按来源 IP 判定，eagler 冒名不算）"
                + "②画面通道已载入 ③进服后至少压 MH 秒（服务端下发剩余时间，刷新页面也不会跳步）\n"
                + "function postScreen(){if(!S){return;}fetch(API+'/cloud/session/screen',{method:'POST',"
                + "headers:{'Content-Type':'application/json'},body:JSON.stringify({sessionId:S})}).catch(function(){});}\n"
                + "function reveal(){if(shown){return;}shown=true;busy(false);pr.finish();paintStage();postScreen();}\n"
                + "function tickReveal(){if(shown){return;}if(joined&&loaded&&Date.now()>=readyAt){reveal();return;}"
                + "setTimeout(tickReveal,250);}\n"
                + "v.addEventListener('load',function(){loaded=true;paintStage();"
                + "mt.textContent='画面通道已连通，等待玩家进服…';st.textContent='玩家进入游戏后自动揭开画面';});\n"
                + "tickReveal();\n"
                + "function onJoined(d){\n"
                + "  if(joined){return;}\n"
                + "  joined=true;\n"
                + "  var left=(typeof d.revealInMs==='number'&&d.revealInMs>0)?d.revealInMs:0;\n"
                + "  readyAt=Date.now()+left;\n"
                + "  pr.finish();\n"
                + "  mt.textContent='玩家已进服'+(left>0?'，'+Math.ceil(left/1000)+' 秒后揭开画面':'，正在揭开画面')+'…';\n"
                + "  st.textContent='画面即将呈现';\n"
                + "}\n"
                + "function poll(){\n"
                + "  fetch(API+'/cloud/session/status?s='+encodeURIComponent(S))\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    var d=(j&&j.data)||{};\n"
                + "    if(d.player){pl.textContent='玩家 '+d.player+' · '+(d.gameState||'');}\n"
                + "    paintStage(d.stage);\n"
                + "    // 画面地址由服务端在会话状态里下发：会话专属随机前缀 + 令牌放在 URL 片段里\n"
                + "    // （片段不会出现在请求行/代理日志/Referer）。拿不到 liveUrl 就不设 src。\n"
                + "    if(!liveSet&&d.state==='running'&&d.liveUrl){liveSet=true;v.src=d.liveUrl;}\n"
                + "    if(d.joined===true){onJoined(d);}\n"
                + "    if(!S||d.state==='ended'||d.reason==='not-found'){goHome();}\n"
                + "  }).catch(function(){});\n"
                + "}\n"
                + "function beat(){\n"
                + "  fetch(API+'/cloud/session/heartbeat',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({sessionId:S})})\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){var d=(j&&j.data)||{};"
                + "if(!S||d.state==='ended'||d.reason==='not-found'){goHome();}}).catch(function(){});\n"
                + "}\n"
                + "if(!S){goHome();}else{beat();setInterval(beat,Math.max(3,HB)*1000);"
                + "setInterval(poll,3000);setTimeout(poll,600);}\n"
                + "endBtn.addEventListener('click',function(){\n"
                + "  endBtn.disabled=true;endBtn.textContent='正在结束…';\n"
                + "  fetch(API+'/cloud/session/end',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({sessionId:S})}).catch(function(){}).then(goHome,goHome);\n"
                + "});\n"
                + "</script>\n</body>\n</html>\n";
    }

    /** 进度条参数（{@code cloud.progress.*}）：改 config.yml 即可调整，不用重编前端。 */
    private String progressConfig() {
        int initSec = 15, initPct = 90, minSec = 3, maxSec = 7, cap = 99;
        try {
            org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
            initSec = Math.max(1, cfg.getInt("cloud.progress.initial-seconds", 15));
            initPct = Math.min(99, Math.max(1, cfg.getInt("cloud.progress.initial-percent", 90)));
            minSec = Math.max(1, cfg.getInt("cloud.progress.tick-min-seconds", 3));
            maxSec = Math.max(minSec, cfg.getInt("cloud.progress.tick-max-seconds", 7));
            cap = Math.min(100, Math.max(initPct, cfg.getInt("cloud.progress.cap-percent", 99)));
        } catch (Throwable ignored) {
        }
        return "{initSec:" + initSec + ",initPct:" + initPct + ",minSec:" + minSec
                + ",maxSec:" + maxSec + ",cap:" + cap + "}";
    }

    /**
     * 共享进度条 JS（依赖前面的 {@code var P=<progressConfig()>}）。
     *
     * <p>节奏是"不谎报"：{@code initSec} 秒匀速走到 {@code initPct}（默认 15s → 90%），
     * 之后每 {@code minSec~maxSec} 秒（默认 3–7 秒随机）+1，走到 {@code cap}（99%）就停住等人。
     * 真正就绪时调用 {@code finish()} 一次性补到 100；失败时 {@code stop()} 冻住。</p>
     */
    private static String progressJs() {
        return "function startProgress(fillEl,pctEl){var p=0,stop=false,t0=Date.now();"
                + "function paint(){if(fillEl)fillEl.style.width=p+'%';if(pctEl)pctEl.textContent=Math.floor(p);}"
                + "function gap(){return (P.minSec+Math.random()*(P.maxSec-P.minSec))*1000;}"
                + "function creep(){if(stop)return;if(p<P.cap){p+=1;paint();}"
                + "if(p<P.cap){setTimeout(creep,gap());}}"
                + "(function tick(){if(stop)return;var el=(Date.now()-t0)/1000;"
                + "if(el<P.initSec){p=P.initPct*(el/P.initSec);paint();setTimeout(tick,60);return;}"
                + "p=P.initPct;paint();setTimeout(creep,gap());})();"
                + "return{finish:function(){stop=true;p=100;paint();},stop:function(){stop=true;},"
                + "atInit:function(){return p>=P.initPct;}};}\n";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** JS 字符串字面量（仅用于内联跳转，路径不含引号/反斜杠）。 */
    private static String js(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
