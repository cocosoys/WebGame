package com.github.cocosoys.mc.webgame.spring.entity;

import com.dlz.db.annotation.IdType;
import com.dlz.db.annotation.TableField;
import com.dlz.db.annotation.TableId;
import com.dlz.db.annotation.TableName;

/**
 * 统一客户端制品（Eagler 包 / 云游戏包）。
 *
 * <p>包文件统一托管在 {@code plugins/WebGame/packages/}：</p>
 * <ul>
 *   <li>云游戏：{@code packages/cloud/cloud-2/client.zip}，{@code file} 为相对 packages 的路径；</li>
 *   <li>Eagler：{@code packages/eagler/eagler-2/}，{@code file} 为目录，{@code entryPath} 为入口；</li>
 *   <li>内置 Eagler：文件仍是 {@code eagler-clients/1.12.2/index.html}，用 {@code source=builtin} 区分，
 *       不复制到 packages；</li>
 *   <li>外链 Eagler：不落盘，{@code externalUrl} 即客户端地址。</li>
 * </ul>
 */
@TableName("client-packages")
public class ClientPackage {

    public static final String TYPE_EAGLER = "eagler";
    public static final String TYPE_CLOUD = "cloud";

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOADED = "uploaded";
    public static final String SOURCE_EXTERNAL = "external";

    /** 服务端生成的稳定 id（如 eagler-1 / cloud-1），不展示给管理员。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";
    /** eagler / cloud。 */
    @TableField("package_type")
    private String packageType = TYPE_CLOUD;
    private String name = "";
    private String version = "";
    /** 相对 packages/ 的文件或目录；内置 Eagler 例外，相对插件数据目录。 */
    private String file = "";
    /** Eagler 入口文件（相对 file 目录）。 */
    @TableField("entry_path")
    private String entryPath = "";
    /** 云游戏解包目录。 */
    @TableField("target_dir")
    private String targetDir = "";
    /** 外链 Eagler 地址。 */
    @TableField("external_url")
    private String externalUrl = "";
    private String sha256 = "";
    private long size;
    private int files;
    @TableField("uploaded_at")
    private String uploadedAt = "";
    private boolean enabled = true;
    private String source = SOURCE_UPLOADED;
    private String notes = "";
    /** 当前是否“下发预选”（仅云游戏类型有意义；运行时字段，不落库）。 */
    @TableField(exist = false)
    private boolean preselect;

    public String getId() {
        return id == null ? "" : id;
    }

    public void setId(String id) {
        this.id = id == null ? "" : id.trim();
    }

    /** 对外 JSON 字段名用 type，数据库列名仍是 package_type。 */
    public String getType() {
        return packageType == null ? "" : packageType;
    }

    public void setType(String type) {
        this.packageType = type == null ? "" : type.trim().toLowerCase();
    }

    public String getPackageType() {
        return getType();
    }

    public void setPackageType(String packageType) {
        setType(packageType);
    }

    public boolean isEagler() {
        return TYPE_EAGLER.equals(getType());
    }

    public boolean isCloud() {
        return TYPE_CLOUD.equals(getType());
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getVersion() {
        return version == null ? "" : version;
    }

    public void setVersion(String version) {
        this.version = version == null ? "" : version.trim();
    }

    public String getFile() {
        return file == null ? "" : file.replace('\\', '/');
    }

    public void setFile(String file) {
        this.file = file == null ? "" : file.trim().replace('\\', '/');
    }

    public String getEntryPath() {
        return entryPath == null ? "" : entryPath.replace('\\', '/');
    }

    public void setEntryPath(String entryPath) {
        this.entryPath = entryPath == null ? "" : entryPath.trim().replace('\\', '/');
    }

    public String getTargetDir() {
        return targetDir == null ? "" : targetDir;
    }

    public void setTargetDir(String targetDir) {
        this.targetDir = targetDir == null ? "" : targetDir.trim();
    }

    public String getExternalUrl() {
        return externalUrl == null ? "" : externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl == null ? "" : externalUrl.trim();
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

    public int getFiles() {
        return files;
    }

    public void setFiles(int files) {
        this.files = files;
    }

    public String getUploadedAt() {
        return uploadedAt == null ? "" : uploadedAt;
    }

    public void setUploadedAt(String uploadedAt) {
        this.uploadedAt = uploadedAt == null ? "" : uploadedAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSource() {
        return source == null ? SOURCE_UPLOADED : source;
    }

    public void setSource(String source) {
        this.source = source == null ? SOURCE_UPLOADED : source;
    }

    public boolean isBuiltin() {
        return SOURCE_BUILTIN.equals(getSource());
    }

    public boolean isExternal() {
        return SOURCE_EXTERNAL.equals(getSource());
    }

    public String getNotes() {
        return notes == null ? "" : notes;
    }

    public void setNotes(String notes) {
        this.notes = notes == null ? "" : notes;
    }

    public boolean isPreselect() {
        return preselect;
    }

    public void setPreselect(boolean preselect) {
        this.preselect = preselect;
    }

    /**
     * ClientVersion.path 使用的入口：外链返回 URL；内置返回 eagler-clients/...；
     * 新上传 Eagler 返回 packages/eagler/...；外部/内置不套 packages 前缀。
     */
    public String getStoragePath() {
        if (isExternal()) {
            return getExternalUrl();
        }
        if (isEagler()) {
            String base = getFile();
            if (base.isEmpty()) {
                return "";
            }
            String prefix = isBuiltin() ? "" : "packages/";
            String entry = getEntryPath();
            return entry.isEmpty() ? prefix + base : prefix + base + "/" + entry;
        }
        if (getFile().isEmpty()) {
            return "";
        }
        return "packages/" + getFile();
    }

    /** Web 托管路径（云游戏包可下载；Eagler 包可用于预览）。 */
    public String getPublicPath() {
        if (isExternal() || getFile().isEmpty()) {
            return "";
        }
        if (isCloud()) {
            return "/cloud/client/" + getFile();
        }
        String entry = getEntryPath();
        return "/cloud/client/" + getFile() + (entry.isEmpty() ? "" : "/" + entry);
    }

    /** 列表展示的入口/解包目录。 */
    public String getDisplayPath() {
        if (isExternal()) {
            return getExternalUrl();
        }
        if (isEagler()) {
            return getStoragePath();
        }
        return getTargetDir();
    }
}
