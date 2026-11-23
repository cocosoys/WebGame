package com.github.cocosoys.mc.webgame;

import com.github.cocosoys.mc.soyshttpovermc.api.event.SoysReadyEvent;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.impl.WebGameClientServiceImpl;
import com.github.cocosoys.mc.webgame.spring.impl.CloudClientServiceImpl;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import com.github.cocosoys.mc.webgame.shttp.WebGameClientPages;
import com.github.cocosoys.mc.webgame.shttp.CloudClientPages;
import com.github.cocosoys.mc.webgame.shttp.WebGameErpExpansion;
import com.github.cocosoys.mc.webgame.shttp.WebGameExpansion;
import com.github.cocosoys.mc.webgame.web.cloud.CloudSessionRegistry;
import com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    /** 云游戏会话心跳注册表（cloud-stream 页面通过 /erp/session/heartbeat 上报）。 */
    private final CloudSessionRegistry cloudSessionRegistry = new CloudSessionRegistry();

    /** 页面托管扩展（/web/plugins/WebGame；网关未就绪时为 null）。 */
    private WebGameExpansion pageExpansion;

    /** MCERP 管理面扩展（ERP 菜单 + /erp/* 端点；未装 MCERP 时为 null）。 */
    private WebGameErpExpansion erpExpansion;

    /** 客户端版本服务（eagler-clients.yml + 上传）；WS 准入与会话归属都依赖它。 */
    private WebGameClientService clientService;

    /** 客户端版本页面热登记器（/eagler/ 选择页 + /eagler/&lt;id&gt;/）。 */
    private WebGameClientPages clientPages;

    /** 云游戏客户端服务（cloud-clients.yml；不涉及上传与 WS 协议）。 */
    private CloudClientService cloudClientService;

    /** 云游戏客户端页面热登记器（/cloud/ 选择页 + /cloud/&lt;id&gt;/）。 */
    private CloudClientPages cloudClientPages;

    /** 云游戏"点击即玩"会话服务（槽位占用 + 代理调用 + 看门狗）。 */
    private CloudPlayService cloudPlayService;

    /** 启动对齐只做一次（SOYS 重试注册会反复走 registerPageExpansion）。 */
    private boolean reconciled;

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

        // 客户端版本：释放内置客户端、读取 eagler-clients.yml，并把准入判定挂到 WS 网关
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

        // 云游戏客户端：独立元数据，仅页面托管，不接入 WS 网关
        try {
            cloudClientService = new CloudClientServiceImpl(this);
            cloudPlayService = new CloudPlayService(this, cloudClientService);
            cloudClientPages = new CloudClientPages(this, cloudClientService, cloudPlayService);
            // 90s 心跳：启动后立刻校准一轮，之后定时探测（离线阈值见 config.cloud.ttl-seconds）
            cloudClientService.startHeartbeat();
            // 会话看门狗：玩家在 MC 里退出后，会话必须被判定结束，否则桌面会解锁在玩家眼前
            cloudPlayService.startWatchdog();
            // 启动对齐：云主机上可能还跑着上一轮的会话，等页面托管就绪后对一次账
            scheduleReconcile(1);
            // 客户端品牌只做审计：1.12.2 的 Player API 没有 getClientBrandName()，
            // 只能靠 MC|Brand 入站通道；注册失败不影响任何判定。
            try {
                getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand",
                        (channel, player, message) -> {
                            if (cloudPlayService == null || player == null || message == null) {
                                return;
                            }
                            cloudPlayService.noteBrand(player.getName(),
                                    new String(message, java.nio.charset.StandardCharsets.UTF_8));
                        });
            } catch (Throwable t) {
                getLogger().warning("MC|Brand 审计通道注册失败（品牌不参与判定，可忽略）：" + t.getMessage());
            }
        } catch (Throwable t) {
            getLogger().severe("WebGame 云游戏客户端初始化失败：");
            t.printStackTrace();
        }

        // 管理指令：/webgame cloud status|refresh|key
        org.bukkit.command.PluginCommand webgameCommand = getCommand("webgame");
        if (webgameCommand != null) {
            com.github.cocosoys.mc.webgame.command.WebGameCommand executor =
                    new com.github.cocosoys.mc.webgame.command.WebGameCommand(this);
            webgameCommand.setExecutor(executor);
            webgameCommand.setTabCompleter(executor);
        } else {
            getLogger().warning("plugin.yml 缺少 webgame 指令声明，/webgame cloud 不可用");
        }

        // 主类自身作为监听器：承接 SoysReadyEvent，网关稍后就绪时补挂扩展
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
        try {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, "MC|Brand");
        } catch (Throwable ignored) {
        }
        if (cloudPlayService != null) {
            cloudPlayService.stopWatchdog();
        }
        if (cloudClientService != null) {
            cloudClientService.stopHeartbeat();
        }
        if (clientPages != null) {
            clientPages.unpublish();
        }
        if (cloudClientPages != null) {
            cloudClientPages.unpublish();
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
     * 同步 SOYS 侧模块注册状态（幂等，可重复调用；由启用与 SoysReady 共用）。
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
            if (cloudClientPages != null) {
                cloudClientPages.attach(expansion.getApi());
                if (cloudClientService != null) {
                    cloudClientService.publishPages();
                }
            }
            getLogger().info("前端页面托管已挂载：" + WebGameConfig.WEB_PLUGIN_PREFIX + "/");
        } else {
            getLogger().warning("页面托管注册失败（网关未就绪或 identifier 冲突），将由 SoysReadyEvent 重试。");
        }
    }

    /**
     * 注册 MCERP 管理面扩展（已注册则跳过）。
     */
    private void registerErpExpansion() {
        if (erpExpansion != null) {
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

    /**
     * 启动对齐：等页面托管就绪（否则开闸没有落点），再读各云主机代理的 /health，
     * 把"机器上还跑着、但内存里没记录"的会话恢复回来。最多重试 10 次（每 5 秒一次）。
     */
    private void scheduleReconcile(int tries) {
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
            if (cloudPlayService == null) {
                return;
            }
            if (cloudClientPages == null || !cloudClientPages.isAttached()) {
                if (tries < 10) {
                    scheduleReconcile(tries + 1);
                }
                return;
            }
            if (reconciled) {
                return;
            }
            reconciled = true;
            try {
                cloudPlayService.reconcileFromAgents();
            } catch (Throwable t) {
                getLogger().warning("云会话启动对齐失败：" + t);
            }
        }, 100L);
    }

    /**
     * 云游戏进服判定入口：这里只把事件递给会话服务，判定规则（来源 IP 必须是该会话
     * 那台云机的地址、回环永不判云）全部收在 {@link CloudPlayService} 里，避免两处口径漂移。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCloudPlayerJoin(PlayerJoinEvent event) {
        CloudPlayService svc = cloudPlayService;
        Player p = event == null ? null : event.getPlayer();
        if (svc == null || p == null) {
            return;
        }
        svc.onPlayerJoin(p.getName(), addressOf(p), svc.brandOf(p.getName()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCloudPlayerQuit(PlayerQuitEvent event) {
        CloudPlayService svc = cloudPlayService;
        Player p = event == null ? null : event.getPlayer();
        if (svc == null || p == null) {
            return;
        }
        svc.onPlayerQuit(p.getName(), addressOf(p));
    }

    /** 玩家来源地址（IPv4/IPv6 字符串）；拿不到就返回空串，判定时按"非云机"处理。 */
    private static String addressOf(Player p) {
        try {
            if (p.getAddress() != null && p.getAddress().getAddress() != null) {
                return p.getAddress().getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ===== getters =====

    public WebGameConfig getWebGameConfig() {
        return webGameConfig;
    }

    public WsGateway getWsGateway() {
        return wsGateway;
    }

    public CloudSessionRegistry getCloudSessionRegistry() {
        return cloudSessionRegistry;
    }

    public WebGameClientService getClientService() {
        return clientService;
    }

    public WebGameClientPages getClientPages() {
        return clientPages;
    }

    public CloudClientService getCloudClientService() {
        return cloudClientService;
    }

    public CloudClientPages getCloudClientPages() {
        return cloudClientPages;
    }

    public CloudPlayService getCloudPlayService() {
        return cloudPlayService;
    }

    /** 已运行秒数。 */
    public long getUptimeSeconds() {
        return enabledAt <= 0 ? 0 : Math.max(0, (System.currentTimeMillis() - enabledAt) / 1000L);
    }
}
