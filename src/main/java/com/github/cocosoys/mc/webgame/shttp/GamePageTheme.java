package com.github.cocosoys.mc.webgame.shttp;

/**
 * 玩家侧页面共享视觉主题。
 *
 * <p>统一入口（/play/）与两个二级选择页（/cloud/、/eagler/）共用同一套 CSS 与页面骨架：
 * 深蓝渐变背景、顶栏（品牌 + 返回入口）、卡片网格、状态点、徽章与页脚。三处页面只填内容，
 * 视觉口径只在这一处维护，避免再出现"两套入口页样式各写各的、改一处漏一处"。</p>
 */
public final class GamePageTheme {

    private GamePageTheme() {
    }

    /** 已读入的配图 data URI 缓存：同一张图被 /play/、/cloud/、/eagler/ 反复拼接，只读一次 jar。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> IMAGE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 读取 jar 内 {@code webgame-assets/<name>} 的配图，返回可内联的 data URI。
     * <p>图片作为 classpath 资源随 jar 分发，页面仍是单文件自包含，不需要额外的静态资源路由；
     * 读取失败（资源缺失）时返回空串，页面退化为纯文字卡片，不影响其它内容。</p>
     */
    public static String image(String name) {
        String cached = IMAGE_CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String uri = loadImage(name);
        if (uri == null) {
            uri = "";
        }
        IMAGE_CACHE.put(name, uri);
        return uri;
    }

    private static String loadImage(String name) {
        try (java.io.InputStream in = GamePageTheme.class.getResourceAsStream("/webgame-assets/" + name)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            String mime = name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")
                    ? "image/png" : "image/jpeg";
            return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(buf.toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 共享样式表（一行一条规则，与服务端生成 HTML 的现有风格保持一致）。
     *
     * <p>视觉基调「快乐轻快 · 天天系列」（参考天美"向快乐出发"品牌主张）：高饱和蓝青紫渐变、
     * 漂浮白云光斑、白色超大圆角卡片 + 柔和投影、糖果色徽章、高对比白标题。</p>
     */
    public static final String CSS = ""
            + "*{box-sizing:border-box}\n"
            + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:#5b7ae8;"
            + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
            + "color:#fff}\n"
            + ".wrap{width:100%;max-width:940px;padding:40px 24px 56px}\n"
            + ".topbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:34px}\n"
            + ".brand{display:flex;align-items:center;gap:10px;font-size:15px;font-weight:700;"
            + "letter-spacing:.5px;color:#fff}\n"
            + ".brand .logo{width:34px;height:34px;border-radius:10px;"
            + "background:rgba(255,255,255,.92);display:inline-flex;"
            + "align-items:center;justify-content:center;box-shadow:0 4px 12px rgba(30,40,120,.25)}\n"
            + ".brand .logo .logo-img{width:26px;height:26px;border-radius:7px;display:block}\n"
            + ".brand .logo .logo-fallback{font-size:15px;font-weight:800;color:#5a3400}\n"
            + ".back{color:#fff;text-decoration:none;font-size:13px;padding:6px 14px;border-radius:999px;"
            + "background:rgba(255,255,255,.22);transition:background .15s ease}\n"
            + ".back:hover{background:rgba(255,255,255,.38)}\n"
            + "h1{margin:0 0 8px;font-size:32px;font-weight:700;letter-spacing:2px;"
            + "text-shadow:0 2px 12px rgba(30,40,120,.35)}\n"
            + ".sub{margin:0 0 26px;color:rgba(255,255,255,.88);font-size:14px;line-height:1.7}\n"
            + ".banner{display:block;width:100%;height:200px;object-fit:cover;border-radius:24px;"
            + "border:2px solid rgba(255,255,255,.72);margin:6px 0 24px;"
            + "box-shadow:0 14px 34px rgba(46,54,140,.30),inset 0 1px 0 rgba(255,255,255,.7)}\n"
            + ".sec{margin:26px 0 12px;color:rgba(255,255,255,.92);font-size:13px;letter-spacing:1px}\n"
            + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:16px}\n"
            + ".card{display:block;padding:18px 20px;border-radius:22px;text-decoration:none;"
            + "color:#24315e;background:rgba(255,255,255,.94);border:2px solid rgba(255,255,255,.72);"
            + "box-shadow:0 14px 34px rgba(46,54,140,.28),inset 0 1px 0 rgba(255,255,255,.8);"
            + "transition:transform .15s ease,box-shadow .15s ease}\n"
            + ".card:hover{transform:translateY(-4px);box-shadow:0 16px 36px rgba(30,40,120,.32)}\n"
            + ".card-default{border-color:#5ed478}\n"
            + ".card-head{display:flex;align-items:center;justify-content:space-between;gap:8px}\n"
            + ".card-name{font-size:16px;font-weight:700}\n"
            + ".badge{font-size:11px;padding:3px 9px;border-radius:999px;background:#5ed478;color:#0b3a1e;"
            + "font-weight:700;white-space:nowrap}\n"
            + ".badge-blue{background:#ffd54d;color:#5a3c00}\n"
            + ".badge-dim{background:#e8edfb;color:#3a4c86}\n"
            + ".dot{width:8px;height:8px;border-radius:50%;background:#5ed478;flex:none}\n"
            + ".dot.off{background:#c3cbe0}\n"
            + ".dot.warn{background:#ffb84d}\n"
            + ".dot.err{background:#ff6b6b}\n"
            + ".card-meta{margin-top:12px;display:flex;flex-wrap:wrap;gap:8px;color:#5a6a99;font-size:12px}\n"
            + ".card-meta span{padding:2px 9px;border-radius:7px;background:rgba(64,84,160,.10)}\n"
            + ".card-meta span.live{color:#1f9e52}\n"
            + ".statusline{display:flex;align-items:center;gap:6px;font-size:12px;color:#5a6a99}\n"
            + ".empty{padding:32px;text-align:center;color:#5a6a99;background:rgba(255,255,255,.94);"
            + "border:1px dashed rgba(64,84,160,.35);border-radius:22px}\n"
            + ".foot{margin-top:40px;text-align:center;color:rgba(255,255,255,.75);font-size:12px}\n"
            + "@media (max-width:640px){.wrap{padding:28px 16px 40px}h1{font-size:25px}}\n";

    /**
     * 共享样式 + 动态整页背景：把 jar 内的方块世界场景图作为 body 背景，上面叠一层半透明
     * 蓝紫渐变保证白色卡片与标题的可读性。背景图缺失时退回纯 CSS 渐变，页面照常可用。
     */
    public static String css() {
        String bg = image("bg.jpg");
        if (bg.isEmpty()) {
            return CSS
                    + "body{background-image:radial-gradient(1400px 620px at 50% -12%,"
                    + "rgba(160,196,255,.55) 0%,rgba(160,196,255,0) 55%),"
                    + "linear-gradient(175deg,#3f72e8 0%,#4b62e2 30%,#5d52d8 62%,#7a49c8 100%)}\n";
        }
        return CSS
                + "body{background-image:linear-gradient(180deg,rgba(58,78,180,.32) 0%,"
                + "rgba(96,74,190,.26) 45%,rgba(62,46,140,.44) 100%),"
                + "url(\"" + bg + "\");"
                + "background-size:auto,cover;background-position:0 0,center top;"
                + "background-repeat:no-repeat,no-repeat}\n";
    }

    /**
     * 整页骨架：HTML5 声明 + head（UTF-8、viewport、标题、样式）+ 内容容器。
     * {@code extraCss} 是页面私有样式（例如统一入口的双列大卡片），拼在共享主题之后。
     */
    public static String shell(String title, String extraCss, String bodyInner) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh-CN\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + title + "</title>\n"
                + "<style>\n" + css()
                + (extraCss == null || extraCss.isEmpty() ? "" : "\n" + extraCss + "\n")
                + "</style>\n</head>\n<body>\n"
                + "<div class=\"wrap\">\n"
                + bodyInner + "\n"
                + "</div>\n</body>\n</html>\n";
    }

    /** 顶栏品牌块（WebGame 标识）：白色圆角瓷砖 + 草方块图标，图标缺失时退回字母 W。 */
    public static String brand() {
        return "<div class=\"brand\"><span class=\"logo\">" + logoInner()
                + "</span><span>WebGame</span></div>";
    }

    /** 品牌图标瓷砖内部：草方块图标，缺失时退回字母 W。 */
    public static String logoInner() {
        String logo = image("logo.jpg");
        return logo.isEmpty()
                ? "<span class=\"logo-fallback\">W</span>"
                : "<img class=\"logo-img\" src=\"" + logo + "\" alt=\"\">";
    }

    /** 顶栏返回链接：二级选择页统一回到 {@code /play/}。 */
    public static String back(String href) {
        return "<a class=\"back\" href=\"" + href + "\">← 选择游戏方式</a>";
    }

    /** 顶栏：左侧品牌，右侧返回链接。 */
    public static String topbar(String backHref) {
        String right = (backHref == null || backHref.isEmpty()) ? "" : back(backHref);
        return "<div class=\"topbar\">" + brand() + right + "</div>";
    }

    /** 页脚：统一入口地址提示。 */
    public static String foot(String note) {
        return "<div class=\"foot\">" + note + "</div>";
    }
}
