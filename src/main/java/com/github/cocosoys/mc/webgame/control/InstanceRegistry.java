package com.github.cocosoys.mc.webgame.control;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例注册表：instanceId → {@link InstanceHandle} 的路由表。
 *
 * <p>接收 {@link ControlClient} 上行上报并按 instanceId 驱动对应黑盒状态，
 * 同时把实例生命周期事件转发给监听者（{@link Listener}，由会话管理器实现）。</p>
 */
public final class InstanceRegistry {

    /** 实例生命周期监听（CloudSessionManager 实现：就绪→下采集端点，停止→清会话）。 */
    public interface Listener {
        void onInstanceReady(InstanceHandle h);

        void onInstanceStopped(InstanceHandle h, String reason);

        void onInstanceFailed(InstanceHandle h, String reason);
    }

    private final JavaPlugin plugin;
    private volatile Listener listener;
    private final Map<String, InstanceHandle> instances = new ConcurrentHashMap<>();

    public InstanceRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 设置实例生命周期监听（会话管理器装配完成后调用）。 */
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 登记新实例（spawn 前调用）。 */
    public InstanceHandle register(String instanceId, String username, String deviceIp) {
        InstanceHandle h = new InstanceHandle(instanceId, username, deviceIp,
                new InstanceHandle.Listener() {
                    @Override
                    public void onInstanceReady(InstanceHandle h) {
                        notifyReady(h);
                    }

                    @Override
                    public void onInstanceStopped(InstanceHandle h, String reason) {
                        notifyStopped(h, reason);
                    }

                    @Override
                    public void onInstanceFailed(InstanceHandle h, String reason) {
                        notifyFailed(h, reason);
                    }
                });
        instances.put(instanceId, h);
        return h;
    }

    /** 移除并返回实例（会话结束时调用）。 */
    public InstanceHandle remove(String instanceId) {
        return instances.remove(instanceId);
    }

    public InstanceHandle get(String instanceId) {
        return instances.get(instanceId);
    }

    public int size() {
        return instances.size();
    }

    public List<InstanceHandle> all() {
        return new ArrayList<>(instances.values());
    }

    private void notifyReady(InstanceHandle h) {
        Listener l = listener;
        if (l != null) {
            l.onInstanceReady(h);
        }
    }

    private void notifyStopped(InstanceHandle h, String reason) {
        Listener l = listener;
        if (l != null) {
            l.onInstanceStopped(h, reason);
        }
    }

    private void notifyFailed(InstanceHandle h, String reason) {
        Listener l = listener;
        if (l != null) {
            l.onInstanceFailed(h, reason);
        }
    }

    // ===== 管控上行消息路由 =====

    public void onControlMessage(ControlMessage msg) {
        switch (msg.type) {
            case ControlProtocol.S_SPAWN_ACK: {
                String id = msg.get("instanceId");
                InstanceHandle h = get(id);
                if (h != null) {
                    h.onSpawnAck(msg.getBool("ok", false), msg.get("reason", ""));
                }
                return;
            }
            case ControlProtocol.S_READY: {
                String id = msg.get("instanceId");
                InstanceHandle h = get(id);
                if (h != null) {
                    h.onReady(msg.get("display", ""), msg.get("host", ""),
                            msg.getInt("kasmPort", -1), msg.get("windowTitle", ""));
                }
                return;
            }
            case ControlProtocol.S_STOPPED: {
                String id = msg.get("instanceId");
                InstanceHandle h = remove(id);
                if (h != null) {
                    h.onStopped(msg.get("reason", ""));
                }
                return;
            }
            case ControlProtocol.S_ERROR: {
                String id = msg.get("instanceId");
                if (id != null && !id.isEmpty()) {
                    InstanceHandle h = get(id);
                    if (h != null && !h.isReady()) {
                        remove(id);
                        notifyFailed(h, msg.get("message", "执行面错误"));
                    }
                }
                return;
            }
            default:
                // STATUS_RPT / PONG / 其它通用上报：由 ControlClient.Listener 处理
        }
    }
}
