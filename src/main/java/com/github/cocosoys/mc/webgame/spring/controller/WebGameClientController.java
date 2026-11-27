package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.McVersionOption;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ERP 管理面 —— Eaglercraft 客户端版本管理。路径：/api/plugins/WebGame/erp/clients/*。
 *
 * <p>授权走 Bukkit 权限镜像（{@code webgame.erp.clients.*}，OP 默认放行）；节点同时被
 * {@code ControllerPermScanner} 投影为 ERP 菜单下的 F 按钮。本类只声明接口并转发
 * {@link WebGameClientService}。</p>
 */
@RequestMapping("/erp/clients")
public class WebGameClientController {

    private final WebGameClientService clientService;

    public WebGameClientController(WebGameClientService clientService) {
        this.clientService = clientService;
    }

    @ApiName("客户端版本列表")
    @ApiPermission("webgame.erp.clients.list")
    @GetMapping("/list")
    public AjaxResult list() {
        return clientService.list();
    }

    @ApiName("Minecraft 版本枚举")
    @ApiPermission("webgame.erp.clients.list")
    @GetMapping("/mc-versions")
    public AjaxResult mcVersions() {
        return AjaxResult.success(McVersionOption.all());
    }

    @ApiName("客户端版本影响面")
    @ApiPermission("webgame.erp.clients.list")
    @GetMapping("/impact")
    public AjaxResult impact(@RequestParam(name = "id", required = true) String id) {
        return clientService.impact(id);
    }

    @ApiName("启用/关闭客户端版本")
    @ApiPermission("webgame.erp.clients.edit")
    @PostMapping("/enabled")
    public AjaxResult enabled(@RequestParam(name = "id", required = true) String id,
                              @RequestParam(name = "enabled", required = true) boolean enabled,
                              @RequestParam(name = "kick", required = false) boolean kick) {
        return clientService.setEnabled(id, enabled, kick);
    }

    @ApiName("设置默认客户端版本")
    @ApiPermission("webgame.erp.clients.edit")
    @PostMapping("/default")
    public AjaxResult setDefault(@RequestParam(name = "id", required = true) String id) {
        return clientService.setDefault(id);
    }

    @ApiName("删除客户端版本")
    @ApiPermission("webgame.erp.clients.delete")
    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam(name = "id", required = true) String id) {
        return clientService.delete(id);
    }

    @ApiName("保存客户端版本排序")
    @ApiPermission("webgame.erp.clients.edit")
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
        return clientService.reorder(list);
    }

    @ApiName("允许玩家自选客户端版本")
    @ApiPermission("webgame.erp.clients.edit")
    @PostMapping("/allow-player-select")
    public AjaxResult allowPlayerSelect(@RequestParam(name = "allow", required = true) boolean allow) {
        return clientService.setAllowPlayerSelect(allow);
    }

    @ApiName("新增 eagler 客户端")
    @ApiPermission("webgame.erp.clients.edit")
    @PostMapping("/create")
    public AjaxResult create(@RequestBody Map<String, Object> body) {
        return clientService.create(body);
    }

    @ApiName("修改 eagler 客户端")
    @ApiPermission("webgame.erp.clients.edit")
    @PostMapping("/update")
    public AjaxResult update(@RequestParam(name = "id", required = true) String id,
                             @RequestBody Map<String, Object> body) {
        return clientService.update(id, body);
    }
}
