package com.github.cocosoys.mc.webgame;

import com.github.cocosoys.mc.soyshttpovermc.api.event.SoysReadyEvent;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.impl.WebGameClientServiceImpl;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;
import com.github.cocosoys.mc.webgame.shttp.WebGameClientPages;
import com.github.cocosoys.mc.webgame.shttp.WebGameErpExpansion;
import com.github.cocosoys.mc.webgame.shttp.WebGameExpansion;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * WebGame 主类。
 *
 * <p>页面托管 + 地址注入已改为构建期静态化：Eaglercraft HTML/JS 作为普通静态资源打进
 * 插件 {@code dist/}，由 {@link WebGameExpansion} 登记到 {@code /web/plugins/WebGame}
 * （页面内用 {@code location.host} 现场计算 WS 地址），运行时不再拷贝/改写文件。</p>
 *
 * <p>M2：同端口 WS 隧道（WebGame 自装嗅探器首包分流 → 101 升级 → EaglerX 握手 → MC 回环登录）。</p>
 *
 * <p>云游戏能力已从插件内移除，后续以 cloud-stream 方式接入。</p>
 */
public final class WebGame extends JavaPlugin implements Listener {

    private WebGameConfig webGameConfig;
    private WsGateway wsGateway;

    /** 页面托管扩展（/web/plugins/WebGame；网关未就绪时为 null）。 */
    private WebGameExpansion pageExpansion;

    /** MCERP 管理面扩展（ERP 菜单 + /erp/* 端点；未装 MCERP 时为 null）。 */
    private WebGameErpExpansion erpExpansion;

    /** 客户端版本服务（clients.yml + 上传）；WS 准入与会话归属都依赖它。 */
    private WebGameClientService clientService;

    /** 客户端版本页面热登记器（/eagler/ 选择页 + /eagler/&lt;id&gt;/）。 */
    private WebGameClientPages clientPages;

    /** 插件启用时刻（毫秒），用于 status 端点的 uptimeSeconds。 */
    private long enabledAt;

    @Override
    public void onEnable() {
        enabledAt = System.currentTimeMillis();
        saveDefaultConfig();
        webGameConfig = new WebGameConfig(this);

        if (!Bukkit.getPluginManager().isPluginEnabled("SOYSHTTPOverMC")) {
            getLogger().severe("SOYSHTTPOverMC 未加载，WebGame 无法工作，已禁用自身。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            int serverPort = getServer().getPort();
            wsGateway = new WsGateway(this,
                    webGameConfig.getWsPath(),
                    webGameConfig.getLoopbackHost(),
                    webGameConfig.getLoopbackPort(serverPort),
                    webGameConfig.isAllowV3(),
                    webGameConfig.isAllowV4(),
                    webGameConfig.getMinMcProtocol(),
                    webGameConfig.getMaxMcProtocol(),
                    webGameConfig.getServerBrand(),
                    webGameConfig.getServerVersion(),
                    webGameConfig.getMaxConnectionsPerDevice());
            wsGateway.install();
        } catch (Throwable t) {
            getLogger().severe("WebGame WS 隧道初始化失败：");
            t.printStackTrace();
        }

        // 客户端版本：释放内置客户端、读取 clients.yml，并把准入判定挂到 WS 网关
        try {
            clientService = new WebGameClientServiceImpl(this);
            clientPages = new WebGameClientPages(this, clientService);
            if (wsGateway != null) {
                wsGateway.setBuiltinClientId(clientService.builtinId());
                wsGateway.setClientGate(clientService::isServable);
            }
        } catch (Throwable t) {
            getLogger().severe("WebGame 客户端版本初始化失败：");
            t.printStackTrace();
        }

        // 主类自身作为监听器：承接 SoysReadyEvent / 软依赖晚启用，补挂扩展
        Bukkit.getPluginManager().registerEvents(this, this);
        syncSoysModules();

        File legacyDist = new File(getDataFolder(), "dist");
        if (legacyDist.isDirectory()) {
            getLogger().warning("检测到旧版运行时页面目录 " + legacyDist.getPath()
                    + "（运行时改写产物）。页面托管磁盘优先，会覆盖 jar 内新前端；请删除该目录后重启。");
        }

        int port = getServer().getPort();
        getLogger().info("WebGame 已启用：页面 http://" + webGameConfig.getPublicHost() + ":"
                + webGameConfig.getPublicPort(port) + webGameConfig.getPageFullUrl()
                + " | 隧道 ws://" + webGameConfig.getPublicHost() + ":"
                + webGameConfig.getPublicPort(port) + webGameConfig.getWsPath());
    }

    @Override
    public void onDisable() {
        if (clientPages != null) {
            clientPages.unpublish();
        }
        if (erpExpansion != null) {
            erpExpansion.unregister();
            erpExpansion = null;
        }
        if (pageExpansion != null) {
            pageExpansion.unregister();
            pageExpansion = null;
        }
        if (wsGateway != null) {
            try {
                wsGateway.uninstall();
            } catch (Throwable ignored) {
            }
            wsGateway = null;
        }
    }

    // ===== SOYS / MCERP 模块装配 =====

    /**
     * 同步 SOYS 侧模块注册状态（幂等，可重复调用；由启用、软依赖晚到、SoysReady 共用）。
     *
     * <ul>
     *   <li>{@link WebGameExpansion}：托管前端 {@code dist}（{@code /web/plugins/WebGame}）；</li>
     *   <li>{@link WebGameErpExpansion}：装有 MCERP 时注册菜单「网页游戏」+ {@code /erp/*} 端点。</li>
     * </ul>
     */
    private void syncSoysModules() {
        if (!Bukkit.getPluginManager().isPluginEnabled("SOYSHTTPOverMC")) {
            getLogger().warning("未检测到 SOYSHTTPOverMC，页面托管与 ERP 管理面未挂载。");
            return;
        }
        registerPageExpansion();
        registerErpExpansion();
    }

    /** 注册页面托管扩展（已注册则跳过）。 */
    private void registerPageExpansion() {
        if (pageExpansion != null) {
            return;
        }
        WebGameExpansion expansion = new WebGameExpansion(this);
        if (expansion.register()) {
            pageExpansion = expansion;
            // 页面扩展就绪后才能拿到 WebPageApi：登记版本目录与选择页
            if (clientPages != null) {
                clientPages.attach(expansion.getApi());
                if (clientService != null) {
                    clientService.publishPages();
                }
            }
            getLogger().info("前端页面托管已挂载：" + WebGameConfig.WEB_PLUGIN_PREFIX + "/");
        } else {
            getLogger().warning("页面托管注册失败（网关未就绪或 identifier 冲突），将由 SoysReadyEvent 重试。");
        }
    }

    /**
     * 注册 MCERP 管理面扩展（未装 MCERP 时静默跳过；已注册则跳过）。
     *
     * <p>{@code isPluginEnabled("MCERP")} 前置守卫必须先于对本类父级
     * {@code McerpExpansion} 的任何引用，避免未安装 MCERP 时出现 NoClassDefFoundError。</p>
     */
    private void registerErpExpansion() {
        if (erpExpansion != null) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("MCERP")) {
            getLogger().info("未检测到 MCERP，ERP 管理面未注册（安装 MCERP 后执行 /soyshttp reload 即可）。");
            return;
        }
        WebGameErpExpansion expansion = new WebGameErpExpansion(this);
        if (expansion.register()) {
            erpExpansion = expansion;
            getLogger().info("MCERP 管理面已接入：菜单「网页游戏」+ /api/plugins/WebGame/erp/*。");
        } else {
            getLogger().warning("MCERP 管理面注册失败（网关/ERP 未就绪或 identifier 冲突），"
                    + "可由 /mcerp reload 或 /soyshttp reload 兜底重试。");
        }
    }

    /** SOYS 网关就绪事件：解决「本插件先于 SOYS 加载」时的延迟注册。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSoysReady(SoysReadyEvent event) {
        syncSoysModules();
    }

    /** 软依赖晚于本插件启用时补挂（SOYSHTTPOverMC / MCERP）。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        String name = event.getPlugin().getName();
        if ("SOYSHTTPOverMC".equals(name) || "MCERP".equals(name)) {
            syncSoysModules();
        }
    }

    // ===== getters =====

    public WebGameConfig getWebGameConfig() {
        return webGameConfig;
    }

    public WsGateway getWsGateway() {
        return wsGateway;
    }

    public WebGameClientService getClientService() {
        return clientService;
    }

    public WebGameClientPages getClientPages() {
        return clientPages;
    }

    /** 已运行秒数。 */
    public long getUptimeSeconds() {
        return enabledAt <= 0 ? 0 : Math.max(0, (System.currentTimeMillis() - enabledAt) / 1000L);
    }
}
