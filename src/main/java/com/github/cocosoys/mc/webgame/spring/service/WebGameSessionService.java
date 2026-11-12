package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;

/**
 * WS 会话查询（只读，数据源为 WsGateway 的内存会话表）。
 */
public interface WebGameSessionService {

    /** 当前全部 WS 会话。 */
    AjaxResult list();
}
