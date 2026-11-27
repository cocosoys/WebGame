package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端版本页面热登记。
 *
 * <p>每个启用版本登记到 {@code /web/plugins/WebGame/eagler/<id>/}（磁盘目录懒读，
 * 30MB 文件不占堆）；{@code /eagler/} 与 {@code /eagler/index.html} 登记为统一版本选择页。
 * 所有登记条目都打上 {@link #TAG}，变更时 {@code unregisterByTag} 精确撤销后再重建，
 * 不影响 {@link WebGameExpansion} 托管的 dist 页面。</p>
 */
public final class WebGameClientPages {

    private static final Logger LOG = Logger.getLogger(WebGameClientPages.class.getName());

    /** 本类登记条目的专用 tag（与 SoysExpansion 的 dist tag 隔离）。 */
    public static final String TAG = "webgame-client-pages";

    private static final String INDEX_FILE = "index.html";

    private final WebGame plugin;
    private final WebGameClientService clientService;

    private volatile SoysHttpOverMcApi api;

    public WebGameClientPages(WebGame plugin, WebGameClientService clientService) {
        this.plugin = plugin;
        this.clientService = clientService;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */
    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
    }

    /** 幂等重建：先撤销本 tag 全部条目，再按当前版本列表重新登记。 */
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
            LOG.log(Level.FINE, "unregister client pages failed: " + t);
        }

        String pagePath = pagePath();
        String rel = pagePath.startsWith("/") ? pagePath.substring(1) : pagePath;
        boolean allowSelect = clientService.allowPlayerSelect();
        String defaultId = clientService.defaultId();
        for (ClientVersion v : clientService.versions()) {
            if (!v.isEnabled() || v.isMissing()) {
                continue;
            }
            // 禁止自选版本时只开放默认版本页面，其余版本页面 404
            if (!allowSelect && !v.getId().equals(defaultId)) {
                continue;
            }
            String id = v.getId();
            String entry = pagePath + id + "/";
            if (ClientVersion.isUrl(v.getPath())) {
                // 远程 URL：入口页直接跳转
                registerPage(pages, entry, buildRedirectPage(v.getPath().trim()));
                continue;
            }
            File file = clientService.resolveClientFile(v);
            if (file == null) {
                continue;
            }
            if (INDEX_FILE.equalsIgnoreCase(file.getName())) {
                // index.html：登记父目录，支持相对资源且懒读磁盘
                try {
                    Set<WebRegistry.Entry> entries = pages.registerDirectory(
                            plugin, "/" + rel + id, file.getParentFile());
                    tag(entries);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "登记客户端目录失败 " + id + ": " + t);
                }
                try {
                    // 目录登记只覆盖 index.html，不处理 /eagler/<id>/ 尾斜杠；补一个跳转页
                    byte[] redirect = buildRedirectPage(entry + INDEX_FILE)
                            .getBytes(StandardCharsets.UTF_8);
                    tag(java.util.Collections.singleton(
                            pages.registerPage(plugin, entry, redirect, TAG)));
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "登记客户端入口别名失败 " + id + ": " + t);
                }
            } else {
                try {
                    registerPage(pages, entry, new String(
                            Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "登记客户端页面失败 " + id + ": " + t);
                }
            }
        }

        String entryHtml = allowSelect ? buildSelectionPage() : buildRedirectPage(defaultEntry(defaultId));
        registerPage(pages, pagePath, entryHtml);
        // 统一入口页的版本统计跟着本页数据一起刷新
        com.github.cocosoys.mc.webgame.shttp.GameHubPages hub = plugin.getGameHubPages();
        if (hub != null) {
            hub.publish();
        }
    }

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
            // 部分实现对以 "/" 结尾的路径不接受；index.html 已覆盖目录索引
            LOG.log(Level.FINE, "登记目录别名失败 " + entry + ": " + t);
        }
    }

    private String defaultEntry(String id) {
        return com.github.cocosoys.mc.webgame.config.WebGameConfig.WEB_PLUGIN_PREFIX
                + pagePath() + id + "/";
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
            LOG.log(Level.FINE, "unpublish client pages failed: " + t);
        }
    }

    // ===== 内部 =====

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

    private String pagePath() {
        String p = plugin.getWebGameConfig() == null ? "/eagler/" : plugin.getWebGameConfig().getPagePath();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (!p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }

    /**
     * Eagler 选择页：统一入口（/play/）的下一级。
     *
     * <p>顶栏品牌 + 返回统一入口；默认版本置顶带「默认」徽标，其余按管理员拖拽顺序。
     * 视觉全部走 {@link com.github.cocosoys.mc.webgame.shttp.GamePageTheme}，
     * 与 /play/、/cloud/ 同款。</p>
     */
    private String buildSelectionPage() {
        String pagePath = pagePath();
        String prefix = com.github.cocosoys.mc.webgame.config.WebGameConfig.WEB_PLUGIN_PREFIX;
        String defaultId = clientService.defaultId();

        // 默认版本强制置顶，其余按管理员拖拽顺序
        List<ClientVersion> ordered = new ArrayList<>();
        ClientVersion defaultVersion = null;
        for (ClientVersion v : clientService.versions()) {
            if (!v.isEnabled() || v.isMissing()) {
                continue;
            }
            if (v.getId().equals(defaultId)) {
                defaultVersion = v;
            } else {
                ordered.add(v);
            }
        }
        if (defaultVersion != null) {
            ordered.add(0, defaultVersion);
        }

        StringBuilder cards = new StringBuilder(1024);
        int count = 0;
        for (ClientVersion v : ordered) {
            count++;
            String id = escape(v.getId());
            String name = escape(v.getName());
            String mc = escape(v.getMcVersionText());
            boolean isDefault = v.getId().equals(defaultId);
            cards.append("<a class=\"card").append(isDefault ? " card-default" : "")
                    .append("\" href=\"").append(prefix).append(pagePath).append(id).append("/\">")
                    .append("<div class=\"card-head\"><span class=\"card-name\">").append(name).append("</span>");
            if (isDefault) {
                cards.append("<span class=\"badge\">默认</span>");
            }
            cards.append("</div><div class=\"card-meta\">");
            if (!mc.isEmpty()) {
                cards.append("<span>MC ").append(mc).append("</span>");
            }
            cards.append("<span>版本 ").append(id).append("</span>");
            if (v.getSource() != null && ClientVersion.SOURCE_BUILTIN.equals(v.getSource())) {
                cards.append("<span>内置</span>");
            }
            cards.append("</div></a>");
        }
        if (count == 0) {
            cards.append("<div class=\"empty\">暂无可用的客户端版本，请联系管理员。</div>");
        }

        StringBuilder body = new StringBuilder(1024);
        body.append(com.github.cocosoys.mc.webgame.shttp.GamePageTheme
                .topbar(com.github.cocosoys.mc.webgame.shttp.GameHubPages.PLAY_PATH))
                .append("<h1>Eagler 网页版</h1>\n")
                .append("<p class=\"sub\">浏览器直连服务器，不需要云主机、也不用安装客户端。选择一个版本开始游戏。</p>\n")
                .append("<img class=\"banner\" src=\"")
                .append(com.github.cocosoys.mc.webgame.shttp.GamePageTheme.image("eagler.jpg"))
                .append("\" alt=\"\">\n")
                .append("<div class=\"sec\">客户端版本 · ").append(count).append(" 个</div>\n")
                .append("<div class=\"grid\">").append(cards).append("</div>\n")
                .append(com.github.cocosoys.mc.webgame.shttp.GamePageTheme
                        .foot("WebGame · Eagler 网页版"));

        return com.github.cocosoys.mc.webgame.shttp.GamePageTheme
                .shell("Eagler 网页版 · 选择版本", "", body.toString());
    }

    /**
     * 跳转页：{@code /eagler/} 的严格模式入口与远程 URL 客户端共用。
     *
     * <p>不能把客户端 HTML 直接挂到 {@code /eagler/}：内置客户端的 WS 地址是
     * {@code /eagler}，会被网关按子路径归属成内置版本。</p>
     *
     * @param target 完整跳转地址（可为绝对 URL 或站内路径）
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
