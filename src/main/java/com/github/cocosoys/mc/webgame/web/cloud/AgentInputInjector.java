package com.github.cocosoys.mc.webgame.web.cloud;

import java.io.DataOutputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路线 B 注入后端：javaagent 进程内注入（LWJGL 事件队列）。
 *
 * <p>容器客户端 JVM 以 {@code -javaagent:input-agent.jar} 启动，代理监听
 * {@code 127.0.0.1} 随机端口并把端口号写入实例目录 {@code input-agent.port}；
 * 本注入器在 {@link #onGameReady(String)} 读取该文件并建立 TCP 通道，
 * 之后把浏览器事件编码为代理协议二进制帧下行（通道断开后丢弃后续事件，
 * 会话重建时重新连接）。</p>
 *
 * <p>编码协议（与代理约定）：</p>
 * <pre>
 *   type=1 KEY        : keyCode(int BE) pressed(byte)
 *   type=2 MOUSE_MOVE : dx(int BE) dy(int BE)
 *   type=3 MOUSE_BTN  : button(int BE) pressed(byte)
 *   type=4 WHEEL      : delta(int BE)
 * </pre>
 *
 * <p>LWJGL 键码 = ANSI 扫描码（Keyboard.KEY_W=0x11 等），由本类在插件侧把
 * 浏览器 {@code KeyboardEvent.code} 翻译为 LWJGL 键码，代理端只负责注入。</p>
 */
final class AgentInputInjector implements InputInjector {

    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile DataOutputStream out;
    private volatile boolean connected;

    @Override
    public void onGameReady(String instanceDir) {
        File portFile = new File(instanceDir, "input-agent.port");
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && socket == null) {
            try {
                if (!portFile.isFile()) {
                    Thread.sleep(500);
                    continue;
                }
                String s = new String(Files.readAllBytes(portFile.toPath()), StandardCharsets.US_ASCII).trim();
                int port = Integer.parseInt(s);
                if (port <= 0) {
                    Thread.sleep(500);
                    continue;
                }
                Socket sk = new Socket();
                sk.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                sk.setTcpNoDelay(true);
                socket = sk;
                out = new DataOutputStream(sk.getOutputStream());
                connected = true;
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    @Override
    public void dispatch(List<CloudProtocol.InputEvent> events) {
        DataOutputStream o = out;
        if (o == null) {
            return;
        }
        synchronized (writeLock) {
            try {
                for (CloudProtocol.InputEvent e : events) {
                    switch (e.type) {
                        case CloudProtocol.EVENT_MOUSE_MOVE:
                            o.write(2);
                            o.writeInt(e.dx);
                            o.writeInt(e.dy);
                            break;
                        case CloudProtocol.EVENT_MOUSE_BUTTON:
                            o.write(3);
                            o.writeInt(e.button);
                            o.write(e.pressed ? 1 : 0);
                            break;
                        case CloudProtocol.EVENT_KEY: {
                            Integer kc = KEY_TO_LWJGL.get(e.code);
                            if (kc != null) {
                                o.write(1);
                                o.writeInt(kc);
                                o.write(e.pressed ? 1 : 0);
                            }
                            break;
                        }
                        case CloudProtocol.EVENT_WHEEL:
                            o.write(4);
                            o.writeInt(e.delta);
                            break;
                        case CloudProtocol.EVENT_MOUSE_CLICK:
                            // 绝对坐标点击（GUI 交互）：x y button pressed
                            o.write(5);
                            o.writeInt(e.dx);
                            o.writeInt(e.dy);
                            o.writeInt(e.button);
                            o.write(e.pressed ? 1 : 0);
                            break;
                        default:
                            break;
                    }
                }
                o.flush();
            } catch (Throwable t) {
                closeSocket();
            }
        }
    }

    private void closeSocket() {
        synchronized (writeLock) {
            connected = false;
            Socket sk = socket;
            socket = null;
            out = null;
            if (sk != null) {
                try {
                    sk.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public void close() {
        closeSocket();
    }

    // ================= 浏览器 KeyboardEvent.code → LWJGL 键码（ANSI 扫描码） =================

    private static final Map<String, Integer> KEY_TO_LWJGL = new HashMap<>();

    static {
        String[] letters = {"KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG", "KeyH",
                "KeyI", "KeyJ", "KeyK", "KeyL", "KeyM", "KeyN", "KeyO", "KeyP",
                "KeyQ", "KeyR", "KeyS", "KeyT", "KeyU", "KeyV", "KeyW", "KeyX", "KeyY", "KeyZ"};
        int[] codes = {0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23,
                0x17, 0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19,
                0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C};
        for (int i = 0; i < letters.length; i++) {
            KEY_TO_LWJGL.put(letters[i], codes[i]);
        }
        String[] digits = {"Digit0", "Digit1", "Digit2", "Digit3", "Digit4",
                "Digit5", "Digit6", "Digit7", "Digit8", "Digit9"};
        for (int i = 0; i < digits.length; i++) {
            KEY_TO_LWJGL.put(digits[i], (i == 0 ? 0x0B : 0x01 + i));
        }
        KEY_TO_LWJGL.put("Space", 0x39);
        KEY_TO_LWJGL.put("Enter", 0x1C);
        KEY_TO_LWJGL.put("Tab", 0x0F);
        KEY_TO_LWJGL.put("Backspace", 0x0E);
        KEY_TO_LWJGL.put("Escape", 0x01);
        KEY_TO_LWJGL.put("ShiftLeft", 0x2A);
        KEY_TO_LWJGL.put("ShiftRight", 0x36);
        KEY_TO_LWJGL.put("ControlLeft", 0x1D);
        KEY_TO_LWJGL.put("ControlRight", 0x9D);
        KEY_TO_LWJGL.put("AltLeft", 0x38);
        KEY_TO_LWJGL.put("AltRight", 0xB8);
        KEY_TO_LWJGL.put("MetaLeft", 0xDB);
        KEY_TO_LWJGL.put("MetaRight", 0xDC);
        KEY_TO_LWJGL.put("ArrowUp", 0xC8);
        KEY_TO_LWJGL.put("ArrowDown", 0xD0);
        KEY_TO_LWJGL.put("ArrowLeft", 0xCB);
        KEY_TO_LWJGL.put("ArrowRight", 0xCD);
        KEY_TO_LWJGL.put("Minus", 0x0C);
        KEY_TO_LWJGL.put("Equal", 0x0D);
        KEY_TO_LWJGL.put("BracketLeft", 0x1A);
        KEY_TO_LWJGL.put("BracketRight", 0x1B);
        KEY_TO_LWJGL.put("Backslash", 0x2B);
        KEY_TO_LWJGL.put("Semicolon", 0x27);
        KEY_TO_LWJGL.put("Quote", 0x28);
        KEY_TO_LWJGL.put("Backquote", 0x29);
        KEY_TO_LWJGL.put("Comma", 0x33);
        KEY_TO_LWJGL.put("Period", 0x34);
        KEY_TO_LWJGL.put("Slash", 0x35);
        KEY_TO_LWJGL.put("CapsLock", 0x3A);
        for (int i = 1; i <= 12; i++) {
            KEY_TO_LWJGL.put("F" + i, 0x3A + i);
        }
        KEY_TO_LWJGL.put("Numpad0", 0x47);
        KEY_TO_LWJGL.put("Numpad1", 0x48);
        KEY_TO_LWJGL.put("Numpad2", 0x49);
        KEY_TO_LWJGL.put("Numpad3", 0x4A);
        KEY_TO_LWJGL.put("Numpad4", 0x4B);
        KEY_TO_LWJGL.put("Numpad5", 0x4C);
        KEY_TO_LWJGL.put("Numpad6", 0x4D);
        KEY_TO_LWJGL.put("Numpad7", 0x4E);
        KEY_TO_LWJGL.put("Numpad8", 0x4F);
        KEY_TO_LWJGL.put("Numpad9", 0x50);
        KEY_TO_LWJGL.put("NumpadAdd", 0x4E);
        KEY_TO_LWJGL.put("NumpadSubtract", 0x4A);
        KEY_TO_LWJGL.put("NumpadMultiply", 0x37);
        KEY_TO_LWJGL.put("NumpadDivide", 0xB5);
        KEY_TO_LWJGL.put("NumpadDecimal", 0x53);
        KEY_TO_LWJGL.put("NumpadEnter", 0x9C);
        KEY_TO_LWJGL.put("Home", 0xC7);
        KEY_TO_LWJGL.put("End", 0xCF);
        KEY_TO_LWJGL.put("PageUp", 0xC9);
        KEY_TO_LWJGL.put("PageDown", 0xD1);
        KEY_TO_LWJGL.put("Insert", 0xD2);
        KEY_TO_LWJGL.put("Delete", 0xD3);
        KEY_TO_LWJGL.put("Pause", 0xC5);
        KEY_TO_LWJGL.put("PrintScreen", 0xB7);
    }
}
