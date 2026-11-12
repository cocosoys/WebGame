package com.github.cocosoys.mc.webgame.spring.controller;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPermission;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.spring.service.WebGameConfigService;

import java.util.Map;

/**
 * ERP 管理面 —— 插件配置文件可视化编辑端点。路径：/api/plugins/WebGame/erp/config/*。
 *
 * <p>授权走 Bukkit 权限镜像（{@code webgame.erp.config.*}，OP 默认放行）；节点同时被
 * {@code ControllerPermScanner} 投影为 ERP 菜单下的 F 按钮。本类只声明接口并转发
 * {@link WebGameConfigService}。注意注解来自 {@code soyshttpovermc.annotations}，不是 Spring。</p>
 */
@RequestMapping("/erp/config")
public class WebGameConfigController {

    private final WebGameConfigService configService;

    public WebGameConfigController(WebGameConfigService configService) {
        this.configService = configService;
    }

    @ApiName("配置文件清单")
    @ApiPermission("webgame.erp.config.list")
    @GetMapping("/files")
    public AjaxResult files() {
        return configService.list();
    }

    @ApiName("配置文件读取")
    @ApiPermission("webgame.erp.config.query")
    @GetMapping("/load")
    public AjaxResult load(@RequestParam(name = "file", required = true) String file) {
        return configService.load(file);
    }

    @ApiName("配置文件保存")
    @ApiPermission("webgame.erp.config.edit")
    @PostMapping("/save")
    public AjaxResult save(@RequestParam(name = "file", required = true) String file,
                           @RequestBody Map<String, Object> data) {
        return configService.save(file, data);
    }
}
