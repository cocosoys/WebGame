package com.github.cocosoys.mc.webgame.control;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 实例黑盒（插件侧镜像）：插件唯一认知的"游戏实例"。
 *
 * <p>插件只持有 instanceId + 状态 + 采集端点（display / kasmPort），
 * 实例内部（Xvnc / Forge 客户端 / KasmVNC）完全由执行面（控制客户端）负责。</p>
 *
 * <p>状态迁移（由 {@link InstanceRegistry} 依据执行面上报驱动）：</p>
 * <pre>
 * spawning → starting → ready
 *                ↘ failed ↘ stopping → stopped
 * </pre>
 */
public final class InstanceHandle {

    /** 生命周期回调（注册表驱动，均不在 Netty IO 线程做重活）。 */
    public interface Listener {
        void onInstanceReady(InstanceHandle h);

        void onInstanceStopped(InstanceHandle h, String reason);

        void onInstanceFailed(InstanceHandle h, String reason);
    }

    private final String instanceId;
    private final String username;
    private final String deviceIp;
    private final long createdAt = System.currentTimeMillis();
    private final AtomicReference<String> state = new AtomicReference<>(ControlProtocol.ST_SPAWNING);

    private volatile String display = "";
    private volatile String kasmHost = "";
    private volatile int kasmPort = -1;
    private volatile String windowTitle = "";
    private volatile long readyAt;

    private final Listener listener;

    public InstanceHandle(String instanceId, String username, String deviceIp, Listener listener) {
        this.instanceId = instanceId;
        this.username = username;
        this.deviceIp = deviceIp;
        this.listener = listener;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getUsername() {
        return username;
    }

    public String getDeviceIp() {
        return deviceIp;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getState() {
        return state.get();
    }

    void setState(String s) {
        state.set(s);
    }

    /** 采集端点：X display（如 :99）。 */
    public String getDisplay() {
        return display;
    }

    public String getKasmHost() {
        return kasmHost;
    }

    /** 采集端点：KasmVNC HTTP/WS 端口（如 8542）。 */
    public int getKasmPort() {
        return kasmPort;
    }

    public String getWindowTitle() {
        return windowTitle;
    }

    public long getReadyAt() {
        return readyAt;
    }

    /** 已就绪（可被浏览器连接）。 */
    public boolean isReady() {
        return ControlProtocol.ST_READY.equals(state.get());
    }

    // ---- 由 InstanceRegistry 调用的状态驱动 ----

    void onSpawnAck(boolean ok, String reason) {
        if (ok) {
            state.compareAndSet(ControlProtocol.ST_SPAWNING, ControlProtocol.ST_STARTING);
        } else {
            state.set(ControlProtocol.ST_FAILED);
            if (listener != null) {
                listener.onInstanceFailed(this, reason == null ? "spawn 被拒绝" : reason);
            }
        }
    }

    void onReady(String display, String kasmHost, int kasmPort, String windowTitle) {
        this.display = display == null ? "" : display;
        this.kasmHost = kasmHost == null ? "" : kasmHost;
        this.kasmPort = kasmPort;
        this.windowTitle = windowTitle == null ? "" : windowTitle;
        this.readyAt = System.currentTimeMillis();
        state.set(ControlProtocol.ST_READY);
        if (listener != null) {
            listener.onInstanceReady(this);
        }
    }

    void onStopped(String reason) {
        state.set(ControlProtocol.ST_STOPPED);
        if (listener != null) {
            listener.onInstanceStopped(this, reason);
        }
    }

    @Override
    public String toString() {
        return "Instance{id=" + instanceId + ", user=" + username
                + ", state=" + state.get() + ", kasm=" + kasmHost + ":" + kasmPort
                + ", display=" + display + "}";
    }
}
