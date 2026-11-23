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
        // alias：玩家从 /cloud/<别名> 进来时带上门牌，服务端据此校验"这个名字确实指向这台机器"
        String alias = str(payload.get("alias"));
        // pool：玩家从 /cloud/pool/<池> 进来时只报池名，具体挑哪台机器由服务端决定
        String pool = str(payload.get("pool"));
        // identity 目前只做透传占位（free / verified），P0 一律按 free 处理
        return playService.start(clientId, player, token, alias, pool);
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

    /**
     * 画面回执：包装页在 iframe 真的载入后打一次，把阶段 4 落到服务端。
     * <p>只认已经「可信进服」的会话；没进服就来说画面好了属于谎报，直接拒。</p>
     */
    @ApiName("云游戏画面就绪回执")
    @PostMapping("/screen")
    public AjaxResult screen(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        return playService.screen(str(payload.get("sessionId")));
    }

    /**
     * 浏览器心跳：包装页每 10 秒打一次；玩家关掉标签页后停止发送，
     * 服务端超过阈值（{@code cloud.heartbeat.timeout-seconds}）收不到就按同一套收尾释放槽位。
     */
    @ApiName("云游戏会话心跳")
    @PostMapping("/heartbeat")
    public AjaxResult heartbeat(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : body;
        return playService.heartbeat(str(payload.get("sessionId")));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
