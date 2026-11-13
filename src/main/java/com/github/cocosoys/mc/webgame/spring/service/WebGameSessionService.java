package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;

import java.util.Map;

/**
 * 会话查询（eagler WS 实时会话 + 云游戏心跳会话）。
 */
public interface WebGameSessionService {

    /** 当前全部会话（eagler 与云游戏合并）。 */
    AjaxResult list();

    /** 按会话 id 强制踢出（eagler 断开 WS；云游戏移除会话记录）。 */
    AjaxResult kick(String sessionId);

    /**
     * 云游戏客户端心跳（预留接入点，供 cloud-stream 页面周期上报）。
     *
     * <p>body：{@code clientId}（必填）、{@code sessionId}、{@code username}、{@code uuid}。</p>
     */
    AjaxResult heartbeat(Map<String, Object> body, String ip);
}
