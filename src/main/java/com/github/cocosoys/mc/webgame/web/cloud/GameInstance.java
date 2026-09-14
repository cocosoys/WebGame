package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.config.WebGameConfig;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 容器客户端（游戏实例）：由插件动态 spawn 的独立 Minecraft Forge 客户端进程。
 *
 * <p>每个会话使用独立实例目录 {@code instances-dir/<username>/instance} 作为
 * {@code --gameDir}（客户端日志/配置互不覆盖），共享目录（versions/mods）通过
 * Windows junction（mklink /J）链接，失败时回退复制。natives 解压到实例目录，
 * classpath 使用共享 libraries 的绝对路径。客户端 stdout/stderr 重定向到实例目录
 * 日志文件，避免污染服务器 stdout/日志。</p>
 *
 * <p>启动参数（1.12.2 Forge）：{@code --username <user> --version <id> --gameDir <dir>
 * --assetsDir <共享assets> --assetIndex 1.12 --uuid <uuid> --accessToken 0 --userType legacy
 * --versionType Forge --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker
 * --server <host> --port <port>}。服务器为离线模式（online-mode=false），
 * 任意用户名 + legacy 认证即可直连。</p>
 */
public final class GameInstance {

    /** 生命周期回调（均在 GameInstance 工作线程触发）。 */
    public interface Listener {
        /** 客户端已进入游戏、窗口已可采集（参数为窗口标题）。 */
        void onReady(String windowTitle);

        /** 进程退出（含被主动停止）。 */
        void onStopped(String reason);

        /** 启动失败（配置错误 / 库缺失 / 超时），实例未可用。 */
        void onUnavailable(String reason);
    }

    private final String baseDir;
    private final String versionId;
    private final String shareDir;   // 共享 .minecraft（libraries/assets/versions/mods 源）
    private final String instancesRoot;
    private final String server;
    private final int port;
    private final String username;
    private final String xmx;
    private final String javaPath;
    private final int readyTimeoutSeconds;
    private final String windowTitle;
    private final Listener listener;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile Process process;
    private volatile Thread worker;
    private volatile String instanceDir;

    public GameInstance(WebGameConfig config, String username, int serverPort, Listener listener) {
        this.baseDir = config.getClientBaseDir();
        this.versionId = config.getClientVersion();
        this.shareDir = config.getClientGameDir().isEmpty()
                ? baseDir + File.separator + ".minecraft" : config.getClientGameDir();
        this.instancesRoot = config.getClientInstancesDir().isEmpty()
                ? baseDir + File.separator + "instances" : config.getClientInstancesDir();
        this.server = config.getClientServer().isEmpty()
                ? config.getLoopbackHost() : config.getClientServer();
        this.port = config.getClientPort() > 0 ? config.getClientPort() : serverPort;
        this.username = username;
        this.xmx = config.getClientXmx();
        this.javaPath = config.getClientJavaPath();
        this.readyTimeoutSeconds = config.getClientReadyTimeoutSeconds();
        this.windowTitle = config.getClientWindowTitle();
        this.listener = listener;
    }

    /** 异步启动：工作线程完成实例目录/natives/classpath/进程/就绪等待。 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(this::run, "WebGame-Cloud-GameInstance-" + username);
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** 停止并回收客户端进程（幂等）。 */
    public void stop() {
        stopping.set(true);
        Process p = process;
        process = null;
        if (p != null && p.isAlive()) {
            killTree(p);
        }
        if (worker != null) {
            try {
                worker.join(3000);
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public String getWindowTitle() {
        return windowTitle;
    }

    /** 容器客户端实例目录（含 input-agent.port 等输入通道状态文件）。 */
    public String getInstanceDir() {
        return instanceDir;
    }

    // ================= 工作线程 =================

    private void run() {
        try {
            File share = new File(shareDir);
            if (!new File(share, "versions").isDirectory()) {
                listener.onUnavailable("客户端目录无效（无 versions）：" + shareDir);
                return;
            }
            File versionJar = new File(share, "versions" + File.separator + versionId
                    + File.separator + versionId + ".jar");
            if (!versionJar.isFile()) {
                listener.onUnavailable("未找到版本 jar：" + versionJar);
                return;
            }
            // 1. 准备独立实例目录（--gameDir；共享目录 junction 链接）
            File instDir = new File(instancesRoot, username + File.separator + "instance");
            prepareInstanceDir(instDir, share);
            instanceDir = instDir.getAbsolutePath();
            // 1.5 解出输入注入代理 jar（路由 B：javaagent）到实例目录
            extractInputAgent(instDir);
            // 2. 解压 natives（幂等，实例目录内）
            String nativesDir = prepareNatives(new File(instDir, "natives"));
            // 3. 构建 classpath（共享 libraries 绝对路径 + 版本 jar）
            String classpath = buildClasspath(share, versionJar);
            // 4. 组装启动命令
            List<String> cmd = buildCommand(instDir, share, nativesDir, classpath);
            // 5. spawn 进程（stdout/stderr 重定向到实例日志，避免污染服务器 stdout）
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            File stdoutLog = new File(instDir, "client-stdout.log");
            pb.redirectOutput(stdoutLog);
            pb.redirectError(stdoutLog);
            Process p = pb.start();
            process = p;
            // 6. 就绪等待（客户端 stdout 已重定向到 client-stdout.log；latest.log 仅作备用）
            boolean ready = awaitReady(p, stdoutLog);
            if (ready && !stopping.get()) {
                // 窗口创建后稍等首帧渲染
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
                listener.onReady(windowTitle);
                return;
            }
            if (p.isAlive()) {
                if (!ready) {
                    listener.onUnavailable("客户端启动超时（" + readyTimeoutSeconds + "s 未进入服务器）");
                }
                killTree(p);
                return;
            }
            listener.onStopped("客户端进程已退出");
        } catch (Throwable t) {
            listener.onUnavailable("客户端启动失败: " + t.getMessage());
        }
    }

    /** 从插件 jar 资源解出 input-agent.jar 到实例目录（每次覆盖，带占用重试）。 */
    private void extractInputAgent(File instDir) throws IOException {
        File target = new File(instDir, "input-agent.jar");
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("input-agent.jar")) {
                if (in == null) {
                    throw new IOException("插件资源缺失 input-agent.jar（打包时未包含输入注入代理）");
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e; // Windows 上旧客户端进程可能仍锁定 javaagent jar，短暂重试
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("input-agent.jar 解出失败（未知原因）");
    }

    /** 准备实例目录：logs 目录 + versions/mods 的 junction（失败回退复制）。 */
    private void prepareInstanceDir(File instDir, File share) throws IOException {
        instDir.mkdirs();
        new File(instDir, "logs").mkdirs();
        linkOrCopy(instDir, share, "versions");
        linkOrCopy(instDir, share, "mods");
        // assets 通过 --assetsDir 直接指向共享目录，不需要链接
    }

    /** 在 instDir 下建立指向 shareDir/<name> 的 junction；失败则复制目录。 */
    private void linkOrCopy(File instDir, File share, String name) {
        File link = new File(instDir, name);
        File target = new File(share, name);
        if (!target.isDirectory()) {
            return;
        }
        if (link.exists()) {
            return;
        }
        try {
            Process j = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.getAbsolutePath(), target.getAbsolutePath())
                    .redirectErrorStream(true).start();
            j.waitFor();
            if (link.isDirectory()) {
                return; // junction 建立成功
            }
        } catch (Throwable ignored) {
        }
        // 回退：复制目录（versions/mods 体积可接受）
        try {
            copyDir(target, link);
        } catch (IOException e) {
            // 复制失败则留空目录（后续启动会报库缺失，由 onUnavailable 反馈）
        }
    }

    private static void copyDir(File src, File dst) throws IOException {
        if (!dst.exists()) {
            dst.mkdirs();
        }
        File[] files = src.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            Path s = f.toPath();
            Path d = dst.toPath().resolve(f.getName());
            if (f.isDirectory()) {
                copyDir(f, d.toFile());
            } else {
                Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 解压共享 libraries 下所有 *-natives-windows.jar 内的 dll/so 到目标目录（幂等）。 */
    private String prepareNatives(File outDir) throws IOException {
        outDir.mkdirs();
        List<File> nativeJars = new ArrayList<>();
        collectNativeJars(new File(shareDir, "libraries"), nativeJars);
        for (File nj : nativeJars) {
            try (ZipFile zip = new ZipFile(nj)) {
                java.util.Enumeration<? extends ZipEntry> en = zip.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) {
                        continue;
                    }
                    String lower = e.getName().toLowerCase();
                    if (!lower.endsWith(".dll") && !lower.endsWith(".so") && !lower.endsWith(".dylib")) {
                        continue;
                    }
                    String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                    Path target = outDir.toPath().resolve(base);
                    if (!Files.exists(target)) {
                        Files.copy(zip.getInputStream(e), target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException ignored) {
                // 单个 native jar 损坏不影响其余
            }
        }
        return outDir.getAbsolutePath();
    }

    private static void collectNativeJars(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectNativeJars(f, out);
            } else if (f.getName().toLowerCase().contains("-natives-windows.jar")) {
                out.add(f);
            }
        }
    }

    /** 构建 classpath：共享 libraries 下全部 jar + 版本 jar。 */
    private String buildClasspath(File share, File versionJar) {
        List<String> jars = new ArrayList<>();
        collectJars(new File(share, "libraries"), jars);
        jars.add(versionJar.getAbsolutePath());
        return String.join(File.pathSeparator, jars);
    }

    private static void collectJars(File dir, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectJars(f, out);
            } else if (f.getName().endsWith(".jar")) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    /** 组装 java 启动命令（1.12.2 Forge 离线客户端）。 */
    private List<String> buildCommand(File instDir, File share, String nativesDir, String classpath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath.isEmpty() ? "java" : javaPath);
        cmd.add("-Xmx" + xmx);
        cmd.add("-Djava.library.path=" + nativesDir);
        // 路线 B：输入注入代理（javaagent）——随客户端 JVM 启动，监听本地端口写 input-agent.port
        cmd.add("-javaagent:" + new File(instDir, "input-agent.jar").getAbsolutePath());
        cmd.add("-Dwebgame.agent.portFile="
                + new File(instDir, "input-agent.port").getAbsolutePath());
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add("net.minecraft.launchwrapper.Launch");
        cmd.add("--username");
        cmd.add(username);
        cmd.add("--version");
        cmd.add(versionId);
        cmd.add("--gameDir");
        cmd.add(instDir.getAbsolutePath());
        cmd.add("--assetsDir");
        cmd.add(new File(share, "assets").getAbsolutePath());
        cmd.add("--assetIndex");
        cmd.add("1.12");
        cmd.add("--uuid");
        cmd.add(uuidFor(username));
        cmd.add("--accessToken");
        cmd.add("0");
        cmd.add("--userType");
        cmd.add("legacy");
        cmd.add("--versionType");
        cmd.add("Forge");
        cmd.add("--tweakClass");
        cmd.add("net.minecraftforge.fml.common.launcher.FMLTweaker");
        cmd.add("--server");
        cmd.add(server);
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        return cmd;
    }

    /** 轮询客户端日志（stdout 重定向文件，备用 latest.log）直到出现进服信号或超时。 */
    private boolean awaitReady(Process p, File stdoutLog) {
        File mcLog = new File(instanceDir, "logs" + File.separator + "latest.log");
        long deadline = System.currentTimeMillis() + readyTimeoutSeconds * 1000L;
        long lastSize = -1;
        long lastMcSize = -1;
        while (!stopping.get() && System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) {
                return false;
            }
            File f = stdoutLog.isFile() ? stdoutLog : mcLog;
            if (f.isFile()) {
                long size = f.length();
                long last = (f == stdoutLog) ? lastSize : lastMcSize;
                if (size != last) {
                    if (f == stdoutLog) {
                        lastSize = size;
                    } else {
                        lastMcSize = size;
                    }
                    String tail = tail(f, 8192);
                    if (tail.contains("Setting user") || tail.contains("joined the game")
                            || tail.contains("Connecting to")) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** 读取文件尾部文本（UTF-8）。 */
    private static String tail(File f, int maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 由用户名生成固定 UUID（离线模式，服务端按名识别）。 */
    private static String uuidFor(String name) {
        return String.format("%08x-%04x-%04x-%04x-%012x",
                name.hashCode(), 0x1234, 0x5678, 0x9abc, 0xdef0L);
    }

    /** 结束进程树：taskkill /T /F（PID 可获取时），否则 destroy()（Windows TerminateProcess）。 */
    private void killTree(Process p) {
        boolean tried = false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                long pid = getPid(p);
                if (pid > 0) {
                    Process k = new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                            .redirectErrorStream(true).start();
                    k.waitFor();
                    tried = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!tried || p.isAlive()) {
            try {
                p.destroy();
            } catch (Throwable t) {
                try {
                    p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 读取 Java 8 进程 PID（ProcessImpl/UNIXProcess 私有字段 pid）。 */
    private static long getPid(Process p) {
        try {
            Class<?> cls = p.getClass();
            while (cls != null && cls.getSuperclass() != Object.class) {
                cls = cls.getSuperclass();
            }
            java.lang.reflect.Field f = cls.getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            return v instanceof Number ? ((Number) v).longValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
