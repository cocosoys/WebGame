package com.github.cocosoys.mc.webgame.agent;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * WebGame 输入注入代理（路线 B：进程内注入，poll() hook 版）。
 *
 * <p>以 {@code -javaagent} 方式随容器客户端 JVM 启动。核心机制：
 * 用 ASM 字节码插桩在 {@code org.lwjgl.input.Mouse.poll()} 与
 * {@code org.lwjgl.input.Keyboard.poll()} 的末尾（RETURN 前）插入对
 * {@link #postPollMouse()}/{@link #postPollKeyboard()} 的静态调用；
 * TCP 线程把插件发来的事件放入挂起队列，poll() 完成后（native 已把真实
 * 事件写入 readBuffer）由 hook 回调处理：键盘直驱 MC KeyBinding +
 * keyDownBuffer（见 {@link #postPollKeyboard()}），鼠标追加到 readBuffer
 * 队尾（见 {@link #postPollMouse()}）。</p>
 *
 * <p>与插件进程的通信：agent 监听 {@code 127.0.0.1} 随机端口，端口号写入
 * {@code -Dwebgame.agent.portFile} 指定的文件；插件读取后建立 TCP 连接，
 * 下行协议（二进制，DataInputStream 大端）：</p>
 * <pre>
 *   type=1 KEY        : keyCode(int) pressed(byte)
 *   type=2 MOUSE_MOVE : dx(int) dy(int)          （仅 grabbed 视角模式注入）
 *   type=3 MOUSE_BTN  : button(int) pressed(byte)
 *   type=4 WHEEL      : delta(int)
 *   type=5 MOUSE_CLICK: x(int) y(int) button(int) pressed(byte)（绝对坐标 GUI 点击）
 * </pre>
 *
 * <p>LWJGL 事件字节格式（2.9.4，均为小端）：
 * Keyboard 18 字节 = keyCode(4) state(1) character(4) nanos(8) repeat(1)；
 * Mouse 22 字节 = button(1) state(1) dx/dy(4+4) dwheel(4) nanos(8)
 * （grabbed 时 dx/dy 为相对增量；非 grabbed 时这两字段为绝对 raw 坐标）。</p>
 */
public final class WebGameInputAgent {

    private static volatile boolean running = true;

    private static volatile Class<?> keyboardClass;
    private static volatile Class<?> mouseClass;
    private static volatile Field keyReadBufferField;
    private static volatile Field keyDownBufferField;
    private static volatile Field mouseReadBufferField;
    private static volatile Field mouseButtonsField;
    private static volatile Field mouseGrabbedField;

    /** TCP 已接收注入尝试计数。 */
    private static volatile long injectedCount = 0;
    /** postPoll 实际写入 readBuffer 的事件数（被游戏消费的近似）。 */
    private static volatile long consumedCount = 0;

    /** 挂起事件队列：TCP 线程入队，postPoll 回调消费。事件为 Object[]。 */
    private static final List<Object[]> PENDING_KEYS = new CopyOnWriteArrayList<Object[]>();
    private static final List<Object[]> PENDING_MOUSE = new CopyOnWriteArrayList<Object[]>();

    // 事件类型标记（挂起队列内）
    private static final int T_MOVE = 1;
    private static final int T_BTN = 2;
    private static final int T_WHEEL = 4;
    private static final int T_CLICK = 5;

    private WebGameInputAgent() {
    }

    // ================= premain & transformer =================

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new PollHookTransformer(), true);
        Thread t = new Thread(WebGameInputAgent::run, "WebGame-InputAgent");
        t.setDaemon(true);
        t.start();
    }

    /** ASM 转换器：① Mouse.poll()/Keyboard.poll() RETURN 前插入 hook；
     *  ② Display.isActive()/Mouse.isGrabbed() 强制返回 true（MC 失焦时
     *  inGameHasFocus=false 会跳过 runTick 输入处理段，云游戏须"假装焦点"）。 */
    private static final class PollHookTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            boolean isMouse = "org/lwjgl/input/Mouse".equals(className);
            boolean isKeyboard = "org/lwjgl/input/Keyboard".equals(className);
            boolean isDisplay = "org/lwjgl/opengl/Display".equals(className);
            if (!isMouse && !isKeyboard && !isDisplay) {
                return null;
            }
            System.out.println("[WebGameInputAgent] transform hit: " + className);
            try {
                ClassReader cr = new ClassReader(classfileBuffer);
                ClassWriter cw = new ClassWriter(cr, 0);
                ClassVisitor cv;
                if (isDisplay) {
                    cv = new ClassVisitor(Opcodes.ASM5, cw) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                            if ("isActive".equals(name) && "()Z".equals(desc)) {
                                return new MethodVisitor(Opcodes.ASM5, mv) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.IRETURN) {
                                            mv.visitInsn(Opcodes.ICONST_1);
                                            mv.visitInsn(Opcodes.IRETURN);
                                        } else {
                                            super.visitInsn(opcode);
                                        }
                                    }
                                };
                            }
                            return mv;
                        }
                    };
                } else {
                    final String hook = isMouse ? "postPollMouse" : "postPollKeyboard";
                    cv = new ClassVisitor(Opcodes.ASM5, cw) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                            if ("poll".equals(name) && "()V".equals(desc)) {
                                return new MethodVisitor(Opcodes.ASM5, mv) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.RETURN) {
                                            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                                    "com/github/cocosoys/mc/webgame/agent/WebGameInputAgent",
                                                    hook, "()V", false);
                                        }
                                        super.visitInsn(opcode);
                                    }
                                };
                            }
                            if (isMouse && "isGrabbed".equals(name) && "()Z".equals(desc)) {
                                return new MethodVisitor(Opcodes.ASM5, mv) {
                                    @Override
                                    public void visitInsn(int opcode) {
                                        if (opcode == Opcodes.IRETURN) {
                                            mv.visitInsn(Opcodes.ICONST_1);
                                            mv.visitInsn(Opcodes.IRETURN);
                                        } else {
                                            super.visitInsn(opcode);
                                        }
                                    }
                                };
                            }
                            return mv;
                        }
                    };
                }
                cr.accept(cv, 0);
                return cw.toByteArray();
            } catch (Throwable t) {
                System.out.println("[WebGameInputAgent] transform FAILED for " + className + ": " + t);
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new java.io.PrintWriter(sw));
                System.out.println("[WebGameInputAgent] " + sw.toString().replace("\n", " | "));
                return null; // 转换失败则保留原类，避免启动失败
            }
        }
    }

    // ================= TCP 服务 =================

    private static void run() {
        String portFile = System.getProperty("webgame.agent.portFile", "");
        if (portFile.isEmpty()) {
            return;
        }
        try {
            long deadline = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    keyboardClass = Class.forName("org.lwjgl.input.Keyboard");
                    mouseClass = Class.forName("org.lwjgl.input.Mouse");
                    break;
                } catch (Throwable t) {
                    sleep(200);
                }
            }
            if (keyboardClass == null) {
                writePort(portFile, "-1");
                return;
            }
            keyReadBufferField = field(keyboardClass, "readBuffer");
            keyDownBufferField = field(keyboardClass, "keyDownBuffer");
            mouseReadBufferField = field(mouseClass, "readBuffer");
            mouseButtonsField = field(mouseClass, "buttons");
            mouseGrabbedField = field(mouseClass, "isGrabbed");
            ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            writePort(portFile, String.valueOf(server.getLocalPort()));
            while (running) {
                try {
                    Socket s = server.accept();
                    Thread ct = new Thread(() -> handle(s), "WebGame-InputAgent-Conn");
                    ct.setDaemon(true);
                    ct.start();
                } catch (Throwable t) {
                    break;
                }
            }
        } catch (Throwable t) {
            if (!portFile.isEmpty()) {
                writePort(portFile, "-1");
            }
        }
    }

    private static void handle(Socket s) {
        try (DataInputStream in = new DataInputStream(s.getInputStream())) {
            while (running) {
                int type;
                try {
                    type = in.read();
                } catch (EOFException e) {
                    break;
                }
                if (type < 0) {
                    break;
                }
                switch (type) {
                    case 1: { // KEY: keyCode pressed
                        int keyCode = in.readInt();
                        byte pressed = in.readByte();
                        enqueueKey(keyCode, pressed == 1);
                        break;
                    }
                    case 2: { // MOUSE_MOVE: dx dy
                        int dx = in.readInt();
                        int dy = in.readInt();
                        enqueueMouse(T_MOVE, dx, dy, 0, 0, false);
                        break;
                    }
                    case 3: { // MOUSE_BUTTON: button pressed
                        int button = in.readInt();
                        byte pressed = in.readByte();
                        enqueueMouse(T_BTN, 0, 0, button, 0, pressed == 1);
                        break;
                    }
                    case 4: { // WHEEL: delta
                        int delta = in.readInt();
                        enqueueMouse(T_WHEEL, 0, 0, 0, delta, false);
                        break;
                    }
                    case 5: { // MOUSE_CLICK: x y button pressed
                        int x = in.readInt();
                        int y = in.readInt();
                        int button = in.readInt();
                        byte pressed = in.readByte();
                        enqueueMouse(T_CLICK, x, y, button, 0, pressed == 1);
                        break;
                    }
                    case 6: { // GUI_CHAT: 直驱打开聊天框（绕开按键消费段）
                        guiPendingCmd = 6;
                        break;
                    }
                    case 7: { // VIEW_DIAG: 打印 GameSettings int 字段（定位 thirdPersonView）
                        guiPendingCmd = 7;
                        break;
                    }
                    case 8: { // VIEW_TOGGLE: 切换视角（field_74320_O = thirdPersonView）
                        guiPendingCmd = 8;
                        break;
                    }
                    case 9: { // CLOSE_GUI: 关闭当前 GUI（displayGuiScreen(null)）
                        guiPendingCmd = 9;
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 获取 Minecraft 实例（TCCL + 签名匹配静态 getMinecraft()）。 */
    private static Object mcInstance() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, cl);
            for (java.lang.reflect.Method m : mcClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                        && m.getReturnType().getName().equals("net.minecraft.client.Minecraft")) {
                    m.setAccessible(true);
                    return m.invoke(null);
                }
            }
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] mcInstance fail: " + t);
        }
        return null;
    }

    /** 直驱打开聊天框：Minecraft.getMinecraft().displayGuiScreen(new GuiChat())，绕开按键消费段。 */
    private static void guiOpenChat() {
        try {
            Object mc = mcInstance();
            if (mc == null) {
                System.out.println("[WebGameInputAgent] guiOpenChat: no MC instance");
                return;
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Class<?> guiChat = Class.forName("net.minecraft.client.gui.GuiChat", true, cl);
            Object chat = guiChat.getConstructor().newInstance();
            for (java.lang.reflect.Method m : mc.getClass().getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].getName().equals("net.minecraft.client.gui.GuiScreen")) {
                    m.setAccessible(true);
                    m.invoke(mc, chat);
                    System.out.println("[WebGameInputAgent] guiOpenChat invoked via " + m.getName());
                    return;
                }
            }
            System.out.println("[WebGameInputAgent] guiOpenChat: displayGuiScreen method not found");
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] guiOpenChat fail: " + t);
        }
    }

    /** 诊断 GameSettings 的 int 字段值（定位 thirdPersonView）。 */
    private static void guiViewDiag() {
        try {
            Object mc = mcInstance();
            if (mc == null) {
                System.out.println("[WebGameInputAgent] guiViewDiag: no MC instance");
                return;
            }
            Object gs = null;
            for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.client.settings.GameSettings")) {
                    f.setAccessible(true);
                    gs = f.get(mc);
                    break;
                }
            }
            System.out.println("[WebGameInputAgent] GameSettings=" + gs);
            if (gs == null) {
                return;
            }
            for (java.lang.reflect.Field f : gs.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    System.out.println("[WebGameInputAgent] GS int: " + f.getName() + "=" + f.getInt(gs));
                }
            }
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] guiViewDiag fail: " + t);
        }
    }

    /** 直驱切换视角：GameSettings.thirdPersonView = (v+1)%3（SRG 字段 field_74320_O）。 */
    private static void guiViewToggle() {
        try {
            Object mc = mcInstance();
            if (mc == null) {
                System.out.println("[WebGameInputAgent] guiViewToggle: no MC instance");
                return;
            }
            Object gs = null;
            for (java.lang.reflect.Field f : mc.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("net.minecraft.client.settings.GameSettings")) {
                    f.setAccessible(true);
                    gs = f.get(mc);
                    break;
                }
            }
            if (gs == null) {
                System.out.println("[WebGameInputAgent] guiViewToggle: no GameSettings");
                return;
            }
            java.lang.reflect.Field tp = gs.getClass().getDeclaredField("field_74320_O");
            tp.setAccessible(true);
            int v = tp.getInt(gs);
            int nv = (v + 1) % 3;
            tp.setInt(gs, nv);
            System.out.println("[WebGameInputAgent] view toggled " + v + " -> " + nv);
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] guiViewToggle fail: " + t);
        }
    }

    /** 直驱关闭 GUI：Minecraft.getMinecraft().displayGuiScreen(null)。 */
    private static void guiCloseGui() {
        try {
            Object mc = mcInstance();
            if (mc == null) {
                System.out.println("[WebGameInputAgent] guiCloseGui: no MC instance");
                return;
            }
            for (java.lang.reflect.Method m : mc.getClass().getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0].getName().equals("net.minecraft.client.gui.GuiScreen")) {
                    m.setAccessible(true);
                    m.invoke(mc, new Object[] { null });
                    System.out.println("[WebGameInputAgent] guiCloseGui invoked");
                    return;
                }
            }
            System.out.println("[WebGameInputAgent] guiCloseGui: displayGuiScreen method not found");
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] guiCloseGui fail: " + t);
        }
    }

    private static void enqueueKey(int keyCode, boolean pressed) {
        injectedCount++;        PENDING_KEYS.add(new Object[] { Integer.valueOf(keyCode), Boolean.valueOf(pressed) });
        stats();
    }

    private static void enqueueMouse(int t, int a, int b, int c, int d, boolean pressed) {
        injectedCount++;
        PENDING_MOUSE.add(new Object[] { Integer.valueOf(t), Integer.valueOf(a), Integer.valueOf(b),
                Integer.valueOf(c), Integer.valueOf(d), Boolean.valueOf(pressed) });
        stats();
    }

    // ================= poll() hook 回调 =================

    /**
     * Mouse.poll() 末尾回调：把挂起鼠标事件追加到 readBuffer 队尾。
     * 此刻 poll() 已完成 native 事件填充，随后游戏主线程的 next() 循环会消费它们。
     */
    public static void postPollMouse() {
        try {
            if (PENDING_MOUSE.isEmpty()) {
                return;
            }
            ByteBuffer rb = get(mouseReadBufferField);
            if (rb == null) {
                return;
            }
            boolean grabbed = true;
            try {
                grabbed = Boolean.TRUE.equals(mouseGrabbedField.get(null));
            } catch (Throwable ignored) {
            }
            int injected = 0;
            synchronized (rb) {
                for (Object[] ev : PENDING_MOUSE) {
                    int t = ((Integer) ev[0]).intValue();
                    int a = ((Integer) ev[1]).intValue();
                    int b = ((Integer) ev[2]).intValue();
                    int c = ((Integer) ev[3]).intValue();
                    int d = ((Integer) ev[4]).intValue();
                    boolean pressed = ((Boolean) ev[5]).booleanValue();
                    if (t == T_MOVE && !grabbed) {
                        continue; // 非 grabbed 忽略相对移动（视角模式）
                    }
                    int lim = rb.limit();
                    if (lim + 22 > rb.capacity()) {
                        break; // 缓冲区满，丢弃本批剩余（下帧再试）
                    }
                    rb.limit(rb.capacity());
                    rb.position(lim);
                    if (t == T_CLICK) {
                        rb.put((byte) (c & 0xFF));
                        rb.put((byte) (pressed ? 1 : 0));
                        rb.putInt(a); // raw_x
                        rb.putInt(displayHeight() - b); // raw_y（LWJGL y 向上翻转）
                        rb.putInt(0);
                    } else if (t == T_MOVE) {
                        rb.put((byte) 0);
                        rb.put((byte) 0);
                        rb.putInt(a);
                        rb.putInt(b);
                        rb.putInt(0);
                    } else if (t == T_BTN) {
                        rb.put((byte) (c & 0xFF));
                        rb.put((byte) (pressed ? 1 : 0));
                        rb.putInt(0);
                        rb.putInt(0);
                        rb.putInt(0);
                    } else { // WHEEL
                        rb.put((byte) 0);
                        rb.put((byte) 0);
                        rb.putInt(0);
                        rb.putInt(0);
                        rb.putInt(d);
                    }
                    rb.putLong(System.nanoTime());
                    int newLim = rb.position();
                    rb.limit(newLim);
                    rb.position(lim); // 关键：position 回退到事件起始，next() 的 hasRemaining 为真
                    injected++;
                }
            }
            // buttons 状态位（在 next() 消费前同步更新）
            for (Object[] ev : PENDING_MOUSE) {
                int t = ((Integer) ev[0]).intValue();
                if (t == T_BTN || t == T_CLICK) {
                    int c = ((Integer) ev[3]).intValue();
                    boolean pressed = ((Boolean) ev[5]).booleanValue();
                    try {
                        ByteBuffer bt = (ByteBuffer) mouseButtonsField.get(null);
                        if (bt != null && c >= 0 && c < bt.capacity()) {
                            bt.put(c, (byte) (pressed ? 1 : 0));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            PENDING_MOUSE.clear();
            if (injected > 0) {
                consumedCount += injected;
                stats();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Keyboard.poll() 末尾回调：更新 keyDownBuffer 状态位 + 直驱 MC KeyBinding。
     *
     * <p>实测（VM 云游戏场景）：客户端窗口失焦时，MC 主线程的事件循环
     * {@code while (Keyboard.next())} 无法消费追加进 readBuffer 的事件
     * （F5 视角 / T 聊天框无反应），而 keyDownBuffer 路径（WASD 移动，
     * {@code Keyboard.isKeyDown}）有效。为保证 F5/T 等 KeyBinding 事件在
     * 失焦场景同样生效，本回调除更新 keyDownBuffer 外，直接反射调用
     * {@code net.minecraft.client.settings.KeyBinding#setKeyBindState} 与
     * {@code #onTick}，驱动 MC 的 pressTime/isPressed 机制。
     * 不再追加 readBuffer（避免事件循环双触发）。</p>
     */
    /** GUI 直驱命令（由连接线程写入、MC 主线程在 postPollKeyboard 消费）：
     *  0=none，6=打开聊天框，7=诊断 GameSettings int 字段，8=切换视角，9=关闭 GUI。 */
    private static volatile int guiPendingCmd;

    public static void postPollKeyboard() {
        try {
            // 先消费 GUI 直驱命令（本方法运行在 MC 主线程，TCCL=LaunchClassLoader）
            int cmd = guiPendingCmd;
            if (cmd != 0) {
                guiPendingCmd = 0;
                if (cmd == 6) {
                    guiOpenChat();
                } else if (cmd == 7) {
                    guiViewDiag();
                } else if (cmd == 8) {
                    guiViewToggle();
                } else if (cmd == 9) {
                    guiCloseGui();
                }
            }
            if (PENDING_KEYS.isEmpty()) {
                return;
            }
            int injected = 0;
            for (Object[] ev : PENDING_KEYS) {
                int keyCode = ((Integer) ev[0]).intValue();
                boolean pressed = ((Boolean) ev[1]).booleanValue();
                try {
                    ByteBuffer kdb = (ByteBuffer) keyDownBufferField.get(null);
                    if (kdb != null && keyCode >= 0 && keyCode < kdb.capacity()) {
                        kdb.put(keyCode, (byte) (pressed ? 1 : 0));
                    }
                } catch (Throwable ignored) {
                }
                driveKeyBinding(keyCode, pressed);
                injected++;
            }
            PENDING_KEYS.clear();
            if (injected > 0) {
                consumedCount += injected;
                stats();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 反射驱动 MC KeyBinding（pressTime/isPressed 机制），见 {@link #postPollKeyboard()}。
     * 注意：① net.minecraft.* 由 Forge LaunchClassLoader 加载，须用线程上下文类加载器；
     * ② 运行时方法名是 SRG 名（func_XXXX），不能用 MCP 名（onTick/setKeyBindState），
     * 因此按参数签名匹配（1×int 的 static void / 2×int,boolean 的 static void）。 */
    private static volatile java.lang.reflect.Method kbOnTick;
    private static volatile java.lang.reflect.Method kbSetState;

    private static void driveKeyBinding(int keyCode, boolean pressed) {
        try {
            if (kbOnTick == null) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                Class<?> kb = Class.forName("net.minecraft.client.settings.KeyBinding", true, cl);
                for (java.lang.reflect.Method m : kb.getDeclaredMethods()) {
                    int mod = m.getModifiers();
                    if (java.lang.reflect.Modifier.isStatic(mod) && m.getReturnType() == void.class) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 1 && pts[0] == int.class && kbOnTick == null) {
                            kbOnTick = m;
                        } else if (pts.length == 2 && pts[0] == int.class && pts[1] == boolean.class && kbSetState == null) {
                            kbSetState = m;
                        }
                    }
                }
                System.out.println("[WebGameInputAgent] KeyBinding resolved via TCCL=" + cl
                        + " onTick=" + (kbOnTick == null ? "null" : kbOnTick.getName())
                        + " setState=" + (kbSetState == null ? "null" : kbSetState.getName()));
            }
            if (kbOnTick == null) {
                System.out.println("[WebGameInputAgent] KeyBinding methods not found, skip code=" + keyCode);
                return;
            }
            if (kbSetState != null) {
                kbSetState.invoke(null, Integer.valueOf(keyCode), Boolean.valueOf(pressed));
            }
            if (pressed) {
                kbOnTick.invoke(null, Integer.valueOf(keyCode));
                System.out.println("[WebGameInputAgent] onTick called code=" + keyCode + " method=" + kbOnTick.getName());
            }
        } catch (Throwable t) {
            System.out.println("[WebGameInputAgent] driveKeyBinding fail code=" + keyCode + " pressed=" + pressed + " err=" + t);
        }
    }

    // ================= 工具 =================

    private static volatile Integer displayHeightCache;

    private static int displayHeight() {
        try {
            if (displayHeightCache == null) {
                Class<?> c = Class.forName("org.lwjgl.opengl.Display");
                java.lang.reflect.Method m = c.getMethod("getHeight");
                Object v = m.invoke(null);
                displayHeightCache = v instanceof Number ? ((Number) v).intValue() : 480;
            }
            return displayHeightCache;
        } catch (Throwable t) {
            return 480;
        }
    }

    /** 每秒把 注入尝试/实际消费 计数写入 input-agent.stats（第一行兼容旧格式）。 */
    private static volatile long lastStatsWrite = 0;

    private static void stats() {
        long now = System.currentTimeMillis();
        if (now - lastStatsWrite < 1000) {
            return;
        }
        lastStatsWrite = now;
        String portFile = System.getProperty("webgame.agent.portFile", "");
        if (!portFile.isEmpty()) {
            writePort(portFile.replace(".port", ".stats"),
                    injectedCount + "," + consumedCount);
        }
    }

    private static ByteBuffer get(Field f) {
        try {
            return f == null ? null : (ByteBuffer) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> cls, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writePort(String file, String port) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(port.getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
