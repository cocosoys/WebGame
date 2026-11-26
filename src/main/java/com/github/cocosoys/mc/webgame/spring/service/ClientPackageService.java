package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;

import java.util.List;
import java.util.Map;

/**
 * 统一客户端制品库（Eagler 包 + 云游戏包）。
 *
 * <p>制品元数据统一存 {@code data/client-packages} / MySQL {@code client-packages}；
 * 文件仍托管在 {@code packages/}。旧的 {@code cloud-client-packages.yml} 在首次启动时
 * 自动迁移成云游戏制品。</p>
 */
public interface ClientPackageService {

    /** 全部制品，按类型、id 排序。 */
    List<ClientPackage> packages();

    /** 按类型过滤；type 为空/全部返回全部。 */
    List<ClientPackage> packages(String type);

    /** 按 id 找；没有返回 null。 */
    ClientPackage find(String id);

    /** 云游戏“下发预选”制品；没有返回 null。 */
    ClientPackage preselectPackage();

    /** 兼容旧调用：等同于 {@link #preselectPackage()}。 */
    ClientPackage defaultPackage();

    /** 预选制品的下载 URL；基址未知或没有制品时返回空串。 */
    String defaultUrl();

    /** 某云游戏制品的下载 URL；基址未知返回空串。 */
    String urlOf(ClientPackage p);

    /** manifest.json 内容；按“默认包仅预选”口径，不再对外自动下发。 */
    String manifestJson();

    /** ERP 列表；type 可空。 */
    AjaxResult list(String type);

    /** 兼容旧 ERP：只列云游戏包。 */
    AjaxResult list();

    /** 修改名称/版本/解包目录/备注/启用；Eagler 可改入口（必须仍在该包目录内）。 */
    AjaxResult update(String id, Map<String, Object> body);

    /** 设置云游戏下发预选。 */
    AjaxResult setDefault(String id);

    /** 兼容旧命名：设置云游戏下发预选。 */
    AjaxResult setPreselect(String id);

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
