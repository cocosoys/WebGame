package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;

import java.io.File;
import java.nio.charset.StandardCharsets;
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
        for (ClientVersion v : clientService.versions()) {
            if (!v.isEnabled() || v.isMissing()) {
                continue;
            }
            File dir = new File(plugin.getDataFolder(), "clients/" + v.getId());
            if (!dir.isDirectory()) {
                continue;
            }
            try {
                Set<WebRegistry.Entry> entries = pages.registerDirectory(plugin, "/" + rel + v.getId(), dir);
                tag(entries);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "登记客户端目录失败 " + v.getId() + ": " + t);
            }
        }

        byte[] html = buildSelectionPage().getBytes(StandardCharsets.UTF_8);
        try {
            WebRegistry.Entry entry = pages.registerPage(plugin, pagePath + "index.html", html, TAG);
            tag(entry);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记版本选择页失败: " + t);
        }
        try {
            WebRegistry.Entry entry = pages.registerPage(plugin, pagePath, html, TAG);
            tag(entry);
        } catch (Throwable t) {
            // 部分实现对以 "/" 结尾的路径不接受；index.html 已覆盖目录索引
            LOG.log(Level.FINE, "登记选择页目录别名失败: " + t);
        }
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

    private String buildSelectionPage() {
        String pagePath = pagePath();
        String prefix = com.github.cocosoys.mc.webgame.config.WebGameConfig.WEB_PLUGIN_PREFIX;
        String defaultId = clientService.defaultId();

        StringBuilder cards = new StringBuilder(1024);
        int count = 0;
        for (ClientVersion v : clientService.versions()) {
            if (!v.isEnabled() || v.isMissing()) {
                continue;
            }
            count++;
            String id = escape(v.getId());
            String name = escape(v.getName());
            String mc = escape(v.getMcVersion());
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

        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>选择游戏版本</title>\n"
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
                + "<h1>选择游戏版本</h1>\n"
                + "<p class=\"sub\">选择一个客户端版本开始游戏</p>\n"
                + "<div class=\"grid\">" + cards + "</div>\n"
                + "</div>\n</body>\n</html>\n";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
