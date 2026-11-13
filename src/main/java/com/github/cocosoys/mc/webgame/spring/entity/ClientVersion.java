package com.github.cocosoys.mc.webgame.spring.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个已安装的 Eaglercraft 客户端（eagler-clients.yml 里的条目 + 运行时状态）。
 *
 * <p>磁盘布局：{@code plugins/WebGame/eagler-clients/<id>/index.html}；
 * 元数据集中在 {@code plugins/WebGame/eagler-clients.yml}（不放进版本目录，
 * 避免被页面托管直接暴露）。</p>
 */
public class ClientVersion {

    /** 内置版本 id（与 jar 内 eagler-clients/1.12.2/index.html 对应）。 */
    public static final String BUILTIN_ID = "1.12.2";
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOADED = "uploaded";
    public static final String SOURCE_URL = "url";
    public static final String SOURCE_LOCAL = "local";

    /** 版本标识，同时是页面子路径 /eagler/<id>/ 与 WS 子路径 /eagler/<id>。 */
    private String id = "";
    /** 展示名称。 */
    private String name = "";
    /**
     * 客户端入口路径：{@code http(s)://…} 直接跳转；否则视为本地文件
     * （相对插件数据目录 / 服务器根目录，或绝对路径）。
     */
    private String path = "";
    /** 支持的 Minecraft 版本（版本名 + 协议号，仅展示/记录，不参与协议协商）。 */
    private List<McVersionOption> mcVersions = new ArrayList<>();
    /** Eaglercraft 协议版本（3/4，0 表示自适应）。 */
    private int eaglerProtocol;

    public List<McVersionOption> getMcVersions() {
        return mcVersions == null ? new ArrayList<>() : mcVersions;
    }

    public void setMcVersions(List<McVersionOption> mcVersions) {
        this.mcVersions = mcVersions == null ? new ArrayList<>() : mcVersions;
    }

    /** 选择页展示用的版本名拼接（如 "1.8.9 / 1.12.2"）。 */
    public String getMcVersionText() {
        StringBuilder sb = new StringBuilder();
        for (McVersionOption o : getMcVersions()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(o.getVersion());
        }
        return sb.toString();
    }
    /** 是否启用。 */
    private boolean enabled = true;
    /** 玩家选择页的展示顺序（升序）。 */
    private int order;
    /** 数据来源：builtin / uploaded。 */
    private String source = SOURCE_UPLOADED;
    /** 文件 SHA-256（内置版本为空）。 */
    private String sha256 = "";
    /** 文件字节数。 */
    private long size;
    /** 上传/登记时间（epoch millis）。 */
    private long uploadedAt;
    /** 运行时：目录或 index.html 缺失。 */
    private boolean missing;

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

    /** 是否为远程 URL（http/https）。 */
    public static boolean isUrl(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim().toLowerCase();
        return p.startsWith("http://") || p.startsWith("https://");
    }

    public int getEaglerProtocol() {
        return eaglerProtocol;
    }

    public void setEaglerProtocol(int eaglerProtocol) {
        this.eaglerProtocol = eaglerProtocol;
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
        this.source = source;
    }

    public boolean isBuiltin() {
        return SOURCE_BUILTIN.equals(source);
    }

    public String getSha256() {
        return sha256 == null ? "" : sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256 == null ? "" : sha256;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(long uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public boolean isMissing() {
        return missing;
    }

    public void setMissing(boolean missing) {
        this.missing = missing;
    }
}
