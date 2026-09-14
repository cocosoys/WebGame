package com.github.cocosoys.mc.webgame.control;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebGame 管控契约（Control Protocol）：插件（控制面）↔ 控制客户端（执行面）。
 *
 * <p><b>通道模型</b>：插件为 TCP 客户端，控制客户端为 TCP 服务端（监听执行面
 * 所在主机，默认 127.0.0.1:25576）。插件启动时连接并保持，断线自动重连；
 * 执行面可动态接入/重启而不影响插件其余功能。</p>
 *
 * <p><b>帧格式</b>（大端）：</p>
 * <pre>
 * [u32 length][u8 type][payload]
 *   length = payload 字节数（不含 4 字节长度与 1 字节类型）
 * </pre>
 *
 * <p><b>payload 格式</b>：URL 编码的 {@code key=value} 行（{@code \n} 分隔）。
 * 零第三方依赖，Java/Python 两侧均可直接处理、便于抓包调试。</p>
 *
 * <p><b>消息类型</b>：</p>
 * <pre>
 * 下行（插件 → 执行面）：
 *   1  SPAWN        instanceId, username, server, port[, display, xmx, scale, fps, bitrate, clientDir]
 *   2  KILL         instanceId
 *   3  STATUS       [instanceId]（空 = 全量）
 *   4  CONFIG       key, value（配置下发，可多条）
 *   5  PING         ts
 * 上行（执行面 → 插件）：
 *   0x81 SPAWN_ACK  instanceId, ok, [reason]
 *   0x82 READY      instanceId, display, kasmPort, [host], [windowTitle], readyAt
 *   0x83 STOPPED    instanceId, reason
 *   0x84 STATUS_RPT instances（如 "id:state" 逗号分隔）, capacity, [runs]
 *   0x85 PONG       ts
 *   0x86 ERROR      [instanceId], code, message
 * </pre>
 *
 * <p>实例（Instance）是插件唯一认知的"黑盒"：插件只持有 instanceId + 状态 +
 * 采集端点（display / kasmPort），不关心 Xvnc / Forge 客户端如何组织。</p>
 */
public final class ControlProtocol {

    // ---- 下行类型 ----
    public static final int C_SPAWN = 1;
    public static final int C_KILL = 2;
    public static final int C_STATUS = 3;
    public static final int C_CONFIG = 4;
    public static final int C_PING = 5;

    // ---- 上行类型 ----
    public static final int S_SPAWN_ACK = 0x81;
    public static final int S_READY = 0x82;
    public static final int S_STOPPED = 0x83;
    public static final int S_STATUS_RPT = 0x84;
    public static final int S_PONG = 0x85;
    public static final int S_ERROR = 0x86;

    // ---- 实例状态（执行面上报，插件侧镜像） ----
    public static final String ST_SPAWNING = "spawning";
    public static final String ST_STARTING = "starting";
    public static final String ST_READY = "ready";
    public static final String ST_STOPPING = "stopping";
    public static final String ST_STOPPED = "stopped";
    public static final String ST_FAILED = "failed";

    private ControlProtocol() {
    }

    // ================= 编码 =================

    /** 组装一帧：payload 从 k=v 行（自动 URL 编码值）。 */
    public static byte[] encode(int type, Map<String, String> fields) {
        StringBuilder sb = new StringBuilder(128);
        if (fields != null) {
            boolean first = true;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!first) {
                    sb.append('\n');
                }
                first = false;
                sb.append(enc(e.getKey())).append('=').append(enc(e.getValue() == null ? "" : e.getValue()));
            }
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(5 + payload.length);
        bos.write((payload.length >>> 24) & 0xFF);
        bos.write((payload.length >>> 16) & 0xFF);
        bos.write((payload.length >>> 8) & 0xFF);
        bos.write(payload.length & 0xFF);
        bos.write(type & 0xFF);
        bos.write(payload, 0, payload.length);
        return bos.toByteArray();
    }

    /** 便捷：单字段编码。 */
    public static byte[] encode(int type, String k1, String v1) {
        Map<String, String> m = new LinkedHashMap<>(2);
        m.put(k1, v1);
        return encode(type, m);
    }

    public static byte[] encode(int type, String k1, String v1, String k2, String v2) {
        Map<String, String> m = new LinkedHashMap<>(4);
        m.put(k1, v1);
        m.put(k2, v2);
        return encode(type, m);
    }

    // ================= 解码 =================

    /** 从完整帧字节中解析类型与字段（长度字段由调用方先校验/剥离）。 */
    public static ControlMessage parse(byte[] frameBodyWithType) {
        if (frameBodyWithType == null || frameBodyWithType.length < 1) {
            return null;
        }
        int type = frameBodyWithType[0] & 0xFF;
        Map<String, String> fields = new LinkedHashMap<>();
        String body = new String(frameBodyWithType, 1, frameBodyWithType.length - 1, StandardCharsets.UTF_8);
        if (!body.isEmpty()) {
            for (String line : body.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = dec(line.substring(0, eq));
                    String v = dec(line.substring(eq + 1));
                    if (k != null) {
                        fields.put(k, v == null ? "" : v);
                    }
                }
            }
        }
        return new ControlMessage(type, fields);
    }

    /** 帧首字节类型（不足 1 字节返回 -1）。 */
    public static int peekType(byte[] buf) {
        if (buf == null || buf.length < 1) {
            return -1;
        }
        return buf[0] & 0xFF;
    }

    // ================= 编码原语 =================

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
