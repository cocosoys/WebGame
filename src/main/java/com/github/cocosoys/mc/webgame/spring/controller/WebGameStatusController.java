package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.WebGameStatusService;

/**
 * ERP 管理面 —— 运行概览端点。路径：/api/plugins/WebGame/erp/status/*。
 *
 * <p>只读，不涉及数据库；认证走网关原生体系（ERP 登录态），
 * 授权走 Bukkit 权限镜像 {@code webgame.erp.status.*}。</p>
 */
@RequestMapping("/erp/status")
public class WebGameStatusController {

    private final WebGameStatusService statusService;

    public WebGameStatusController(WebGameStatusService statusService) {
        this.statusService = statusService;
    }

    @ApiName("运行概览")
    @ApiPermission("webgame.erp.status.view")
    @GetMapping("/overview")
    public AjaxResult overview() {
        return statusService.overview();
    }
}
