package com.github.cocosoys.mc.webgame.spring.entity;

/**
 * 云游戏客户端条目（cloud-clients.yml 里的条目 + 运行时状态）。
 *
 * <p>与 eagler 客户端共用路径解析规则，但不涉及上传与 WS 协议：
 * id 由服务端自增生成，MC 版本为管理员自由填写的文本。</p>
 */
public class CloudClient {

    public static final String SOURCE_URL = "url";
    public static final String SOURCE_LOCAL = "local";

    /** 运行态：尚未成功探测过（新登记设备 / 服务端刚重启）。 */
    public static final String STATUS_UNKNOWN = "unknown";
    /** 运行态：最近一次探测成功，且在 TTL 内。 */
    public static final String STATUS_ONLINE = "online";
    /** 运行态：连续探测失败超过 TTL（连接拒绝/超时/非 200）。 */
    public static final String STATUS_OFFLINE = "offline";
    /** 运行态：探测返回 401/403（共享密钥不匹配或未配置）。 */
    public static final String STATUS_UNAUTHORIZED = "unauthorized";
    /**
     * 运行态：端口通、密钥也对，但健康端点自己说"不健康"。
     *
     * <p>这是比 offline 更精确的一档：8080 还在监听、TCP 也连得上，但
     * {@code /api/v1/health} 返回 503（内部子系统/桌面会话/编码器没起来）。
     * 以前这种情况会被判成 online，玩家点进去才失败；现在单独标出来，
     * 既不让玩家进，也不误导运维以为"机器离线"。</p>
     */
    public static final String STATUS_DEGRADED = "degraded";

    /** 自增数字 id，同时是页面子路径 /cloud/&lt;id&gt;/。 */
    private String id = "";
    /** 展示名称。 */
    private String name = "";
    /**
     * 客户端入口路径：{@code http(s)://…} 直接跳转；否则视为本地文件
     * （相对插件数据目录 / 服务器根目录，或绝对路径）。
     */
    private String path = "";
    /** Minecraft 版本描述（自由文本，仅展示）。 */
    private String mcVersion = "";
    /** 是否启用。 */
    private boolean enabled = true;
    /** 玩家选择页的展示顺序（升序；-1 表示未指定，加载时补号）。 */
    private int order = -1;
    /** 数据来源：url / local。 */
    private String source = SOURCE_LOCAL;
    /** 运行时：入口文件不存在。 */
    private boolean missing;

    /**
     * 对外别名（根路径门牌）：{@code /cloud/<alias>}。可同时给中文与 ASCII，
     * 例：{@code aliases: [测试服务器, test]}。
     *
     * <p>别名是"玩家看到的名字"，id 是"系统内部编号"——玩家不该记 {@code /cloud/1/}。
     * 同一客户端可以有多个别名；全局唯一（冲突时按 order+id 先到先得，其余记警告）。</p>
     */
    private java.util.List<String> aliases = new java.util.ArrayList<>();
    /**
     * 预留：逻辑池名（未来"唯一入口、不选设备、点击自动匹配"时，alias/池 映射到多台机器）。
     * P0 一律为空字符串 = 该客户端自成一池。
     */
    private String pool = "";

    // ===== 运行态（cloud-stream 客户端上报 + 服务端定时探测） =====

    /** 设备 IPv4/IPv6 或主机名（上报时带上来；管理员也可手动填）。 */
    private String ip = "";
    /** 设备端口（stream-server 的 HTTP 监听端口）。 */
    private int port;
    /**
     * 该设备是否只提供 HTTPS（stream-server 配了 tls_cert/tls_key）。
     *
     * <p>浏览器在明文 HTTP 下没有 WebCodecs/WebGPU 这些"安全上下文"能力，
     * 前端会直接报 "This application requires a secure connection"，
     * 所以正式环境基本都会上证书。探针按这个开关选 scheme，不靠猜。</p>
     */
    private boolean tls;
    /** 见 STATUS_* 常量。 */
    private String status = STATUS_UNKNOWN;
    /** 客户端自报的版本（/health 的 server.version 回填）。 */
    private String version = "";
    /** 客户端自报的运行时长（秒），来自 /health 的 server.uptime_s。 */
    private long uptimeSeconds;
    /** 客户端启动上报时刻（yyyy-MM-dd HH:mm:ss）。 */
    private String lastReport = "";
    /** 最近一次探测成功时刻（TTL 判据）。 */
    private String lastOk = "";
    /** 最近一次探测尝试时刻。 */
    private String lastCheck = "";
    /** 连续探测失败次数（成功即清零）。 */
    private int failCount;
    /** 共享密钥 X-Cloud-Key（等于 stream-server 的 auth_token；ERP 只显示掩码）。 */
    private String cloudKey = "";
    /** 最近一次探测的 HTTP 往返耗时（毫秒）。 */
    private long latencyMs;
    /**
     * 最近一次探测的"人话原因"：不可达时的异常摘要、HTTP 状态码、或 {@code health.ok=false}。
     * <p>ERP 直接展示这一列——比只有一个 offline 标签有用得多。</p>
     */
    private String healthReason = "";

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id;
    }

    public String getName() {
        return name == null || name.isEmpty() ? id : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getPath() {
        return path == null ? "" : path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String getMcVersion() {
        return mcVersion == null ? "" : mcVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion == null ? "" : mcVersion;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source == null ? "" : source;
    }

    public boolean isMissing() {
        return missing;
    }

    public void setMissing(boolean missing) {
        this.missing = missing;
    }

    public java.util.List<String> getAliases() {
        return aliases;
    }

    public void setAliases(java.util.List<String> aliases) {
        this.aliases = aliases == null ? new java.util.ArrayList<>() : aliases;
    }

    public String getPool() {
        return pool == null ? "" : pool;
    }

    public void setPool(String pool) {
        this.pool = pool == null ? "" : pool.trim();
    }

    public String getIp() {
        return ip == null ? "" : ip;
    }

    public void setIp(String ip) {
        this.ip = ip == null ? "" : ip.trim();
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

    public String getStatus() {
        return status == null || status.isEmpty() ? STATUS_UNKNOWN : status;
    }

    public void setStatus(String status) {
        this.status = status == null || status.isEmpty() ? STATUS_UNKNOWN : status;
    }

    public String getVersion() {
        return version == null ? "" : version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(long uptimeSeconds) {
        this.uptimeSeconds = Math.max(0L, uptimeSeconds);
    }

    public String getLastReport() {
        return lastReport == null ? "" : lastReport;
    }

    public void setLastReport(String lastReport) {
        this.lastReport = lastReport == null ? "" : lastReport;
    }

    public String getLastOk() {
        return lastOk == null ? "" : lastOk;
    }

    public void setLastOk(String lastOk) {
        this.lastOk = lastOk == null ? "" : lastOk;
    }

    public String getLastCheck() {
        return lastCheck == null ? "" : lastCheck;
    }

    public void setLastCheck(String lastCheck) {
        this.lastCheck = lastCheck == null ? "" : lastCheck;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = Math.max(0, failCount);
    }

    public String getCloudKey() {
        return cloudKey == null ? "" : cloudKey;
    }

    public void setCloudKey(String cloudKey) {
        this.cloudKey = cloudKey == null ? "" : cloudKey.trim();
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = Math.max(0L, latencyMs);
    }

    public String getHealthReason() {
        return healthReason == null ? "" : healthReason;
    }

    public void setHealthReason(String healthReason) {
        String s = healthReason == null ? "" : healthReason.trim();
        // 原因文本只做展示，截断防脏数据把 ERP 表格撑爆
        this.healthReason = s.length() > 120 ? s.substring(0, 120) : s;
    }

    /** 健康端点是否健康：degraded / offline / unauthorized 都不算。 */
    public boolean isHealthy() {
        String s = getStatus();
        return !STATUS_DEGRADED.equals(s) && !STATUS_OFFLINE.equals(s) && !STATUS_UNAUTHORIZED.equals(s);
    }

    /** 设备地址 {@code ip:port}；未配置返回空串（刷新时据此报“未配置设备地址”）。 */
    public String address() {
        String host = getIp();
        if (host.isEmpty() || port <= 0) {
            return "";
        }
        return host + ":" + port;
    }

    /**
     * 玩家入口地址：按 {@link #tls} 拼 scheme，末尾补斜杠。
     *
     * <p>明文 HTTP 下浏览器不给 WebCodecs/WebGPU，前端会拒绝启动，所以启用证书的
     * 设备必须用 https；地址本身仍是同一对 ip:port（stream-server 是同一端口
     * 上先嗅探后握手）。</p>
     */
    public String pageUrl() {
        String host = address();
        if (host.isEmpty()) {
            return "";
        }
        return (tls ? "https://" : "http://") + host + "/";
    }

    /** 是否为远程 URL（http/https）。 */
    public static boolean isUrl(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim().toLowerCase();
        return p.startsWith("http://") || p.startsWith("https://");
    }
}
