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

    // ---- cloud 云游戏通道 ----
    private final boolean cloudEnabled;
    private final String cloudPath;
    private final String cloudWsPath;
    private final String cloudTransport;
    private final String cloudUdpPortRange;
    private final String cloudFfmpegPath;
    private final String cloudCapture;
    private final int cloudFps;
    private final int cloudBitrateKbps;
    private final String cloudScale;
    private final int cloudGopFrames;
    private final String cloudInputInject;
    private final int cloudPingIntervalSeconds;
    private final int cloudIdleTimeoutSeconds;
    private final int cloudResumeGraceSeconds;

    // ---- cloud 容器客户端（游戏实例，路线 B 遗留，S1 起由执行面管控，仅保留参数下发）----
    private final boolean clientEnabled;
    private final String clientBaseDir;
    private final String clientVersion;
    private final String clientGameDir;
    private final String clientInstancesDir;
    private final String clientServer;
    private final int clientPort;
    private final String clientWindowTitle;
    private final String clientXmx;
    private final String clientJavaPath;
    private final int clientReadyTimeoutSeconds;

    // ---- 管控契约（插件 ↔ 执行面控制客户端）----
    private final String controlHost;
    private final int controlPort;

    public WebGameConfig(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        this.publicHost = c.getString("page.public-host", "127.0.0.1").trim();
        this.publicPort = Math.max(0, c.getInt("page.public-port", 0));
        this.wsPath = c.getString("tunnel.ws-path", "/eagler");
        this.htmlFile = c.getString("page.html-file", "Eaglercraft_IR_1.12.2.html");
        this.pagePath = normalizePath(c.getString("page.path", "/api/plugins/WebGame/eagler/"));
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

        this.cloudEnabled = c.getBoolean("cloud.enabled", true);
        this.cloudPath = normalizePath(c.getString("cloud.path", "/api/plugins/WebGame/cloud/"));
        this.cloudWsPath = normalizeWsPath(c.getString("cloud.ws-path", "/cloud"));
        this.cloudTransport = c.getString("cloud.transport", "ws").trim().toLowerCase();
        this.cloudUdpPortRange = c.getString("cloud.udp-port-range", "").trim();
        this.cloudFfmpegPath = c.getString("cloud.ffmpeg-path", "").trim();
        this.cloudCapture = c.getString("cloud.capture", "auto").trim().toLowerCase();
        this.cloudFps = Math.max(1, Math.min(120, c.getInt("cloud.fps", 30)));
        this.cloudBitrateKbps = Math.max(200, c.getInt("cloud.bitrate-kbps", 4000));
        this.cloudScale = c.getString("cloud.scale", "1280x720").trim();
        this.cloudGopFrames = Math.max(1, c.getInt("cloud.gop-frames", 60));
        this.cloudInputInject = c.getString("cloud.input-inject", "none").trim().toLowerCase();
        this.cloudPingIntervalSeconds = Math.max(1, c.getInt("cloud.ping-interval-seconds", 2));
        this.cloudIdleTimeoutSeconds = Math.max(5, c.getInt("cloud.idle-timeout-seconds", 15));
        // 断线/刷新重连宽限期：连接断开后实例保留时间（秒），期间同一用户可凭 sessionId 恢复
        this.cloudResumeGraceSeconds = Math.max(10, c.getInt("cloud.resume-grace-seconds", 60));

        this.clientEnabled = c.getBoolean("cloud.client.enabled", false);
        this.clientBaseDir = c.getString("cloud.client.base-dir", "").trim();
        this.clientVersion = c.getString("cloud.client.version", "1.12.2-Forge_14.23.5.2864").trim();
        this.clientGameDir = c.getString("cloud.client.game-dir", "").trim();
        this.clientInstancesDir = c.getString("cloud.client.instances-dir", "").trim();
        this.clientServer = c.getString("cloud.client.server", "").trim();
        this.clientPort = Math.max(0, c.getInt("cloud.client.port", 0));
        this.clientWindowTitle = c.getString("cloud.client.window-title", "Minecraft 1.12.2").trim();
        this.clientXmx = c.getString("cloud.client.xmx", "1G").trim();
        this.clientJavaPath = c.getString("cloud.client.java-path", "").trim();
        this.clientReadyTimeoutSeconds = Math.max(10, c.getInt("cloud.client.ready-timeout-seconds", 90));

        this.controlHost = c.getString("control.host", "127.0.0.1").trim();
        this.controlPort = Math.max(1, Math.min(65535, c.getInt("control.port", 25576)));
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

    /** 游戏页面挂载路径（以 / 开头、以 / 结尾），如 /api/plugins/WebGame/eagler/。 */
    public String getPagePath() {
        return pagePath;
    }

    /** 规范化页面路径：确保以 / 开头并以 / 结尾（根路径 "/" 原样返回）。 */
    private static String normalizePath(String p) {
        String s = (p == null || p.trim().isEmpty()) ? "/api/plugins/WebGame/eagler/" : p.trim();
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

    // ===== cloud 云游戏通道 =====

    public boolean isCloudEnabled() {
        return cloudEnabled;
    }

    /** 云游戏页面挂载路径（以 / 开头、以 / 结尾）。 */
    public String getCloudPath() {
        return cloudPath;
    }

    /** 云游戏 WS 媒体通道路径（以 / 开头、无结尾斜杠）。 */
    public String getCloudWsPath() {
        return cloudWsPath;
    }

    /** 传输模式：ws | webrtc（v2 预留）。 */
    public String getCloudTransport() {
        return cloudTransport;
    }

    /** v2 预留的 UDP 媒体端口段（v1 为空）。 */
    public String getCloudUdpPortRange() {
        return cloudUdpPortRange;
    }

    public String getCloudFfmpegPath() {
        return cloudFfmpegPath;
    }

    /** 采集模式：auto | gdigrab | x11grab | none。 */
    public String getCloudCapture() {
        return cloudCapture;
    }

    public int getCloudFps() {
        return cloudFps;
    }

    public int getCloudBitrateKbps() {
        return cloudBitrateKbps;
    }

    /** 编码分辨率，如 1280x720。 */
    public String getCloudScale() {
        return cloudScale;
    }

    /** GOP 帧数（关键帧间隔）。 */
    public int getCloudGopFrames() {
        return cloudGopFrames;
    }

    /** 输入注入后端：none | robot。 */
    public String getCloudInputInject() {
        return cloudInputInject;
    }

    public int getCloudPingIntervalSeconds() {
        return cloudPingIntervalSeconds;
    }

    public int getCloudIdleTimeoutSeconds() {
        return cloudIdleTimeoutSeconds;
    }

    /** 断线/刷新重连宽限期（秒）：连接断开后实例保留时间，期间可凭 sessionId 恢复。 */
    public int getCloudResumeGraceSeconds() {
        return cloudResumeGraceSeconds;
    }

    // ===== cloud 容器客户端（游戏实例）=====

    /** 是否由插件动态启动 Forge 客户端作为采集源。 */
    public boolean isClientEnabled() {
        return clientEnabled;
    }

    /** 客户端根目录（含 .minecraft）。 */
    public String getClientBaseDir() {
        return clientBaseDir;
    }

    /** 版本 id（versions 目录下）。 */
    public String getClientVersion() {
        return clientVersion;
    }

    /** --gameDir；为空时使用 base-dir/.minecraft。 */
    public String getClientGameDir() {
        return clientGameDir;
    }

    /** 实例工作目录根（每会话独立子目录）。 */
    public String getClientInstancesDir() {
        return clientInstancesDir;
    }

    /** 自动连服地址；为空时使用 tunnel.loopback-host。 */
    public String getClientServer() {
        return clientServer;
    }

    /** 自动连服端口；0 时跟随 server-port。 */
    public int getClientPort() {
        return clientPort;
    }

    /** gdigrab 窗口采集匹配的窗口标题。 */
    public String getClientWindowTitle() {
        return clientWindowTitle;
    }

    public String getClientXmx() {
        return clientXmx.isEmpty() ? "1G" : clientXmx;
    }

    /** Java 可执行文件；为空时从 PATH 探测 java。 */
    public String getClientJavaPath() {
        return clientJavaPath;
    }

    public int getClientReadyTimeoutSeconds() {
        return clientReadyTimeoutSeconds;
    }

    // ===== 管控契约（执行面控制客户端）=====

    /** 控制客户端地址（执行面所在主机）。 */
    public String getControlHost() {
        return controlHost.isEmpty() ? "127.0.0.1" : controlHost;
    }

    /** 控制客户端 TCP 端口（管控契约服务端）。 */
    public int getControlPort() {
        return controlPort;
    }

    /** 规范化 WS 路径：确保以 / 开头、无结尾斜杠。 */
    private static String normalizeWsPath(String p) {
        String s = (p == null || p.trim().isEmpty()) ? "/cloud" : p.trim();
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
