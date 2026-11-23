package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * 云游戏客户端列表行（GET /erp/cloud-clients/list）。
 */
public class CloudClientVo {

    private String id;
    private String name;
    /** 客户端入口路径（URL 或本地文件）。 */
    private String path;
    /** Minecraft 版本描述（自由文本）。 */
    private String mcVersion;
    private boolean enabled;
    /** 玩家选择页展示顺序（升序）。 */
    private int order;
    /** 是否为当前默认客户端。 */
    private boolean defaultVersion;
    /** url / local。 */
    private String source;
    private boolean missing;
    /** 客户端页面完整访问路径（/web/plugins/WebGame/cloud/&lt;id&gt;/）。 */
    private String pagePath;
    /**
     * 同端口转播前缀（/web/plugins/WebGame/cloud/&lt;id&gt;/live/），空串表示该条目未走同端口转播
     * （本地页面、老 SOYS jar、或管理员关闭了 cloud.same-port-relay）。
     */
    private String livePath;

    // ===== 运行态（前端展示用；密钥只给掩码） =====

    private String ip;
    private int port;
    /** 是否只提供 HTTPS（前端拿到后按 scheme 显示入口地址）。 */
    private boolean tls;
    /** 拼接后的设备地址 ip:port（未配置为空）。 */
    private String address;
    /** online / offline / unknown / unauthorized。 */
    private String status;
    private String version;
    private long uptimeSeconds;
    private String lastReport;
    private String lastOk;
    private String lastCheck;
    private int failCount;
    private long latencyMs;
    /** 密钥掩码，如 ab12****89ef；未配置为空串。 */
    private String cloudKeyMasked;

    // ===== 别名 / 池 / 版本 / 更新 / 会话（ERP 列表展示与编辑用） =====

    /** 根路径门牌别名列表（规范化后；空 = 只能按 id 进入）。 */
    private java.util.List<String> aliases = new java.util.ArrayList<>();
    /** 逻辑池名（空 = 该机器自成一池，不参与池匹配）。 */
    private String pool = "";
    /** 最近一次探测的原因（不可达异常 / HTTP 状态 / health.ok=false）。 */
    private String healthReason = "";
    /** WebGame 期望的客户端包版本（config.yml 的 cloud.client-update.version）。 */
    private String expectedVersion = "";
    /** 实际版本与期望版本是否一致（期望为空时视为一致）。 */
    private boolean versionMatch = true;
    /** 该机器当前是否被会话占用。 */
    private boolean busy;
    /** 包更新状态：{@code updating} / {@code error} / 空。 */
    private String updateState = "";
    /** 包更新失败的最近一次原因。 */
    private String updateError = "";

    public java.util.List<String> getAliases() {
        return aliases;
    }

    public void setAliases(java.util.List<String> aliases) {
        this.aliases = aliases == null ? new java.util.ArrayList<>() : aliases;
    }

    public String getPool() {
        return pool;
    }

    public void setPool(String pool) {
        this.pool = pool == null ? "" : pool;
    }

    public String getHealthReason() {
        return healthReason;
    }

    public void setHealthReason(String healthReason) {
        this.healthReason = healthReason == null ? "" : healthReason;
    }

    public String getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(String expectedVersion) {
        this.expectedVersion = expectedVersion == null ? "" : expectedVersion;
    }

    public boolean isVersionMatch() {
        return versionMatch;
    }

    public void setVersionMatch(boolean versionMatch) {
        this.versionMatch = versionMatch;
    }

    public boolean isBusy() {
        return busy;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public String getUpdateState() {
        return updateState;
    }

    public void setUpdateState(String updateState) {
        this.updateState = updateState == null ? "" : updateState;
    }

    public String getUpdateError() {
        return updateError;
    }

    public void setUpdateError(String updateError) {
        this.updateError = updateError == null ? "" : updateError;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public boolean isDefaultVersion() {
        return defaultVersion;
    }

    public void setDefaultVersion(boolean defaultVersion) {
        this.defaultVersion = defaultVersion;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isMissing() {
        return missing;
    }

    public void setMissing(boolean missing) {
        this.missing = missing;
    }

    public String getPagePath() {
        return pagePath;
    }

    public void setPagePath(String pagePath) {
        this.pagePath = pagePath;
    }

    public String getLivePath() {
        return livePath;
    }

    public void setLivePath(String livePath) {
        this.livePath = livePath;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isTls() {
        return tls;
    }

    public void setTls(boolean tls) {
        this.tls = tls;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }

    public String getLastReport() {
        return lastReport;
    }

    public void setLastReport(String lastReport) {
        this.lastReport = lastReport;
    }

    public String getLastOk() {
        return lastOk;
    }

    public void setLastOk(String lastOk) {
        this.lastOk = lastOk;
    }

    public String getLastCheck() {
        return lastCheck;
    }

    public void setLastCheck(String lastCheck) {
        this.lastCheck = lastCheck;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getCloudKeyMasked() {
        return cloudKeyMasked;
    }

    public void setCloudKeyMasked(String cloudKeyMasked) {
        this.cloudKeyMasked = cloudKeyMasked;
    }
}
