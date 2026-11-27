package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;

import java.util.List;
import java.util.Map;

/**
 * 统一客户端制品库（Eagler 包 + 云游戏包）。
 *
 * <p>制品元数据统一存 {@code data/client-packages} / MySQL {@code client-packages}；
 * 文件统一托管在 {@code packages/}：云游戏包在 {@code packages/clients/<id>/}，
 * Eagler 包在 {@code packages/eagler/<id>/}。主键直接用 SOYS {@code IdType.AUTO} 数字 id。</p>
 */
public interface ClientPackageService {

    /** 全部制品，按类型、id 排序。 */
    List<ClientPackage> packages();

    /** 按类型过滤；type 为空/全部返回全部。 */
    List<ClientPackage> packages(String type);

    /** 按 id 找；没有返回 null。 */
    ClientPackage find(String id);

    /** 内置 Eagler 制品的数字 id；不存在返回空串。 */
    String builtinPackageId();

    /** 某云游戏制品的下载 URL；基址未知返回空串。 */
    String urlOf(ClientPackage p);

    /** manifest.json 内容；不下发默认包，由管理员显式指定期望包。 */
    String manifestJson();

    /** ERP 列表；type 可空。 */
    AjaxResult list(String type);

    /** 兼容旧 ERP：只列云游戏包。 */
    AjaxResult list();

    /** 修改名称/版本/解包目录/备注/启用；Eagler 可改入口（必须仍在该包目录内）。 */
    AjaxResult update(String id, Map<String, Object> body);

    /** 启用/停用；被 Eagler 客户端引用的 Eagler 包禁止停用。 */
    AjaxResult setEnabled(String id, boolean enabled);

    /** 删除制品；被 Eagler 客户端引用的 Eagler 包禁止删除。 */
    AjaxResult delete(String id);

    /** 开始分块上传；body: type/name/version/targetDir/fileName/size。 */
    AjaxResult uploadInit(Map<String, Object> body);

    /** 写入一块。 */
    AjaxResult uploadChunk(String uploadId, Map<String, Object> body);

    /** 合并、校验、登记成制品。 */
    AjaxResult uploadFinish(String uploadId);

    /** 放弃上传。 */
    AjaxResult uploadAbort(String uploadId);

    /** 登记 Eagler 外链客户端；body: name/version/externalUrl/notes/enabled。 */
    AjaxResult registerExternal(Map<String, Object> body);
}
