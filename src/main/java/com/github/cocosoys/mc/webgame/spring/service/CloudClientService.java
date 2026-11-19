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

    // ===== 运行态：上报 / 心跳 / 手动刷新（cloud-stream 客户端） =====

    /**
     * 客户端启动上报（TOFU 注册）：按 {@code ip:port} 找条目，不存在则新建一条。
     *
     * <p>共享密钥不匹配时返回失败（403 语义），只允许管理员用 {@link #applyKey} 覆盖。</p>
     *
     * @param cloudKey 请求头 X-Cloud-Key（空=客户端未配置 auth_token）
     * @param body     name/ip/port/version/mcVersion/mc-version/startedAt
     */
    AjaxResult report(String cloudKey, Map<String, Object> body);

    /** 单台手动刷新（按条目 id）；异步执行，返回本轮结果摘要。 */
    AjaxResult refresh(String id);

    /** 全部手动刷新（并发受限）；异步执行，返回本轮结果摘要。 */
    AjaxResult refreshAll();

    /** 管理员手动写入/更正共享密钥并立即探测一次（成功才落库）。 */
    AjaxResult applyKey(String id, String key);

    /** 启动 90s 心跳调度（onEnable 调用；重复调用幂等）。 */
    void startHeartbeat();

    /** 停止心跳调度（onDisable 调用）。 */
    void stopHeartbeat();
}
