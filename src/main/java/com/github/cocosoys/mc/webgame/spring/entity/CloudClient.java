package com.github.cocosoys.mc.webgame.spring.entity;

import com.dlz.db.annotation.IdType;
import com.dlz.db.annotation.TableField;
import com.dlz.db.annotation.TableId;
import com.dlz.db.annotation.TableName;

@TableName("cloud-clients")
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

    /** 自增数字 id，同时是页面子路径 /cloud/&lt;id&gt;/；由 SOYS @TableId(AUTO) 生成。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    /** 展示名称。 */
    private String name = "";
    /**
     * 客户端入口路径（运行时字段，不持久化）：由 {@link #ip}/{@link #port}/{@link #tls}
     * 实时拼出，避免和设备地址重复落盘。
     */
    @TableField(exist = false)
    private String path = "";
    /** 管理员最近一次成功下发/期望的客户端制品 id（client-packages），用于玩家进入时版本闸门。 */
    @TableField("package_id")
    private String packageId = "";
    /** Minecraft 版本描述（运行时从 client-packages 回填，不持久化）。 */
    @TableField(exist = false)
    private String mcVersion = "";
    /** 是否启用。 */
    private boolean enabled = true;
    /** 玩家选择页的展示顺序（升序；-1 表示未指定，加载时补号）。 */
    private int order = -1;
    /** 数据来源（运行时字段，不持久化）：url / local，由入口地址推导。 */
    @TableField(exist = false)
    private String source = SOURCE_LOCAL;
    /** 运行时：入口文件不存在。 */
    @TableField(exist = false)
    private boolean missing;

    /**
     * 对外别名（根路径门牌）：{@code /cloud/<alias>}。可同时给中文与 ASCII，
     * 例：{@code aliases: [测试服务器, test]}。
     *
     * <p>别名是"玩家看到的名字"，id 是"系统内部编号"——玩家不该记 {@code /cloud/1/}。
     * 同一客户端可以有多个别名；全局唯一（冲突时按 order+id 先到先得，其余记警告）。</p>
     */
    @TableField(exist = false)
    private java.util.List<String> aliases = new java.util.ArrayList<>();
    /** 持久化列 aliases：逗号分隔，避免 SQL 端 List 反序列化限制。 */
    @TableField("aliases")
    private String aliasesData = "";
    /**
     * 预留：逻辑池名（未来"唯一入口、不选设备、点击自动匹配"时，alias/池 映射到多台机器）。
     * P0 一律为空字符串 = 该客户端自成一池。
     */
    private String pool = "";

    /**
     * ERP 管理范围：纯展示/过滤字段，不参与玩家调度，也不作为授权边界。
     * 空串 = 未分配；新设备上报时保持空串，由管理员重新连接后手工分配。
     */
    private String adminScope = "";

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
    /**
     * 该设备 TLS 证书的 DER SHA-256（小写十六进制）。
     *
     * <p>由 cloud-stream 首次上报时带上，WebGame 只信任这张证书；换证书后同一
     * cloud-key 的上报会刷新它。空 = 老客户端/没上报证书，仍走全局 truststore。</p>
     */
    private String tlsCertSha256 = "";
    /** 该设备证书 PEM（只存公钥证书，不含私钥）；用于构建按设备 TrustManager。 */
    private String tlsCertPem = "";
    /** 证书 Subject（ERP 展示/排查用）。 */
    private String tlsCertSubject = "";
    /** 证书到期时间（ERP 展示/排查用）。 */
    private String tlsCertExpiresAt = "";
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
        return id == null || id <= 0 ? "" : String.valueOf(id);
    }

    public void setId(String id) {
        if (id == null || id.trim().isEmpty()) {
            this.id = null;
            return;
        }
        try {
            long parsed = Long.parseLong(id.trim());
            this.id = parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            this.id = null;
        }
    }

    public String getName() {
        return name == null || name.isEmpty() ? getId() : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public String getPath() {
        String p = path == null ? "" : path.trim();
        return p.isEmpty() ? pageUrl() : p;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String getPackageId() {
        return packageId == null ? "" : packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId == null ? "" : packageId.trim();
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

    public String getAliasesData() {
        return aliasesData == null ? "" : aliasesData;
    }

    public void setAliasesData(String aliasesData) {
        this.aliasesData = aliasesData == null ? "" : aliasesData;
    }

    public String getPool() {
        return pool == null ? "" : pool;
    }

    public void setPool(String pool) {
        this.pool = pool == null ? "" : pool.trim();
    }

    public String getAdminScope() {
        return adminScope == null ? "" : adminScope;
    }

    public void setAdminScope(String adminScope) {
        String s = adminScope == null ? "" : adminScope.trim();
        this.adminScope = s.length() > 24 ? s.substring(0, 24) : s;
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

    public String getTlsCertSha256() {
        return tlsCertSha256 == null ? "" : tlsCertSha256;
    }

    public void setTlsCertSha256(String tlsCertSha256) {
        if (tlsCertSha256 == null) {
            this.tlsCertSha256 = "";
            return;
        }
        String s = tlsCertSha256.replace(":", "").replace(" ", "").trim()
                .toLowerCase(java.util.Locale.ROOT);
        this.tlsCertSha256 = s.matches("[0-9a-f]{64}") ? s : "";
    }

    public String getTlsCertPem() {
        return tlsCertPem == null ? "" : tlsCertPem;
    }

    public void setTlsCertPem(String tlsCertPem) {
        if (tlsCertPem == null) {
            this.tlsCertPem = "";
            return;
        }
        String s = tlsCertPem.trim();
        // 一张自签证书通常 1–2 KB；上限只是防止脏数据把 YAML/ERP 撑爆。
        this.tlsCertPem = s.length() > 32768 ? "" : s;
    }

    public String getTlsCertSubject() {
        return tlsCertSubject == null ? "" : tlsCertSubject;
    }

    public void setTlsCertSubject(String tlsCertSubject) {
        String s = tlsCertSubject == null ? "" : tlsCertSubject.trim();
        this.tlsCertSubject = s.length() > 256 ? s.substring(0, 256) : s;
    }

    public String getTlsCertExpiresAt() {
        return tlsCertExpiresAt == null ? "" : tlsCertExpiresAt;
    }

    public void setTlsCertExpiresAt(String tlsCertExpiresAt) {
        String s = tlsCertExpiresAt == null ? "" : tlsCertExpiresAt.trim();
        this.tlsCertExpiresAt = s.length() > 64 ? s.substring(0, 64) : s;
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
