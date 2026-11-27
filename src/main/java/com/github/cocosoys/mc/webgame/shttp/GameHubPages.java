package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 玩家统一入口页：{@code /web/plugins/WebGame/play/}。
 *
 * <p>这是发给玩家的唯一入口地址：先选「云游戏客户端」还是「Eagler 网页版」，再进各自
 * 二级选择页（{@code /cloud/}、{@code /eagler/}）。两张卡片上的数量不在页面里固化：
 * 页面加载后由前端请求 {@code /api/plugins/WebGame/play/stats}，后端按
 * {@code eagler-clients} / {@code cloud-clients} 表统计后回填，因此菜单增删与云机上下线
 * 不需要重新发布页面。</p>
 *
 * <p>视觉为「版本 C · 快乐轻快 · 天天系列」1:1 还原稿：深蓝紫方块世界背景、磨砂发光玻璃卡、
 * 金色齿轮/绿色叶瓣徽章、3D 立体标题、斜切丝带、彩色分段切换环、白色胶囊口碑条，
 * 以及按压/扫光/浮动/心跳/旋转等动效。</p>
 *
 * <p>登记路径：{@code /play/index.html}、{@code /play/}、{@code /play}（无斜杠别名，
 * 避免玩家少打一个斜杠落到 ERP SPA 兜底页）。</p>
 */
public final class GameHubPages {

    private static final Logger LOG = Logger.getLogger(GameHubPages.class.getName());

    /** 本类登记条目的专用 tag。 */
    public static final String TAG = "webgame-play-hub-pages";

    /** 统一入口路径（相对插件页面根，以 / 开头、以 / 结尾）。 */
    public static final String PLAY_PATH = "/play/";

    /** 统一入口完整站内地址（含 /web/plugins/WebGame 前缀；页面链接必须用它）。 */
    public static final String PLAY_URL = WebGameConfig.WEB_PLUGIN_PREFIX + PLAY_PATH;

    private static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";

    private final WebGame plugin;

    private volatile SoysHttpOverMcApi api;

    public GameHubPages(WebGame plugin) {
        this.plugin = plugin;
    }

    /** 页面扩展注册成功后注入 api（页面扩展未就绪时不发布）。 */
    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
    }

    /**
     * 幂等重建：先撤销本 tag 全部条目，再按当前统计重新登记。
     * 加锁是因为它同时被 {@code /cloud/} 与 {@code /eagler/} 两个发布入口回调，
     * 两个线程交错撤销/登记会把中间态暴露给玩家。
     */
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
            LOG.log(Level.FINE, "unregister play hub pages failed: " + t);
        }
        String html = buildHubPage();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        // /play/index.html 与 /play/
        try {
            WebRegistry.Entry e = pages.registerPage(plugin, PLAY_PATH + "index.html", bytes,
                    HTML_CONTENT_TYPE);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记统一入口失败 " + PLAY_PATH + "index.html: " + t);
        }
        try {
            WebRegistry.Entry e = pages.registerPage(plugin, PLAY_PATH, bytes, HTML_CONTENT_TYPE);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记统一入口目录别名失败 " + PLAY_PATH + ": " + t);
        }
        // /play（无斜杠）与 /play/（根路径别名）：玩家直接访问这两个短地址也能进
        try {
            WebRegistry.Entry e = pages.registerProxyPage(plugin,
                    PLAY_PATH.substring(0, PLAY_PATH.length() - 1), bytes, HTML_CONTENT_TYPE);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记统一入口无斜杠别名失败: " + t);
        }
        try {
            WebRegistry.Entry e = pages.registerProxyPage(plugin,
                    PLAY_PATH, bytes, HTML_CONTENT_TYPE);
            tag(e);
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记统一入口根路径别名失败: " + t);
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
            LOG.log(Level.FINE, "unpublish play hub pages failed: " + t);
        }
    }

    // ===== 页面构建 =====

    private String buildHubPage() {
        String cloudHref = WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/";
        String eaglerHref = eaglerPageUrl();

        // 数量不在这里固化：页面先渲染占位，打开后由前端请求 /api/plugins/WebGame/play/stats，
        // 后端按 eagler-clients / cloud-clients 表实时整理后回填。这样菜单增删、上下线都无需重发页面。
        String cloudChip = "<span id=\"wg-cloud-chip\" class=\"live-chip off\"><i></i>正在统计云机…</span>";
        String eaglerChip = "<span id=\"wg-eagler-chip\" class=\"live-chip off\"><i></i>正在统计版本…</span>";

        String bg = GamePageTheme.image("bg.jpg");
        String badgeGold = GamePageTheme.image("badge-gold2.png");
        String badgeGreen = GamePageTheme.image("badge-green2.png");
        String iconPad = GamePageTheme.image("icon-pad.png");
        String iconE = GamePageTheme.image("icon-e.png");
        String artCloud = GamePageTheme.image("cloud.jpg");
        String artEagler = GamePageTheme.image("eagler.jpg");
        String cat = GamePageTheme.image("cat.png");

        StringBuilder b = new StringBuilder(16384);

        // 漂浮装饰
        b.append("<span class=\"deco d1\">✦</span>")
                .append("<span class=\"deco d2\">✚</span>")
                .append("<span class=\"deco d3\">✚</span>")
                .append("<span class=\"deco d4\">✦</span>");

        b.append("<div class=\"hub\">");

        // ---- 顶部品牌 + 语言 ----
        b.append("<div class=\"hub-top\">")
                .append("<div class=\"brand-word\"><div class=\"cn\">方块世界<sup>®</sup></div>")
                .append("<div class=\"en\">MINECRAFT</div></div>")
                .append("<span class=\"lang\">")
                .append("<svg width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" "
                        + "stroke=\"#2f6be0\" stroke-width=\"1.9\"><circle cx=\"12\" cy=\"12\" r=\"9\"/>"
                        + "<path d=\"M3 12h18M12 3c3 3.2 3 14.8 0 18M12 3c-3 3.2-3 14.8 0 18\"/></svg>")
                .append("简体中文")
                .append("<svg class=\"chev\" width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" "
                        + "stroke=\"#2b3566\" stroke-width=\"2.6\" stroke-linecap=\"round\" "
                        + "stroke-linejoin=\"round\"><path d=\"M6 9l6 6 6-6\"/></svg>")
                .append("</span></div>");

        // ---- 大标题 ----
        b.append("<h1 class=\"title\">选择游戏方式")
                .append("<i class=\"pl y a1\"></i><i class=\"pl c a2\"></i><i class=\"pl y a3\"></i>")
                .append("<i class=\"pl y b1\"></i><i class=\"pl c b2\"></i><i class=\"pl p b3\"></i>")
                .append("</h1>");

        // ---- 斜切丝带 ----
        b.append("<div class=\"ribbon\">开启你的方块冒险<span class=\"h\">♡</span></div>");

        b.append("<div class=\"picks\">");

        // ============ 左卡：云游戏客户端 ============
        b.append("<a class=\"pc pc-blue\" href=\"").append(cloudHref).append("\">")
                .append("<img class=\"badge\" src=\"").append(badgeGold).append("\" alt=\"自动匹配\">")
                .append("<div class=\"pc-head\">")
                .append("<img class=\"hic\" src=\"").append(iconPad).append("\" alt=\"\">")
                .append("<div><h3 class=\"pc-name\">云游戏客户端</h3>")
                .append("<p class=\"pc-tag\">高性能 · 低门槛 · 随时畅玩</p></div></div>")
                .append("<div class=\"art\"><img src=\"").append(artCloud).append("\" alt=\"云游戏客户端\">")
                .append(cloudChip).append("</div>")
                .append("<div class=\"feats\">")
                .append(feat("f-blue",
                        "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M13 2L4.5 13.5H11L9.8 22l8.7-11.5h-6z\"/></svg>",
                        "即点即玩", "无需高配设备"))
                .append(feat("f-purple",
                        "<svg width=\"23\" height=\"23\" viewBox=\"0 0 24 24\"><circle cx=\"9\" cy=\"8\" r=\"3\" fill=\"#fff\"/>"
                        + "<circle cx=\"16.5\" cy=\"8\" r=\"2.5\" fill=\"#fff\"/>"
                        + "<path fill=\"#fff\" d=\"M3.5 20c0-3.3 2.3-5.5 5.5-5.5s5.5 2.2 5.5 5.5v.8H3.5V20z\"/>"
                        + "<path fill=\"#fff\" d=\"M16.6 14.9c1.9.7 3.1 2.5 3.1 4.6v.8h-2.7v-5.4z\"/></svg>",
                        "自动匹配", "快速找到玩伴"))
                .append(feat("f-teal",
                        "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M12 2l8 3.5V12c0 5.2-3.4 8.6-8 10-4.6-1.4-8-4.8-8-10V5.5L12 2zm0 2.3L6 7v5"
                        + "c0 4 2.5 6.9 6 8 3.5-1.1 6-4 6-8V7l-6-2.7z\"/>"
                        + "<path fill=\"#18b394\" d=\"M10.6 14.6l-2-2 1.3-1.3.7.7 3-3 1.3 1.3z\"/></svg>",
                        "稳定流畅", "专业网络加速"))
                .append("</div>")
                .append("<span class=\"go go-blue\"><b>立即体验</b><span class=\"arr\">›</span></span>")
                .append("</a>");

        // ============ 中间切换环 ============
        b.append("<div class=\"mid\">")
                .append("<div class=\"ring\" title=\"一键切换\">")
                .append("<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#2f7bf0\" stroke-width=\"2.6\" "
                        + "stroke-linecap=\"round\" stroke-linejoin=\"round\">"
                        + "<path d=\"M20 11a8 8 0 0 0-14.2-4.9L4 8\"/><path d=\"M4 4v4h4\"/>"
                        + "<path d=\"M4 13a8 8 0 0 0 14.2 4.9L20 16\"/><path d=\"M20 20v-4h-4\"/></svg>")
                .append("</div>")
                .append("<span class=\"switch-btn\">一键切换</span>")
                .append("<div class=\"slogan\">不同方式 <span class=\"h\">♡</span><br>同样精彩</div>")
                .append("</div>");

        // ============ 右卡：Eagler 网页版 ============
        b.append("<a class=\"pc pc-green\" href=\"").append(eaglerHref).append("\">")
                .append("<img class=\"badge\" src=\"").append(badgeGreen).append("\" alt=\"网页版\">")
                .append("<div class=\"pc-head\">")
                .append("<img class=\"hic\" src=\"").append(iconE).append("\" alt=\"\">")
                .append("<div><h3 class=\"pc-name\">Eagler 网页版</h3>")
                .append("<p class=\"pc-tag\">无需下载 · 打开即玩</p></div></div>")
                .append("<div class=\"art\"><img src=\"").append(artEagler).append("\" alt=\"Eagler 网页版\">")
                .append(eaglerChip).append("</div>")
                .append("<div class=\"feats\">")
                .append(feat("f-green",
                        "<svg width=\"23\" height=\"23\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#fff\" "
                        + "stroke-width=\"1.9\"><circle cx=\"12\" cy=\"12\" r=\"9\"/>"
                        + "<path d=\"M3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18\"/></svg>",
                        "无需下载", "浏览器直接玩"))
                .append(feat("f-green",
                        "<svg width=\"23\" height=\"23\" viewBox=\"0 0 24 24\"><circle cx=\"9\" cy=\"8\" r=\"3\" fill=\"#fff\"/>"
                        + "<circle cx=\"16.5\" cy=\"8\" r=\"2.5\" fill=\"#fff\"/>"
                        + "<path fill=\"#fff\" d=\"M3.5 20c0-3.3 2.3-5.5 5.5-5.5s5.5 2.2 5.5 5.5v.8H3.5V20z\"/>"
                        + "<path fill=\"#fff\" d=\"M16.6 14.9c1.9.7 3.1 2.5 3.1 4.6v.8h-2.7v-5.4z\"/></svg>",
                        "跨平台联机", "支持多设备"))
                .append(feat("f-green",
                        "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M12 2l8 3.5V12c0 5.2-3.4 8.6-8 10-4.6-1.4-8-4.8-8-10V5.5L12 2z\"/>"
                        + "<path fill=\"#2fa84c\" d=\"M10.6 14.6l-2-2 1.3-1.3.7.7 3-3 1.3 1.3z\"/></svg>",
                        "安全可靠", "官方正版支持"))
                .append("</div>")
                .append("<span class=\"go go-green\"><b>开始游戏</b><span class=\"arr\">›</span></span>")
                .append("</a>");

        b.append("</div>"); // /.picks

        // ---- 底部口碑条 ----
        b.append("<div class=\"bottom\">")
                .append("<div class=\"score-card\">")
                .append("<span class=\"score-pill\">")
                .append("<svg width=\"19\" height=\"19\" viewBox=\"0 0 24 24\"><path fill=\"#e8890c\" "
                        + "stroke=\"#a86400\" stroke-width=\".8\" d=\"M12 2l2.9 6.2 6.6.8-4.9 4.5 1.3 6.5L12 16.9 "
                        + "6.1 20l1.3-6.5L2.5 9l6.6-.8z\"/></svg><b>4.8</b></span>")
                .append("<span class=\"score-label\">玩家好评</span></div>")
                .append("<img class=\"cat\" src=\"").append(cat).append("\" alt=\"\">");

        b.append("<div class=\"pill\">")
                .append(stat("s1",
                        "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M12 21s-8.5-5.4-8.5-11.3C3.5 6.5 5.6 4.5 8 4.5c1.6 0 3 .8 4 2 1-1.2 2.4-2 4-2 "
                        + "2.4 0 4.5 2 4.5 5.2 0 5.9-8.5 11.3-8.5 11.3z\"/></svg>",
                        "百万玩家", "共同冒险"))
                .append(stat("s2",
                        "<svg width=\"22\" height=\"22\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M12 3l7 3.8v8.4L12 19l-7-3.8V6.8L12 3zm0 2.2L7.2 8.4 12 11l4.8-2.6L12 5.2z\"/>"
                        + "<path fill=\"rgba(255,255,255,.75)\" d=\"M5 8.4v7.5l7 3.8v-7.5l-7-3.8z\"/></svg>",
                        "精彩内容", "持续更新"))
                .append(stat("s3",
                        "<svg width=\"21\" height=\"21\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M7 3h10v1h3v3c0 2.6-1.9 4.7-4.4 5.2-.7 1.9-2.2 3.2-4.1 3.6V18h3v2H8v-2h3v-2.2"
                        + "c-1.9-.4-3.4-1.7-4.1-3.6C4.4 11.7 3 9.6 3 7V4h4V3zm-2 3v1c0 1.7 1.1 3 2.8 3.4"
                        + "C7.9 9 8 7.5 8 6.2V6H5zm12 0h-2v.2c0 1.3.1 2.8.2 4.2C17.9 10 19 8.7 19 7V6z\"/></svg>",
                        "多元玩法", "创造、生存、联机"))
                .append(stat("s4",
                        "<svg width=\"21\" height=\"21\" viewBox=\"0 0 24 24\"><path fill=\"#fff\" "
                        + "d=\"M3 7l4.5 4L12 4l4.5 7L21 7l-1.8 10H4.8L3 7zm3.4 8h11.2l.9-5-3.3 2.9L12 8.5"
                        + "l-3.2 4.4-3.4-2.9.9 5z\"/></svg>",
                        "社区支持", "与玩家一起成长"));

        b.append("<div class=\"happy\">")
                .append("<svg width=\"33\" height=\"33\" viewBox=\"0 0 24 24\"><defs>"
                        + "<linearGradient id=\"hg\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\">"
                        + "<stop offset=\"0\" stop-color=\"#ffb3d6\"/><stop offset=\"1\" stop-color=\"#f5458c\"/>"
                        + "</linearGradient></defs><path fill=\"url(#hg)\" d=\"M12 21s-8.5-5.4-8.5-11.3"
                        + "C3.5 6.5 5.6 4.5 8 4.5c1.6 0 3 .8 4 2 1-1.2 2.4-2 4-2 2.4 0 4.5 2 4.5 5.2 "
                        + "0 5.9-8.5 11.3-8.5 11.3z\"/><ellipse cx=\"8.6\" cy=\"8\" rx=\"2.1\" ry=\"1.3\" "
                        + "fill=\"rgba(255,255,255,.65)\" transform=\"rotate(-28 8.6 8)\"/></svg>")
                .append("<b>游戏，让快乐更简单！</b></div>");
        b.append("</div>"); // /.pill

        b.append("<a class=\"join\" href=\"#\">")
                .append("<svg width=\"36\" height=\"36\" viewBox=\"0 0 32 32\"><defs>"
                        + "<linearGradient id=\"pg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">"
                        + "<stop offset=\"0\" stop-color=\"#a98bff\"/><stop offset=\"1\" stop-color=\"#6a45e8\"/>"
                        + "</linearGradient></defs>"
                        + "<path fill=\"url(#pg)\" d=\"M12 3h8a2 2 0 0 1 2 2v3h2a3 3 0 0 1 0 6h-2v3a2 2 0 0 1-2 2h-3v-1"
                        + "a3 3 0 0 0-6 0v1H8a2 2 0 0 1-2-2v-3H5a3 3 0 0 1 0-6h1V5a2 2 0 0 1 2-2h4z\"/>"
                        + "<path fill=\"rgba(255,255,255,.35)\" d=\"M12 3h8a2 2 0 0 1 2 2v2H10V5a2 2 0 0 1 2-2z\"/></svg>")
                .append("<span class=\"jt\"><b>加入我们</b><i>发现更多精彩</i></span>")
                .append("<span class=\"ja\"><svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" "
                        + "stroke=\"#fff\" stroke-width=\"2.8\" stroke-linecap=\"round\" "
                        + "stroke-linejoin=\"round\"><path d=\"M9 5l7 7-7 7\"/></svg></span>")
                .append("</a>");

        b.append("</div>"); // /.bottom
        b.append("</div>"); // /.hub

        String statsScript = "<script>\n"
                + "(function () {\n"
                + "  var API = '/api/plugins/WebGame/play/stats';\n"
                + "  function paint(id, text, live) {\n"
                + "    var el = document.getElementById(id);\n"
                + "    if (!el) return;\n"
                + "    el.className = 'live-chip' + (live ? '' : ' off');\n"
                + "    el.innerHTML = '<i></i>' + text;\n"
                + "  }\n"
                + "  function render(d) {\n"
                + "    var e = (d && d.eagler) || {};\n"
                + "    var c = (d && d.cloud) || {};\n"
                + "    var et = Number(e.total) || 0;\n"
                + "    var ee = Number(e.enabled) || 0;\n"
                + "    paint('wg-eagler-chip', et > 0 ? et + ' 个可用版本' : '暂无可用版本', et > 0 && ee > 0);\n"
                + "    var ct = Number(c.total) || 0;\n"
                + "    var co = Number(c.online) || 0;\n"
                + "    paint('wg-cloud-chip', ct > 0 ? ct + ' 台云机 · ' + co + ' 在线' : '暂无可用云机', ct > 0 && co > 0);\n"
                + "  }\n"
                + "  function refresh() {\n"
                + "    if (document.hidden) return;\n"
                + "    fetch(API + (API.indexOf('?') < 0 ? '?' : '&') + 't=' + Date.now(),\n"
                + "      { cache: 'no-store', credentials: 'same-origin' })\n"
                + "      .then(function (r) { return r.json(); })\n"
                + "      .then(function (j) {\n"
                + "        if (!j || j.code !== 200 || !j.data) throw new Error((j && j.msg) || '统计失败');\n"
                + "        render(j.data);\n"
                + "      })\n"
                + "      .catch(function () {\n"
                + "        paint('wg-eagler-chip', '统计失败', false);\n"
                + "        paint('wg-cloud-chip', '统计失败', false);\n"
                + "      });\n"
                + "  }\n"
                + "  if (document.readyState === 'loading') {\n"
                + "    document.addEventListener('DOMContentLoaded', refresh);\n"
                + "  } else {\n"
                + "    refresh();\n"
                + "  }\n"
                + "  setInterval(refresh, 30000);\n"
                + "  document.addEventListener('visibilitychange', function () { if (!document.hidden) refresh(); });\n"
                + "})();\n"
                + "</script>";

        String css = hubCss(bg);
        return GamePageTheme.shell("方块世界 · 选择游戏方式", css, b.toString() + statsScript);
    }

    /** 单个特性条目（彩色圆图标 + 标题 + 说明）。 */
    private static String feat(String colorClass, String svg, String title, String sub) {
        return "<div class=\"feat\"><span class=\"fi " + colorClass + "\">" + svg + "</span>"
                + "<div><b>" + title + "</b><i>" + sub + "</i></div></div>";
    }

    /** 底部口碑统计条目。 */
    private static String stat(String colorClass, String svg, String title, String sub) {
        return "<div class=\"stat\"><span class=\"si " + colorClass + "\">" + svg + "</span>"
                + "<div><b>" + title + "</b><i>" + sub + "</i></div></div>";
    }

    /**
     * 统一入口页私有样式：与 preview/play-v3.html 保持同构。
     * 共享主题之后加载，同名选择器以后定义为准。
     */
    private static String hubCss(String bg) {
        return ""
                + ".wrap{max-width:1340px;padding:12px 26px 16px;display:flex;flex-direction:column;"
                + "min-height:100vh}\n"
                + "body{align-items:flex-start;background:#2a3590 url(\"" + bg
                + "\") center top/cover no-repeat fixed}\n"
                + "body::before{content:'';position:fixed;inset:0;pointer-events:none;z-index:0;"
                + "background:linear-gradient(180deg,rgba(18,22,74,.28) 0%,rgba(18,22,74,0) 26%,"
                + "rgba(18,22,74,0) 62%,rgba(46,24,104,.30) 100%)}\n"
                + ".wrap{z-index:1}\n"
                + ".hub{position:relative;display:flex;flex-direction:column;flex:1}\n"
                // 顶部
                + ".hub-top{position:relative;display:flex;justify-content:center;align-items:flex-start;"
                + "height:62px;flex:none}\n"
                + ".brand-word{text-align:center;color:#fff;line-height:1;user-select:none}\n"
                + ".brand-word .cn{font-size:39px;font-weight:900;letter-spacing:8px;padding-left:8px;"
                + "text-shadow:0 2px 0 #6a63c8,0 4px 0 #4a43a6,0 6px 0 #332d80,0 8px 14px rgba(10,8,44,.6)}\n"
                + ".brand-word .cn sup{font-size:12px;letter-spacing:0;vertical-align:super;margin-left:2px}\n"
                + ".brand-word .en{margin-top:5px;font-size:12px;font-weight:800;letter-spacing:10px;"
                + "padding-left:10px;color:rgba(255,255,255,.94);text-shadow:0 1px 6px rgba(20,30,100,.6)}\n"
                + ".lang{position:absolute;right:4px;top:4px;display:inline-flex;align-items:center;gap:8px;"
                + "padding:9px 18px;border-radius:999px;background:rgba(255,255,255,.95);"
                + "box-shadow:0 8px 20px rgba(20,28,90,.35),inset 0 1px 0 #fff;font-size:15px;font-weight:700;"
                + "color:#2b3566;cursor:pointer;transition:transform .15s,box-shadow .15s}\n"
                + ".lang:hover{transform:translateY(-1px);box-shadow:0 10px 24px rgba(20,28,90,.42)}\n"
                + ".lang:active{transform:translateY(1px) scale(.97)}\n"
                + ".lang .chev{transition:transform .2s}\n"
                + ".lang:hover .chev{transform:translateY(2px)}\n"
                // 大标题
                + ".title{position:relative;text-align:center;margin:8px 0 0;font-size:74px;font-weight:900;"
                + "letter-spacing:10px;padding-left:10px;color:#fff;line-height:1.1;flex:none;"
                + "text-shadow:0 1px 0 #e6eeff,0 2px 0 #cdd9f7,0 3px 0 #b3c3ee,0 4px 0 #97a9e2,"
                + "0 5px 0 #7b8dd4,0 6px 0 #6374c2,0 7px 0 #515fa9,0 10px 16px rgba(12,10,50,.55)}\n"
                + ".pl{position:absolute;font-style:normal;font-weight:900;line-height:1;pointer-events:none;"
                + "animation:twinkle 2.6s ease-in-out infinite}\n"
                + ".pl::before{content:'+'}\n"
                + ".pl.y{color:#ffd23f;text-shadow:0 3px 0 #e09a1c,0 6px 10px rgba(20,20,80,.4);font-size:40px}\n"
                + ".pl.c{color:#54e0f0;text-shadow:0 3px 0 #1f9fc0,0 6px 10px rgba(20,20,80,.4);font-size:34px}\n"
                + ".pl.p{color:#ff6fc8;text-shadow:0 3px 0 #d23a96,0 6px 10px rgba(20,20,80,.4);font-size:34px}\n"
                + ".pl.a1{left:17%;top:-6px}.pl.a2{left:21%;top:52px;animation-delay:.5s}\n"
                + ".pl.a3{left:24%;top:96px;font-size:24px;animation-delay:1.1s}\n"
                + ".pl.b1{right:18%;top:-14px;animation-delay:.8s}.pl.b2{right:14%;top:44px;animation-delay:1.4s}\n"
                + ".pl.b3{right:20%;top:96px;font-size:24px;animation-delay:.3s}\n"
                + "@keyframes twinkle{0%,100%{transform:scale(1) rotate(0);opacity:.95}"
                + "50%{transform:scale(1.18) rotate(12deg);opacity:1}}\n"
                // 丝带（斜切燕尾）
                + ".ribbon{position:relative;width:fit-content;margin:12px auto 0;padding:10px 58px 11px;flex:none;"
                + "font-family:'STXingkai','华文行楷','STKaiti','KaiTi','楷体',cursive;font-size:26px;"
                + "letter-spacing:3px;color:#fff;background:linear-gradient(180deg,#5b47ad 0%,#43328c 52%,#352776 100%);"
                + "clip-path:polygon(3% 0,97% 0,100% 50%,97% 100%,3% 100%,0 50%);"
                + "filter:drop-shadow(0 10px 18px rgba(28,18,80,.5));text-shadow:0 2px 4px rgba(20,12,70,.6)}\n"
                + ".ribbon::before{content:'';position:absolute;left:4%;right:4%;top:3px;height:42%;"
                + "background:linear-gradient(180deg,rgba(255,255,255,.30),rgba(255,255,255,0));pointer-events:none}\n"
                + ".ribbon .h{color:#ff9ad0;margin-left:6px;display:inline-block;animation:beat 1.7s ease-in-out infinite}\n"
                // 卡片栅格
                + ".picks{display:grid;grid-template-columns:1fr 164px 1fr;gap:18px;align-items:center;"
                + "margin-top:24px;flex:none}\n"
                + ".pc{position:relative;display:flex;flex-direction:column;padding:24px 28px 26px;border-radius:32px;"
                + "text-decoration:none;color:#22305f;border:3px solid rgba(255,255,255,.92);"
                + "transition:transform .18s cubic-bezier(.2,.8,.3,1),box-shadow .18s;"
                + "backdrop-filter:blur(14px) saturate(1.12);-webkit-backdrop-filter:blur(14px) saturate(1.12)}\n"
                + ".pc-blue{background:linear-gradient(163deg,rgba(255,255,255,.95) 0%,rgba(233,243,255,.90) 40%,"
                + "rgba(176,209,247,.90) 100%);"
                + "box-shadow:0 22px 50px rgba(38,52,140,.34),inset 0 2px 0 rgba(255,255,255,.95),"
                + "inset 0 -12px 34px rgba(96,158,238,.35)}\n"
                + ".pc-green{background:linear-gradient(163deg,rgba(255,255,255,.95) 0%,rgba(231,249,242,.90) 38%,"
                + "rgba(244,199,241,.90) 100%);"
                + "box-shadow:0 22px 50px rgba(70,40,130,.32),inset 0 2px 0 rgba(255,255,255,.95),"
                + "inset 0 -12px 34px rgba(226,150,224,.32)}\n"
                + ".pc::after{content:'';position:absolute;left:9%;right:9%;bottom:8px;height:4px;border-radius:99px;"
                + "pointer-events:none;filter:blur(1px)}\n"
                + ".pc-blue::after{background:linear-gradient(90deg,transparent,rgba(90,200,255,.95),transparent)}\n"
                + ".pc-green::after{background:linear-gradient(90deg,transparent,rgba(120,230,150,.95),transparent)}\n"
                + ".pc:hover{transform:translateY(-6px)}\n"
                + ".pc-blue:hover{box-shadow:0 30px 60px rgba(38,52,140,.44),0 0 0 5px rgba(140,200,255,.35),"
                + "inset 0 2px 0 #fff}\n"
                + ".pc-green:hover{box-shadow:0 30px 60px rgba(70,40,130,.42),0 0 0 5px rgba(150,240,180,.35),"
                + "inset 0 2px 0 #fff}\n"
                // 卡头
                + ".pc-head{display:flex;align-items:center;gap:12px;padding-right:96px}\n"
                + ".hic{width:46px;height:46px;object-fit:contain;flex:none;"
                + "filter:drop-shadow(0 5px 8px rgba(40,70,160,.3))}\n"
                + ".pc-name{margin:0;font-size:30px;font-weight:900;letter-spacing:1px;line-height:1.15;color:#1c2a63}\n"
                + ".pc-tag{margin:5px 0 0;font-size:15px;letter-spacing:1.5px;color:#7c89b8;font-weight:500}\n"
                // 徽章
                + ".badge{position:absolute;right:16px;top:58px;width:114px;z-index:6;pointer-events:none;"
                + "filter:drop-shadow(0 10px 14px rgba(60,50,120,.35));animation:badgeSwing 4.6s ease-in-out infinite;"
                + "transform-origin:50% 30%}\n"
                + ".pc-green .badge{animation-delay:1.6s}\n"
                + "@keyframes badgeSwing{0%,100%{transform:translateY(0) rotate(-3deg)}"
                + "50%{transform:translateY(-7px) rotate(3deg)}}\n"
                + ".pc:hover .badge{animation-play-state:paused;transform:scale(1.07) rotate(0deg);transition:transform .2s}\n"
                // 插画
                + ".art{position:relative;margin-top:13px;border-radius:20px;overflow:hidden;"
                + "border:2px solid rgba(255,255,255,.8);"
                + "box-shadow:0 12px 26px rgba(40,60,150,.22),inset 0 1px 0 #fff}\n"
                + ".art img{display:block;width:100%;height:286px;object-fit:cover;transition:transform .35s ease}\n"
                + ".pc:hover .art img{transform:scale(1.045)}\n"
                + ".live-chip{position:absolute;left:12px;bottom:12px;display:inline-flex;align-items:center;gap:7px;"
                + "padding:6px 13px;border-radius:999px;background:rgba(255,255,255,.82);backdrop-filter:blur(6px);"
                + "font-size:12px;font-weight:800;color:#33447e;box-shadow:0 4px 12px rgba(30,40,110,.25)}\n"
                + ".live-chip i{width:8px;height:8px;border-radius:50%;background:#27c465;"
                + "box-shadow:0 0 0 3px rgba(39,196,101,.22);animation:pulse 1.8s ease-in-out infinite}\n"
                + ".live-chip.off i{background:#b7c0dc;box-shadow:none;animation:none}\n"
                + "@keyframes pulse{0%,100%{box-shadow:0 0 0 3px rgba(39,196,101,.22)}"
                + "50%{box-shadow:0 0 0 6px rgba(39,196,101,.08)}}\n"
                // 特性
                + ".feats{display:flex;gap:6px;margin-top:18px}\n"
                + ".feat{display:flex;align-items:center;gap:9px;flex:1;min-width:0}\n"
                + ".fi{width:45px;height:45px;border-radius:50%;flex:none;display:inline-flex;align-items:center;"
                + "justify-content:center;box-shadow:inset 0 -3px 6px rgba(0,0,0,.16),"
                + "inset 0 3px 5px rgba(255,255,255,.5),0 5px 10px rgba(50,70,160,.22);transition:transform .16s}\n"
                + ".feat:hover .fi{transform:scale(1.13) rotate(-7deg)}\n"
                + ".f-blue{background:linear-gradient(180deg,#5aa6ff,#2f7bf0)}\n"
                + ".f-purple{background:linear-gradient(180deg,#b084ff,#8357ec)}\n"
                + ".f-teal{background:linear-gradient(180deg,#4fe0c2,#18b394)}\n"
                + ".f-green{background:linear-gradient(180deg,#63d472,#2fa84c)}\n"
                + ".feat b{display:block;font-size:16px;font-weight:900;color:#22305f;line-height:1.25;white-space:nowrap}\n"
                + ".feat i{display:block;font-style:normal;font-size:11.5px;color:#8290bf;line-height:1.35;white-space:nowrap}\n"
                // 按钮（光泽描边 + 扫光 + 按压 + 呼吸辉光）
                + ".go{position:relative;display:block;margin:20px auto 2px;width:80%;text-align:center;"
                + "border-radius:999px;padding:14px 0;cursor:pointer;overflow:hidden;user-select:none;"
                + "transition:transform .13s ease,filter .15s ease,box-shadow .15s ease}\n"
                + ".go b{position:relative;z-index:2;color:#fff;font-size:22px;font-weight:900;letter-spacing:4px;"
                + "padding-left:4px;text-shadow:0 2px 3px rgba(0,30,100,.4)}\n"
                + ".go .arr{position:relative;z-index:2;color:#fff;font-size:26px;font-weight:900;margin-left:8px}\n"
                + ".go-blue{background:linear-gradient(180deg,#4d97ff 0%,#276fe6 48%,#1553c6 100%);"
                + "border:3px solid #a9d6ff;"
                + "box-shadow:0 0 0 2px rgba(74,158,255,.55),0 12px 26px rgba(27,102,224,.5),"
                + "inset 0 2px 2px rgba(255,255,255,.55),inset 0 -5px 9px rgba(0,35,120,.3);"
                + "animation:glowBlue 2.6s ease-in-out infinite}\n"
                + ".go-green{background:linear-gradient(180deg,#6fdc63 0%,#3cba46 48%,#27963a 100%);"
                + "border:3px solid #c4f4a6;"
                + "box-shadow:0 0 0 2px rgba(96,210,110,.5),0 12px 26px rgba(40,170,80,.46),"
                + "inset 0 2px 2px rgba(255,255,255,.55),inset 0 -5px 9px rgba(10,90,30,.3);"
                + "animation:glowGreen 2.6s ease-in-out infinite}\n"
                + ".go:hover{filter:brightness(1.08)}\n"
                + ".go-blue:hover{box-shadow:0 0 0 3px rgba(120,190,255,.75),0 15px 32px rgba(27,102,224,.6),"
                + "inset 0 2px 2px rgba(255,255,255,.6)}\n"
                + ".go-green:hover{box-shadow:0 0 0 3px rgba(150,245,160,.7),0 15px 32px rgba(40,170,80,.58),"
                + "inset 0 2px 2px rgba(255,255,255,.6)}\n"
                + ".go:active{transform:translateY(3px) scale(.965);filter:brightness(1.02)}\n"
                + ".go::after{content:'';position:absolute;top:0;left:-85%;width:55%;height:100%;z-index:1;"
                + "background:linear-gradient(100deg,transparent,rgba(255,255,255,.55),transparent);"
                + "transform:skewX(-20deg);transition:left .5s ease}\n"
                + ".go:hover::after{left:135%}\n"
                + "@keyframes glowBlue{0%,100%{box-shadow:0 0 0 2px rgba(74,158,255,.55),"
                + "0 12px 26px rgba(27,102,224,.5),inset 0 2px 2px rgba(255,255,255,.55),"
                + "inset 0 -5px 9px rgba(0,35,120,.3)}"
                + "50%{box-shadow:0 0 22px 3px rgba(110,190,255,.75),0 12px 26px rgba(27,102,224,.5),"
                + "inset 0 2px 2px rgba(255,255,255,.55),inset 0 -5px 9px rgba(0,35,120,.3)}}\n"
                + "@keyframes glowGreen{0%,100%{box-shadow:0 0 0 2px rgba(96,210,110,.5),"
                + "0 12px 26px rgba(40,170,80,.46),inset 0 2px 2px rgba(255,255,255,.55),"
                + "inset 0 -5px 9px rgba(10,90,30,.3)}"
                + "50%{box-shadow:0 0 22px 3px rgba(150,245,160,.7),0 12px 26px rgba(40,170,80,.46),"
                + "inset 0 2px 2px rgba(255,255,255,.55),inset 0 -5px 9px rgba(10,90,30,.3)}}\n"
                // 中间切换
                + ".mid{display:flex;flex-direction:column;align-items:center;gap:13px;align-self:center}\n"
                + ".ring{position:relative;width:126px;height:126px;border-radius:50%;cursor:pointer;"
                + "background:conic-gradient(from -90deg,"
                + "#ffd23f 0 23deg,transparent 23deg 29deg,#ff9f43 29deg 52deg,transparent 52deg 58deg,"
                + "#ff6fb5 58deg 81deg,transparent 81deg 87deg,#8a63ff 87deg 110deg,transparent 110deg 116deg,"
                + "#5b7bff 116deg 139deg,transparent 139deg 145deg,#3fa9f5 145deg 168deg,transparent 168deg 174deg,"
                + "#35d6e0 174deg 197deg,transparent 197deg 203deg,#4fe3b0 203deg 226deg,transparent 226deg 232deg,"
                + "#7be88a 232deg 255deg,transparent 255deg 261deg,#b6e84a 261deg 284deg,transparent 284deg 290deg,"
                + "#ffd23f 290deg 313deg,transparent 313deg 319deg,#ff9f43 319deg 342deg,transparent 342deg 348deg,"
                + "#ff6fb5 348deg 360deg);"
                + "filter:drop-shadow(0 12px 20px rgba(30,40,120,.4));animation:ringSpin 18s linear infinite;"
                + "transition:transform .2s}\n"
                + ".ring:hover{animation-duration:7s}\n"
                + ".ring::before{content:'';position:absolute;inset:15px;border-radius:50%;background:#fff;"
                + "box-shadow:inset 0 -4px 8px rgba(70,90,190,.22),inset 0 4px 8px rgba(70,90,190,.12)}\n"
                + ".ring::after{content:'';position:absolute;inset:3px;border-radius:50%;pointer-events:none;"
                + "background:radial-gradient(circle at 34% 26%,rgba(255,255,255,.75),rgba(255,255,255,0) 42%)}\n"
                + ".ring svg{position:absolute;inset:0;margin:auto;width:58px;height:58px;z-index:2;"
                + "animation:ringSpinRev 18s linear infinite}\n"
                + "@keyframes ringSpin{to{transform:rotate(360deg)}}\n"
                + "@keyframes ringSpinRev{to{transform:rotate(-360deg)}}\n"
                + ".switch-btn{padding:9px 26px;border-radius:999px;background:#fff;color:#2f6be0;"
                + "font-size:17px;font-weight:900;letter-spacing:3px;padding-left:29px;cursor:pointer;"
                + "border:2px solid #d3e2ff;box-shadow:0 8px 18px rgba(30,40,120,.35),inset 0 1px 0 #fff;"
                + "transition:transform .14s,box-shadow .14s}\n"
                + ".switch-btn:hover{transform:translateY(-2px);box-shadow:0 11px 22px rgba(30,40,120,.42)}\n"
                + ".switch-btn:active{transform:translateY(1px) scale(.94)}\n"
                + ".slogan{text-align:center;font-family:'STXingkai','华文行楷','STKaiti','KaiTi','楷体',cursive;"
                + "color:#fff;font-size:26px;line-height:1.24;font-weight:700;letter-spacing:2px;"
                + "text-shadow:0 3px 10px rgba(20,20,80,.65),0 0 2px rgba(20,20,80,.4);"
                + "transform:rotate(-3deg);animation:sloganFloat 4.2s ease-in-out infinite}\n"
                + ".slogan .h{color:#ffd6ec}\n"
                + "@keyframes sloganFloat{0%,100%{transform:rotate(-3deg) translateY(0)}"
                + "50%{transform:rotate(-1deg) translateY(-5px)}}\n"
                // 底部口碑条
                + ".bottom{display:flex;align-items:center;gap:14px;margin-top:auto;padding-top:24px;flex:none;"
                + "flex-wrap:nowrap;margin-left:-92px;margin-right:-92px}\n"
                + ".score-card{flex:none;width:132px;background:linear-gradient(180deg,#fff,#fdf6e2);"
                + "border:2.5px solid #f4c34d;border-radius:19px;padding:10px 10px 8px;text-align:center;"
                + "box-shadow:0 10px 22px rgba(120,80,20,.3),inset 0 1px 0 #fff}\n"
                + ".score-pill{display:inline-flex;align-items:center;gap:5px;padding:4px 13px;border-radius:999px;"
                + "background:linear-gradient(180deg,#ffe88a,#f6b733);"
                + "box-shadow:inset 0 1px 0 rgba(255,255,255,.7),0 3px 7px rgba(200,140,20,.4)}\n"
                + ".score-pill b{font-size:22px;font-weight:900;color:#7a4a00;line-height:1}\n"
                + ".score-label{display:block;margin-top:5px;font-size:13.5px;font-weight:800;color:#4a5580}\n"
                + ".cat{flex:none;width:72px;height:72px;object-fit:contain;"
                + "filter:drop-shadow(0 6px 10px rgba(30,40,120,.4));animation:catFloat 4.4s ease-in-out infinite}\n"
                + "@keyframes catFloat{0%,100%{transform:translateY(0)}50%{transform:translateY(-7px)}}\n"
                + ".pill{flex:1;min-width:0;display:flex;align-items:center;gap:2px;padding:14px 22px;"
                + "border-radius:999px;background:linear-gradient(180deg,rgba(255,255,255,.95),"
                + "rgba(241,246,255,.9));border:2px solid rgba(255,255,255,.95);"
                + "box-shadow:0 14px 32px rgba(40,40,120,.3),inset 0 1px 0 #fff;backdrop-filter:blur(10px)}\n"
                + ".stat{display:flex;align-items:center;gap:10px;padding:0 14px;border-right:1px solid #e4eaf8;flex:none}\n"
                + ".stat:last-of-type{border-right:none}\n"
                + ".si{width:44px;height:44px;border-radius:50%;flex:none;display:inline-flex;align-items:center;"
                + "justify-content:center;box-shadow:inset 0 -3px 6px rgba(0,0,0,.14),"
                + "inset 0 2px 4px rgba(255,255,255,.5),0 4px 9px rgba(60,70,160,.2)}\n"
                + ".s1{background:linear-gradient(180deg,#5fb0ff,#2f7bf0)}\n"
                + ".s2{background:linear-gradient(180deg,#5fe6b6,#1fc084)}\n"
                + ".s3{background:linear-gradient(180deg,#c79bff,#955ff0)}\n"
                + ".s4{background:linear-gradient(180deg,#ff9ed0,#f565ad)}\n"
                + ".stat b{display:block;font-size:16px;font-weight:900;color:#22305f;line-height:1.2;white-space:nowrap}\n"
                + ".stat i{display:block;font-style:normal;font-size:12px;color:#8a96c2;line-height:1.3;white-space:nowrap}\n"
                + ".happy{display:flex;align-items:center;gap:10px;margin-left:12px;flex:none}\n"
                + ".happy b{font-size:18px;font-weight:900;color:#22305f;white-space:nowrap}\n"
                + ".happy svg{animation:beat 1.8s ease-in-out infinite}\n"
                + "@keyframes beat{0%,100%{transform:scale(1)}30%{transform:scale(1.18)}"
                + "45%{transform:scale(.96)}60%{transform:scale(1.12)}}\n"
                // 加入我们
                + ".join{flex:none;display:flex;align-items:center;gap:8px;padding:8px 10px 8px 12px;border-radius:18px;"
                + "background:linear-gradient(180deg,#fff,#f3f0ff);border:2px solid rgba(255,255,255,.95);"
                + "box-shadow:0 12px 26px rgba(50,35,120,.34),inset 0 1px 0 #fff;cursor:pointer;text-decoration:none;"
                + "transition:transform .15s,box-shadow .15s}\n"
                + ".join:hover{transform:translateY(-3px);box-shadow:0 16px 32px rgba(50,35,120,.44)}\n"
                + ".join:active{transform:translateY(1px) scale(.96)}\n"
                + ".join .jt b{display:block;font-size:15px;font-weight:900;color:#4a3a9a;line-height:1.2}\n"
                + ".join .jt i{display:block;font-style:normal;font-size:10.5px;color:#9a90d0;line-height:1.3}\n"
                + ".join .ja{width:34px;height:34px;border-radius:50%;flex:none;display:inline-flex;align-items:center;"
                + "justify-content:center;background:linear-gradient(180deg,#9a7bff,#6a45e8);"
                + "box-shadow:inset 0 2px 3px rgba(255,255,255,.4),0 5px 11px rgba(90,60,220,.45)}\n"
                // 漂浮装饰
                + ".deco{position:fixed;z-index:0;pointer-events:none;font-weight:900;"
                + "animation:floaty 6s ease-in-out infinite;text-shadow:0 4px 10px rgba(20,20,80,.35)}\n"
                + ".deco.d1{left:5%;top:22%;color:#ffd23f;font-size:26px}\n"
                + ".deco.d2{right:4.5%;top:18%;color:#6fe6ff;font-size:24px;animation-delay:1.3s}\n"
                + ".deco.d3{left:9%;bottom:12%;color:#ff8fd0;font-size:22px;animation-delay:2.2s}\n"
                + ".deco.d4{right:8%;bottom:16%;color:#ffd23f;font-size:20px;animation-delay:.7s}\n"
                + "@keyframes floaty{0%,100%{transform:translateY(0) rotate(0)}"
                + "50%{transform:translateY(-14px) rotate(12deg)}}\n"
                // 响应式
                + "@media (max-width:1280px){.bottom{flex-wrap:wrap;justify-content:center;margin-left:0;margin-right:0}"
                + ".pill{order:5;flex-basis:100%;flex-wrap:wrap;border-radius:28px;justify-content:center;gap:10px}"
                + ".stat{border-right:none}}\n"
                + "@media (max-width:1000px){.picks{grid-template-columns:1fr;gap:26px}"
                + ".mid{flex-direction:row;flex-wrap:wrap;justify-content:center;gap:16px}.slogan{width:100%}"
                + ".title{font-size:46px;letter-spacing:6px}.pc-head{padding-right:80px}}\n";
    }

    /** eagler 选择页地址：固定 /web/plugins/WebGame/eagler/，异常时退回同一常量。 */
    private String eaglerPageUrl() {
        try {
            if (plugin.getWebGameConfig() != null) {
                return plugin.getWebGameConfig().getPageFullUrl();
            }
        } catch (Throwable ignored) {
        }
        return WebGameConfig.WEB_PLUGIN_PREFIX + "/eagler/";
    }

    private void tag(WebRegistry.Entry entry) {
        if (entry != null && entry.tags != null && !entry.tags.contains(TAG)) {
            entry.tags.add(TAG);
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
}
