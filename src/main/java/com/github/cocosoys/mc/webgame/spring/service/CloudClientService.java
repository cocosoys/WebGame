package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 云游戏客户端管理（cloud-clients.yml）。
 *
 * <p>与 eagler 客户端共享路径解析与选择页模型，但不含上传与 WS 协议协商。</p>
 */
public interface CloudClientService {

    /** 列表：rows + defaultId + allowPlayerSelect。 */
    AjaxResult list();

    /** 新增一个云游戏客户端（id 由服务端自增生成；body: name/path/mcVersion/enabled）。 */
    AjaxResult create(Map<String, Object> body);

    /** 修改一个云游戏客户端（id 不可变）。 */
    AjaxResult update(String id, Map<String, Object> body);

    /** 启用/关闭；默认客户端不可关闭。 */
    AjaxResult setEnabled(String id, boolean enabled);

    /** 设置默认客户端。 */
    AjaxResult setDefault(String id);

    /** 删除客户端（默认客户端删除后自动回退）。 */
    AjaxResult delete(String id);

    /** 保存玩家选择页排序。 */
    AjaxResult reorder(List<String> ids);

    /** 允许/禁止玩家自行选择；禁止时只能进入默认客户端。 */
    AjaxResult setAllowPlayerSelect(boolean allow);

    /** 当前全部客户端（按 order 排序后的副本）。 */
    List<CloudClient> versions();

    String defaultId();

    boolean allowPlayerSelect();

    /** 解析本地入口文件；URL 条目返回 null。 */
    File resolveClientFile(CloudClient client);

    /** 重新发布 /cloud/ 页面。 */
    void publishPages();
}
