package com.github.cocosoys.mc.webgame.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WebGame 配置（对应 config.yml）。
 */
public final class WebGameConfig {

    private final String publicHost;
    private final int publicPort;
    private final String wsPath;
    private final String htmlFile;
    private final String indexPath;
    private final String pagePath;
    private final boolean autoJoinEnabled;
    private final String defaultUsername;
    private final String loopbackHost;
    private final int loopbackPort;
    private final boolean allowV3;
    private final boolean allowV4;
    private final int minMcProtocol;
    private final int maxMcProtocol;
    private final String serverBrand;
    private final String serverVersion;
    private final int maxConnectionsPerDevice;

    public WebGameConfig(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        this.publicHost = c.getString("page.public-host", "127.0.0.1").trim();
        this.publicPort = Math.max(0, c.getInt("page.public-port", 0));
        this.wsPath = c.getString("tunnel.ws-path", "/eagler");
        this.htmlFile = c.getString("page.html-file", "Eaglercraft_IR_1.12.2.html");
        this.indexPath = normalizePath(c.getString("page.index-path", "/"));
        this.pagePath = normalizePath(c.getString("page.path", "/eagler/"));
        this.autoJoinEnabled = c.getBoolean("auto-join.enabled", true);
        this.defaultUsername = c.getString("auto-join.default-username", "WebPlayer").trim();
        this.loopbackHost = c.getString("tunnel.loopback-host", "127.0.0.1").trim();
        this.loopbackPort = Math.max(0, c.getInt("tunnel.loopback-port", 0));
        this.allowV3 = c.getBoolean("tunnel.allow-v3", true);
        this.allowV4 = c.getBoolean("tunnel.allow-v4", true);
        this.minMcProtocol = c.getInt("tunnel.min-mc-protocol", 340);
        this.maxMcProtocol = c.getInt("tunnel.max-mc-protocol", 340);
        this.serverBrand = c.getString("tunnel.server-brand", "WebGame").trim();
        this.serverVersion = c.getString("tunnel.server-version", "1.0.0").trim();
        this.maxConnectionsPerDevice = Math.max(1, c.getInt("tunnel.max-connections-per-device", 2));
    }

    public String getPublicHost() {
        return publicHost;
    }

    /** 返回实际对外端口：配置 >0 用配置，否则跟随 server-port */
    public int getPublicPort(int serverPort) {
        return publicPort > 0 ? publicPort : serverPort;
    }

    public String getWsPath() {
        return wsPath.startsWith("/") ? wsPath : "/" + wsPath;
    }

    public String getHtmlFile() {
        return htmlFile;
    }

    /**
     * SOYS WebRegistry 对附属插件页面自动补充的前缀（registerPage 时无需手写，
     * 此处用于生成完整访问 URL / 注入前端绝对路径引用）。
     */
    public static final String WEB_PLUGIN_PREFIX = "/web/plugins/WebGame";

    /** 游戏页面挂载路径（相对 WEB_PLUGIN_PREFIX 的子路径，以 / 开头、以 / 结尾），如 /eagler/。 */
    public String getPagePath() {
        return pagePath;
    }

    /** 完整访问 URL（如 /web/plugins/WebGame/eagler/）。 */
    public String getPageFullUrl() {
        return WEB_PLUGIN_PREFIX + pagePath;
    }

    /** Entry page (index.html) mount path (relative). */
    public String getIndexPath() {
        return indexPath;
    }

    /** 完整入口 URL（如 /web/plugins/WebGame/）。 */
    public String getIndexFullUrl() {
        return WEB_PLUGIN_PREFIX + indexPath;
    }

    /** 规范化页面路径：确保以 / 开头并以 / 结尾（根路径 "/" 原样返回）。 */
    private static String normalizePath(String p) {
        String s = (p == null || p.trim().isEmpty()) ? "/eagler/" : p.trim();
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        return s;
    }

    public boolean isAutoJoinEnabled() {
        return autoJoinEnabled;
    }

    public String getDefaultUsername() {
        return defaultUsername.isEmpty() ? "WebPlayer" : defaultUsername;
    }

    public String getLoopbackHost() {
        return loopbackHost.isEmpty() ? "127.0.0.1" : loopbackHost;
    }

    /** 实际回环端口：配置 >0 用配置，否则跟随 server-port。 */
    public int getLoopbackPort(int serverPort) {
        return loopbackPort > 0 ? loopbackPort : serverPort;
    }

    public boolean isAllowV3() {
        return allowV3;
    }

    public boolean isAllowV4() {
        return allowV4;
    }

    public int getMinMcProtocol() {
        return minMcProtocol;
    }

    public int getMaxMcProtocol() {
        return maxMcProtocol;
    }

    public String getServerBrand() {
        return serverBrand.isEmpty() ? "WebGame" : serverBrand;
    }

    public String getServerVersion() {
        return serverVersion.isEmpty() ? "1.0.0" : serverVersion;
    }

    /** 同一设备（按来源 IP）最多同时进入游戏的浏览器会话数，默认 2。 */
    public int getMaxConnectionsPerDevice() {
        return maxConnectionsPerDevice;
    }
}
