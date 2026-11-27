package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.api.WebPageApi;
import com.github.cocosoys.mc.soyshttpovermc.web.WebRegistry;
import com.github.cocosoys.mc.webgame.WebGame;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 独立的“输入游戏名称”页：玩家选完客户端后先到这里填名字，再继续进入对应客户端。
 *
 * <p>页面不带任何设备密钥，只接收 {@code kind / label / back} 三个参数；提交后把名字以
 * {@code player}（云游戏）或 {@code user}（Eagler）追加到 {@code back} 再跳转。云游戏入口页、
 * Eagler 客户端页都不再自己弹出用户名表单。</p>
 */
public final class PlayerNamePage {

    private static final Logger LOG = Logger.getLogger(PlayerNamePage.class.getName());

    /** 页面路径（相对插件页面根）。 */
    public static final String PATH = "/play/name/";
    /** 本类登记条目的专用 tag。 */
    public static final String TAG = "webgame-player-name-page";

    private final WebGame plugin;
    private volatile SoysHttpOverMcApi api;

    public PlayerNamePage(WebGame plugin) {
        this.plugin = plugin;
    }

    public void attach(SoysHttpOverMcApi api) {
        this.api = api;
    }

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
            LOG.log(Level.FINE, "unregister player name page failed: " + t);
        }
        byte[] html = buildPage().getBytes(StandardCharsets.UTF_8);
        register(pages, PATH + "index.html", html);
        register(pages, PATH, html);
        register(pages, PATH.substring(0, PATH.length() - 1), html);
    }

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
            LOG.log(Level.FINE, "unpublish player name page failed: " + t);
        }
    }

    private void register(WebPageApi pages, String path, byte[] html) {
        try {
            WebRegistry.Entry e = pages.registerPage(plugin, path, html, "text/html; charset=utf-8");
            if (e != null && e.tags != null && !e.tags.contains(TAG)) {
                e.tags.add(TAG);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "登记游戏名称页失败 " + path + ": " + t);
        }
    }

    private String buildPage() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>输入游戏名称 · WebGame</title>\n"
                + "<style>\n"
                + "*{box-sizing:border-box}\n"
                + "html,body{height:100%}\n"
                + "body{margin:0;display:flex;align-items:center;justify-content:center;overflow:hidden;"
                + "background:radial-gradient(1100px 700px at 50% -10%,#4054c8 0%,#252b66 45%,#0b1020 100%);"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;color:#eef2ff}\n"
                + "body:before{content:'';position:fixed;inset:-20px;background:rgba(8,12,28,.55);"
                + "backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px)}\n"
                + ".card{position:relative;width:min(92vw,520px);padding:34px 32px 26px;border-radius:24px;"
                + "background:linear-gradient(180deg,rgba(30,38,78,.96),rgba(16,22,50,.96));"
                + "border:1px solid rgba(170,190,255,.30);"
                + "box-shadow:0 30px 90px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.14)}\n"
                + ".eyebrow{display:inline-flex;align-items:center;gap:8px;padding:5px 11px;border-radius:999px;"
                + "background:rgba(120,150,255,.16);color:#b9c9ff;font-size:12px;letter-spacing:.08em}\n"
                + ".eyebrow:before{content:'';width:7px;height:7px;border-radius:50%;background:#79a0ff;"
                + "box-shadow:0 0 10px #79a0ff}\n"
                + "h1{margin:20px 0 8px;font-size:26px;line-height:1.25;font-weight:800}\n"
                + ".sub{margin:0 0 24px;color:#a7b2d8;font-size:14px;line-height:1.8}\n"
                + ".sub b{color:#e8edff}\n"
                + ".label{display:block;margin:0 0 9px;font-size:13px;color:#c4cdf0}\n"
                + "input{width:100%;padding:14px 16px;border-radius:12px;border:1px solid rgba(170,190,255,.28);"
                + "background:rgba(8,14,36,.62);color:#fff;font-size:17px;outline:none;text-align:center;letter-spacing:.04em}\n"
                + "input:focus{border-color:#7b9cff;box-shadow:0 0 0 4px rgba(90,130,255,.16)}\n"
                + ".hint{margin:9px 0 0;font-size:12px;color:#818db6;line-height:1.6}\n"
                + "button{margin-top:22px;width:100%;padding:14px 18px;border:0;border-radius:12px;cursor:pointer;"
                + "background:linear-gradient(135deg,#6f8dff,#4263e8);color:#fff;font-size:16px;font-weight:700;"
                + "box-shadow:0 10px 24px rgba(55,90,220,.35)}\n"
                + "button:disabled{opacity:.55;cursor:not-allowed}\n"
                + ".msg{min-height:20px;margin-top:12px;font-size:13px;color:#ff9aa4}\n"
                + ".back{display:block;margin-top:16px;text-align:center;color:#8e9ac4;font-size:13px;"
                + "text-decoration:none}.back:hover{color:#b9c9ff}\n"
                + "</style>\n</head>\n<body>\n"
                + "<form class=\"card\" id=\"f\" autocomplete=\"off\">\n"
                + "<span class=\"eyebrow\" id=\"kind\">选择客户端</span>\n"
                + "<h1>输入游戏名称</h1>\n"
                + "<p class=\"sub\">即将进入 <b id=\"label\">所选客户端</b>。该名称会作为客户端登录名，"
                + "请与你在服务器中的名字保持一致。</p>\n"
                + "<label class=\"label\" for=\"p\">游戏名称</label>\n"
                + "<input id=\"p\" maxlength=\"16\" placeholder=\"3–16 位字母、数字或下划线\" "
                + "autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\">\n"
                + "<p class=\"hint\">只允许字母、数字、下划线；3–16 个字符。该名称仅用于本次游戏登录。</p>\n"
                + "<button id=\"b\" type=\"submit\">继续进入游戏</button>\n"
                + "<div class=\"msg\" id=\"m\"></div>\n"
                + "<a class=\"back\" id=\"back\" href=\"javascript:history.back()\">← 返回上一步</a>\n"
                + "</form>\n"
                + "<script>\n"
                + "(function(){\n"
                + "  var Q=new URLSearchParams(location.search);\n"
                + "  var KIND=Q.get('kind')==='cloud'?'cloud':'eagler';\n"
                + "  var LABEL=Q.get('label')||'';\n"
                + "  var BACK=Q.get('back')||'';\n"
                + "  var MSG=Q.get('msg')||'';\n"
                + "  var f=document.getElementById('f'),p=document.getElementById('p'),b=document.getElementById('b'),"
                + "m=document.getElementById('m'),kindEl=document.getElementById('kind'),labelEl=document.getElementById('label');\n"
                + "  var RE=/^[A-Za-z0-9_]{3,16}$/;\n"
                + "  kindEl.textContent=KIND==='cloud'?'云游戏客户端':'Eagler 网页版';\n"
                + "  if(LABEL) labelEl.textContent=LABEL;\n"
                + "  if(MSG){m.textContent=MSG;}\n"
                + "  function safeBack(v){\n"
                + "    if(!v||v.charAt(0)!=='/'||v.indexOf('//')===0||v.indexOf('\\\\')>=0||v.indexOf('://')>=0)return false;\n"
                + "    return true;\n"
                + "  }\n"
                + "  if(!safeBack(BACK)){\n"
                + "    b.disabled=true;m.textContent='入口参数无效，请返回重新选择客户端。';return;\n"
                + "  }\n"
                + "  try{p.value=localStorage.getItem('wg_player_name')||'';}catch(e){}\n"
                + "  p.focus();\n"
                + "  f.addEventListener('submit',function(e){\n"
                + "    e.preventDefault();\n"
                + "    var v=(p.value||'').trim();\n"
                + "    if(!v){m.textContent='请先输入游戏名称';p.focus();return;}\n"
                + "    if(!RE.test(v)){m.textContent='游戏名称需为 3–16 位字母、数字或下划线';p.focus();return;}\n"
                + "    b.disabled=true;m.textContent='正在进入…';\n"
                + "    try{localStorage.setItem('wg_player_name',v);}catch(e){}\n"
                + "    if(KIND==='eagler'){try{localStorage.setItem('wg_auto_last',v);}catch(e){}}\n"
                + "    var u=BACK.replace(/([?&])(player|user)=[^&]*/g,'').replace(/[?&]+$/,'');\n"
                + "    var sep=u.indexOf('?')>=0?'&':'?';\n"
                + "    location.replace(u+sep+(KIND==='cloud'?'player=':'user=')+encodeURIComponent(v));\n"
                + "  });\n"
                + "})();\n"
                + "</script>\n</body>\n</html>\n";
    }
}
