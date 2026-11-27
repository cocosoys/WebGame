package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.Anonymous;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.data.WebGameDataStore;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家统一入口 {@code /play/} 的实时统计端点：{@code GET /api/plugins/WebGame/play/stats}。
 *
 * <p>入口页是匿名页面，不能直接打需要 ERP 登录态的 {@code /erp/*} 列表接口；同时两张卡片
 * 上的数量必须以 {@code eagler-clients} / {@code cloud-clients} 表为准，而不是页面登记时
 * 固化的快照。因此这里提供只读、匿名的聚合端点，由前端在打开页面时发起请求，之后定时刷新。</p>
 *
 * <p>统计口径：</p>
 * <ul>
 *   <li>Eagler：{@code eagler-clients} 表的行数（与「Eagler 客户端」菜单列表数量一致），
 *       另附 {@code enabled} 供页面判断是否有启用项；</li>
 *   <li>云机：{@code cloud-clients} 表的行数（与「云游戏设备」菜单列表数量一致），
 *       {@code online} 按每行的运行态 {@code status=online} 统计。</li>
 * </ul>
 *
 * <p>只读端点，不修改数据；出错时返回 {@code code!=200}，前端保留上一次成功值。</p>
 */
@Anonymous
@ApiPublic
@RequestMapping("/play")
public class WebGamePlayStatsController {

    private final WebGame plugin;

    public WebGamePlayStatsController(WebGame plugin) {
        this.plugin = plugin;
    }

    @ApiName("玩家入口实时统计")
    @GetMapping("/stats")
    public AjaxResult stats() {
        try {
            WebGameDataStore store = plugin.getDataStore();
            if (store == null) {
                return AjaxResult.error("数据层未就绪");
            }

            List<ClientVersion> eaglerRows = store.select(ClientVersion.class);
            List<CloudClient> cloudRows = store.select(CloudClient.class);

            Map<String, Object> eagler = new LinkedHashMap<>();
            eagler.put("total", eaglerRows == null ? 0 : eaglerRows.size());
            eagler.put("enabled", countEnabledEagler(eaglerRows));

            Map<String, Object> cloud = new LinkedHashMap<>();
            cloud.put("total", cloudRows == null ? 0 : cloudRows.size());
            cloud.put("online", countOnlineCloud(cloudRows));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("eagler", eagler);
            data.put("cloud", cloud);
            return AjaxResult.success("ok", data);
        } catch (Throwable t) {
            return AjaxResult.error("统计失败：" + t.getMessage());
        }
    }

    private static int countEnabledEagler(List<ClientVersion> rows) {
        int count = 0;
        if (rows == null) {
            return count;
        }
        for (ClientVersion row : rows) {
            if (row != null && row.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    private static int countOnlineCloud(List<CloudClient> rows) {
        int count = 0;
        if (rows == null) {
            return count;
        }
        for (CloudClient row : rows) {
            if (row != null && CloudClient.STATUS_ONLINE.equals(row.getStatus())) {
                count++;
            }
        }
        return count;
    }
}
