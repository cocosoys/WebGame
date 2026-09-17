package com.github.cocosoys.mc.webgame.web.cloud;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云游戏封禁库（持久化到 plugins/WebGame/bans.yml）。
 *
 * <p>封禁按用户名（忽略大小写）记录，包含原因与封禁时间。封禁只作用于
 * WebGame 云游戏通道（create/resume 拒绝 + 已有会话踢出）；如需同时拦
 * Eagler/容器内同名进服，由调用方（CloudSessionManager#ban）同步 Bukkit
 * BanList（NAME 类型）。</p>
 */
public final class BanStore {

    /** 单条封禁记录。 */
    public static final class Ban {
        private final String username;
        private final String reason;
        private final long at;

        Ban(String username, String reason, long at) {
            this.username = username;
            this.reason = reason == null ? "" : reason;
            this.at = at;
        }

        public String getUsername() {
            return username;
        }

        public String getReason() {
            return reason;
        }

        /** 封禁时间戳（ms）。 */
        public long getAt() {
            return at;
        }
    }

    private final File file;
    private final Map<String, Ban> bans = new ConcurrentHashMap<>(); // key = 小写用户名

    public BanStore(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "bans.yml");
        load();
    }

    // ===== 读取 =====

    public boolean isBanned(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        return bans.containsKey(username.toLowerCase());
    }

    public Ban getBan(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        return bans.get(username.toLowerCase());
    }

    /** 全部封禁记录（按封禁时间升序）。 */
    public List<Ban> list() {
        List<Ban> out = new ArrayList<>(bans.values());
        out.sort(Comparator.comparingLong(Ban::getAt));
        return out;
    }

    // ===== 写入 =====

    /** 封禁（幂等：已封禁则更新原因/时间）。 */
    public void ban(String username, String reason) {
        if (username == null || username.isEmpty()) {
            return;
        }
        bans.put(username.toLowerCase(), new Ban(username, reason, System.currentTimeMillis()));
        save();
    }

    /** 解封。返回是否存在该封禁记录。 */
    public boolean unban(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        boolean removed = bans.remove(username.toLowerCase()) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    // ===== 持久化 =====

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            org.bukkit.configuration.ConfigurationSection s = y.getConfigurationSection("banned");
            if (s == null) {
                return;
            }
            for (String key : s.getKeys(false)) {
                String name = s.getString(key + ".name", key);
                String reason = s.getString(key + ".reason", "");
                long at = s.getLong(key + ".at", System.currentTimeMillis());
                bans.put(name.toLowerCase(), new Ban(name, reason, at));
            }
        } catch (Throwable t) {
            // 加载失败不阻断启动：视为空封禁库（下次写入覆盖）
        }
    }

    private void save() {
        try {
            YamlConfiguration y = new YamlConfiguration();
            for (Ban b : bans.values()) {
                String key = b.getUsername().toLowerCase();
                y.set("banned." + key + ".name", b.getUsername());
                y.set("banned." + key + ".reason", b.getReason());
                y.set("banned." + key + ".at", b.getAt());
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            y.save(file);
        } catch (Throwable t) {
            // 持久化失败仅记录（封禁仍在本进程生效）
        }
    }
}
