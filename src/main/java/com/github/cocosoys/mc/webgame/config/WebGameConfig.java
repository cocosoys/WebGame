package com.github.cocosoys.mc.webgame.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WebGame 的可维护配置（对应 config.yml）。
 *
 * <p>这里只保留管理员真正需要调整的运行参数。页面路径、WS 路径、回环地址、
 * 协议范围、数据后端、默认客户端、自选开关、制品序号等都属于代码常量或
 * 内部运行态，不再进入 config.yml。</p>
 */
public final class WebGameConfig {

    /** SOYS WebRegistry 对附属插件页面自动补充的固定前缀。 */
    public static final String WEB_PLUGIN_PREFIX = "/web/plugins/WebGame";
    /** 游戏页面固定挂载路径（相对 WEB_PLUGIN_PREFIX）。 */
    public static final String PAGE_PATH = "/eagler/";
    /** WebSocket 隧道固定入口路径。 */
    public static final String WS_PATH = "/eagler";
    /** 同机隧道的固定回环地址。 */
    public static final String LOOPBACK_HOST = "127.0.0.1";
    /** EaglerX 握手协议支持范围固定值（V3 与 V4 均支持）。 */
    public static final boolean ALLOW_EAGLER_V3 = true;
    public static final boolean ALLOW_EAGLER_V4 = true;
    /** 服务端 MC 协议版本固定范围（1.12.2 = 340）。 */
    public static final int MIN_MC_PROTOCOL = 340;
    public static final int MAX_MC_PROTOCOL = 340;

    private final JavaPlugin plugin;
    private final boolean autoJoinEnabled;
    private final String defaultUsername;
    private final String serverBrand;
    private final int maxConnectionsPerDevice;

    public WebGameConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration c = plugin.getConfig();
        this.autoJoinEnabled = c.getBoolean("auto-join.enabled", true);
        this.defaultUsername = c.getString("auto-join.default-username", "WebPlayer").trim();
        this.serverBrand = c.getString("tunnel.server-brand", "WebGame").trim();
        this.maxConnectionsPerDevice = Math.max(1, c.getInt("tunnel.max-connections-per-device", 2));
    }

    /** 对外展示地址：优先 server-ip；未配置或监听全部网卡时回退 127.0.0.1。 */
    public String getPublicHost() {
        String ip = plugin.getServer().getIp();
        if (ip == null || ip.trim().isEmpty() || "0.0.0.0".equals(ip.trim())) {
            return "127.0.0.1";
        }
        return ip.trim();
    }

    /** 对外端口固定跟随 server.properties 的 server-port。 */
    public int getPublicPort(int serverPort) {
        return serverPort;
    }

    public String getWsPath() {
        return WS_PATH;
    }

    /** 游戏页面挂载路径，固定 /eagler/。 */
    public String getPagePath() {
        return PAGE_PATH;
    }

    /** 完整访问 URL（如 /web/plugins/WebGame/eagler/）。 */
    public String getPageFullUrl() {
        return WEB_PLUGIN_PREFIX + PAGE_PATH;
    }

    public boolean isAutoJoinEnabled() {
        return autoJoinEnabled;
    }

    public String getDefaultUsername() {
        return defaultUsername.isEmpty() ? "WebPlayer" : defaultUsername;
    }

    public String getLoopbackHost() {
        return LOOPBACK_HOST;
    }

    /** 回环端口固定跟随 server-port。 */
    public int getLoopbackPort(int serverPort) {
        return serverPort;
    }

    public boolean isAllowV3() {
        return ALLOW_EAGLER_V3;
    }

    public boolean isAllowV4() {
        return ALLOW_EAGLER_V4;
    }

    public int getMinMcProtocol() {
        return MIN_MC_PROTOCOL;
    }

    public int getMaxMcProtocol() {
        return MAX_MC_PROTOCOL;
    }

    public String getServerBrand() {
        return serverBrand.isEmpty() ? "WebGame" : serverBrand;
    }

    /** SERVER_VERSION 帧中的版本直接取插件自身版本，不再单独配置。 */
    public String getServerVersion() {
        String version = plugin.getDescription() == null ? "" : plugin.getDescription().getVersion();
        return version == null || version.trim().isEmpty() ? "1.0.0" : version.trim();
    }

    /** 同一设备（按来源 IP）最多同时进入游戏的浏览器会话数，默认 2。 */
    public int getMaxConnectionsPerDevice() {
        return maxConnectionsPerDevice;
    }
}
