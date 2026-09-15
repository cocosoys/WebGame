package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 云游戏页面登记器：把 {@code dist/cloud.html}（WebCodecs 播放器 + Pointer Lock 输入）
 * 注册到标准接口路径 {@code /api/plugins/WebGame/cloud/}。
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
        byte[] html = readJarResource("cloud.html");
        String path = config.getCloudPath();
        api.getWebPage().registerProxyPage(plugin, path, "GET", html,
                "text/html; charset=utf-8", true, "WebGame 云游戏（WebCodecs）", null);
        plugin.getLogger().info("WebGame 云游戏页面已登记到 " + path + " （" + html.length + " bytes）");
        // 设备选择页（独立入口：输入用户名之前先选设备型号；未来可扩展更多设计）
        byte[] dev = readJarResource("device-select.html");
        String devPath = config.getCloudDevicePath();
        api.getWebPage().registerProxyPage(plugin, devPath, "GET", dev,
                "text/html; charset=utf-8", true, "WebGame 设备选择", null);
        plugin.getLogger().info("WebGame 设备选择页已登记到 " + devPath + " （" + dev.length + " bytes）");
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
