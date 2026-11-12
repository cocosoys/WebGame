package com.github.cocosoys.mc.webgame.shttp;

import com.github.cocosoys.mc.mcerp.ErpMenus;
import com.github.cocosoys.mc.mcerp.McerpExpansion;
import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.controller.WebGameConfigController;
import com.github.cocosoys.mc.webgame.spring.controller.WebGameSessionController;
import com.github.cocosoys.mc.webgame.spring.controller.WebGameStatusController;
import com.github.cocosoys.mc.webgame.spring.impl.WebGameConfigServiceImpl;
import com.github.cocosoys.mc.webgame.spring.impl.WebGameSessionServiceImpl;
import com.github.cocosoys.mc.webgame.spring.impl.WebGameStatusServiceImpl;
import com.github.cocosoys.mc.webgame.spring.service.WebGameConfigService;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;
import com.github.cocosoys.mc.webgame.spring.service.WebGameStatusService;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * WebGame 的 MCERP 管理面接入（ERP 端点 + 菜单登记）。
 *
 * <p>与页面托管扩展（{@link WebGameExpansion}）的分工：</p>
 * <ul>
 *   <li>本扩展仅在服务器装有 MCERP 时由主类实例化并注册；</li>
 *   <li>端点落在 {@code /api/plugins/WebGame/erp/*}（owner 插件名决定前缀），
 *       认证走网关登录态，授权走 {@code webgame.erp.*} 权限镜像；</li>
 *   <li>{@link #menus()} 声明菜单树，{@code menu(..., Controller.class)} 由
 *       {@code ControllerPermScanner} 把 {@code @ApiPermission} 端点投影为 F 按钮。</li>
 * </ul>
 *
 * <p><b>页面归属</b>：前端 dist 由 {@link WebGameExpansion} 托管在
 * {@code /web/plugins/WebGame}，本扩展 {@link #resourceRoot()} 返回 null；
 * 框架默认 {@code pageRoot()} 取 identifier（{@code WebGame-ERP}）会得到不存在的路径，
 * 故覆写为 owner 插件名。{@code pageDirRoot} 沿用父类默认
 * （{@code /web/plugins/WebGame/webgameerp}），与前端 moduleRoot 顶层段对齐。</p>
 */
public final class WebGameErpExpansion extends McerpExpansion {

    private final WebGame plugin;

    /** 缓存的控制器实例列表（register/unregister 共用同一来源）。 */
    private List<Object> controllers;

    public WebGameErpExpansion(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "WebGame-ERP";
    }

    @Override
    public String getAuthor() {
        return "cocosoys";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** 页面前缀 = owner 插件自己的命名空间（{@code /web/plugins/WebGame}），与 dist 托管路径一致。 */
    @Override
    public String pageRoot() {
        Plugin owner = getOwner();
        String name = owner == null ? "WebGame" : owner.getName();
        SoysHttpOverMcApi api = getApi();
        String prefix = api == null ? null : api.getToolkit().pageFullPrefix(name);
        if (prefix == null || prefix.isEmpty()) {
            return "/web/plugins/" + name;
        }
        return prefix;
    }

    /** 不托管页面：dist 由 {@link WebGameExpansion} 统一托管，避免重复登记与 tag 分裂。 */
    @Override
    protected String resourceRoot() {
        return null;
    }

    // ===== ERP 声明钩子 =====

    @Override
    protected String displayName() {
        return "网页游戏";
    }

    @Override
    protected String icon() {
        return "monitor";
    }

    @Override
    protected int sortOrder() {
        return 90;
    }

    @Override
    protected String permission() {
        return "webgame.erp";
    }

    /**
     * 菜单树：运行概览、会话监控、插件配置（目录 → 核心配置）。
     *
     * <p>component 由框架自动拼 {@code pageDirRoot + "/" + menuId}，即
     * {@code /web/plugins/WebGame/webgameerp/overview}、{@code …/session}、
     * {@code …/config/config}，与前端路由同源。</p>
     */
    @Override
    protected ErpMenus menus() {
        return ErpMenus.create()
                .menu("overview", "运行概览", "dashboard", WebGameStatusController.class)
                .orderNum(1)
                .menu("session", "会话监控", "online", WebGameSessionController.class)
                .orderNum(2)
                .dir("config", "插件配置", "edit", d -> d
                        .menu("config", "核心配置", "input", WebGameConfigController.class)
                        .orderNum(1))
                .orderNum(10);
    }

    // ===== 端点注册（ERP 管理面） =====

    @Override
    protected List<Object> buildControllers() {
        if (controllers == null) {
            WebGameStatusService statusService = new WebGameStatusServiceImpl(plugin);
            WebGameSessionService sessionService = new WebGameSessionServiceImpl(plugin);
            WebGameConfigService configService = new WebGameConfigServiceImpl(plugin);
            controllers = new ArrayList<>();
            controllers.add(new WebGameStatusController(statusService));
            controllers.add(new WebGameSessionController(sessionService));
            controllers.add(new WebGameConfigController(configService));
        }
        return controllers;
    }
}
