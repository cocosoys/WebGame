package com.github.cocosoys.mc.webgame;

import com.github.cocosoys.mc.soyshttpovermc.HttpOverMcPlugin;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.control.ControlClient;
import com.github.cocosoys.mc.webgame.control.InstanceRegistry;
import com.github.cocosoys.mc.webgame.web.EaglerPageRegistrar;
import com.github.cocosoys.mc.webgame.web.admin.AdminController;
import com.github.cocosoys.mc.webgame.web.admin.AdminExecutor;
import com.github.cocosoys.mc.webgame.web.admin.AdminSubCommand;
import com.github.cocosoys.mc.webgame.web.admin.CloudAdminSubCommands;
import com.github.cocosoys.mc.webgame.web.cloud.CloudCapacityController;
import com.github.cocosoys.mc.webgame.web.cloud.CloudPageRegistrar;
import com.github.cocosoys.mc.webgame.web.cloud.CloudSessionManager;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WebGame 主类。
 *
 * <p>M1：页面托管 + 地址注入（SOYSHTTPOverMC 网关根路径托管 Eaglercraft HTML）。
 * M2：同端口 WS 隧道（WebGame 自装嗅探器首包分流 → 101 升级 → EaglerX 握手 → MC 回环登录）。
 * Cloud：云游戏通道（路线 C：插件为控制面，经管控契约驱动执行面控制客户端；
 * 执行面在 WSL2 容器内跑 Xvnc + Forge 客户端 + KasmVNC，浏览器经 25574 信令
 * 获取采集端点后 v1 直连、S2 起由插件 25574 反代）。</p>
 */
public final class WebGame extends JavaPlugin {

    private EaglerPageRegistrar pageRegistrar;
    private WsGateway wsGateway;
    private CloudSessionManager cloudManager;
    private ControlClient controlClient;
    private InstanceRegistry instanceRegistry;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        WebGameConfig cfg = new WebGameConfig(this);

        HttpOverMcPlugin soys = HttpOverMcPlugin.getInstance();
        if (soys == null) {
            getLogger().severe("SOYSHTTPOverMC 未加载，WebGame 无法工作，已禁用自身。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            pageRegistrar = new EaglerPageRegistrar(this, cfg, soys.getApi());
            pageRegistrar.install();
            int port = getServer().getPort();
            String host = cfg.getPublicHost();
            getLogger().info("WebGame 已启用：页面 http://" + host + ":" + port + cfg.getIndexFullUrl()
                    + " | 隧道 ws://" + host + ":" + port + cfg.getWsPath());
        } catch (Exception e) {
            getLogger().severe("WebGame 页面初始化失败：");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            int serverPort = getServer().getPort();
            wsGateway = new WsGateway(this,
                    cfg.getWsPath(),
                    cfg.getLoopbackHost(),
                    cfg.getLoopbackPort(serverPort),
                    cfg.isAllowV3(),
                    cfg.isAllowV4(),
                    cfg.getMinMcProtocol(),
                    cfg.getMaxMcProtocol(),
                    cfg.getServerBrand(),
                    cfg.getServerVersion(),
                    cfg.getMaxConnectionsPerDevice());

            // Cloud 云游戏通道（路线 C）：管控契约 + 实例黑盒路由
            if (cfg.isCloudEnabled()) {
                int port = getServer().getPort();

                // 管控契约客户端（连接执行面控制客户端）与实例注册表
                instanceRegistry = new InstanceRegistry(this);
                controlClient = new ControlClient(this,
                        cfg.getControlHost(), cfg.getControlPort(), instanceRegistry, null);
                controlClient.start();

                cloudManager = new CloudSessionManager(this, cfg, wsGateway,
                        controlClient, instanceRegistry);
                instanceRegistry.setListener(cloudManager);

                wsGateway.setCloudEndpoint(cfg.getCloudWsPath(), cfg, cloudManager);
                cloudManager.install();
                new CloudPageRegistrar(this, cfg, soys.getApi()).install();
                // 设备选择页容量接口（静态 min + 已用数 + 预置型号）
                try {
                    soys.getApi().getApiRegistration()
                            .registerController(new CloudCapacityController(cfg, cloudManager), this);
                    getLogger().info("WebGame 设备容量接口已注册 /api/plugins/WebGame/devices/capacity");
                } catch (Throwable t) {
                    getLogger().warning("WebGame 设备容量接口注册失败：" + t);
                }
                getLogger().info("WebGame 云游戏通道已启用：页面 http://" + cfg.getPublicHost() + ":" + port + cfg.getCloudFullUrl()
                        + " | 信令 ws://" + cfg.getPublicHost() + ":" + port + cfg.getCloudWsPath()
                        + " | 执行面 " + cfg.getControlHost() + ":" + cfg.getControlPort());
            }

            wsGateway.install();

            // 管理员控制台（SOYS auth + 仅 OP）：/api/admin/* 非豁免路径由网关鉴权
            try {
                installAdmin(soys, cfg, cloudManager);
            } catch (Throwable t) {
                getLogger().warning("WebGame 管理员控制台注册失败：" + t);
            }
        } catch (Throwable t) {
            getLogger().severe("WebGame WS 隧道/云游戏初始化失败：");
            t.printStackTrace();
        }
    }

    /**
     * 安装管理员控制台：入口页 + 受保护 API（/api/admin/*）+ 游戏内 OP 入口指令。
     */
    private void installAdmin(HttpOverMcPlugin soys, WebGameConfig cfg,
                              CloudSessionManager cloudManager) {
        AdminExecutor adminExecutor = new AdminExecutor();

        // 1) 受保护 API：主插件代理注册（无 /plugins/WebGame 前缀，落在非豁免 /api/admin/*）
        soys.getApi().getApiRegistration()
                .registerProxyController(new AdminController(adminExecutor, cfg, cloudManager));
        getLogger().info("WebGame 管理员 API 已注册（SOYS auth 保护）: /api/admin/*");

        // 2) 前端认证组件（复用 SOYS 的 soys-auth.js，登录弹窗/令牌管理/401 自动重试）
        //    registerPage 自动挂到 /web/plugins/WebGame/soys-auth.js（页面标准命名空间）
        try (java.io.InputStream ain = getResource("dist/soys-auth.js")) {
            if (ain != null) {
                soys.getApi().getWebPage()
                        .registerPage(this, "/soys-auth.js", "GET",
                                readAll(ain), "application/javascript; charset=utf-8", true,
                                "SOYS 前端认证组件", null);
            } else {
                getLogger().warning("dist/soys-auth.js 资源缺失");
            }
        } catch (Exception e) {
            getLogger().warning("注册 soys-auth.js 失败: " + e);
        }

        // 3) 入口页（HTML 壳公开加载，实际数据经 /api/admin/* 鉴权）
        try (java.io.InputStream in = getResource("dist/admin.html")) {
            if (in != null) {
                byte[] html = readAll(in);
                soys.getApi().getWebPage()
                        .registerPage(this, "/admin/", "GET", html,
                                "text/html; charset=utf-8", true,
                                "WebGame 管理员控制台", null);
                getLogger().info("WebGame 管理员入口页已注册: " + WebGameConfig.WEB_PLUGIN_PREFIX + "/admin/");
            } else {
                getLogger().warning("dist/admin.html 资源缺失，管理员入口页未注册");
            }
        } catch (Exception e) {
            getLogger().warning("注册管理员入口页失败: " + e);
        }

        // 4) 游戏内入口引导（/soyshttp webadmin，SubCommand requireOp 默认 true）
        soys.getApi().getExtension().registerSubCommand(new AdminSubCommand(soys));
        getLogger().info("WebGame 管理员指令已注册: /soyshttp webadmin");

        // 5) 云游戏容器管理指令（cloudstatus / cloudkick / cloudban / cloudunban，仅 OP）
        soys.getApi().getExtension().registerSubCommand(
                new CloudAdminSubCommands.CloudStatusCommand(soys, cloudManager));
        soys.getApi().getExtension().registerSubCommand(
                new CloudAdminSubCommands.CloudKickCommand(soys, cloudManager));
        soys.getApi().getExtension().registerSubCommand(
                new CloudAdminSubCommands.CloudBanCommand(soys, cloudManager));
        soys.getApi().getExtension().registerSubCommand(
                new CloudAdminSubCommands.CloudUnbanCommand(soys, cloudManager));
        getLogger().info("WebGame 云游戏容器指令已注册: /soyshttp cloudstatus|cloudkick|cloudban|cloudunban");
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    @Override
    public void onDisable() {
        if (cloudManager != null) {
            try {
                cloudManager.uninstall();
            } catch (Throwable ignored) {
            }
        }
        if (controlClient != null) {
            try {
                controlClient.stop();
            } catch (Throwable ignored) {
            }
        }
        if (wsGateway != null) {
            try {
                wsGateway.uninstall();
            } catch (Throwable ignored) {
            }
        }
        // SOYSHTTPOverMC 的 ApiLifecycleListener 会在本插件禁用时自动卸载名下页面
    }
}
