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
 * 不用 Bukkit 权限镜像）。</p>
 *
 * <p><b>鉴权口径（没有 identityMode）</b>：这三个端点只认两类凭证——</p>
 * <ul>
 *   <li><b>设备令牌</b>：与客户端 cloud-stream {@code auth_token} 严格比对（指定机器/池入口用）；</li>
 *   <li><b>入场票据</b>：{@code /cloud/auto/} 页面签发的 180 秒<b>一次性</b>随机票据，由服务端自己挑机器，
 *       浏览器拿不到任何设备的密钥（唯一入口用）。</li>
 * </ul>
 * <p>历史上这里有一个"identity=free/verified 透传占位"，但页面是匿名的，没有任何可验证的身份源，
 * 占位只会让人误以为有实名开关。已移除：真正的实名/登录态要接 SOYS auth，接之前不做半套。</p>
 *
 * <p><b>为什么"未填游戏名"必须在服务端拒</b>：入口页的前端校验只是体验，绕过它成本极低
 * （改 URL、直接 POST）。服务端拒绝才是真正的门。</p>
 */
@Anonymous
@ApiPublic
@RequestMapping("/cloud/session")
public class WebGameCloudSessionController {

    private final CloudPlayService playService;

    public WebGameCloudSessionController(CloudPlayService playService) {
        this.playService = playService;
    }

    /** 开始会话：指定机器（clientId+token）/ 门牌（alias）/ 池（pool）/ 唯一入口（ticket），四种入口四选一。 */
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
        // ticket：玩家从 /cloud/auto/（唯一入口）进来时带票据；此时 clientId/alias/pool 都被忽略
        String ticket = str(payload.get("ticket"));
        return playService.start(clientId, player, token, alias, pool, ticket);
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
