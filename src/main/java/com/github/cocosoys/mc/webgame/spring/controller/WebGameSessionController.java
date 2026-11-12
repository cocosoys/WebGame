package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;

/**
 * ERP 管理面 —— WS 会话查询端点。路径：/api/plugins/WebGame/erp/session/*。
 */
@RequestMapping("/erp/session")
public class WebGameSessionController {

    private final WebGameSessionService sessionService;

    public WebGameSessionController(WebGameSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @ApiName("会话列表")
    @ApiPermission("webgame.erp.session.list")
    @GetMapping("/list")
    public AjaxResult list() {
        return sessionService.list();
    }
}
