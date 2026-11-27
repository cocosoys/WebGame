package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.ClientPackageService;

import java.util.Map;

/**
 * ERP 管理面 —— 统一客户端制品库。
 * 路径：/api/plugins/WebGame/erp/client-packages/*
 */
@RequestMapping("/erp/client-packages")
public class WebGameClientPackageController {

    private final ClientPackageService packageService;

    public WebGameClientPackageController(ClientPackageService packageService) {
        this.packageService = packageService;
    }

    @ApiName("客户端制品列表")
    @ApiPermission("webgame.erp.cloudpackages.list")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(name = "type", required = false) String type) {
        return packageService.list(type);
    }

    @ApiName("修改客户端制品")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/update")
    public AjaxResult update(@RequestParam(name = "id", required = true) String id,
                             @RequestBody Map<String, Object> body) {
        return packageService.update(id, body);
    }

    @ApiName("启用/停用客户端制品")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/enabled")
    public AjaxResult enabled(@RequestParam(name = "id", required = true) String id,
                              @RequestParam(name = "enabled", required = true) boolean enabled) {
        return packageService.setEnabled(id, enabled);
    }

    @ApiName("删除客户端制品")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/delete")
    public AjaxResult delete(@RequestParam(name = "id", required = true) String id) {
        return packageService.delete(id);
    }

    @ApiName("开始上传客户端制品")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/upload/init")
    public AjaxResult uploadInit(@RequestBody Map<String, Object> body) {
        return packageService.uploadInit(body);
    }

    @ApiName("上传客户端制品分块")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/upload/chunk")
    public AjaxResult uploadChunk(@RequestParam(name = "uploadId", required = true) String uploadId,
                                  @RequestBody Map<String, Object> body) {
        return packageService.uploadChunk(uploadId, body);
    }

    @ApiName("完成客户端制品上传")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/upload/finish")
    public AjaxResult uploadFinish(@RequestParam(name = "uploadId", required = true) String uploadId) {
        return packageService.uploadFinish(uploadId);
    }

    @ApiName("放弃客户端制品上传")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/upload/abort")
    public AjaxResult uploadAbort(@RequestParam(name = "uploadId", required = true) String uploadId) {
        return packageService.uploadAbort(uploadId);
    }

    @ApiName("登记 Eagler 外链客户端")
    @ApiPermission("webgame.erp.cloudpackages.edit")
    @PostMapping("/external")
    public AjaxResult registerExternal(@RequestBody Map<String, Object> body) {
        return packageService.registerExternal(body);
    }
}
