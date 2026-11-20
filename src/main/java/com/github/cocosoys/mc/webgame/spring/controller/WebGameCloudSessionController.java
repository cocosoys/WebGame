package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.Anonymous;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云游戏会话端点：玩家"点一下就能玩"这件事的服务器侧入口。
 *
 * <p>{@code POST /cloud/session/start}、{@code GET /cloud/session/status}、
 * {@code POST /cloud/session/end}。与 {@code /cloud/report} 一样需要
 * {@code @Anonymous}（浏览器玩家没有 SOYS 登录态）+ {@code @ApiPublic}（走自带令牌校验，
 * 不用 Bukkit 权限镜像）。令牌与客户端的 cloud-stream {@code auth_token} 严格比对。</p>
 *
 * <p><b>为什么"未填游戏名"必须在服务端拒</b>：入口页的前端校验只是体验，绕过它成本极低
 * （改 URL、直接 POST）。服务端拒绝才是真正的门——同时这也是将来接"实名/登录身份"
 * 的唯一收口点（见 {@code identityMode} 预留）。</p>
 */
@Anonymous
@ApiPublic
@RequestMapping("/cloud/session")
public class WebGameCloudSessionController {

    private final CloudPlayService playService;

    public WebGameCloudSessionController(CloudPlayService playService) {
        this.playService = playService;
    }

    /** 开始会话：需要 clientId + 玩家名 + 令牌。 */
    @ApiName("开始云游戏会话")
    @PostMapping("/start")
    public AjaxResult start(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        String clientId = str(payload.get("clientId"));
        String player = str(payload.get("player"));
        String token = str(payload.get("token"));
        // identity 目前只做透传占位（free / verified），P0 一律按 free 处理
        return playService.start(clientId, player, token);
    }

    /** 会话状态：包装页靠它判断何时跳回选择页。 */
    @ApiName("云游戏会话状态")
    @GetMapping("/status")
    public AjaxResult status(@RequestParam(name = "s", required = false) String sessionId) {
        return playService.status(sessionId);
    }

    /** 主动结束会话。 */
    @ApiName("结束云游戏会话")
    @PostMapping("/end")
    public AjaxResult end(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        return playService.end(str(payload.get("sessionId")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
