package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.control.InstanceHandle;
import com.github.cocosoys.mc.webgame.control.InstanceRegistry;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地执行后端（v0.5 定案，S2）：插件内直接 spawn 游戏实例（进程级多实例）。
 *
 * <p>把路线 C 遗留的三件套（{@link GameInstance} 镜像驱动 + {@link EncoderProcess}
 * 窗口采集编码 + {@link InputInjector} 进程内注入）复活着线，作为
 * {@link CloudSessionManager} 的默认执行后端（config: cloud.executor=local）：</p>
 *
 * <pre>
 * 浏览器 ⇅ WS /cloud（CloudProtocol 帧）
 *    ↓ 会话建立（限流/封禁/同名）→ backend.spawn
 * GameInstance（-javaagent input-agent.jar 随客户端 JVM 起，进服后回调 onReady）
 *    ↓ onReady → 状态机 READY → EncoderProcess 启动（gdigrab 窗口采集 → libx264）
 *    ↓ → onConfig/onFrame 经 CloudSessionManager 转发浏览器
 * InputInjector（agent 注入器）：onGameReady 读取 input-agent.port 建立 TCP 通道
 *    ↑ forwardInput（浏览器键鼠事件 → LWJGL 队列）
 * </pre>
 *
 * <p>进程级纵深加固（ADR-13）随实例目录隔离已落地（junction 共享 + 独立 gameDir）；
 * 受限令牌/低完整性等 Windows 加固留待镜像构建（S4）阶段并入实例启动封装。</p>
 */
public final class LocalBackend implements ExecutorBackend {

    private final JavaPlugin plugin;
    private final WebGameConfig config;
    private final CloudSessionManager manager;
    private final InstanceRegistry registry;

    /** instanceId → 本地实例上下文（并发安全）。 */
    private final Map<String, Ctx> contexts = new ConcurrentHashMap<>();

    /** 单实例运行时上下文：游戏进程 + 编码器 + 注入器 + 所属会话。 */
    private static final class Ctx {
        final CloudImage img;
        final CloudSession session;
        final GameInstance game;
        final InputInjector injector;
        volatile EncoderProcess encoder;
        volatile boolean killed;

        Ctx(CloudImage img, CloudSession session, GameInstance game, InputInjector injector) {
            this.img = img;
            this.session = session;
            this.game = game;
            this.injector = injector;
        }
    }

    public LocalBackend(JavaPlugin plugin, WebGameConfig config,
                        CloudSessionManager manager, InstanceRegistry registry) {
        this.plugin = plugin;
        this.config = config;
        this.manager = manager;
        this.registry = registry;
    }

    @Override
    public InstanceHandle spawn(CloudSession session, CloudImage img, SpawnParams params) {
        InstanceHandle h = session.getInstance();
        if (h == null || session.isClosed()) {
            return null;
        }
        String id = h.getInstanceId();
        Ctx old = contexts.get(id);
        if (old != null && old.game.isAlive() && !old.killed) {
            // 幂等：resume / SPAWN 补发时实例仍存活 → 复用（不重启客户端）
            return h;
        }
        if (old != null) {
            // 残留死上下文（进程已退出但回调未及清理）：回收后重建
            teardown(old);
            contexts.remove(id, old);
        }

        InputInjector injector = createInjector();
        GameInstance game = new GameInstance(img, session.getUsername(),
                plugin.getServer().getPort(), session.getXmx(),
                new GameInstance.Listener() {
                    @Override
                    public void onReady(String windowTitle) {
                        onGameReady(id, windowTitle);
                    }

                    @Override
                    public void onStopped(String reason) {
                        onGameStopped(id, reason);
                    }

                    @Override
                    public void onUnavailable(String reason) {
                        onGameUnavailable(id, reason);
                    }
                });
        Ctx ctx = new Ctx(img, session, game, injector);
        contexts.put(id, ctx);
        game.start();
        plugin.getLogger().info("Cloud 本地执行面 spawn instance=" + id
                + " user=" + session.getUsername() + " image=" + img.getId()
                + " xmx=" + session.getXmx() + " inject=" + config.getCloudInputInject());
        return h;
    }

    @Override
    public void kill(String instanceId) {
        Ctx ctx = contexts.remove(instanceId);
        if (ctx == null) {
            return;
        }
        ctx.killed = true;
        teardown(ctx);
        registry.remove(instanceId);
    }

    @Override
    public void forwardInput(String instanceId, byte[] eventsBody) {
        Ctx ctx = contexts.get(instanceId);
        if (ctx == null || ctx.killed || eventsBody == null || eventsBody.length == 0) {
            return;
        }
        // eventsBody = [u16 count][events...]（0x10 头已在 CloudFrameHandler 剥离）；
        // CloudProtocol.parseInput 期望首字节为类型 → 拼回 0x10 复用同一解析器
        byte[] payload = new byte[eventsBody.length + 1];
        payload[0] = (byte) CloudProtocol.TYPE_INPUT;
        System.arraycopy(eventsBody, 0, payload, 1, eventsBody.length);
        List<CloudProtocol.InputEvent> events = CloudProtocol.parseInput(payload);
        if (!events.isEmpty()) {
            ctx.injector.dispatch(events);
        }
    }

    @Override
    public void reportStatus() {
        // 本机快照（兼容 InstanceRegistry.machineSnapshot JSON 格式，供管理员面板）
        Runtime rt = Runtime.getRuntime();
        long totalMb = rt.maxMemory() / 1048576L;
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
        long freeMb = rt.freeMemory() / 1048576L;
        long availMb = totalMb - usedMb;
        File root = new File(".");
        long dTotal = root.getTotalSpace();
        long dFree = root.getUsableSpace();
        long dUsed = dTotal - dFree;
        String diskJson = "{\"size\":\"" + fmtSize(dTotal) + "\",\"used\":\"" + fmtSize(dUsed)
                + "\",\"avail\":\"" + fmtSize(dFree) + "\",\"usePct\":\""
                + (dTotal > 0 ? Math.round(dUsed * 100.0 / dTotal) : 0) + "%\"}";
        String memJson = "{\"totalMb\":" + totalMb + ",\"usedMb\":" + usedMb
                + ",\"freeMb\":" + freeMb + ",\"availMb\":" + availMb + "}";
        InstanceRegistry.machineSnapshot = "{\"procs\":" + contexts.size()
                + ",\"mem\":" + memJson
                + ",\"disk\":" + diskJson
                + ",\"load\":\"" + contexts.size() + "\",\"ccPid\":0}";
    }

    @Override
    public String type() {
        return "local";
    }

    /** 关闭全部实例（插件卸载 / 会话管理 uninstall 时调用）。 */
    public void closeAll() {
        Iterator<Map.Entry<String, Ctx>> it = contexts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Ctx> e = it.next();
            e.getValue().killed = true;
            teardown(e.getValue());
            it.remove();
        }
    }

    // ================= 游戏实例回调 =================

    /** 客户端进服（窗口可采集）：驱动状态机 READY → 启动编码器 + 注入器通道。 */
    private void onGameReady(String id, String windowTitle) {
        Ctx ctx = contexts.get(id);
        if (ctx == null || ctx.killed) {
            return;
        }
        InstanceHandle h = registry.get(id);
        if (h == null) {
            return;
        }
        // 1. 状态机 → notifyReady → CloudSessionManager.onInstanceReady → 浏览器收到就绪信令
        registry.markReady(h, "", windowTitle == null ? "" : windowTitle);
        // 2. 启动编码器（gdigrab 窗口采集 → libx264，scale 按会话请求/服务端 config）
        int w = ctx.session.getReqWidth();
        int rh = ctx.session.getReqHeight();
        String scale = (w > 0 && rh > 0)
                ? w + "x" + rh : config.getCloudScale();
        // ffmpeg 优先取镜像内 tools/ffmpeg.exe（镜像自包含），回落 config cloud.ffmpeg-path
        java.io.File ffmpegExe = ctx.img.resolveFfmpeg();
        String ffmpegPath = ffmpegExe != null
                ? ffmpegExe.getAbsolutePath() : config.getCloudFfmpegPath();
        EncoderProcess enc = new EncoderProcess(
                ffmpegPath,
                config.getCloudCapture(),
                scale,
                ctx.session.getFps(),
                config.getCloudBitrateKbps(),
                config.getCloudGopFrames(),
                windowTitle,
                new EncoderProcess.Listener() {
                    @Override
                    public void onConfig(byte[] sps, byte[] pps, int width, int height, int fps) {
                        InstanceHandle hh = registry.get(id);
                        if (hh != null) {
                            manager.onVideoConfig(hh, CloudProtocol.buildConfig(sps, pps, width, height, fps));
                        }
                    }

                    @Override
                    public void onFrame(long tsMs, boolean keyFrame, byte[] annexB) {
                        InstanceHandle hh = registry.get(id);
                        if (hh != null) {
                            manager.onVideoFrame(hh, CloudProtocol.buildVideo(tsMs, keyFrame, annexB));
                        }
                    }

                    @Override
                    public void onUnavailable(String reason) {
                        plugin.getLogger().warning("Cloud 编码器不可用 instance=" + id + ": " + reason);
                    }

                    @Override
                    public void onStopped(String reason) {
                        plugin.getLogger().warning("Cloud 编码器已退出 instance=" + id + ": " + reason);
                    }
                });
        ctx.encoder = enc;
        enc.start();
        // 3. 输入注入通道（agent 注入器等待 input-agent.port 并建立 TCP 连接）
        try {
            ctx.injector.onGameReady(ctx.game.getInstanceDir());
        } catch (Throwable t) {
            plugin.getLogger().warning("Cloud 输入注入器通道建立失败 instance=" + id + ": " + t);
        }
    }

    /** 客户端进程退出（非主动 kill）：通知会话 → 关闭。 */
    private void onGameStopped(String id, String reason) {
        Ctx ctx = contexts.remove(id);
        if (ctx == null || ctx.killed) {
            return;
        }
        InstanceHandle h = registry.get(id);
        if (h != null) {
            // → notifyStopped → CloudSessionManager.onInstanceStopped → 错误帧 + 会话关闭
            registry.markStopped(h, reason == null ? "客户端进程已退出" : reason);
        }
    }

    /** 客户端启动失败：直接报会话错误（不驱动状态机）。 */
    private void onGameUnavailable(String id, String reason) {
        Ctx ctx = contexts.remove(id);
        if (ctx == null || ctx.killed) {
            return;
        }
        InstanceHandle h = registry.get(id);
        if (h != null) {
            // CloudSessionManager 是 InstanceRegistry.Listener，可直接回调
            manager.onInstanceFailed(h, reason == null ? "客户端启动失败" : reason);
        }
    }

    // ================= 内部工具 =================

    /** 注入器选择：config cloud.input-inject（agent 默认推荐 / robot 调试 / none 禁用）。 */
    private InputInjector createInjector() {
        String mode = config.getCloudInputInject();
        if ("agent".equals(mode)) {
            return InputInjector.agent();
        }
        if ("robot".equals(mode)) {
            return InputInjector.robot();
        }
        return InputInjector.noop();
    }

    /** 回收实例资源：注入器通道 + 编码器 + 客户端进程树（幂等）。 */
    private void teardown(Ctx ctx) {
        try {
            ctx.injector.close();
        } catch (Throwable ignored) {
        }
        EncoderProcess enc = ctx.encoder;
        if (enc != null) {
            try {
                enc.stop();
            } catch (Throwable ignored) {
            }
        }
        try {
            ctx.game.stop();
        } catch (Throwable ignored) {
        }
    }

    private static String fmtSize(long bytes) {
        if (bytes >= 1073741824L) {
            return String.format("%.1fG", bytes / 1073741824.0);
        }
        if (bytes >= 1048576L) {
            return String.format("%.0fM", bytes / 1048576.0);
        }
        return bytes + "B";
    }
}
