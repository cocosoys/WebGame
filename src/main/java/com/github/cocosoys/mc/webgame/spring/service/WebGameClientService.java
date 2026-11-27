package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;

import java.util.List;
import java.util.Map;

/**
 * Eaglercraft 客户端入口管理：列表、开关、默认版本、删除、排序。
 *
 * <p>文件与名称/SHA/体积统一由 {@code client-packages} 持有；本表只记录
 * “哪个版本入口引用哪个制品、是否启用、排序”。</p>
 */
public interface WebGameClientService {

    /** 版本列表（含 defaultId）。 */
    AjaxResult list();

    /** 关闭/删除前的在线影响面统计。 */
    AjaxResult impact(String id);

    /** 启用/关闭某个上传的版本；关闭时可一并强制断连使用它的玩家。 */
    AjaxResult setEnabled(String id, boolean enabled, boolean kick);

    /** 设置默认版本。 */
    AjaxResult setDefault(String id);

    /** 删除某个上传的版本（内置版本不可删除）。 */
    AjaxResult delete(String id);

    /** 按给定 id 顺序保存玩家选择页的展示顺序（默认版本在页面上仍置顶）。 */
    AjaxResult reorder(List<String> ids);

    /** 允许/禁止玩家自行选择版本；禁止时只能进入默认版本。 */
    AjaxResult setAllowPlayerSelect(boolean allow);

    /** 新增一个 eagler 客户端条目（body: id/name/path/mcVersions/eaglerProtocol/enabled）。 */
    AjaxResult create(Map<String, Object> body);

    /** 修改一个 eagler 客户端条目（id 不可变；内置客户端不可换包）。 */
    AjaxResult update(String id, Map<String, Object> body);

    // ===== 供页面登记 / WS 嗅探器使用 =====

    /** 当前全部版本快照（按展示顺序排序）。 */
    List<ClientVersion> versions();

    /** 该版本是否存在、已启用且当前允许新连接（WS 升级准入）。 */
    boolean isServable(String id);

    /** 是否允许玩家自行选择版本。 */
    boolean allowPlayerSelect();

    /** 解析本地客户端文件路径（URL 条目返回 null；不存在也返回 null）。 */
    java.io.File resolveClientFile(ClientVersion version);

    /** 内置版本 id。 */
    String builtinId();

    /** 当前默认版本 id。 */
    String defaultId();

    /** 幂等重发版本目录与选择页（页面扩展未就绪时静默跳过）。 */
    void publishPages();
}
