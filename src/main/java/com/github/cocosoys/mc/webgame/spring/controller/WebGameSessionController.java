package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;

/**
 * ERP 管理面 —— 会话查询端点。路径：/api/plugins/WebGame/erp/session/*。
 *
 * <p>只读 + 踢出。<b>没有</b>匿名心跳端点：云游戏会话的状态由
 * {@code CloudPlayService} 自己维护（页面心跳走 {@code /cloud/session/heartbeat}），
 * ERP 只需要合并展示。</p>
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

    @ApiName("强制踢出会话")
    @ApiPermission("webgame.erp.session.kick")
    @PostMapping("/kick")
    public AjaxResult kick(@RequestParam(name = "sessionId", required = true) String sessionId) {
        return sessionService.kick(sessionId);
    }
}
