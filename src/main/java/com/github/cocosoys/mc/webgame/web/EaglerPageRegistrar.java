package com.github.cocosoys.mc.webgame.web;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

/**
 * Eaglercraft 页面登记器（M1）。
 *
 * <p>职责：从插件 jar 读取 Eaglercraft HTML → 把 {@code eaglercraftXOpts.servers}
 * 的服务器地址注入为本地 WS 地址（如 {@code ws://127.0.0.1:25574/eagler}）→
 * 注入 joinServer 与独立 bootstrap 脚本引用（{@code eagler-bootstrap.js}，前端按需加载）→
 * 落盘一份到 {@code plugins/WebGame/dist/}（便于检查/热替换）→
 * 通过 SOYSHTTPOverMC 登记到标准页面命名空间 {@code /web/plugins/WebGame/eagler/}。</p>
 */
public final class EaglerPageRegistrar {

    /** 客户端 HTML 内当前第三方服务器地址（注入的替换目标） */
    private static final String OLD_ADDR = "wss://mc.adderall.ir";

    private final JavaPlugin plugin;
    private final WebGameConfig config;
    private final SoysHttpOverMcApi api;
    private final File distDir;

    public EaglerPageRegistrar(JavaPlugin plugin, WebGameConfig config, SoysHttpOverMcApi api) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.distDir = new File(plugin.getDataFolder(), "dist");
    }

    public void install() throws Exception {
        if (!distDir.exists() && !distDir.mkdirs()) {
            throw new IllegalStateException("无法创建 dist 目录: " + distDir);
        }

        byte[] raw = readJarResource(config.getHtmlFile());
        byte[] injected = injectAddr(raw);
        if (config.isAutoJoinEnabled()) {
            injected = injectJoinServer(injected);
            injected = injectBootstrapScriptTag(injected);
        }

        // 落盘注入后的副本（便于人工检查注入结果 / 未来热替换）
        File out = new File(distDir, config.getHtmlFile());
        if (!out.exists() || out.length() != injected.length) {
            Files.write(out.toPath(), injected);
            plugin.getLogger().info("已写出注入后的客户端副本: " + out);
        }

        // 标准页面登记（registerPage：SOYS 自动补 /web/plugins/WebGame 前缀），
        // GET <page-path> 出游戏页面，不再占用根路径 "/"（SOYSHTTPOverMC 默认首页恢复）。
        // 独立 bootstrap 脚本：随插件 jar 打包，注册为 <page-path>eagler-bootstrap.js 路由，
        // 由 HTML 内 <script src="/web/plugins/WebGame/eagler/eagler-bootstrap.js" defer> 前端按需加载
        // （defer 保证页面解析完成后才执行，运行时机受控；JS 本体非后端注入）。
        String pagePath = config.getPagePath();
        byte[] bootstrapJs = readJarResource("eagler-bootstrap.js");
        api.getWebPage().registerPage(plugin, pagePath + "eagler-bootstrap.js", "GET", bootstrapJs,
                "application/javascript; charset=utf-8", true, "WebGame bootstrap 脚本", null);
        plugin.getLogger().info("Eaglercraft bootstrap 脚本已登记到 " + WebGameConfig.WEB_PLUGIN_PREFIX
                + pagePath + "eagler-bootstrap.js (" + bootstrapJs.length + " bytes)");

        api.getWebPage().registerPage(plugin, pagePath, "GET", injected, "text/html; charset=utf-8",
                true, "Eaglercraft 1.12.2 网页客户端", null);
        plugin.getLogger().info("Eaglercraft 页面已登记到 " + config.getPageFullUrl() + " （" + injected.length + " bytes）");
    }

    private byte[] readJarResource(String name) throws Exception {
        String resPath = "dist/" + name;
        try (InputStream in = plugin.getClass().getClassLoader().getResourceAsStream(resPath)) {
            if (in == null) {
                throw new IllegalStateException("jar 资源不存在: " + resPath);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 注入本地 WS 地址：{@code addr: "wss://mc.adderall.ir"} → {@code addr: "ws://<host>:<port>/eagler"}。
     *
     * <p><b>实现约束</b>：必须做<b>字节级替换</b>（indexOf + arraycopy 拼接），不能用 String.replace——
     * 31.5MB HTML 转 UTF-16 String 后单次 replace 产生 60MB+ 副本，多次链式 replace 在 512MB 堆下直接 OOM
     * （M1 实测踩坑）。字节级替换峰值内存 ≈ 2 份原始数组（约 63MB）。</p>
     *
     * <p>未找到替换标记时返回原始字节并告警（页面按原样托管，此时玩家无法连本地隧道）。</p>
     */
    byte[] injectAddr(byte[] raw) {
        byte[] marker = ("addr: \"" + OLD_ADDR + "\"").getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(raw, marker);
        if (idx < 0) {
            plugin.getLogger().warning("未找到服务器地址标记 " + OLD_ADDR + "，页面保持原样托管");
            return raw;
        }

        String host = config.getPublicHost();
        int port = config.getPublicPort(plugin.getServer().getPort());
        String wsAddr = "ws://" + host + ":" + port + config.getWsPath();
        byte[] replacement = ("addr: \"" + wsAddr + "\"").getBytes(StandardCharsets.UTF_8);

        byte[] out = new byte[raw.length - marker.length + replacement.length];
        System.arraycopy(raw, 0, out, 0, idx);
        System.arraycopy(replacement, 0, out, idx, replacement.length);
        int tailLen = raw.length - idx - marker.length;
        System.arraycopy(raw, idx + marker.length, out, idx + replacement.length, tailLen);

        plugin.getLogger().info("已注入服务器地址: " + wsAddr);
        return out;
    }

    /**
     * 注入 {@code joinServer} 启动选项：客户端启动后自动连接本地 WS 隧道，
     * 免去玩家手动点击「Join Server」。
     *
     * <p>标记：launch options 中 {@code servers: [...]} 数组结束后的 {@code ],\n        ;}。
     * 未找到标记时返回原字节并告警（此时仅能手动加入）。</p>
     */
    byte[] injectJoinServer(byte[] raw) {
        byte[] marker = "                ],\n        };".getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(raw, marker);
        if (idx < 0) {
            plugin.getLogger().warning("未找到 servers 数组结束标记，无法注入 joinServer（自动进服关闭）");
            return raw;
        }
        String wsAddr = "ws://" + config.getPublicHost() + ":" + config.getPublicPort(plugin.getServer().getPort())
                + config.getWsPath();
        byte[] replacement = ("                ],\n        joinServer: \"" + wsAddr + "\",\n        };")
                .getBytes(StandardCharsets.UTF_8);

        byte[] out = new byte[raw.length - marker.length + replacement.length];
        System.arraycopy(raw, 0, out, 0, idx);
        System.arraycopy(replacement, 0, out, idx, replacement.length);
        int tailLen = raw.length - idx - marker.length;
        System.arraycopy(raw, idx + marker.length, out, idx + replacement.length, tailLen);

        plugin.getLogger().info("已注入 joinServer: " + wsAddr);
        return out;
    }

    /**
     * 注入独立 bootstrap 脚本引用：在客户端 launch options 结束标记后追加
     * {@code <script src="/eagler-bootstrap.js" defer></script>}。
     *
     * <p><b>v4 变更</b>：自动进服引导 JS 从「Java 字符串内嵌 + 字节级注入 HTML」
     * 改为独立文件 {@code dist/eagler-bootstrap.js}，随插件 jar 打包，并由 SOYS
     * 以 {@code /eagler-bootstrap.js} 路由托管。页面仅注入一行 script 引用，
     * 浏览器在 HTML 解析完成后（defer）才加载执行——运行时机由浏览器按需控制，
     * 不再在页面解析到内嵌 script 时立即执行，避免玩家长时间挂机在用户名输入界面时
     * 引导脚本已提前运行。</p>
     */
    byte[] injectBootstrapScriptTag(byte[] raw) {
        byte[] marker = "        // %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n    </script>".getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(raw, marker);
        if (idx < 0) {
            plugin.getLogger().warning("未找到 launch options 结束标记，无法注入 bootstrap 脚本引用");
            return raw;
        }
        byte[] replacement = ("        // %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n    </script>\n"
                + "    <script src=\"" + WebGameConfig.WEB_PLUGIN_PREFIX + config.getPagePath()
                + "eagler-bootstrap.js\" defer></script>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length - marker.length + replacement.length];
        System.arraycopy(raw, 0, out, 0, idx);
        System.arraycopy(replacement, 0, out, idx, replacement.length);
        int tailLen = raw.length - idx - marker.length;
        System.arraycopy(raw, idx + marker.length, out, idx + replacement.length, tailLen);

        plugin.getLogger().info("已注入 bootstrap 脚本引用 <script src=" + WebGameConfig.WEB_PLUGIN_PREFIX
                + config.getPagePath() + "eagler-bootstrap.js defer>");
        return out;
    }

    /**
     * 生成客户端 profile 的 gzip-NBT base64。
     *
     * <p>字段结构与当前客户端实际存储的 profile 一致（已实测逆向，见
     * {@link #injectBootstrapScriptTag(byte[])} 注释）。NBT 字符串用 {@link DataOutputStream#writeUTF}
     * （modified UTF-8 + UTF-16 码元数长度），与客户端 Java readUTF 完全兼容。</p>
     */
    static String buildProfileB64(String username) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeByte(10); out.writeUTF("");               // root: Compound ""
        out.writeByte(9); out.writeUTF("capes");          // capes: List<End>
        out.writeByte(0); out.writeInt(0);
        out.writeByte(3); out.writeUTF("presetCape");     // presetCape: Int 0
        out.writeInt(0);
        out.writeByte(3); out.writeUTF("customCape");     // customCape: Int -1
        out.writeInt(-1);
        out.writeByte(9); out.writeUTF("skins");          // skins: List<End>
        out.writeByte(0); out.writeInt(0);
        out.writeByte(8); out.writeUTF("username");       // username: String
        out.writeUTF(username);
        out.writeByte(3); out.writeUTF("presetSkin");     // presetSkin: Int 0
        out.writeInt(0);
        out.writeByte(3); out.writeUTF("customSkin");     // customSkin: Int -1
        out.writeInt(-1);
        out.writeByte(0);                                 // end compound
        out.flush();

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
            g.write(bos.toByteArray());
        }
        return Base64.getEncoder().encodeToString(gz.toByteArray());
    }

    /**
     * v4：自动进服引导 JS 已从本常量内嵌改为独立文件
     * {@code src/main/resources/dist/eagler-bootstrap.js}（随 jar 打包，
     * 由 {@link #injectBootstrapScriptTag(byte[])} 注入的
     * {@code <script src="/eagler-bootstrap.js" defer>} 按需加载执行）。
     */
    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
