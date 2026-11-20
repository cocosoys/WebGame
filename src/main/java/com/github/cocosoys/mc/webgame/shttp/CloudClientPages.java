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

    private final WebGame plugin;
    private final CloudClientService cloudClientService;
    private final com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService playService;

    private volatile SoysHttpOverMcApi api;

    /**
     * 已生效的同端口转播前缀（id → {@code /web/plugins/WebGame/cloud/&lt;id&gt;/live}）。
     * ERP 列表用它展示"同端口接入"状态；未登记成功（老 jar / 关闭开关）的条目不在其中。
     */
    private volatile java.util.Map<String, String> livePrefixes = java.util.Collections.emptyMap();

    public CloudClientPages(WebGame plugin, CloudClientService cloudClientService,
                            com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService playService) {
        this.plugin = plugin;
        this.cloudClientService = cloudClientService;
        this.playService = playService;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
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
                registerPage(pages, entry, buildEntryPage(c, token));
                registerPage(pages, PAGE_PATH + id + PLAY_SEGMENT, buildPlayPage(c, liveUrl, token));
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
                ? buildRedirectPage(withToken(defaultEntry(defaultId), keyOf(defaultId)))
                : buildSelectionPage();
        registerPage(pages, PAGE_PATH, entryHtml);
        livePrefixes = live;
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
     * 会话开始时"开闸"：把该客户端的直播前缀注册给 SOYS upstream。
     *
     * <p>这是防"绕开入口页"的关键一环——页面只是体验，真正的门在这里：
     * 没有进行中的会话，前缀根本不存在，{@code /cloud/<id>/live/} 就是 404，
     * 谁也没法凭地址直接看到（更别说操作）云主机的桌面。</p>
     *
     * @return true 表示已开闸；false 表示同端口转播不可用（老 jar / 关闭开关）
     */
    public synchronized boolean openLive(String clientId) {
        SoysHttpOverMcApi a = api;
        if (a == null || !relayEnabled()) {
            return false;
        }
        CloudClient c = clientOf(clientId);
        if (c == null || !CloudClient.isUrl(c.getPath())) {
            return false;
        }
        String livePrefix = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + clientId + LIVE_SEGMENT;
        return registerLiveUpstream(a, livePrefix, c, clientId, liveTag(clientId));
    }

    /** 会话结束时"关闸"：撤销直播前缀。 */
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
     * 给远程客户端地址补 {@code ?token=}。
     *
     * <p>selkies 的页面与 WebSocket 会话共用同一个 token：静态资源虽然开放，但
     * {@code /api/*}（含 {@code /api/<mode>/signaling/}）默认要求它，缺 token 时
     * 握手直接 401，前端表现就是无限重连。地址里已经带了 token 的不重复追加。</p>
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
     * 无条件给目标补 {@code ?token=}（不区分绝对/相对地址）。
     * <p>同端口转播的跳转目标是本地相对前缀，{@link #withToken} 会因"不是 URL"而跳过它，
     * 所以此处单列一条路径。</p>
     */
    private static String appendToken(String url, String token) {
        if (url == null || url.isEmpty() || token == null || token.isEmpty() || url.contains("token=")) {
            return url;
        }
        try {
            return url + (url.contains("?") ? "&" : "?")
                    + "token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Throwable t) {
            return url;
        }
    }

    // ===== 同端口转播 =====

    /**
     * 把一条远程客户端的 {@code <livePrefix>} 前缀注册给 SOYS 的 upstream 转播。
     *
     * <p>换算规则只有一条：上游路径 = 请求路径 − 本地前缀，前缀只做剥离、不参与上游拼接。
     * 因此浏览器在 {@code <livePrefix>/api/websockets?token=X} 上发起的 WS，
     * 到上游就是 {@code /api/websockets?token=X}，这正是 selkies 的原始路径。</p>
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
     * 入口页：{@code /cloud/<id>/}。玩家必须先填游戏名，再点"进入云游戏"。
     *
     * <p>前端校验只是体验层（空格、长度、字符集），服务端 {@code /cloud/session/start}
     * 会再校验一次：绕过页面直接 POST 同样会被拒。</p>
     */
    private String buildEntryPage(CloudClient c, String token) {
        String name = escape(c.getName());
        String id = js(c.getId());
        String tok = js(token);
        String mc = escape(c.getMcVersion());
        String mcLine = mc.isEmpty() ? "" : "<span class=\"pill\">MC " + mc + "</span>";
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
                + ".back{display:inline-block;margin-top:18px;font-size:13px;color:#7c879c;text-decoration:none}\n"
                + ".back:hover{color:#4f8cff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + "<h1>" + name + "</h1>\n"
                + "<p class=\"sub\"><span class=\"pill\">ID " + escape(c.getId()) + "</span>" + mcLine
                + "<br>进入前请填写你在服务器里的游戏名；云主机将按它启动客户端并自动连接本服。</p>\n"
                + "<form id=\"f\" autocomplete=\"off\">\n"
                + "<div class=\"field\">游戏名称</div>\n"
                + "<input id=\"p\" maxlength=\"16\" placeholder=\"3–16 位字母、数字或下划线\" "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">\n"
                + "<p class=\"tip\">该名称会作为客户端登录名（--username）使用，请与你在服务器中的名字保持一致。</p>\n"
                + "<button id=\"b\" type=\"submit\">进入云游戏</button>\n"
                + "<div class=\"msg\" id=\"m\"></div>\n"
                + "</form>\n"
                + "<a class=\"back\" href=\"" + WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH + "\">← 返回客户端列表</a>\n"
                + "</div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var CLIENT=" + id + ";\n"
                + "var TOKEN=" + tok + ";\n"
                + "var RE=/^[A-Za-z0-9_]{3,16}$/;\n"
                + "var f=document.getElementById('f'),p=document.getElementById('p'),"
                + "b=document.getElementById('b'),m=document.getElementById('m');\n"
                + "function say(t,err){m.textContent=t;m.className='msg '+(err?'err':'ok');}\n"
                + "f.addEventListener('submit',function(e){\n"
                + "  e.preventDefault();\n"
                + "  var v=(p.value||'').trim();\n"
                + "  if(!v){say('请先输入游戏名称',true);p.focus();return;}\n"
                + "  if(!RE.test(v)){say('游戏名称需为 3–16 位字母、数字或下划线',true);p.focus();return;}\n"
                + "  b.disabled=true;say('正在准备云主机，首次启动约需 1–3 分钟…',false);\n"
                + "  fetch(API+'/cloud/session/start',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({clientId:CLIENT,player:v,token:TOKEN})})\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    if(j&&j.code===200&&j.data&&j.data.playUrl){say('已进入，正在连接画面…',false);"
                + "location.replace(j.data.playUrl);return;}\n"
                + "    b.disabled=false;say((j&&j.msg)||'开始失败，请稍后重试',true);\n"
                + "  })\n"
                + "  .catch(function(){b.disabled=false;say('网络异常，请稍后重试',true);});\n"
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
    private String buildPlayPage(CloudClient c, String liveUrl, String token) {
        String name = escape(c.getName());
        String live = js(liveUrl);
        String tok = js(token);
        String home = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH;
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
                + ".grow{flex:1}\n"
                + ".btn{padding:6px 12px;border-radius:8px;border:1px solid rgba(255,255,255,.14);"
                + "background:transparent;color:#c7d0e2;font-size:13px;cursor:pointer}\n"
                + ".btn:hover{border-color:#4f8cff;color:#fff}\n"
                + "iframe{display:block;width:100%;height:calc(100% - 40px);border:0;background:#000}\n"
                + ".mask{position:fixed;inset:40px 0 0 0;display:flex;align-items:center;justify-content:center;"
                + "background:rgba(7,10,18,.92);color:#8f9bb3;font-size:14px;text-align:center;line-height:1.8;z-index:2}\n"
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"bar\">\n"
                + "<span class=\"dot\"></span><span>" + name + "</span><span id=\"pl\" class=\"grow\"></span>\n"
                + "<button class=\"btn\" id=\"end\" type=\"button\">结束游戏</button>\n"
                + "</div>\n"
                + "<iframe id=\"v\" src=\"" + escape(liveUrl) + "\" allow=\"autoplay; fullscreen; microphone; camera;"
                + " clipboard-read; clipboard-write; gamepad\" allowfullscreen></iframe>\n"
                + "<div class=\"mask\" id=\"mask\">正在连接云主机…<br>首次启动客户端约需 1–3 分钟，请勿关闭本页</div>\n"
                + "<script>\n"
                + "var API=" + js(API_PREFIX) + ";\n"
                + "var TOKEN=" + tok + ";\n"
                + "var HOME=" + js(home) + ";\n"
                + "var q=new URLSearchParams(location.search),S=q.get('s')||'';\n"
                + "var mask=document.getElementById('mask'),pl=document.getElementById('pl'),"
                + "v=document.getElementById('v'),endBtn=document.getElementById('end');\n"
                + "function goHome(){location.replace(HOME);}\n"
                + "function busy(on){mask.style.display=on?'flex':'none';}\n"
                + "busy(true);\n"
                + "v.addEventListener('load',function(){setTimeout(function(){busy(false);},1200);});\n"
                + "setTimeout(function(){busy(false);},20000);\n"
                + "function poll(){\n"
                + "  fetch(API+'/cloud/session/status?s='+encodeURIComponent(S))\n"
                + "  .then(function(r){return r.json();})\n"
                + "  .then(function(j){\n"
                + "    var d=(j&&j.data)||{};\n"
                + "    if(d.player){pl.textContent='玩家 '+d.player+' · '+(d.gameState||'');}\n"
                + "    if(!S||d.state==='ended'||d.reason==='not-found'){goHome();}\n"
                + "  }).catch(function(){});\n"
                + "}\n"
                + "if(!S){goHome();}else{setInterval(poll,3000);setTimeout(poll,600);}\n"
                + "endBtn.addEventListener('click',function(){\n"
                + "  endBtn.disabled=true;endBtn.textContent='正在结束…';\n"
                + "  fetch(API+'/cloud/session/end',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "body:JSON.stringify({sessionId:S})}).catch(function(){}).then(goHome,goHome);\n"
                + "});\n"
                + "</script>\n</body>\n</html>\n";
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
