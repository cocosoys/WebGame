package com.github.cocosoys.mc.webgame.spring.service;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;

/**
 * 运行状态查询（只读）。
 */
public interface WebGameStatusService {

    /** 插件 / 隧道 / 会话的运行快照。 */
    AjaxResult overview();
}
