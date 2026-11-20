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

    private final WebGame plugin;
    private final CloudClientService cloudClientService;

    private volatile SoysHttpOverMcApi api;

    /**
     * 已生效的同端口转播前缀（id → {@code /web/plugins/WebGame/cloud/&lt;id&gt;/live}）。
     * ERP 列表用它展示"同端口接入"状态；未登记成功（老 jar / 关闭开关）的条目不在其中。
     */
    private volatile java.util.Map<String, String> livePrefixes = java.util.Collections.emptyMap();

    public CloudClientPages(WebGame plugin, CloudClientService cloudClientService) {
        this.plugin = plugin;
        this.cloudClientService = cloudClientService;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */
    public void attach(SoysHttpOverMcApi api) {
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
                String html;
                if (relay && registerLiveUpstream(a, livePrefix, c, id)) {
                    live.put(id, livePrefix);
                    // 相对路径也要补 token：withToken 只处理绝对 URL，这里必须无条件追加，
                    // 否则 selkies 的 /api/* 会 401、前端表现为无限重连。
                    html = buildRedirectPage(appendToken(livePrefix + "/", c.getCloudKey()));
                } else {
                    html = buildRedirectPage(withToken(c.getPath().trim(), c.getCloudKey()));
                }
                registerPage(pages, entry, html);
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
            a.unregisterUpstreamByTag(TAG);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "unpublish cloud upstreams failed: " + t);
        }
        livePrefixes = java.util.Collections.emptyMap();
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
        String base = c.getPath() == null ? "" : c.getPath().trim();
        if (base.isEmpty()) {
            return false;
        }
        try {
            // 内网自签证书必须放宽校验，否则 TLS 握手直接失败；公网目标保持严格校验
            boolean insecure = isPrivateHost(base);
            a.registerUpstream(livePrefix, UpstreamTarget.of(base, insecure), TAG);
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
