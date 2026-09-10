package com.github.cocosoys.mc.webgame;

import com.github.cocosoys.mc.soyshttpovermc.HttpOverMcPlugin;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.web.EaglerPageRegistrar;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * WebGame 主类。
 *
 * <p>M1：页面托管 + 地址注入（SOYSHTTPOverMC 网关根路径托管 Eaglercraft HTML）。
 * M2：同端口 WS 隧道（WebGame 自装嗅探器首包分流 → 101 升级 → EaglerX 握手 → MC 回环登录）。</p>
 */
public final class WebGame extends JavaPlugin {

    private EaglerPageRegistrar pageRegistrar;
    private WsGateway wsGateway;

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
            getLogger().info("WebGame 已启用：页面 http://" + host + ":" + port + "/"
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
            wsGateway.install();
        } catch (Throwable t) {
            getLogger().severe("WebGame WS 隧道初始化失败：");
            t.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (wsGateway != null) {
            try {
                wsGateway.uninstall();
            } catch (Throwable ignored) {
            }
        }
        // SOYSHTTPOverMC 的 ApiLifecycleListener 会在本插件禁用时自动卸载名下页面
    }
}
