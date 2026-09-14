package com.github.cocosoys.mc.webgame.web.cloud;

import java.util.List;

/**
 * 云游戏输入注入器：把浏览器上行输入事件注入到游戏实例。
 *
 * <p>后端按"注入层级"演进（路线图）：
 * <ul>
 *   <li><b>路线 B（当前）</b>：{@code agent} —— javaagent 进程内注入，
 *   直接写 LWJGL 事件队列（光标零移动、焦点零依赖、浏览器始终前台）；</li>
 *   <li><b>路线 C（预留）</b>：客户端运行于 VM/容器（KVM+Xvfb / IDD 虚拟显示器），
 *   注入后端换为容器内 uinput/XTest（Linux）或虚拟 HID 驱动（Windows）；
 *   通过 config {@code cloud.input-inject} 切换，接口与
 *   {@link #onGameReady(String)} 生命周期点已为该演进预留。</li>
 * </ul>
 * 旧有 {@link #noop()}（仅解析记录）与 {@link #robot()}（AWT Robot 桌面注入，
 * 移动真实光标，仅用于验证，不满足容器隔离）保留用于调试。</p>
 */
public interface InputInjector {

    /**
     * 分发一批输入事件。由 Cloud 会话调用（Netty IO 线程），实现应尽快返回，
     * 不得阻塞（如需阻塞注入请自行排队到后台线程）。
     */
    void dispatch(List<CloudProtocol.InputEvent> events);

    /**
     * 游戏实例就绪回调：容器客户端进入服务器、窗口可采集后由会话调用，
     * 注入器可在此建立到客户端进程的输入通道（如 agent 的 TCP 连接；
     * 未来路线 C 的 VM/Xvfb 注入亦在此建立通道）。
     *
     * @param instanceDir 容器客户端实例目录（含输入通道所需状态文件）
     */
    default void onGameReady(String instanceDir) {
    }

    /** 关闭并释放注入资源。 */
    void close();

    /** 空实现：仅解析记录，不注入。 */
    static InputInjector noop() {
        return new InputInjector() {
            @Override
            public void dispatch(List<CloudProtocol.InputEvent> events) {
                // 第一版：游戏实例未就绪，仅忽略（会话心跳已由上层更新）
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * 本机桌面注入（java.awt.Robot）：抓屏模式（EncoderProcess）下可端到端验证
     * "浏览器 → 桌面" 输入回传。会移动宿主真实光标，不满足容器隔离，
     * 默认不启用（config cloud.input-inject: none）。
     */
    static InputInjector robot() {
        try {
            return new RobotInputInjector();
        } catch (Throwable t) {
            return noop();
        }
    }

    /**
     * 路线 B：进程内注入（javaagent → LWJGL 事件队列）。
     * 容器客户端随 {@code -javaagent} 启动输入代理并监听本地端口，
     * 本注入器在 {@link #onGameReady(String)} 时读取代理端口文件并建立
     * TCP 通道，之后把浏览器事件编码后下行注入。光标零移动、焦点零依赖。
     */
    static InputInjector agent() {
        return new AgentInputInjector();
    }
}
