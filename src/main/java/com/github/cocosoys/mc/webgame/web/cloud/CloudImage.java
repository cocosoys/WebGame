package com.github.cocosoys.mc.webgame.web.cloud;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 云游戏镜像（自包含应用包，目录即镜像）。
 *
 * <p>镜像目录结构（v0.5 定案，docs/GFN-Windows-Architecture.md §11.2）：</p>
 * <pre>
 * server-cloud/images/
 * └── mc-1.12.2-v2/
 *     ├── image.json               ← manifest（镜像自描述，唯一事实来源）
 *     ├── runtime/jre8/            ← 镜像自带 JDK（版本锁定）
 *     ├── client/                  ← MC 客户端根（.minecraft：versions/libraries/assets/mods）
 *     ├── tools/ffmpeg.exe         ← 采集编码依赖
 *     └── tools/input-agent.jar    ← 输入注入代理（javaagent）
 * </pre>
 *
 * <p>image.json 字段（缺省均有合理回落）：</p>
 * <pre>
 * id / version / displayName / mcVersion
 * launchStyle        : legacy（1.12.x launchwrapper）| bootstrap（1.20+ BootstrapLauncher）
 * java.path          : 镜像内 JDK 可执行文件（相对镜像根；缺省按 runtime/jre8|jre17 探测）
 * java.defaultXmx    : 默认堆内存
 * launch.versionId   : versions 目录下版本 id
 * launch.tweakClass  : legacy 模板用（Forge FMLTweaker）
 * launch.assetIndex  : 资源索引（缺省按 mcVersion 推断：1.12.x→"1.12"、1.20.x→"8"）
 * capture.engine     : 采集引擎（gdigrab）
 * capture.windowTitle: 采集窗口标题匹配
 * resource.defaultCores / defaultFps / defaultRes
 * server.host / server.port（port=0 跟随 server-port）
 * </pre>
 */
public final class CloudImage {

    /** 镜像唯一 id（manifest.id，用于 URL ?client=<id> 选定）。 */
    private final String id;
    /** 镜像版本号（更新 = 新目录/新版本，同 id 可取新版本）。 */
    private final String version;
    private final String displayName;
    private final String mcVersion;
    private final String launchStyle;
    private final String javaPath;        // 相对镜像根的 JDK 可执行文件
    private final String defaultXmx;
    private final String versionId;
    private final String tweakClass;
    private final String assetIndex;
    private final String captureEngine;
    private final String windowTitle;
    private final int defaultCores;
    private final int defaultFps;
    private final int defaultWidth;
    private final int defaultHeight;
    private final String serverHost;
    private final int serverPort;

    /** 镜像目录绝对路径（manifest 所在目录）。 */
    private final File dir;

    private CloudImage(Builder b, File dir) {
        this.id = b.id;
        this.version = b.version;
        this.displayName = b.displayName;
        this.mcVersion = b.mcVersion;
        this.launchStyle = b.launchStyle;
        this.javaPath = b.javaPath;
        this.defaultXmx = b.defaultXmx;
        this.versionId = b.versionId;
        this.tweakClass = b.tweakClass;
        this.assetIndex = b.assetIndex;
        this.captureEngine = b.captureEngine;
        this.windowTitle = b.windowTitle;
        this.defaultCores = b.defaultCores;
        this.defaultFps = b.defaultFps;
        this.defaultWidth = b.defaultWidth;
        this.defaultHeight = b.defaultHeight;
        this.serverHost = b.serverHost;
        this.serverPort = b.serverPort;
        this.dir = dir;
    }

    // ===== getters =====

    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getDisplayName() { return displayName; }
    public String getMcVersion() { return mcVersion; }
    public String getLaunchStyle() { return launchStyle; }
    public String getJavaPath() { return javaPath; }
    public String getDefaultXmx() { return defaultXmx; }
    public String getVersionId() { return versionId; }
    public String getTweakClass() { return tweakClass; }
    public String getAssetIndex() { return assetIndex; }
    public String getCaptureEngine() { return captureEngine; }
    public String getWindowTitle() { return windowTitle; }
    public int getDefaultCores() { return defaultCores; }
    public int getDefaultFps() { return defaultFps; }
    public int getDefaultWidth() { return defaultWidth; }
    public int getDefaultHeight() { return defaultHeight; }
    public String getServerHost() { return serverHost; }
    public int getServerPort() { return serverPort; }
    public File getDir() { return dir; }

    // ===== 派生 =====

    /** 客户端根目录（含 .minecraft；缺省 = 镜像根/client）。 */
    public File getClientRoot() {
        return new File(dir, "client");
    }

    /** 共享 .minecraft 目录（libraries/assets/versions/mods 源）。 */
    public File getMinecraftDir() {
        return new File(getClientRoot(), ".minecraft");
    }

    /** 实例目录根（每会话独立子目录；缺省 = 镜像根/instances）。 */
    public File getInstancesRoot() {
        return new File(dir, "instances");
    }

    /** 镜像内 JDK 可执行文件绝对路径；缺省按版本探测 runtime/jre8|jre17/bin/java.exe。 */
    public File resolveJavaExecutable() {
        if (javaPath != null && !javaPath.isEmpty()) {
            File f = new File(dir, javaPath);
            if (f.isFile()) {
                return f;
            }
        }
        File jre8 = new File(dir, "runtime/jre8/bin/java.exe");
        if (jre8.isFile()) {
            return jre8;
        }
        File jre17 = new File(dir, "runtime/jre17/bin/java.exe");
        if (jre17.isFile()) {
            return jre17;
        }
        // 兜底：目录内任意 runtime/jre8|jre17/bin/java.exe
        File runtime = new File(dir, "runtime");
        File[] sub = runtime.listFiles();
        if (sub != null) {
            for (File s : sub) {
                if (s.isDirectory()) {
                    File j = new File(s, "bin/java.exe");
                    if (j.isFile()) {
                        return j;
                    }
                }
            }
        }
        return null;
    }

    /** 镜像内 ffmpeg 可执行文件；缺省 tools/ffmpeg.exe（不存在返回 null，由调用方决定探测全局）。 */
    public File resolveFfmpeg() {
        File f = new File(dir, "tools/ffmpeg.exe");
        return f.isFile() ? f : null;
    }

    /** 镜像内 input-agent.jar（javaagent 注入代理）；缺省 tools/input-agent.jar。 */
    public File resolveInputAgent() {
        File f = new File(dir, "tools/input-agent.jar");
        return f.isFile() ? f : null;
    }

    /** 镜像展示摘要（设备选择页渲染用）。 */
    public String summary() {
        return displayName + " v" + version
                + "（" + defaultCores + "核 / " + defaultXmx + " / "
                + defaultWidth + "x" + defaultHeight + " / " + defaultFps + "fps）";
    }

    // ===== 解析 =====

    /** 从镜像目录解析 image.json；缺失/损坏返回 null（扫描时跳过并告警）。 */
    public static CloudImage parse(File imageDir) {
        if (imageDir == null || !imageDir.isDirectory()) {
            return null;
        }
        File manifest = new File(imageDir, "image.json");
        if (!manifest.isFile()) {
            return null;
        }
        YamlConfiguration y;
        try (InputStreamReader r = new InputStreamReader(
                Files.newInputStream(manifest.toPath()), StandardCharsets.UTF_8)) {
            y = YamlConfiguration.loadConfiguration(r);
        } catch (IOException e) {
            return null;
        }
        Builder b = new Builder();
        b.id = str(y.get("id"));
        if (b.id.isEmpty()) {
            b.id = imageDir.getName(); // 缺省：目录名即 id
        }
        b.version = str(y.get("version"));
        if (b.version.isEmpty()) {
            b.version = "1";
        }
        b.displayName = str(y.get("displayName"));
        if (b.displayName.isEmpty()) {
            b.displayName = b.id;
        }
        b.mcVersion = str(y.get("mcVersion"));
        if (b.mcVersion.isEmpty()) {
            b.mcVersion = b.id;
        }
        b.launchStyle = str(y.get("launchStyle")).toLowerCase();
        if (!"bootstrap".equals(b.launchStyle)) {
            b.launchStyle = "legacy";
        }
        ConfigurationSection java = y.getConfigurationSection("java");
        if (java != null) {
            b.javaPath = str(java.getString("path"));
            b.defaultXmx = normalizeXmx(java.getString("defaultXmx", "2G"));
        } else {
            b.javaPath = "";
            b.defaultXmx = "2G";
        }
        ConfigurationSection launch = y.getConfigurationSection("launch");
        if (launch != null) {
            b.versionId = str(launch.getString("versionId"));
            b.tweakClass = str(launch.getString("tweakClass"));
            b.assetIndex = str(launch.getString("assetIndex"));
        }
        ConfigurationSection capture = y.getConfigurationSection("capture");
        if (capture != null) {
            b.captureEngine = str(capture.getString("engine"));
            b.windowTitle = str(capture.getString("windowTitle"));
        }
        ConfigurationSection resource = y.getConfigurationSection("resource");
        if (resource != null) {
            b.defaultCores = clamp(resource.getInt("defaultCores", 2), 1, 8);
            b.defaultFps = clamp(resource.getInt("defaultFps", 30), 10, 60);
            String res = str(resource.getString("defaultRes"));
            if (res.indexOf('x') > 0) {
                String[] parts = res.split("x");
                try {
                    b.defaultWidth = clamp(Integer.parseInt(parts[0].trim()), 320, 2560);
                    b.defaultHeight = clamp(Integer.parseInt(parts[1].trim()), 240, 1440);
                } catch (NumberFormatException ignored) {
                    b.defaultWidth = 1280;
                    b.defaultHeight = 720;
                }
            } else {
                b.defaultWidth = 1280;
                b.defaultHeight = 720;
            }
        } else {
            b.defaultCores = 2;
            b.defaultFps = 30;
            b.defaultWidth = 1280;
            b.defaultHeight = 720;
        }
        ConfigurationSection server = y.getConfigurationSection("server");
        if (server != null) {
            b.serverHost = str(server.getString("host"));
            b.serverPort = Math.max(0, server.getInt("port", 0));
        }
        return b.build(imageDir);
    }

    /** 按 mcVersion 推断资源索引：1.12.x→"1.12"、1.20.x→"8"；未知名返回空（由启动模板兜底）。 */
    static String inferAssetIndex(String mcVersion) {
        String v = mcVersion == null ? "" : mcVersion.trim();
        if (v.startsWith("1.12")) {
            return "1.12";
        }
        if (v.startsWith("1.20")) {
            return "8";
        }
        if (v.startsWith("1.21")) {
            return "15";
        }
        if (v.startsWith("1.19")) {
            return "2";
        }
        if (v.startsWith("1.18")) {
            return "1";
        }
        return "";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String normalizeXmx(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase();
        if (!s.matches("[1-8]G")) {
            return "2G";
        }
        return s;
    }

    /** 构造器（Builder 模式；parse 入口）。 */
    static final class Builder {
        String id = "";
        String version = "1";
        String displayName = "";
        String mcVersion = "";
        String launchStyle = "legacy";
        String javaPath = "";
        String defaultXmx = "2G";
        String versionId = "";
        String tweakClass = "";
        String assetIndex = "";
        String captureEngine = "gdigrab";
        String windowTitle = "";
        int defaultCores = 2;
        int defaultFps = 30;
        int defaultWidth = 1280;
        int defaultHeight = 720;
        String serverHost = "127.0.0.1";
        int serverPort = 0;

        CloudImage build(File dir) {
            return new CloudImage(this, dir);
        }
    }
}
