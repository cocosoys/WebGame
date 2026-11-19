package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.Anonymous;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.web.ApiRequestContext;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;

import java.util.LinkedHashMap;
import java.util.Map;

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
 */
@Anonymous
@ApiPublic
@RequestMapping("/cloud")
public class WebGameCloudReportController {

    /** 上报时的共享密钥头（与 stream-server 的 conf.auth_token 同值）。 */
    public static final String HEADER_CLOUD_KEY = "X-Cloud-Key";

    private final CloudClientService cloudClientService;

    public WebGameCloudReportController(CloudClientService cloudClientService) {
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
