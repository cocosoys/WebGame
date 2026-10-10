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

    // 镜像驱动扩展（v0.5）：launch 模板与资源索引
    private final String launchStyle;    // legacy | bootstrap
    private final String tweakClass;     // legacy 模板用（缺省 FMLTweaker）
    private final String assetIndex;     // --assetIndex 参数（缺省按版本推断）
    private final java.io.File imageInputAgent;  // 镜像内 tools/input-agent.jar（可空 → 插件资源回退）

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
        // 旧路径（cloud.client 单条）一律 legacy 模板
        this.launchStyle = "legacy";
        this.tweakClass = "net.minecraftforge.fml.common.launcher.FMLTweaker";
        this.assetIndex = "";
        this.imageInputAgent = null;
    }

    /**
     * 镜像驱动构造器（v0.5 定案）：从 {@link CloudImage} manifest 取
     * baseDir/versionId/java/assets/launch 模板，实例目录落在镜像内
     * {@code instances/} 下（镜像卸载 = 目录删除，实例目录一并回收）。
     *
     * @param img       镜像（image.json 已解析）
     * @param username  离线用户名（连服身份）
     * @param serverPort 跟随端口（镜像 server.port=0 时使用）
     * @param xmx       堆内存（空则取镜像 defaultXmx）
     * @param listener  生命周期回调
     */
    public GameInstance(CloudImage img, String username, int serverPort, String xmx, Listener listener) {
        this.baseDir = img.getClientRoot().getAbsolutePath();
        this.versionId = img.getVersionId().isEmpty() ? img.getId() : img.getVersionId();
        this.shareDir = img.getMinecraftDir().getAbsolutePath();
        this.instancesRoot = img.getInstancesRoot().getAbsolutePath();
        this.server = img.getServerHost().isEmpty() ? "127.0.0.1" : img.getServerHost();
        this.port = img.getServerPort() > 0 ? img.getServerPort() : serverPort;
        this.username = username;
        this.xmx = (xmx == null || xmx.trim().isEmpty()) ? img.getDefaultXmx() : xmx;
        java.io.File javaExe = img.resolveJavaExecutable();
        this.javaPath = javaExe != null ? javaExe.getAbsolutePath() : "";
        this.readyTimeoutSeconds = 90;
        this.windowTitle = img.getWindowTitle().isEmpty()
                ? "Minecraft " + img.getMcVersion() : img.getWindowTitle();
        this.listener = listener;
        this.launchStyle = img.getLaunchStyle();
        this.tweakClass = img.getTweakClass().isEmpty()
                ? "net.minecraftforge.fml.common.launcher.FMLTweaker" : img.getTweakClass();
        this.assetIndex = img.getAssetIndex().isEmpty()
                ? CloudImage.inferAssetIndex(img.getMcVersion()) : img.getAssetIndex();
        this.imageInputAgent = img.resolveInputAgent();
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
            // 3. 构建 classpath：写入实例目录 classpath.jar（Manifest Class-Path 相对路径，
            //    规避 Windows CreateProcess 命令行 32K 上限 —— libraries 全量绝对路径会超限 error=206）
            String classpath = buildClasspathJar(instDir, share, versionJar);
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

    /** 解出输入注入代理到实例目录：优先镜像 tools/input-agent.jar（自包含），否则插件 jar 资源。 */
    private void extractInputAgent(File instDir) throws IOException {
        File target = new File(instDir, "input-agent.jar");
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (java.io.InputStream in = imageInputAgent != null && imageInputAgent.isFile()
                    ? java.nio.file.Files.newInputStream(imageInputAgent.toPath())
                    : getClass().getClassLoader().getResourceAsStream("input-agent.jar")) {
                if (in == null) {
                    throw new IOException("input-agent.jar 缺失（镜像 tools/ 与插件资源均无）");
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

    /**
     * 构建 classpath：把共享 libraries 全部 jar + 版本 jar 的相对路径写入
     * {@code <实例>/classpath.jar} 的 Manifest Class-Path，命令行只带这一个 jar。
     *
     * <p>原因：libraries 全量绝对路径拼出的 classpath 常超 Windows CreateProcess
     * 32767 字符上限（error=206，文件名或扩展名太长）。Manifest Class-Path 是
     * Java 官方支持的替代超长 classpath 机制（Java 9 @argfile 之前的唯一标准做法），
     * URLClassLoader 从 classpath.jar 所在目录按相对路径惰性展开全部条目。</p>
     */
    private String buildClasspathJar(File instDir, File share, File versionJar) throws IOException {
        List<File> jars = new ArrayList<>();
        // 只收集版本 json 声明的 libraries（官方启动器行为）：
        // 整合包 libraries 目录常为全量冗余（混入其他版本库，如 Java 16 编译的
        // modlauncher），全量扫描会导致 1.12.2 launchwrapper 加载不兼容类崩溃。
        collectDeclaredLibraries(new File(share, "versions" + File.separator + versionId
                + File.separator + versionId + ".json"), new File(share, "libraries"), jars);
        jars.add(versionJar);
        Path base = instDir.toPath().toAbsolutePath();
        List<String> rels = new ArrayList<>(jars.size());
        for (File j : jars) {
            Path rel = base.relativize(j.toPath().toAbsolutePath());
            // Manifest Class-Path 条目为 URL 相对路径：/ 分隔、空格需 %20 转义
            rels.add(rel.toString().replace('\\', '/').replace(" ", "%20"));
        }
        File cpJar = new File(instDir, "classpath.jar");
        try (java.util.jar.JarOutputStream jos =
                     new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(cpJar.toPath()))) {
            jos.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
            java.util.jar.Manifest mf = new java.util.jar.Manifest();
            mf.getMainAttributes().putValue("Manifest-Version", "1.0");
            mf.getMainAttributes().putValue("Class-Path", String.join(" ", rels));
            mf.write(jos);
            jos.closeEntry();
        }
        return cpJar.getAbsolutePath();
    }

    /**
     * 解析版本 json 的 {@code libraries} 声明（零依赖正则抽取 {@code "name"}），
     * 按 {@code group/artifact/version} 路径定位 jar；json 缺失/解析失败时回退
     * 全量扫描（旧行为）。natives 分类 jar 只用于解压，不进入 classpath。
     */
    private static void collectDeclaredLibraries(File versionJson, File libsRoot, List<File> out) {
        if (!versionJson.isFile()) {
            collectJars(libsRoot, out);
            return;
        }
        java.util.regex.Pattern namePattern =
                java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        try {
            String text = new String(java.nio.file.Files.readAllBytes(versionJson.toPath()),
                    StandardCharsets.UTF_8);
            java.util.regex.Matcher m = namePattern.matcher(text);
            boolean any = false;
            while (m.find()) {
                String name = m.group(1);
                if (name.indexOf('n') >= 0 && name.contains("natives")) {
                    continue; // natives 分类（只解压不进 classpath）
                }
                String[] parts = name.split(":");
                if (parts.length < 3) {
                    continue;
                }
                String group = parts[0].replace('.', '/');
                String artifact = parts[1];
                String ver = parts[2];
                String cls = parts.length > 3 ? parts[3] : "";
                String jarName = cls.isEmpty()
                        ? artifact + "-" + ver + ".jar"
                        : artifact + "-" + ver + "-" + cls + ".jar";
                File f = new File(libsRoot, group + File.separator + artifact
                        + File.separator + ver + File.separator + jarName);
                if (f.isFile()) {
                    out.add(f);
                    any = true;
                }
            }
            if (!any) {
                collectJars(libsRoot, out); // json 无有效声明 → 回退全量
            }
        } catch (Throwable t) {
            collectJars(libsRoot, out); // 读取失败 → 回退全量
        }
    }

    private static void collectJars(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collectJars(f, out);
            } else if (f.getName().endsWith(".jar")) {
                out.add(f);
            }
        }
    }

    /** 组装 java 启动命令（legacy = 1.12.x launchwrapper；bootstrap = 1.20+ BootstrapLauncher）。 */
    private List<String> buildCommand(File instDir, File share, String nativesDir, String classpath) {
        boolean bootstrap = "bootstrap".equals(launchStyle);
        String mainClass = bootstrap
                ? "net.minecraftforge.bootstrap.BootstrapLauncher"
                : "net.minecraft.launchwrapper.Launch";
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath.isEmpty() ? "java" : javaPath);
        cmd.add("-Xmx" + xmx);
        cmd.add("-Djava.library.path=" + nativesDir);
        // 输入注入代理（javaagent）——随客户端 JVM 启动，监听本地端口写 input-agent.port
        cmd.add("-javaagent:" + new File(instDir, "input-agent.jar").getAbsolutePath());
        cmd.add("-Dwebgame.agent.portFile="
                + new File(instDir, "input-agent.port").getAbsolutePath());
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass);
        cmd.add("--username");
        cmd.add(username);
        cmd.add("--version");
        cmd.add(versionId);
        cmd.add("--gameDir");
        cmd.add(instDir.getAbsolutePath());
        cmd.add("--assetsDir");
        cmd.add(new File(share, "assets").getAbsolutePath());
        cmd.add("--assetIndex");
        cmd.add(assetIndex.isEmpty() ? "1.12" : assetIndex);
        cmd.add("--uuid");
        cmd.add(uuidFor(username));
        cmd.add("--accessToken");
        cmd.add("0");
        cmd.add("--userType");
        cmd.add("legacy");
        if (bootstrap) {
            // 1.20+ 模块化启动器：无 tweakClass，versionType=forge
            cmd.add("--versionType");
            cmd.add("forge");
        } else {
            cmd.add("--versionType");
            cmd.add("Forge");
            cmd.add("--tweakClass");
            cmd.add(tweakClass);
        }
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
