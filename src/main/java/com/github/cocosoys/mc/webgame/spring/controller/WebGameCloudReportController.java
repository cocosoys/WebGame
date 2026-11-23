package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.Anonymous;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.web.ApiRequestContext;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 云游戏客户端上报端点：{@code POST /api/plugins/WebGame/cloud/report}。
 *
 * <p>由 cloud-stream（stream-server）在启动后调用，把「我上线了」写进
 * {@code cloud-clients.yml} 的运行态字段。两个注解缺一不可：</p>
 * <ul>
 *   <li>{@code @Anonymous}：网关认证门放行——客户端没有 SOYS 登录态，否则会在网关先 401；</li>
 *   <li>{@code @ApiPublic}：注解层权限门放行——本端点不用 Bukkit 权限镜像，
 *       改用 {@code X-Cloud-Key} 自行校验（见 {@link CloudClientService#report}）。</li>
 * </ul>
 *
 * <p><b>安全边界</b>：放行不等于免验证。密钥由服务层按 TOFU 规则校验（首次登记即采信，
 * 之后必须一致），因此本端点不能代替鉴权；把 {@code X-Cloud-Key} 泄露给第三方，
 * 对方才能伪造某台已登记设备的状态。</p>
 *
 * <p>另有一个匿名只读端点 {@code GET /cloud/page-config}：页面加载时读取"自动进服"的
 * 服务端口径（{@code auto-join.enabled/default-username}）。它只回这两项，不含任何密钥。</p>
 *
 * <p>以及 {@code GET /cloud/entry-ticket}：给"唯一入口"（{@code /cloud/auto/}）签一张短时
 * 入场票据。票据本身不是秘密（它就是页面能力的凭证），它解决的问题是<b>把选机从浏览器手里收回</b>：
 * 页面只带票据，不带任何设备的 cloud-key，由服务端挑一台空闲机器并用该机密钥调代理。</p>
 */
@Anonymous
@ApiPublic
@RequestMapping("/cloud")
public class WebGameCloudReportController {

    /** 上报时的共享密钥头（与 stream-server 的 conf.auth_token 同值）。 */
    public static final String HEADER_CLOUD_KEY = "X-Cloud-Key";

    /** 与 {@code CloudPlayService} 的玩家名校验保持一致（自动进服不能用非法名）。 */
    private static final Pattern PLAYER_RE = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final CloudClientService cloudClientService;
    private final WebGame plugin;

    public WebGameCloudReportController(WebGame plugin, CloudClientService cloudClientService) {
        this.plugin = plugin;
        this.cloudClientService = cloudClientService;
    }

    @ApiName("云游戏客户端上线上报")
    @PostMapping("/report")
    public AjaxResult report(@RequestBody Map<String, Object> body) {
        Map<String, Object> payload = body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
        // 客户端没带 ip 时用连接来源 IP 兜底（同机/内网部署最常见）
        Object ip = payload.get("ip");
        if (ip == null || String.valueOf(ip).trim().isEmpty()) {
            ApiRequestContext ctx = ApiRequestContext.current();
            if (ctx != null && ctx.getIp() != null && !ctx.getIp().isEmpty()) {
                payload.put("ip", ctx.getIp());
            }
        }
        return cloudClientService.report(headerCloudKey(), payload);
    }

    /**
     * 页面公共配置：当前只有"自动进服"两项。
     *
     * <p>为什么放在这里而不是 ERP：入口页是匿名的，不能读需要 OP 权限的配置接口。
     * 为什么每次请求都读 {@code plugin.getConfig()}，而不是 {@code WebGameConfig} 缓存：
     * ERP 保存 config.yml 后会 {@code reloadConfig()}，但 {@code WebGameConfig} 只在启用时
     * 构造一次 —— 读缓存等于"改了配置要重启才生效"，而这恰恰是之前 auto-join 看起来
     * "配了没用"的原因之一。</p>
     */
    @ApiName("云游戏页面公共配置")
    @GetMapping("/page-config")
    public AjaxResult pageConfig() {
        boolean enabled = false;
        String username = "";
        String note = "";
        try {
            FileConfiguration cfg = plugin.getConfig();
            enabled = cfg.getBoolean("auto-join.enabled", true);
            String raw = cfg.getString("auto-join.default-username", "WebPlayer");
            username = raw == null ? "" : raw.trim();
            if (enabled && !PLAYER_RE.matcher(username).matches()) {
                // 配了个非法名（中文/超长/带空格）时不要静默忽略：降级为"需手动输入"并给出原因
                note = "auto-join.default-username 不合法（需 3–16 位字母/数字/下划线）：" + username;
                enabled = false;
            }
        } catch (Throwable t) {
            note = "读取配置失败：" + t.getMessage();
            enabled = false;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> autoJoin = new LinkedHashMap<>();
        autoJoin.put("enabled", enabled);
        autoJoin.put("username", enabled ? username : "");
        autoJoin.put("note", note);
        data.put("autoJoin", autoJoin);
        data.put("playerNameRule", PLAYER_RE.pattern());
        return AjaxResult.success("ok", data);
    }

    /**
     * 入场票据：{@code GET /cloud/entry-ticket}。
     *
     * <p>只签发"服务端会认真对待的一次入场资格"，不携带任何设备信息。有效期 180 秒，
     * 过期后 {@code /cloud/session/start} 会直接拒（提示刷新入口页）。之所以做成独立端点
     * 而不是把票据写死在页面里：页面是 {@code publish()} 时静态登记的，写死等于"插件一重启
     * 票据就过期"，玩家会看到一堆莫名的失败。</p>
     */
    @ApiName("云游戏入场票据")
    @GetMapping("/entry-ticket")
    public AjaxResult entryTicket() {
        com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService play = plugin.getCloudPlayService();
        if (play == null) {
            return AjaxResult.error("云游戏会话服务未就绪");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticket", play.mintEntryTicket());
        data.put("expiresInSeconds", 180);
        return AjaxResult.success("ok", data);
    }

    private String headerCloudKey() {
        ApiRequestContext ctx = ApiRequestContext.current();
        if (ctx == null) {
            return "";
        }
        Map<String, String> headers = ctx.getHeaders();
        if (headers == null || headers.isEmpty()) {
            return "";
        }
        String direct = headers.get(HEADER_CLOUD_KEY);
        if (direct != null) {
            return direct.trim();
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && HEADER_CLOUD_KEY.equalsIgnoreCase(e.getKey().trim())) {
                return e.getValue() == null ? "" : e.getValue().trim();
            }
        }
        return "";
    }
}
