package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;

import java.util.Map;

/**
 * 插件配置文件可视化读写（仅本插件数据目录下的文件，清单见 WebGameConfigFile）。
 */
public interface WebGameConfigService {

    /** 可编辑配置文件清单。 */
    AjaxResult list();

    /** 读取指定文件（fileId 见 WebGameConfigFile#getId()）。 */
    AjaxResult load(String fileId);

    /** 全量保存指定文件（注释不保留，重启服务器生效）。 */
    AjaxResult save(String fileId, Map<String, Object> data);
}
