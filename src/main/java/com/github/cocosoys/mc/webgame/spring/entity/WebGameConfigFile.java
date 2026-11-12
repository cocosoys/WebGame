package com.github.cocosoys.mc.webgame.spring.entity;

/**
 * WebGame ERP 可编辑的配置文件清单（数据目录 = plugins/WebGame/）。
 *
 * <p>数据驱动的唯一事实源：菜单、HTTP 端点、文件解析全部从本枚举派生。
 * 当前仅开放 {@code config.yml}；新增可编辑文件只需在此加一项 + 加一个前端 .vue。</p>
 *
 * <p>修改保存后隧道/页面参数需重启服务器生效（装配发生在插件启用时）。</p>
 */
public enum WebGameConfigFile {

    CONFIG("config", "核心配置", "config.yml",
            "页面托管目录、WS 隧道、自动进服");

    private final String id;
    private final String name;
    private final String file;
    private final String desc;

    WebGameConfigFile(String id, String name, String file, String desc) {
        this.id = id;
        this.name = name;
        this.file = file;
        this.desc = desc;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getFile() {
        return file;
    }

    public String getDesc() {
        return desc;
    }

    public static WebGameConfigFile byId(String id) {
        if (id == null) {
            return null;
        }
        for (WebGameConfigFile f : values()) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }
}
