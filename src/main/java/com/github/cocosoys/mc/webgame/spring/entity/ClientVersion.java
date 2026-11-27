package com.github.cocosoys.mc.webgame.spring.entity;

import com.dlz.db.annotation.IdType;
import com.dlz.db.annotation.TableField;
import com.dlz.db.annotation.TableId;
import com.dlz.db.annotation.TableName;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个已启用的 Eaglercraft 客户端入口。
 *
 * <p>表里只保留“选哪个制品、是否启用、排序、协议、别名”：名称、入口路径、来源、
 * SHA-256、体积、上传时间全部由 {@code client-packages} 解析，避免两处漂移。
 * 运行时字段用 {@code @TableField(exist=false)} 缓存。</p>
 */
@TableName("eagler-clients")
public class ClientVersion {

    /** 内置版本 id（页面 /eagler/1.12.2/ 与 WS 子路径不变）。 */
    public static final String BUILTIN_ID = "1.12.2";
    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_UPLOADED = "uploaded";
    public static final String SOURCE_URL = "url";
    public static final String SOURCE_LOCAL = "local";

    /** 版本标识，同时是页面子路径 /eagler/<id>/ 与 WS 子路径 /eagler/<id>。 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id = "";
    /** 展示名称：运行时从 client-packages 回填。 */
    @TableField(exist = false)
    private String name = "";
    /** 客户端入口路径：运行时从 client-packages 回填。 */
    @TableField(exist = false)
    private String path = "";
    /** 引用的客户端制品 id（client-packages 数字主键）。 */
    @TableField("package_id")
    private String packageId = "";
    /** 对外别名（展示/短地址用），持久化列为 aliases。 */
    @TableField(exist = false)
    private List<String> aliases = new ArrayList<>();
    /** 持久化列 aliases：逗号分隔，避免 SQL 端 List 反序列化限制。 */
    @TableField("aliases")
    private String aliasesData = "";
    /** 支持的 Minecraft 版本（运行时视图；持久化用 mcVersionData）。 */
    @TableField(exist = false)
    private List<McVersionOption> mcVersions = new ArrayList<>();
    /** 持久化列 mc_versions：version|protocol 逗号分隔，避免 ORM 对 List 类型在 SQL 端的反序列化限制。 */
    @TableField("mc_versions")
    private String mcVersionData = "";
    /** Eaglercraft 协议版本（3/4，0 表示自适应）。 */
    private int eaglerProtocol;

    public List<McVersionOption> getMcVersions() {
        return mcVersions == null ? new ArrayList<>() : mcVersions;
    }

    public void setMcVersions(List<McVersionOption> mcVersions) {
        this.mcVersions = mcVersions == null ? new ArrayList<>() : mcVersions;
    }

    public String getMcVersionData() {
        return mcVersionData == null ? "" : mcVersionData;
    }

    public void setMcVersionData(String mcVersionData) {
        this.mcVersionData = mcVersionData == null ? "" : mcVersionData;
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
    /** 数据来源：builtin / uploaded / url（运行时按制品解析）。 */
    @TableField(exist = false)
    private String source = SOURCE_UPLOADED;
    /** 文件 SHA-256（运行时按制品回填）。 */
    @TableField(exist = false)
    private String sha256 = "";
    /** 文件字节数（运行时按制品回填）。 */
    @TableField(exist = false)
    private long size;
    /** 上传/登记时间（运行时按制品回填）。 */
    @TableField(exist = false)
    private long uploadedAt;
    /** 运行时：目录或 index.html 缺失。 */
    @TableField(exist = false)
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

    public String getPackageId() {
        return packageId == null ? "" : packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId == null ? "" : packageId.trim();
    }

    public List<String> getAliases() {
        return aliases == null ? new ArrayList<>() : aliases;
    }

    /** 列表写入时同步持久化列；空值、重复值与空白字符在这里统一收敛。 */
    public void setAliases(List<String> aliases) {
        List<String> out = new ArrayList<>();
        if (aliases != null) {
            for (String a : aliases) {
                String n = a == null ? "" : a.trim();
                if (!n.isEmpty() && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        this.aliases = out;
        this.aliasesData = joinAliases(out);
    }

    public String getAliasesData() {
        return aliasesData == null ? "" : aliasesData;
    }

    /** ORM 回填持久化列时同步解析成列表。 */
    public void setAliasesData(String aliasesData) {
        this.aliasesData = aliasesData == null ? "" : aliasesData;
        this.aliases = splitAliases(this.aliasesData);
    }

    private static String joinAliases(List<String> aliases) {
        StringBuilder sb = new StringBuilder();
        for (String a : aliases) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static List<String> splitAliases(String data) {
        List<String> out = new ArrayList<>();
        if (data == null || data.trim().isEmpty()) {
            return out;
        }
        for (String part : data.split("[,，]+")) {
            String n = part.trim();
            if (!n.isEmpty() && !out.contains(n)) {
                out.add(n);
            }
        }
        return out;
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
