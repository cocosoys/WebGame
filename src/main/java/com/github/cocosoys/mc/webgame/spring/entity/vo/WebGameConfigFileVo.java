package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * 配置文件清单行 VO（{@link com.github.cocosoys.mc.webgame.spring.entity.WebGameConfigFile} 的展示投影）。
 */
public class WebGameConfigFileVo {

    private String id;
    private String name;
    private String file;
    private String desc;

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

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }
}
