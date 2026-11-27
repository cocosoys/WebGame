package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;
import com.github.cocosoys.mc.webgame.spring.service.ClientPackageService;

import java.util.Map;

/**
 * 兼容旧路径 /erp/cloud-packages/*：只暴露云游戏制品。
 * 新页面请使用 /erp/client-packages/*。
 */
@RequestMapping("/erp/cloud-packages")
public class WebGameCloudPackageController {

    private final ClientPackageService packageService;

    public WebGameCloudPackageController(ClientPackageService packageService) {
        this.packageService = packageService;
    }

    @ApiName("云游戏客户端包列表（兼容）")
    @ApiPermission("webgame.erp.cloudpackages.list")
    @GetMapping("/list")
    public AjaxResult list() {
        return packageService.list(ClientPackage.TYPE_CLOUD);
    }

    @ApiName("修改云游戏客户端包（兼容）")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/update")
    public AjaxResult update(@RequestParam(name = "id", required = true) String id,
                             @RequestBody Map<String, Object> body) {
        return packageService.update(id, body);
    }

    @ApiName("启用/停用云游戏客户端包（兼容）")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/enabled")
    public AjaxResult enabled(@RequestParam(name = "id", required = true) String id,
                              @RequestParam(name = "enabled", required = true) boolean enabled) {
        return packageService.setEnabled(id, enabled);
    }

    @ApiName("删除云游戏客户端包（兼容）")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam(name = "id", required = true) String id) {
        return packageService.delete(id);
    }
}
