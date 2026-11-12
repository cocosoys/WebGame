package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;

import java.util.List;
import java.util.Map;

/**
 * Eaglercraft 客户端版本管理：列表、开关、默认版本、删除、重扫、分块上传。
 *
 * <p>版本文件落在 {@code plugins/WebGame/clients/<id>/index.html}，元数据集中在
 * {@code clients/clients.yml}；页面登记由 {@code WebGameClientPages} 消费本服务的版本列表。</p>
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

    /** 重新扫描客户端目录并发布页面。 */
    AjaxResult rescan();

    /** 开启一次上传（body: id/name/mcVersion/mcProtocol/eaglerProtocol）。 */
    AjaxResult uploadInit(Map<String, Object> body);

    /** 写入一个分块（body: index/data(base64)）。 */
    AjaxResult uploadChunk(String uploadId, Map<String, Object> body);

    /** 结束上传：校验、落盘、登记元数据并发布页面。 */
    AjaxResult uploadFinish(String uploadId);

    /** 取消上传并清理临时文件。 */
    AjaxResult uploadAbort(String uploadId);

    // ===== 供页面登记 / WS 嗅探器使用 =====

    /** 当前全部版本快照（按 id 排序）。 */
    List<ClientVersion> versions();

    /** 该版本是否存在且已启用（WS 升级准入）。 */
    boolean isServable(String id);

    /** 内置版本 id。 */
    String builtinId();

    /** 当前默认版本 id。 */
    String defaultId();

    /** 幂等重发版本目录与选择页（页面扩展未就绪时静默跳过）。 */
    void publishPages();
}
