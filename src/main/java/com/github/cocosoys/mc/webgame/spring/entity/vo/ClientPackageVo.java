package com.github.cocosoys.mc.webgame.spring.entity.vo;

import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;

/**
 * 客户端制品列表/详情行。
 */
public class ClientPackageVo {

    private String id;
    private String type;
    private String name;
    private String version;
    private String file;
    private String entryPath;
    private String targetDir;
    private String externalUrl;
    private String displayPath;
    private String sha256;
    private long size;
    private int files;
    private String uploadedAt;
    private boolean enabled;
    private String source;
    private String notes;
    private boolean preselect;
    private String url;
    /** Eagler 包被多少个客户端条目引用；云游戏包恒为 0。 */
    private int referenceCount;

    public ClientPackageVo() {
    }

    public ClientPackageVo(ClientPackage p) {
        if (p == null) {
            return;
        }
        this.id = p.getId();
        this.type = p.getType();
        this.name = p.getName();
        this.version = p.getVersion();
        this.file = p.getFile();
        this.entryPath = p.getEntryPath();
        this.targetDir = p.getTargetDir();
        this.externalUrl = p.getExternalUrl();
        this.displayPath = p.getDisplayPath();
        this.sha256 = p.getSha256();
        this.size = p.getSize();
        this.files = p.getFiles();
        this.uploadedAt = p.getUploadedAt();
        this.enabled = p.isEnabled();
        this.source = p.getSource();
        this.notes = p.getNotes();
        this.preselect = p.isPreselect();
        this.url = p.getPublicPath();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getEntryPath() {
        return entryPath;
    }

    public void setEntryPath(String entryPath) {
        this.entryPath = entryPath;
    }

    public String getTargetDir() {
        return targetDir;
    }

    public void setTargetDir(String targetDir) {
        this.targetDir = targetDir;
    }

    public String getExternalUrl() {
        return externalUrl;
    }

    public void setExternalUrl(String externalUrl) {
        this.externalUrl = externalUrl;
    }

    public String getDisplayPath() {
        return displayPath;
    }

    public void setDisplayPath(String displayPath) {
        this.displayPath = displayPath;
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

    public int getFiles() {
        return files;
    }

    public void setFiles(int files) {
        this.files = files;
    }

    public String getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(String uploadedAt) {
        this.uploadedAt = uploadedAt;
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

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public boolean isPreselect() {
        return preselect;
    }

    public void setPreselect(boolean preselect) {
        this.preselect = preselect;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    public void setReferenceCount(int referenceCount) {
        this.referenceCount = referenceCount;
    }
}
