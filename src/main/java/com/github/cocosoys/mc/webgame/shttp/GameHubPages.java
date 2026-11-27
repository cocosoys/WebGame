package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 玩家统一入口页：{@code /web/plugins/WebGame/play/}。
 *
 * <p>这是发给玩家的唯一入口地址：先选「云游戏客户端」还是「Eagler 网页版」，再进各自
 * 二级选择页（{@code /cloud/}、{@code /eagler/}）。两张卡片上的实时数据（云机总数/在线数、
 * 可用版本数）在页面登记时由服务端现算，并随两个页面系统每次 republish 一起刷新。</p>
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
    private final WebGameClientService clientService;
    private final CloudClientService cloudClientService;

    private volatile SoysHttpOverMcApi api;

    public GameHubPages(WebGame plugin, WebGameClientService clientService,
                        CloudClientService cloudClientService) {
        this.plugin = plugin;
        this.clientService = clientService;
        this.cloudClientService = cloudClientService;
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
        // 云机统计：只数启用且地址齐全的条目（与 /cloud/ 选择页同一口径）
        int cloudTotal = 0;
        int cloudOnline = 0;
        if (cloudClientService != null) {
            for (CloudClient c : cloudClientService.versions()) {
                if (!c.isEnabled() || c.isMissing()) {
                    continue;
                }
                cloudTotal++;
                if (CloudClient.STATUS_ONLINE.equals(c.getStatus())) {
                    cloudOnline++;
                }
            }
        }
        // 版本统计：与 /eagler/ 选择页同一口径
        int eaglerTotal = 0;
        if (clientService != null) {
            for (ClientVersion v : clientService.versions()) {
                if (!v.isEnabled() || v.isMissing()) {
                    continue;
                }
                eaglerTotal++;
            }
        }

        String cloudHref = WebGameConfig.WEB_PLUGIN_PREFIX + "/cloud/";
        String eaglerHref = eaglerPageUrl();

        String cloudChips = cloudTotal == 0
                ? "<span>暂无可用云机</span>"
                : "<span>" + cloudTotal + " 台云机</span>"
                + (cloudOnline > 0
                        ? "<span class=\"live\">" + cloudOnline + " 台在线</span>"
                        : "<span>暂无在线</span>");
        String eaglerChips = eaglerTotal == 0
                ? "<span>暂无可用版本</span>"
                : "<span>" + eaglerTotal + " 个可用版本</span>";

        // 内联 SVG 图标（白色符号，用于特性圆形图标）
        String icLightning = "<svg viewBox='0 0 24 24' width='14' height='14'><path fill='#fff' "
                + "d='M13 2L4.5 13.5H11L9.8 22l8.7-11.5h-6z'/></svg>";
        String icPeople = "<svg viewBox='0 0 24 24' width='15' height='15'><circle cx='9' cy='8' r='3' fill='#fff'/>"
                + "<circle cx='16.5' cy='8' r='2.5' fill='#fff'/>"
                + "<path fill='#fff' d='M3.5 20c0-3.3 2.3-5.5 5.5-5.5s5.5 2.2 5.5 5.5v.8H3.5V20z'/>"
                + "<path fill='#fff' d='M16.6 14.9c1.9.7 3.1 2.5 3.1 4.6v.8h-2.7v-5.4z'/></svg>";
        String icShield = "<svg viewBox='0 0 24 24' width='14' height='14'><path fill='#fff' "
                + "d='M12 2l8 3.5V12c0 5.2-3.4 8.6-8 10-4.6-1.4-8-4.8-8-10V5.5L12 2zm0 2.3L6 7v5"
                + "c0 4 2.5 6.9 6 8 3.5-1.1 6-4 6-8V7l-6-2.7z'/></svg>";
        String icGlobe = "<svg viewBox='0 0 24 24' width='15' height='15' fill='none' stroke='#fff' "
                + "stroke-width='1.8'><circle cx='12' cy='12' r='9'/><path d='M3 12h18M12 3"
                + "c3 3 3 15 0 18M12 3c-3 3-3 15 0 18'/></svg>";
        // 底部口碑条图标
        String icStar = "<svg viewBox='0 0 24 24' width='18' height='18'><path fill='#e8a400' "
                + "d='M12 2l2.9 6.2 6.6.8-4.9 4.5 1.3 6.5L12 16.9 6.1 20l1.3-6.5L2.5 9l6.6-.8z'/></svg>";
        String icHeart = "<svg viewBox='0 0 24 24' width='20' height='20'><path fill='#ff5a76' "
                + "d='M12 21s-8.5-5.4-8.5-11.3C3.5 6.5 5.6 4.5 8 4.5c1.6 0 3 .8 4 2 1-1.2 2.4-2 4-2 "
                + "2.4 0 4.5 2 4.5 5.2 0 5.9-8.5 11.3-8.5 11.3z'/></svg>";
        String icCube = "<svg viewBox='0 0 24 24' width='20' height='20'>"
                + "<path fill='#4cbf74' d='M12 3l7 3.8v8.4L12 19l-7-3.8V6.8L12 3zm0 2.2L7.2 8.4 12 11l4.8-2.6L12 5.2z'/>"
                + "<path fill='#2e9e58' d='M5 8.4v7.5l7 3.8v-7.5l-7-3.8zm14 0l-7 3.8v7.5l7-3.8V8.4z'/></svg>";
        String icTrophy = "<svg viewBox='0 0 24 24' width='20' height='20'><path fill='#f0a83a' "
                + "d='M7 3h10v1h3v3c0 2.6-1.9 4.7-4.4 5.2-.7 1.9-2.2 3.2-4.1 3.6V18h3v2H8v-2h3v-2.2"
                + "c-1.9-.4-3.4-1.7-4.1-3.6C4.4 11.7 3 9.6 3 7V4h4V3zm-2 3v1c0 1.7 1.1 3 2.8 3.4"
                + "C7.9 9 8 7.5 8 6.2V6H5zm12 0h-2v.2c0 1.3.1 2.8.2 4.2C17.9 10 19 8.7 19 7V6z'/></svg>";
        String icCrown = "<svg viewBox='0 0 24 24' width='20' height='20'><path fill='#f7c948' "
                + "d='M3 7l4.5 4L12 4l4.5 7L21 7l-1.8 10H4.8L3 7zm3.4 8h11.2l.9-5-3.3 2.9L12 8.5"
                + "l-3.2 4.4-3.4-2.9.9 5z'/></svg>";
        String icHeartPink = "<svg viewBox='0 0 24 24' width='20' height='20'><path fill='#ff8fb8' "
                + "d='M12 21s-8.5-5.4-8.5-11.3C3.5 6.5 5.6 4.5 8 4.5c1.6 0 3 .8 4 2 1-1.2 2.4-2 4-2 "
                + "2.4 0 4.5 2 4.5 5.2 0 5.9-8.5 11.3-8.5 11.3z'/></svg>";
        String icJoinPlus = "<svg viewBox='0 0 24 24' width='28' height='28'><rect x='3' y='3' width='18' "
                + "height='18' rx='5' fill='#7b4fd0'/><path d='M12 8v8M8 12h8' stroke='#e6d8ff' "
                + "stroke-width='2.2' stroke-linecap='round'/></svg>";
        String icJoinArrow = "<svg viewBox='0 0 24 24' width='14' height='14' fill='none' stroke='#fff' "
                + "stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'><path d='M9 5l7 7-7 7'/></svg>";

        StringBuilder body = new StringBuilder(8192);
        body.append("<span class=\"deco d1\" style=\"left:6%;top:24%\">✦</span>")
                .append("<span class=\"deco d2\" style=\"right:5%;top:16%\">✚</span>")
                .append("<span class=\"deco d3\" style=\"left:11%;top:64%\">✚</span>")
                .append("<span class=\"deco d4\" style=\"right:10%;top:56%\">✦</span>")
                .append("<span class=\"deco d5\" style=\"left:4%;bottom:8%\">✦</span>")
                .append("<span class=\"deco d6\" style=\"right:6%;bottom:12%\">✚</span>")
                .append("<div class=\"hub-head\">\n")
                .append("<div class=\"hub-logo\"><div class=\"logo-text\">方块世界<sup>®</sup></div>")
                .append("<div class=\"logo-en\">MINECRAFT</div></div>\n")
                .append("<span class=\"lang\">简体中文 ▾</span>\n")
                .append("</div>\n")
                .append("<h1 class=\"hub-h1\">选择游戏方式</h1>\n")
                .append("<div class=\"hub-ribbon\"><span class=\"rib-text\">开启你的方块冒险</span>")
                .append("<b class=\"rib-heart\">♡</b></div>\n")
                .append("<div class=\"picks\">\n")
                // ---- 云游戏客户端 ----
                .append("<a class=\"pc\" href=\"").append(cloudHref).append("\">")
                .append("<span class=\"pc-badge\"><img src=\"")
                .append(GamePageTheme.image("badge-gold.png")).append("\" alt=\"\"></span>")
                .append("<div class=\"pc-head\"><h3 class=\"pc-name\">云游戏客户端</h3>")
                .append("<p class=\"pc-tag\">高性能 · 低门槛 · 随时畅玩</p></div>")
                .append("<div class=\"pc-artwrap\"><img class=\"pc-art\" src=\"")
                .append(GamePageTheme.image("cloud.jpg")).append("\" alt=\"\">")
                .append("<div class=\"pc-chips\">").append(cloudChips).append("</div></div>")
                .append("<div class=\"feats\">")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icLightning)
                .append("</span><div><b>即点即玩</b><i>无需高配设备</i></div></div>")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icPeople)
                .append("</span><div><b>自动匹配</b><i>快速找到玩伴</i></div></div>")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icShield)
                .append("</span><div><b>稳定流畅</b><i>专业网络加速</i></div></div>")
                .append("</div>")
                .append("<span class=\"pc-go\"><span class=\"pc-go-in\">立即体验 &gt;</span></span></a>\n")
                // ---- 中间切换 ----
                .append("<div class=\"switch\">")
                .append("<span class=\"switch-ring\"><span>⇄</span></span>")
                .append("<span class=\"switch-btn\">一键切换</span>")
                .append("<div class=\"switch-slogan\"><span>不同方式</span>")
                .append("<span>同样精彩 <b class=\"heart\">♥</b></span></div>")
                .append("</div>\n")
                // ---- Eagler 网页版 ----
                .append("<a class=\"pc alt\" href=\"").append(eaglerHref).append("\">")
                .append("<span class=\"pc-badge\"><img src=\"")
                .append(GamePageTheme.image("badge-green.png")).append("\" alt=\"\"></span>")
                .append("<div class=\"pc-head\"><h3 class=\"pc-name\">Eagler 网页版</h3>")
                .append("<p class=\"pc-tag\">无需下载 · 打开即玩</p></div>")
                .append("<div class=\"pc-artwrap\"><img class=\"pc-art\" src=\"")
                .append(GamePageTheme.image("eagler.jpg")).append("\" alt=\"\">")
                .append("<div class=\"pc-chips\">").append(eaglerChips).append("</div></div>")
                .append("<div class=\"feats\">")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icGlobe)
                .append("</span><div><b>无需下载</b><i>浏览器直接玩</i></div></div>")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icPeople)
                .append("</span><div><b>跨平台联机</b><i>支持多设备</i></div></div>")
                .append("<div class=\"feat\"><span class=\"fi\">").append(icShield)
                .append("</span><div><b>安全可靠</b><i>官方正版支持</i></div></div>")
                .append("</div>")
                .append("<span class=\"pc-go\"><span class=\"pc-go-in\">开始游戏 &gt;</span></span></a>\n")
                .append("</div>\n")
                // ---- 底部口碑条 ----
                .append("<div class=\"strip\">")
                .append("<div class=\"score\"><span class=\"score-star\">").append(icStar)
                .append("</span><span class=\"score-text\"><b>4.8</b><i>玩家好评</i></span></div>")
                .append("<img class=\"strip-cat\" src=\"")
                .append(GamePageTheme.image("cat.png")).append("\" alt=\"\">")
                .append("<div class=\"si\">").append(icHeart)
                .append("<b>百万玩家</b><i>共同冒险</i></div>")
                .append("<div class=\"si\">").append(icCube)
                .append("<b>精彩内容</b><i>持续更新</i></div>")
                .append("<div class=\"si\">").append(icTrophy)
                .append("<b>多元玩法</b><i>创造、生存、联机</i></div>")
                .append("<div class=\"si\">").append(icCrown)
                .append("<b>社区支持</b><i>与玩家一起成长</i></div>")
                .append("<div class=\"si\">").append(icHeartPink)
                .append("<b class=\"slogan\">游戏，让快乐更简单！</b></div>")
                .append("<span class=\"strip-join\"><span class=\"join-ic\">").append(icJoinPlus)
                .append("</span><span class=\"join-text\"><b>加入我们</b><i>发现更多精彩</i></span>")
                .append("<span class=\"join-arrow\">").append(icJoinArrow).append("</span></span>")
                .append("</div>\n");

        String extraCss = ""
                + ".wrap{max-width:1180px}\n"
                + ".hub-head{position:relative;display:flex;align-items:center;justify-content:center;"
                + "margin:6px 0 16px}\n"
                + ".hub-logo{display:flex;flex-direction:column;align-items:center;gap:1px}\n"
                + ".logo-text{font-size:34px;font-weight:900;letter-spacing:10px;padding-left:10px;color:#fff;"
                + "text-shadow:0 2px 0 #5a54b8,0 4px 0 #3f3a94,0 6px 0 #2c2870,0 8px 0 #1d1957,"
                + "0 13px 18px rgba(16,12,52,.6)}\n"
                + ".logo-text sup{font-size:11px;font-weight:700;letter-spacing:0;vertical-align:super;"
                + "text-shadow:0 2px 0 #5a54b8,0 4px 0 #3f3a94,0 6px 0 #2c2870,0 8px 0 #1d1957}\n"
                + ".logo-en{font-size:11px;font-weight:800;letter-spacing:9px;padding-left:9px;"
                + "color:rgba(255,255,255,.92);text-shadow:0 1px 6px rgba(30,40,120,.5)}\n"
                + ".lang{position:absolute;right:0;top:6px;padding:6px 14px;border-radius:999px;"
                + "background:rgba(255,255,255,.25);border:1px solid rgba(255,255,255,.4);"
                + "font-size:12px;color:#fff;transition:background .15s ease}\n"
                + ".lang:hover{background:rgba(255,255,255,.4)}\n"
                + ".hub-h1{text-align:center;font-size:42px;font-weight:900;letter-spacing:9px;"
                + "padding-left:9px;margin:0 0 14px;color:#fff;"
                + "text-shadow:0 2px 0 #5a54b8,0 4px 0 #3f3a94,0 6px 0 #2c2870,"
                + "0 10px 18px rgba(20,16,60,.55)}\n"
                + ".hub-ribbon{display:flex;align-items:center;justify-content:center;gap:9px;"
                + "width:fit-content;margin:0 auto 28px;padding:9px 34px 9px 30px;position:relative;"
                + "background:linear-gradient(90deg,#5a3fae,#7a54d8 60%,#5a3fae);"
                + "clip-path:polygon(0 0,96% 0,100% 50%,96% 100%,0 100%);"
                + "box-shadow:0 8px 20px rgba(40,28,110,.45)}\n"
                + ".hub-ribbon::before{content:'';position:absolute;left:6px;right:6px;top:2px;height:40%;"
                + "border-radius:999px;background:linear-gradient(180deg,rgba(255,255,255,.28),"
                + "rgba(255,255,255,0))}\n"
                + ".rib-text{color:#fff;font-size:15px;font-weight:800;letter-spacing:3px;"
                + "text-shadow:0 1px 4px rgba(30,20,90,.5)}\n"
                + ".rib-heart{color:#ff8fb8;font-size:17px;font-weight:700;line-height:1;"
                + "animation:beat 1.6s ease-in-out infinite}\n"
                + ".deco{position:fixed;font-size:20px;font-weight:800;opacity:.85;pointer-events:none;"
                + "text-shadow:0 2px 6px rgba(30,40,120,.4);animation:float 5.5s ease-in-out infinite}\n"
                + ".d1{color:#ffd54d}.d2{color:#7fb3ff}.d3{color:#ff9ad0}.d4{color:#ffd54d}"
                + ".d5{color:#7fb3ff}.d6{color:#ff9ad0}\n"
                + ".d2{animation-delay:1.2s}.d3{animation-delay:2.1s}.d4{animation-delay:.7s}"
                + ".d5{animation-delay:1.8s}.d6{animation-delay:2.6s}\n"
                + "@keyframes float{0%,100%{transform:translateY(0) rotate(0)}"
                + "50%{transform:translateY(-12px) rotate(10deg)}}\n"
                + ".picks{display:grid;grid-template-columns:1fr 148px 1fr;gap:22px;align-items:center}\n"
                + ".pc{position:relative;display:block;padding:24px 24px 26px;border-radius:26px;"
                + "text-decoration:none;color:#24315e;background:rgba(255,255,255,.95);"
                + "border:2px solid rgba(255,255,255,.72);"
                + "box-shadow:0 14px 34px rgba(46,54,140,.30),inset 0 1px 0 rgba(255,255,255,.8);"
                + "transition:transform .15s ease,box-shadow .15s ease}\n"
                + ".pc:hover{transform:translateY(-4px);box-shadow:0 18px 40px rgba(30,40,120,.38),"
                + "0 0 0 4px rgba(255,255,255,.28)}\n"
                + ".pc-badge{position:absolute;right:10px;top:60px;z-index:2;width:90px;height:90px;"
                + "pointer-events:none;animation:badgeFloat 4s ease-in-out infinite}\n"
                + ".pc-badge img{display:block;width:100%;height:100%;object-fit:contain;"
                + "filter:drop-shadow(0 6px 14px rgba(30,40,120,.45))}\n"
                + ".pc.alt .pc-badge{animation-delay:1.5s}\n"
                + "@keyframes badgeFloat{0%,100%{transform:translateY(0) rotate(-2deg)}"
                + "50%{transform:translateY(-6px) rotate(2deg)}}\n"
                + ".pc-name{margin:0;font-size:23px;font-weight:900}\n"
                + ".pc-tag{margin:4px 0 0;color:#8a97c4;font-size:12px;letter-spacing:1px}\n"
                + ".pc-artwrap{position:relative;margin-top:14px;overflow:hidden;border-radius:18px}\n"
                + ".pc-art{display:block;width:100%;height:172px;object-fit:cover;border-radius:18px;"
                + "border:1px solid rgba(255,255,255,.5);transition:transform .3s ease}\n"
                + ".pc:hover .pc-art{transform:scale(1.04)}\n"
                + ".pc-chips{position:absolute;left:10px;top:10px;display:flex;gap:6px}\n"
                + ".pc-chips span{padding:3px 10px;border-radius:999px;background:rgba(255,255,255,.92);"
                + "color:#3a4c86;font-size:11px;font-weight:700;box-shadow:0 2px 8px rgba(30,40,120,.25)}\n"
                + ".pc-chips span.live{color:#1f9e52}\n"
                + ".feats{display:flex;gap:12px;margin-top:18px;padding:0;list-style:none}\n"
                + ".feat{display:flex;align-items:center;gap:8px;flex:1}\n"
                + ".feats .fi{width:30px;height:30px;border-radius:50%;display:inline-flex;align-items:center;"
                + "justify-content:center;flex:none;"
                + "box-shadow:inset 0 -2px 4px rgba(0,0,0,.15),inset 0 2px 3px rgba(255,255,255,.4);"
                + "transition:transform .15s ease}\n"
                + ".feat:hover .fi{transform:scale(1.12) rotate(-6deg)}\n"
                + ".pc .fi{background:linear-gradient(180deg,#5f9dff,#3f7bf0)}\n"
                + ".pc.alt .fi{background:linear-gradient(180deg,#4ed07f,#2fae5c)}\n"
                + ".feat b{display:block;font-size:12.5px;font-weight:800;line-height:1.25}\n"
                + ".feat i{display:block;font-style:normal;font-size:10px;color:#7d8bbd;line-height:1.3}\n"
                + ".pc-go{display:block;width:fit-content;margin:20px auto 0;border-radius:14px;"
                + "background:linear-gradient(180deg,#6db1ff,#3f7bf0);"
                + "box-shadow:0 8px 18px rgba(63,140,255,.45),inset 0 2px 0 rgba(255,255,255,.4);"
                + "transition:filter .15s ease,transform .15s ease,box-shadow .15s ease;"
                + "cursor:pointer;position:relative;overflow:hidden}\n"
                + ".pc-go-in{display:block;padding:11px 36px;color:#fff;font-size:15px;font-weight:800;"
                + "letter-spacing:2px}\n"
                + ".pc-go:hover{filter:brightness(1.1);box-shadow:0 10px 22px rgba(63,140,255,.55),"
                + "inset 0 2px 0 rgba(255,255,255,.4)}\n"
                + ".pc-go:active{transform:translateY(2px) scale(.95);filter:brightness(1.03)}\n"
                + ".pc-go::after{content:'';position:absolute;top:0;left:-80%;width:60%;height:100%;"
                + "background:linear-gradient(100deg,transparent,rgba(255,255,255,.5),transparent);"
                + "transform:skewX(-20deg);transition:left .45s ease}\n"
                + ".pc-go:hover::after{left:130%}\n"
                + ".pc.alt .pc-go{background:linear-gradient(180deg,#56d68a,#2fb85f);"
                + "box-shadow:0 8px 18px rgba(52,199,110,.45),inset 0 2px 0 rgba(255,255,255,.4)}\n"
                + ".switch{display:flex;flex-direction:column;align-items:center;gap:14px}\n"
                + ".switch-ring{width:64px;height:64px;border-radius:50%;position:relative;"
                + "background:conic-gradient(from 30deg,#45c8f0 0 40deg,transparent 40deg 62deg,"
                + "#8a6bff 62deg 102deg,transparent 102deg 124deg,#45c8f0 124deg 164deg,"
                + "transparent 164deg 186deg,#8a6bff 186deg 226deg,transparent 226deg 248deg,"
                + "#45c8f0 248deg 288deg,transparent 288deg 310deg,#8a6bff 310deg 350deg,"
                + "transparent 350deg 360deg);box-shadow:0 8px 20px rgba(30,40,120,.35);"
                + "animation:ringSpin 14s linear infinite;cursor:pointer}\n"
                + ".switch-ring::before{content:'';position:absolute;inset:7px;border-radius:50%;"
                + "background:#fff;box-shadow:inset 0 -3px 6px rgba(60,80,180,.25),"
                + "inset 0 3px 6px rgba(60,80,180,.15)}\n"
                + ".switch-ring span{position:absolute;inset:0;display:flex;align-items:center;"
                + "justify-content:center;color:#4f6df0;font-size:22px;font-weight:900;"
                + "animation:ringSpinRev 14s linear infinite}\n"
                + "@keyframes ringSpin{to{transform:rotate(360deg)}}\n"
                + "@keyframes ringSpinRev{to{transform:rotate(-360deg)}}\n"
                + ".switch-btn{display:inline-block;padding:10px 24px;border-radius:999px;"
                + "background:linear-gradient(180deg,#5f8dff,#3f6df0);border:2px solid #fff;"
                + "color:#fff;font-size:14px;font-weight:800;letter-spacing:3px;padding-left:27px;"
                + "box-shadow:0 8px 20px rgba(30,40,120,.4),inset 0 2px 0 rgba(255,255,255,.3);"
                + "cursor:pointer;transition:transform .15s ease,box-shadow .15s ease}\n"
                + ".switch-btn:hover{transform:translateY(-2px);box-shadow:0 10px 24px rgba(30,40,120,.45),"
                + "inset 0 2px 0 rgba(255,255,255,.3)}\n"
                + ".switch-btn:active{transform:translateY(1px) scale(.95)}\n"
                + ".switch-slogan span{display:block;text-align:center;font-size:12.5px;font-weight:800;"
                + "letter-spacing:4px;padding-left:4px;color:#fff;text-shadow:0 2px 6px rgba(30,40,120,.5)}\n"
                + ".switch-slogan .heart{color:#ff9ad0;font-size:15px;display:inline-block;"
                + "animation:beat 1.6s ease-in-out infinite}\n"
                + "@keyframes beat{0%,100%{transform:scale(1)}50%{transform:scale(1.25)}}\n"
                + ".strip{margin-top:34px;display:flex;align-items:center;justify-content:space-between;"
                + "flex-wrap:wrap;gap:10px 16px}\n"
                + ".score{display:flex;align-items:center;gap:8px;padding:7px 16px 7px 12px;"
                + "border-radius:999px;background:linear-gradient(135deg,#ffe9a8,#ffd54d);"
                + "box-shadow:0 6px 16px rgba(230,170,40,.45),inset 0 1px 0 rgba(255,255,255,.6)}\n"
                + ".score-star{display:inline-flex}\n"
                + ".score-text b{display:block;font-size:19px;font-weight:900;color:#8a5a00;line-height:1.05}\n"
                + ".score-text i{display:block;font-style:normal;font-size:10px;font-weight:700;"
                + "color:#a06c00;line-height:1.2}\n"
                + ".strip-cat{width:54px;height:54px;object-fit:contain;"
                + "filter:drop-shadow(0 4px 10px rgba(30,40,120,.4));animation:float 4s ease-in-out infinite}\n"
                + ".si{display:flex;flex-direction:column;align-items:center;gap:3px}\n"
                + ".si b{font-size:12px;font-weight:800;color:#fff;text-shadow:0 1px 5px rgba(30,40,120,.45)}\n"
                + ".si i{font-style:normal;font-size:10px;color:rgba(255,255,255,.85)}\n"
                + ".si .slogan{font-size:14px;letter-spacing:1px}\n"
                + ".strip-join{display:flex;align-items:center;gap:10px;padding:7px 8px 7px 8px;"
                + "border-radius:999px;background:#fff;box-shadow:0 6px 16px rgba(30,40,120,.35);"
                + "cursor:pointer;transition:transform .15s ease,box-shadow .15s ease}\n"
                + ".strip-join:hover{transform:translateY(-2px);box-shadow:0 9px 20px rgba(30,40,120,.45)}\n"
                + ".strip-join:active{transform:translateY(1px) scale(.96)}\n"
                + ".join-ic{display:inline-flex}\n"
                + ".join-text b{display:block;font-size:13px;font-weight:800;color:#4a3a9a;line-height:1.2}\n"
                + ".join-text i{display:block;font-style:normal;font-size:10px;color:#8a7fd0;line-height:1.2}\n"
                + ".join-arrow{width:30px;height:30px;border-radius:50%;"
                + "background:linear-gradient(180deg,#8a6bff,#6a4df0);display:inline-flex;align-items:center;"
                + "justify-content:center;box-shadow:inset 0 2px 0 rgba(255,255,255,.35)}\n"
                + "@media (max-width:760px){.picks{grid-template-columns:1fr}.switch{flex-direction:row;"
                + "justify-content:center;gap:10px}.switch-slogan span{display:inline-block;margin:0 4px}"
                + ".pc-badge{right:10px;top:56px;width:72px;height:72px}}\n";

        return GamePageTheme.shell("WebGame · 选择游戏方式", extraCss, body.toString());
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
