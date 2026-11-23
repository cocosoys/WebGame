package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ERP 管理面 —— 云游戏客户端。路径：/api/plugins/WebGame/erp/cloud-clients/*。
 *
 * <p>授权走 Bukkit 权限镜像（{@code webgame.erp.cloudclients.*}，OP 默认放行）。</p>
 */
@RequestMapping("/erp/cloud-clients")
public class WebGameCloudClientController {

    private final CloudClientService cloudClientService;
    private final CloudPlayService playService;

    public WebGameCloudClientController(CloudClientService cloudClientService, CloudPlayService playService) {
        this.cloudClientService = cloudClientService;
        this.playService = playService;
    }

    @ApiName("云游戏客户端列表")
    @ApiPermission("webgame.erp.cloudclients.list")
    @GetMapping("/list")
    public AjaxResult list() {
        return cloudClientService.list();
    }

    @ApiName("启用/关闭云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/enabled")
    public AjaxResult enabled(@RequestParam(name = "id", required = true) String id,
                              @RequestParam(name = "enabled", required = true) boolean enabled) {
        return cloudClientService.setEnabled(id, enabled);
    }

    @ApiName("设置默认云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/default")
    public AjaxResult setDefault(@RequestParam(name = "id", required = true) String id) {
        return cloudClientService.setDefault(id);
    }

    @ApiName("删除云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.delete")
    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam(name = "id", required = true) String id) {
        return cloudClientService.delete(id);
    }

    @ApiName("保存云游戏客户端排序")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/order")
    public AjaxResult order(@RequestBody Map<String, Object> body) {
        Object ids = body == null ? null : body.get("ids");
        if (!(ids instanceof List)) {
            return AjaxResult.error("缺少排序列表");
        }
        List<String> list = new ArrayList<>();
        for (Object o : (List<?>) ids) {
            if (o != null) {
                list.add(String.valueOf(o));
            }
        }
        return cloudClientService.reorder(list);
    }

    @ApiName("允许玩家自选云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/allow-player-select")
    public AjaxResult allowPlayerSelect(@RequestParam(name = "allow", required = true) boolean allow) {
        return cloudClientService.setAllowPlayerSelect(allow);
    }

    @ApiName("新增云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody Map<String, Object> body) {
        return cloudClientService.create(body);
    }

    @ApiName("修改云游戏客户端")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/update")
    public AjaxResult update(@RequestParam(name = "id", required = true) String id,
                             @RequestBody Map<String, Object> body) {
        return cloudClientService.update(id, body);
    }

    @ApiName("刷新云游戏客户端状态")
    @ApiPermission("webgame.erp.cloudclients.refresh")
    @PostMapping("/refresh")
    public AjaxResult refresh(@RequestParam(name = "id", required = true) String id) {
        return cloudClientService.refresh(id);
    }

    @ApiName("刷新全部云游戏客户端状态")
    @ApiPermission("webgame.erp.cloudclients.refresh")
    @PostMapping("/refresh-all")
    public AjaxResult refreshAll() {
        return cloudClientService.refreshAll();
    }

    @ApiName("更新云游戏客户端共享密钥")
    @ApiPermission("webgame.erp.cloudclients.refresh")
    @PostMapping("/key")
    public AjaxResult key(@RequestParam(name = "id", required = true) String id,
                          @RequestParam(name = "key", required = true) String key) {
        return cloudClientService.applyKey(id, key);
    }

    /**
     * 下发客户端包更新：把 config.yml 里的期望版本推给指定机器（后台下载/解包，立刻返回）。
     * <p>与玩家点击触发的更新是同一条链路，只是发起者从玩家换成了管理员。</p>
     */
    @ApiName("下发云游戏客户端更新")
    @ApiPermission("webgame.erp.cloudclients.edit")
    @PostMapping("/push-update")
    public AjaxResult pushUpdate(@RequestParam(name = "id", required = true) String id) {
        if (playService == null) {
            return AjaxResult.error("云游戏会话服务未就绪");
        }
        return playService.pushUpdate(id);
    }
}
