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

    /** 是否为远程 URL（http/https）。 */
    public static boolean isUrl(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim().toLowerCase();
        return p.startsWith("http://") || p.startsWith("https://");
    }
}
