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
}
