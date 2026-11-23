package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;

/**
 * 会话查询（eagler WS 实时会话 + 云游戏槽位会话）。
 *
 * <p>数据源只有两处：{@code WsGateway} 的 WS 会话、{@code CloudPlayService} 的会话表。
 * 历史上曾有一套"页面心跳上报 + 注册表"的云会话通道，但页面实际走的是
 * {@code /cloud/session/heartbeat}，注册表永远为空，已随本次修复移除。</p>
 */
public interface WebGameSessionService {

    /** 当前全部会话（eagler 与云游戏合并）。 */
    AjaxResult list();

    /** 按会话 id 强制踢出（eagler 断开 WS；云游戏走真实收尾并释放云机）。 */
    AjaxResult kick(String sessionId);
}
