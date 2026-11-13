package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.Anonymous;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;
import com.github.cocosoys.mc.soyshttpovermc.web.ApiRequestContext;

import java.util.Map;

/**
 * ERP 管理面 —— 会话查询端点。路径：/api/plugins/WebGame/erp/session/*。
 *
 * <p>{@code /heartbeat} 为云游戏客户端预留的匿名心跳接入点（cloud-stream 页面周期上报），
 * 不需要登录凭证。</p>
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

    @Anonymous
    @ApiName("云游戏会话心跳")
    @PostMapping("/heartbeat")
    public AjaxResult heartbeat(@RequestBody Map<String, Object> body, ApiRequestContext ctx) {
        return sessionService.heartbeat(body, ctx == null ? "" : ctx.getIp());
    }
}
