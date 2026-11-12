package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * 运行概览：插件/隧道/会话的只读快照（GET /erp/status/overview）。
 */
public class WebGameStatusVo {

    /** 插件是否启用。 */
    private boolean enabled;
    /** 已运行秒数。 */
    private long uptimeSeconds;
    /** 插件版本。 */
    private String pluginVersion;
    /** 服务器端口。 */
    private int serverPort;
    /** 对外访问地址。 */
    private String publicHost;
    /** 对外端口。 */
    private int publicPort;
    /** WS 隧道路径。 */
    private String wsPath;
    /** 游戏页完整 URL。 */
    private String pageUrl;
    /** WS 完整 URL。 */
    private String wsUrl;
    /** Bukkit 在线玩家数。 */
    private int onlinePlayers;
    /** 当前 WS 会话数。 */
    private int sessionCount;
    /** 已进入游戏（PLAY 阶段）的会话数。 */
    private int playingCount;
    /** 每设备并发上限。 */
    private int maxConnectionsPerDevice;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }

    public String getPluginVersion() {
        return pluginVersion;
    }

    public void setPluginVersion(String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public String getPublicHost() {
        return publicHost;
    }

    public void setPublicHost(String publicHost) {
        this.publicHost = publicHost;
    }

    public int getPublicPort() {
        return publicPort;
    }

    public void setPublicPort(int publicPort) {
        this.publicPort = publicPort;
    }

    public String getWsPath() {
        return wsPath;
    }

    public void setWsPath(String wsPath) {
        this.wsPath = wsPath;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public void setOnlinePlayers(int onlinePlayers) {
        this.onlinePlayers = onlinePlayers;
    }

    public int getSessionCount() {
        return sessionCount;
    }

    public void setSessionCount(int sessionCount) {
        this.sessionCount = sessionCount;
    }

    public int getPlayingCount() {
        return playingCount;
    }

    public void setPlayingCount(int playingCount) {
        this.playingCount = playingCount;
    }

    public int getMaxConnectionsPerDevice() {
        return maxConnectionsPerDevice;
    }

    public void setMaxConnectionsPerDevice(int maxConnectionsPerDevice) {
        this.maxConnectionsPerDevice = maxConnectionsPerDevice;
    }
}
