package com.github.cocosoys.mc.webgame.config;

import com.github.cocosoys.mc.soyshttpovermc.platform.PlatformYaml;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * WebGame 的内部运行态（data/runtime-state.yml）。
 *
 * <p>默认客户端、玩家自选开关、制品预选与序号都保存在这里，不再写入
 * config.yml，也不出现在 ERP「核心配置」页。</p>
 */
public final class WebGameRuntimeState {

    private static final String FILE_NAME = "data/runtime-state.yml";
    private static final String KEY_EAGLER_DEFAULT_ID = "eagler.default-client";
    private static final String KEY_EAGLER_ALLOW_SELECT = "eagler.player-selectable";
    private static final String KEY_CLOUD_DEFAULT_ID = "cloud.default-device";
    private static final String KEY_CLOUD_ALLOW_SELECT = "cloud.player-selectable";
    private static final String KEY_CLOUD_NEXT_ID = "cloud.next-device-number";
    private static final String KEY_PACKAGE_PRESELECT = "packages.preselect-cloud";
    private static final String KEY_PACKAGE_NEXT_EAGLER = "packages.next-eagler-number";
    private static final String KEY_PACKAGE_NEXT_CLOUD = "packages.next-cloud-number";

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public WebGameRuntimeState(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建运行态目录: " + parent);
        }
        YamlConfiguration loaded = null;
        if (file.isFile()) {
            try {
                loaded = PlatformYaml.load(file);
            } catch (Throwable t) {
                plugin.getLogger().warning("读取运行态失败，使用默认值: " + t.getMessage());
            }
        }
        this.yaml = loaded == null ? new YamlConfiguration() : loaded;
        if (!file.isFile()) {
            save();
        }
    }

    public String getEaglerDefaultId() {
        return trim(yaml.getString(KEY_EAGLER_DEFAULT_ID, "1.12.2"));
    }

    public boolean isEaglerAllowPlayerSelect() {
        return yaml.getBoolean(KEY_EAGLER_ALLOW_SELECT, true);
    }

    public String getCloudDefaultId() {
        return trim(yaml.getString(KEY_CLOUD_DEFAULT_ID, ""));
    }

    public boolean isCloudAllowPlayerSelect() {
        return yaml.getBoolean(KEY_CLOUD_ALLOW_SELECT, true);
    }

    public int getCloudNextId() {
        return Math.max(1, yaml.getInt(KEY_CLOUD_NEXT_ID, 1));
    }

    public String getPackagePreselectCloudId() {
        return trim(yaml.getString(KEY_PACKAGE_PRESELECT, ""));
    }

    public int getPackageNextEaglerId() {
        return Math.max(1, yaml.getInt(KEY_PACKAGE_NEXT_EAGLER, 1));
    }

    public int getPackageNextCloudId() {
        return Math.max(1, yaml.getInt(KEY_PACKAGE_NEXT_CLOUD, 1));
    }

    public synchronized void setEaglerDefaultId(String id) {
        yaml.set(KEY_EAGLER_DEFAULT_ID, trim(id));
        save();
    }

    public synchronized void setEaglerAllowPlayerSelect(boolean allow) {
        yaml.set(KEY_EAGLER_ALLOW_SELECT, allow);
        save();
    }

    public synchronized void setCloudDefaultId(String id) {
        yaml.set(KEY_CLOUD_DEFAULT_ID, trim(id));
        save();
    }

    public synchronized void setCloudAllowPlayerSelect(boolean allow) {
        yaml.set(KEY_CLOUD_ALLOW_SELECT, allow);
        save();
    }

    public synchronized void setCloudNextId(int nextId) {
        yaml.set(KEY_CLOUD_NEXT_ID, Math.max(1, nextId));
        save();
    }

    public synchronized void setPackagePreselectCloudId(String id) {
        yaml.set(KEY_PACKAGE_PRESELECT, trim(id));
        save();
    }

    public synchronized void setPackageNextEaglerId(int nextId) {
        yaml.set(KEY_PACKAGE_NEXT_EAGLER, Math.max(1, nextId));
        save();
    }

    public synchronized void setPackageNextCloudId(int nextId) {
        yaml.set(KEY_PACKAGE_NEXT_CLOUD, Math.max(1, nextId));
        save();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private synchronized void save() {
        try {
            PlatformYaml.save(yaml, file);
        } catch (Throwable t) {
            plugin.getLogger().warning("写入运行态失败 " + file.getPath() + ": " + t.getMessage());
        }
    }
}
