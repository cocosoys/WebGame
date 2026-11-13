package com.github.cocosoys.mc.webgame.spring.entity.vo;

import com.github.cocosoys.mc.webgame.spring.entity.McVersionOption;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端版本列表行（GET /erp/clients/list）。
 */
public class ClientVersionVo {

    private String id;
    private String name;
    /** 客户端入口路径（URL 或本地文件）。 */
    private String path;
    /** 支持的 Minecraft 版本（版本名 + 协议号）。 */
    private List<McVersionOption> mcVersions = new ArrayList<>();
    private int eaglerProtocol;
    private boolean enabled;
    /** 玩家选择页展示顺序（升序）。 */
    private int order;
    /** 是否为当前默认版本。 */
    private boolean defaultVersion;
    /** builtin / uploaded。 */
    private String source;
    private String sha256;
    private long size;
    private long uploadedAt;
    private boolean missing;
    /** 客户端页面完整访问路径（/web/plugins/WebGame/eagler/&lt;id&gt;/）。 */
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

    public List<McVersionOption> getMcVersions() {
        return mcVersions == null ? new ArrayList<>() : mcVersions;
    }

    public void setMcVersions(List<McVersionOption> mcVersions) {
        this.mcVersions = mcVersions == null ? new ArrayList<>() : mcVersions;
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

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
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

    public String getPagePath() {
        return pagePath;
    }

    public void setPagePath(String pagePath) {
        this.pagePath = pagePath;
    }
}
