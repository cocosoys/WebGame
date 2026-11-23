package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysExpansion;
import com.github.cocosoys.mc.webgame.WebGame;

import java.util.Collections;
import java.util.List;

/**
 * WebGame 的 SOYSHTTPOverMC 模块扩展：仅负责页面托管（{@code resourceRoot = "dist"}）。
 *
 * <p>页面统一挂在 {@code /web/plugins/WebGame}：jar 内 {@code dist/} 来自前端构建产物
 * （{@code web/dist}），包含 ERP 子应用与构建期静态化的 Eaglercraft 页面。
 * ERP 管理面端点由 {@link WebGameErpExpansion} 单独注册（仅当服务器装有 MCERP 时），
 * 其 {@code resourceRoot()} 返回 null，避免重复登记页面。</p>
 */
public class WebGameExpansion extends SoysExpansion {

    private final WebGame plugin;

    public WebGameExpansion(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "WebGame";
    }

    @Override
    public String getAuthor() {
        return "cocosoys";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 自包含前端 dist：磁盘优先（plugins/WebGame/dist），否则读 jar 内 dist。 */
    @Override
    protected String resourceRoot() {
        return "dist";
    }

    @Override
    protected List<Object> buildControllers() {
        // 上报端点与 ERP 无关：即使服务器没装 MCERP，云游戏客户端也要能上报
        if (plugin.getCloudClientService() == null || plugin.getCloudPlayService() == null) {
            return Collections.emptyList();
        }
        return java.util.Arrays.asList(
                new com.github.cocosoys.mc.webgame.spring.controller.WebGameCloudReportController(
                        plugin, plugin.getCloudClientService()),
                // 玩家"点一下就能玩"的会话端点（入口页/包装页都打这里）
                new com.github.cocosoys.mc.webgame.spring.controller.WebGameCloudSessionController(
                        plugin.getCloudPlayService()));
    }
}
