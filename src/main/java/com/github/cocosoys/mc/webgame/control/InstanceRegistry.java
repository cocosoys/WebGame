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

    /** 实例生命周期监听（CloudSessionManager 实现：就绪→下推流信令，停止→清会话）。 */
    public interface Listener {
        void onInstanceReady(InstanceHandle h);

        void onInstanceStopped(InstanceHandle h, String reason);

        void onInstanceFailed(InstanceHandle h, String reason);

        /** 执行面推流配置帧（SPS/PPS/宽高），转发到对应云会话。 */
        void onVideoConfig(InstanceHandle h, byte[] data);

        /** 执行面推流视频帧（Annex-B），转发到对应云会话。 */
        void onVideoFrame(InstanceHandle h, byte[] data);
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

    // ===== 本地执行后端状态驱动（LocalBackend 经注册表驱动黑盒，避免跨包访问包私有方法）=====

    /** 本地执行后端：实例就绪（状态机 → READY + 通知监听 → 会话下发就绪信令）。 */
    public void markReady(InstanceHandle h, String display, String windowTitle) {
        if (h != null) {
            h.onReady(display == null ? "" : display, windowTitle == null ? "" : windowTitle);
        }
    }

    /** 本地执行后端：实例停止（状态机 → STOPPED + 通知监听 → 会话错误并关闭）。 */
    public void markStopped(InstanceHandle h, String reason) {
        if (h != null) {
            h.onStopped(reason == null ? "" : reason);
        }
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

    private void notifyVideoConfig(InstanceHandle h, byte[] data) {
        Listener l = listener;
        if (l != null) {
            l.onVideoConfig(h, data);
        }
    }

    private void notifyVideoFrame(InstanceHandle h, byte[] data) {
        Listener l = listener;
        if (l != null) {
            l.onVideoFrame(h, data);
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
                    h.onReady(msg.get("display", ""), msg.get("windowTitle", ""));
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
            case ControlProtocol.S_VIDEO_CONFIG: {
                byte[] cloud = stripInstancePrefix(msg);
                InstanceHandle h = byInstanceId(msg);
                if (h != null) {
                    notifyVideoConfig(h, cloud);
                }
                return;
            }
            case ControlProtocol.S_VIDEO_FRAME: {
                byte[] cloud = stripInstancePrefix(msg);
                InstanceHandle h = byInstanceId(msg);
                if (h != null) {
                    notifyVideoFrame(h, cloud);
                }
                return;
            }
            default:
                // STATUS_RPT：执行面上报机器快照（procs/mem/disk/load/ccPid），缓存供管理员面板
                String procsJson = msg.get("procs");
                if (procsJson != null) {
                    StringBuilder snap = new StringBuilder(2048);
                    snap.append("{\"procs\":").append(procsJson);
                    String mem = msg.get("mem");
                    if (mem != null && !mem.isEmpty()) {
                        String[] mp = mem.split("\\|");
                        if (mp.length >= 4) {
                            snap.append(",\"mem\":{\"totalMb\":").append(mp[0])
                                    .append(",\"usedMb\":").append(mp[1])
                                    .append(",\"freeMb\":").append(mp[2])
                                    .append(",\"availMb\":").append(mp[3]).append("}");
                        } else {
                            snap.append(",\"mem\":null");
                        }
                    } else {
                        snap.append(",\"mem\":null");
                    }
                    String disk = msg.get("disk");
                    if (disk != null && !disk.isEmpty()) {
                        String[] dp = disk.split("\\|");
                        if (dp.length >= 4) {
                            snap.append(",\"disk\":{\"size\":\"").append(dp[0])
                                    .append("\",\"used\":\"").append(dp[1])
                                    .append("\",\"avail\":\"").append(dp[2])
                                    .append("\",\"usePct\":\"").append(dp[3]).append("\"}");
                        } else {
                            snap.append(",\"disk\":null");
                        }
                    } else {
                        snap.append(",\"disk\":null");
                    }
                    String load = msg.get("load");
                    snap.append(",\"load\":").append(load == null ? "\"\"" : "\"" + load + "\"");
                    String ccPid = msg.get("ccPid");
                    snap.append(",\"ccPid\":").append(ccPid == null || ccPid.isEmpty() ? 0 : ccPid);
                    snap.append("}");
                    machineSnapshot = snap.toString();
                }
        }
    }

    /** 最近一次执行面机器快照 JSON（STATUS_RPT 缓存），未上报时为空字符串。 */
    public static volatile String machineSnapshot = "";

    // ===== 视频帧路由（0x87/0x88）=====

    /**
     * 从二进制消息解析实例 id（payload 前缀格式：[u8 idLen][id utf8][Cloud 协议帧]）。
     * 未命中返回 null。
     */
    private InstanceHandle byInstanceId(ControlMessage msg) {
        byte[] raw = msg.binary();
        if (raw == null || raw.length < 2) {
            return null;
        }
        int len = raw[0] & 0xFF;
        if (len <= 0 || raw.length < 1 + len + 1) {
            return null;
        }
        String id = new String(raw, 1, len, java.nio.charset.StandardCharsets.UTF_8);
        return get(id);
    }

    /** 剥离 instanceId 前缀，返回纯 Cloud 协议帧（无匹配实例时返回空数组）。 */
    private byte[] stripInstancePrefix(ControlMessage msg) {
        byte[] raw = msg.binary();
        if (raw == null || raw.length < 2) {
            return new byte[0];
        }
        int len = raw[0] & 0xFF;
        if (len <= 0 || raw.length < 1 + len + 1) {
            return new byte[0];
        }
        byte[] body = new byte[raw.length - 1 - len];
        System.arraycopy(raw, 1 + len, body, 0, body.length);
        return body;
    }
}
