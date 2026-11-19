package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
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

    private final WebGame plugin;
    private final CloudClientService cloudClientService;

    private volatile SoysHttpOverMcApi api;

    public CloudClientPages(WebGame plugin, CloudClientService cloudClientService) {
        this.plugin = plugin;
        this.cloudClientService = cloudClientService;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */
    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
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

        String rel = PAGE_PATH.substring(1);
        boolean allowSelect = cloudClientService.allowPlayerSelect();
        String defaultId = cloudClientService.defaultId();
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
                // 远程客户端（cloud-stream）：页面本身要带 token 才拿得到 WebSocket 会话，
                // 否则前端会一直 "WebSocket disconnected. Attempting to reconnect..."。
                registerPage(pages, entry, buildRedirectPage(withToken(c.getPath().trim(), c.getCloudKey())));
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
        try {
            return url + (url.contains("?") ? "&" : "?")
                    + "token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Throwable t) {
            return url;
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
