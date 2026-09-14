package com.github.cocosoys.mc.webgame.web.cloud;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * WebGame 云游戏通道（Cloud）帧协议：纯静态编解码工具。
 *
 * <p>与 EaglerX 通道并列，走同一条 SOYS 网关端口（TCP），WS 路径默认 {@code /cloud}。
 * 帧格式参照 Carrot/go2rtc 的 WebSocket 原始编码帧方案：配置帧 + 视频帧 + 心跳，
 * 视频统一 Annex-B H.264（Safari/Chrome/Firefox 的 WebCodecs 全兼容）。</p>
 *
 * <h3>下行（服务器 → 浏览器，WS binary）</h3>
 * <pre>
 * 0x01 CONFIG: [u16 spsLen][sps][u16 ppsLen][pps][u32 width][u32 height][u32 fps]
 * 0x02 VIDEO:  [u32 tsMs][u8 key][Annex-B H.264 一帧（若干 NALU，各带 start code）]
 * 0x03 PING:   [u32 counter]
 * 0x05 ERROR:  [u16 msgLen][msg utf8]
 * </pre>
 *
 * <h3>上行（浏览器 → 服务器，WS binary）</h3>
 * <pre>
 * 0x04 PONG:   [u32 counter]
 * 0x10 INPUT:  [u16 eventCount][event...]
 *   event: [u8 type][payload]
 *     1 MOUSE_MOVE:   [i32 dx][i32 dy]
 *     2 MOUSE_BUTTON: [u8 button][u8 pressed]
 *     3 KEY:          [u16 codeLen][code utf8][u8 pressed]
 *     4 WHEEL:        [i32 delta]
 * </pre>
 *
 * <p>传输层与帧协议解耦：本协议产生的字节块可原样承载于 WebSocket（v1 同端口），
 * 未来 v2 分离端口（WebRTC/UDP）直接复用同一帧格式。</p>
 */
public final class CloudProtocol {

    // ---------- 下行类型 ----------
    public static final int TYPE_CONFIG = 0x01;   // 配置帧（SPS/PPS/宽高/帧率，v2 媒体通道用）
    public static final int TYPE_VIDEO = 0x02;    // 视频帧（Annex-B，v2 媒体通道用）
    public static final int TYPE_PING = 0x03;     // 服务器心跳
    public static final int TYPE_ERROR = 0x05;    // 服务器错误提示
    public static final int TYPE_KASM_URL = 0x06; // 采集端点 URL（路线 C v1：信令下发 KasmVNC 地址）

    // ---------- 上行类型 ----------
    public static final int TYPE_PONG = 0x04;     // 浏览器心跳回执
    public static final int TYPE_INPUT = 0x10;    // 批量输入事件

    // ---------- 输入事件子类型 ----------
    public static final int EVENT_MOUSE_MOVE = 1;
    public static final int EVENT_MOUSE_BUTTON = 2;
    public static final int EVENT_KEY = 3;
    public static final int EVENT_WHEEL = 4;
    public static final int EVENT_MOUSE_CLICK = 5; // 绝对坐标点击（GUI 交互，非 pointer lock）

    private CloudProtocol() {
    }

    // ================= 下行组装 =================

    /** 配置帧：SPS/PPS（Annex-B 提取的裸 NAL 载荷，不含 start code）+ 宽高 + 帧率。 */
    public static byte[] buildConfig(byte[] sps, byte[] pps, int width, int height, int fps) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + sps.length + pps.length);
        bos.write(TYPE_CONFIG);
        writeU16(bos, sps.length);
        bos.write(sps, 0, sps.length);
        writeU16(bos, pps.length);
        bos.write(pps, 0, pps.length);
        writeU32(bos, width);
        writeU32(bos, height);
        writeU32(bos, fps);
        return bos.toByteArray();
    }

    /** 视频帧：时间戳(ms) + 关键帧标记 + Annex-B H.264 一帧。 */
    public static byte[] buildVideo(long tsMs, boolean keyFrame, byte[] annexBFrame) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8 + annexBFrame.length);
        bos.write(TYPE_VIDEO);
        writeU32(bos, (int) (tsMs & 0xFFFFFFFFL));
        bos.write(keyFrame ? 1 : 0);
        bos.write(annexBFrame, 0, annexBFrame.length);
        return bos.toByteArray();
    }

    /** 心跳：计数器。 */
    public static byte[] buildPing(long counter) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(5);
        bos.write(TYPE_PING);
        writeU32(bos, (int) (counter & 0xFFFFFFFFL));
        return bos.toByteArray();
    }

    /** 错误提示：utf8 消息。 */
    public static byte[] buildError(String msg) {
        byte[] m = msg == null ? new byte[0] : msg.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(3 + m.length);
        bos.write(TYPE_ERROR);
        writeU16(bos, m.length);
        bos.write(m, 0, m.length);
        return bos.toByteArray();
    }

    /** KASM_URL 信令：采集端点地址（utf8 URL），浏览器收到后跳转。 */
    public static byte[] buildKasmUrl(String url) {
        byte[] m = url == null ? new byte[0] : url.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 + m.length);
        bos.write(TYPE_KASM_URL);
        bos.write(m, 0, m.length);
        return bos.toByteArray();
    }

    // ================= 上行解析 =================

    /** 解析上行帧首字节类型；不足 1 字节返回 -1。 */
    public static int peekType(byte[] payload) {
        if (payload == null || payload.length < 1) {
            return -1;
        }
        return payload[0] & 0xFF;
    }

    /** 解析 PONG 计数器。 */
    public static long parsePongCounter(byte[] payload) {
        if (payload == null || payload.length < 5) {
            return -1;
        }
        return readU32(payload, 1);
    }

    /** 解析批量输入事件；格式非法返回空列表。 */
    public static List<InputEvent> parseInput(byte[] payload) {
        List<InputEvent> events = new ArrayList<>(8);
        if (payload == null || payload.length < 3) {
            return events;
        }
        int count = readU16(payload, 1);
        int pos = 3;
        for (int i = 0; i < count && pos < payload.length; i++) {
            int type = payload[pos] & 0xFF;
            pos++;
            switch (type) {
                case EVENT_MOUSE_MOVE: {
                    if (pos + 8 > payload.length) {
                        return events;
                    }
                    int dx = readI32(payload, pos);
                    int dy = readI32(payload, pos + 4);
                    pos += 8;
                    events.add(new InputEvent(type, dx, dy, 0, false, null, 0));
                    break;
                }
                case EVENT_MOUSE_BUTTON: {
                    if (pos + 2 > payload.length) {
                        return events;
                    }
                    int button = payload[pos] & 0xFF;
                    boolean pressed = payload[pos + 1] != 0;
                    pos += 2;
                    events.add(new InputEvent(type, 0, 0, button, pressed, null, 0));
                    break;
                }
                case EVENT_KEY: {
                    if (pos + 2 > payload.length) {
                        return events;
                    }
                    int codeLen = readU16(payload, pos);
                    pos += 2;
                    if (codeLen <= 0 || pos + codeLen + 1 > payload.length) {
                        return events;
                    }
                    String code = new String(payload, pos, codeLen, StandardCharsets.UTF_8);
                    pos += codeLen;
                    boolean pressed = payload[pos] != 0;
                    pos += 1;
                    events.add(new InputEvent(type, 0, 0, 0, pressed, code, 0));
                    break;
                }
                case EVENT_WHEEL: {
                    if (pos + 4 > payload.length) {
                        return events;
                    }
                    int delta = readI32(payload, pos);
                    pos += 4;
                    events.add(new InputEvent(type, 0, 0, 0, false, null, delta));
                    break;
                }
                case EVENT_MOUSE_CLICK: {
                    if (pos + 8 > payload.length) {
                        return events;
                    }
                    // [i32 x][i32 y][u8 button][u8 pressed]（视频像素坐标，y 向下）
                    int cx = readI32(payload, pos);
                    int cy = readI32(payload, pos + 4);
                    int button = payload[pos + 8] & 0xFF;
                    boolean pressed = payload[pos + 9] != 0;
                    pos += 10;
                    events.add(new InputEvent(type, cx, cy, button, pressed, null, 0));
                    break;
                }
                default:
                    // 未知事件：跳过（无法确定长度，终止解析）
                    return events;
            }
        }
        return events;
    }

    /** 输入事件值对象。 */
    public static final class InputEvent {
        public final int type;
        public final int dx;
        public final int dy;
        public final int button;
        public final boolean pressed;
        public final String code;
        public final int delta;

        InputEvent(int type, int dx, int dy, int button, boolean pressed, String code, int delta) {
            this.type = type;
            this.dx = dx;
            this.dy = dy;
            this.button = button;
            this.pressed = pressed;
            this.code = code;
            this.delta = delta;
        }
    }

    // ================= 小端序原语 =================

    private static void writeU16(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >>> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream bos, int v) {
        bos.write(v & 0xFF);
        bos.write((v >>> 8) & 0xFF);
        bos.write((v >>> 16) & 0xFF);
        bos.write((v >>> 24) & 0xFF);
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static int readI32(byte[] b, int off) {
        return (int) readU32(b, off);
    }
}
