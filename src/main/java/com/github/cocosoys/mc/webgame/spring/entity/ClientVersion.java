package com.github.cocosoys.mc.webgame.spring.entity;

/**
 * 一个已安装的 Eaglercraft 客户端版本（clients.yml 里的条目 + 运行时状态）。
 *
 * <p>磁盘布局：{@code plugins/WebGame/clients/<id>/index.html}；
 * 元数据集中在 {@code plugins/WebGame/clients/clients.yml}（不放进版本目录，
 * 避免被页面托管直接暴露）。</p>
 */
public class ClientVersion {

    /** 内置版本 id（与 jar 内 clients/1.12.2/index.html 对应）。 */
    public static final String BUILTIN_ID = "1.12.2";
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOADED = "uploaded";

    /** 版本标识，同时是页面子路径 /eagler/<id>/ 与 WS 子路径 /eagler/<id>。 */
    private String id = "";
    /** 展示名称。 */
    private String name = "";
    /** 对应 Minecraft 版本（仅展示/提示，不参与协议协商）。 */
    private String mcVersion = "";
    /** MC 协议号（0 表示未知）。 */
    private int mcProtocol;
    /** Eaglercraft 协议版本（3/4，0 表示自适应）。 */
    private int eaglerProtocol;
    /** 是否启用（内置版本恒为 true）。 */
    private boolean enabled = true;
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

    public String getMcVersion() {
        return mcVersion == null ? "" : mcVersion;
    }

    public void setMcVersion(String mcVersion) {
        this.mcVersion = mcVersion == null ? "" : mcVersion;
    }

    public int getMcProtocol() {
        return mcProtocol;
    }

    public void setMcProtocol(int mcProtocol) {
        this.mcProtocol = mcProtocol;
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
