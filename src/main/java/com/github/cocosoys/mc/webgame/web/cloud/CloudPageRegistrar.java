package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 云游戏页面登记器：把 {@code dist/cloud.html}（WebCodecs 播放器 + Pointer Lock 输入）
 * 注册到标准页面命名空间 {@code /web/plugins/WebGame/}（入口页 / 云游戏 / 设备选择）。
 *
 * <p>与 Eagler 页面并列，不占用根路径；媒体与信令共用同一条 WS（{@code /cloud}）。</p>
 */
public final class CloudPageRegistrar {

    private final JavaPlugin plugin;
    private final WebGameConfig config;
    private final SoysHttpOverMcApi api;

    public CloudPageRegistrar(JavaPlugin plugin, WebGameConfig config, SoysHttpOverMcApi api) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
    }

    public void install() throws Exception {
        // 入口页（index.html）：渲染 Eagler 与云游戏两种登录方式，registerPage 自动挂 /web/plugins/WebGame/
        byte[] index = readJarResource("index.html");
        String indexPath = config.getIndexPath();
        api.getWebPage().registerPage(plugin, indexPath, "GET", index,
                "text/html; charset=utf-8", true, "WebGame 游戏入口", null);
        plugin.getLogger().info("WebGame 入口页已登记到 " + config.getIndexFullUrl() + " （" + index.length + " bytes）");
        // 冗余登记 /index：主插件目录索引兜底只认 .../index 键（无尾斜杠访问
        // /web/plugins/WebGame → 302 → /web/plugins/WebGame/index），不注册则 404。
        if (!"/index".equals(indexPath)) {
            api.getWebPage().registerPage(plugin, "/index", "GET", index,
                    "text/html; charset=utf-8", true, "WebGame 游戏入口", null);
            plugin.getLogger().info("WebGame 入口页冗余登记到 /web/plugins/WebGame/index（目录索引兜底）");
        }

        byte[] html = readJarResource("cloud.html");
        String path = config.getCloudPath();
        api.getWebPage().registerPage(plugin, path, "GET", html,
                "text/html; charset=utf-8", true, "WebGame 云游戏（WebCodecs）", null);
        plugin.getLogger().info("WebGame 云游戏页面已登记到 " + config.getCloudFullUrl() + " （" + html.length + " bytes）");
        // 无尾斜杠别名：主插件目录索引兜底只认 .../index，子目录无斜杠请求会 404，冗余登记同内容
        registerNoSlashAlias(path, html, "WebGame 云游戏（WebCodecs）");
        // 设备选择页（独立入口：输入用户名之前先选设备型号；未来可扩展更多设计）
        byte[] dev = readJarResource("device-select.html");
        String devPath = config.getCloudDevicePath();
        api.getWebPage().registerPage(plugin, devPath, "GET", dev,
                "text/html; charset=utf-8", true, "WebGame 设备选择", null);
        plugin.getLogger().info("WebGame 设备选择页已登记到 " + config.getCloudDeviceFullUrl() + " （" + dev.length + " bytes）");
        registerNoSlashAlias(devPath, dev, "WebGame 设备选择");
    }

    /** 冗余登记无尾斜杠键（如 /cloud → /cloud/ 同内容；根路径 / 已在 install 处理 /index） */
    private void registerNoSlashAlias(String path, byte[] content, String desc) {
        if (path.length() > 1 && path.endsWith("/")) {
            String noSlash = path.substring(0, path.length() - 1);
            api.getWebPage().registerPage(plugin, noSlash, "GET", content,
                    "text/html; charset=utf-8", true, desc, null);
            plugin.getLogger().info("WebGame " + desc + " 冗余登记到 " + WebGameConfig.WEB_PLUGIN_PREFIX + noSlash);
        }
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
}
