package com.github.cocosoys.mc.webgame.data;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysExpansion;
import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.orm.DataSpec;
import com.github.cocosoys.mc.soyshttpovermc.orm.SQL;
import com.github.cocosoys.mc.soyshttpovermc.orm.YamlPojo;
import com.github.cocosoys.mc.soyshttpovermc.orm.executor.SqlBackendExecutor;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.McVersionOption;
import com.github.cocosoys.mc.soyshttpovermc.web.DataHandle;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebGame 数据初始化：YAML 落到 {@code plugins/WebGame/data}；MySQL 走 SOYS DataSpec。
 */
public final class WebGameDataBootstrap {

    private static final String DATA_DIR = "data";
    private static final String[] YAML_FILES = {
            "data/eagler-clients.yml",
            "data/cloud-clients.yml",
            "data/client-packages.yml"
    };

    private WebGameDataBootstrap() {
    }

    /** 仅用于读取 SoysExpansion 的静态 API 门面（生成式 getter 是实例方法）。 */
    private static final class ApiHolder extends SoysExpansion {
        @Override
        public String getIdentifier() {
            return "webgame-data-bootstrap";
        }
    }

    public static WebGameDataStore initialize(WebGame plugin) {
        String mode = System.getProperty("webgame.data.mode", "yaml");
        if ("mysql".equalsIgnoreCase(mode) || "sql".equalsIgnoreCase(mode)) {
            return mysql(plugin);
        }
        return yaml(plugin);
    }

    private static WebGameDataStore yaml(WebGame plugin) {
        File dataDir = new File(plugin.getDataFolder(), DATA_DIR);
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录: " + dataDir);
        }
        for (String resource : YAML_FILES) {
            copyIfMissing(plugin, resource);
        }
        migrateClientPackages(dataDir, plugin);

        YamlPojo yaml = new YamlPojo();
        yaml.init(dataDir);
        ClientPackage builtinPkg = null;
        for (ClientPackage p : yaml.select(ClientPackage.class)) {
            if (p != null && ClientPackage.SOURCE_BUILTIN.equals(p.getSource())) {
                builtinPkg = p;
                break;
            }
        }
        if (builtinPkg == null) {
            builtinPkg = builtinPackage();
            yaml.updateById(builtinPkg);
        }
        boolean hasValidClient = false;
        for (ClientVersion v : yaml.select(ClientVersion.class)) {
            if (v != null && v.getId() != null && !v.getId().trim().isEmpty()) {
                hasValidClient = true;
                break;
            }
        }
        if (!hasValidClient) {
            yaml.updateById(builtinClient(builtinPkg.getId()));
        }
        plugin.getLogger().info("WebGame 数据模式: YAML（" + dataDir.getPath() + "）");
        return new WebGameDataStore(WebGameDataStore.Mode.YAML, yaml);
    }

    private static WebGameDataStore mysql(WebGame plugin) {
        if (!SQL.Pojo.isAvailable()) {
            throw new IllegalStateException(
                    "webgame.data.mode=mysql，但 SOYS 的 storage.backends.mysql 未启用或装配失败");
        }

        // 先用 init.sql 保证 tls_cert_pem 等长文本列是 TEXT；ORM 的自动建表会把 String 当 VARCHAR(255)。
        SqlBackendExecutor sql = SqlBackendExecutor.get();
        if (sql == null) {
            throw new IllegalStateException("webgame.data.mode=mysql，但无法取得 SOYS SQL 执行器");
        }
        if (!"mysql".equalsIgnoreCase(sql.name())) {
            throw new IllegalStateException(
                    "webgame.data.mode=mysql，但当前 SOYS SQL 方言是 " + sql.name() + "，请启用 storage.backends.mysql");
        }
        execSqlResource(plugin, sql, "sql/init.sql");
        ensurePackageColumns(plugin, sql);

        SoysHttpOverMcApi api = new ApiHolder().getApi();
        if (api == null) {
            throw new IllegalStateException("SOYS API 尚未就绪，无法登记 WebGame 数据表");
        }
        DataSpec spec = new DataSpec();
        spec.setPluginName("WebGame");
        spec.setSchemaVersion(1);
        spec.setDataRoots(null); // 不把 YAML 默认文件复制到 SOYS 的全局 data
        spec.setSqlRoots(new String[]{"sql"});
        spec.setTableClasses(new Class<?>[]{ClientVersion.class, CloudClient.class, ClientPackage.class});
        spec.setSeedData(Arrays.asList(builtinPackage(), builtinClient("1")));
        DataHandle handle = api.getDataRegistration().register(plugin, spec);
        if (handle == null) {
            throw new IllegalStateException("WebGame MySQL 数据表登记失败（检查 SOYS auto.ops 与 MySQL 配置）");
        }
        plugin.getLogger().info("WebGame 数据模式: MySQL（SOYS DataSpec 已登记）");
        return new WebGameDataStore(WebGameDataStore.Mode.MYSQL, null);
    }

    private static void copyIfMissing(WebGame plugin, String resourcePath) {
        try {
            File target = new File(plugin.getDataFolder(), resourcePath);
            if (target.isFile()) {
                return;
            }
            InputStream in = plugin.getResource(resourcePath);
            if (in == null) {
                plugin.getLogger().warning("jar 内缺少默认数据文件: " + resourcePath);
                return;
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IllegalStateException("无法创建数据目录: " + parent);
            }
            try (InputStream stream = in) {
                Files.copy(stream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            plugin.getLogger().info("已释放默认数据文件: " + target.getPath());
        } catch (Throwable t) {
            plugin.getLogger().warning("释放默认数据文件失败 " + resourcePath + ": " + t);
        }
    }

    /**
     * 把旧 client-packages.yml 统一成“数字主键”格式：
     * 根键 eagler-1/cloud-1 或旧的 seq 列都会改写为纯数字 id，不再保留业务编号。
     * 同时把 eagler-clients / cloud-clients 里的 package_id 引用改到新数字 id。
     */
    private static void migrateClientPackages(File dataDir, WebGame plugin) {
        File file = new File(dataDir, "client-packages.yml");
        if (!file.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection("client-packages");
            if (section == null || section.getKeys(false).isEmpty()) {
                return;
            }
            boolean needs = false;
            for (String key : section.getKeys(false)) {
                ConfigurationSection row = section.getConfigurationSection(key);
                if (row == null) {
                    continue;
                }
                Object id = row.get("id");
                String source = row.getString("source", "");
                String fileValue = row.getString("file", "");
                if (!key.matches("\\d+")
                        || row.get("seq") != null
                        || id == null || !String.valueOf(id).matches("\\d+")
                        || ("builtin".equals(source) && fileValue.startsWith("eagler-clients/"))) {
                    needs = true;
                    break;
                }
            }
            if (!needs) {
                return;
            }

            Map<String, String> remap = new LinkedHashMap<>();
            java.util.Set<Long> used = new java.util.HashSet<>();
            for (String key : section.getKeys(false)) {
                if (key.matches("\\d+")) {
                    used.add(Long.parseLong(key));
                }
            }
            long next = 1;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String key : section.getKeys(false)) {
                ConfigurationSection row = section.getConfigurationSection(key);
                if (row == null) {
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<>(row.getValues(false));
                long id;
                if (key.matches("\\d+")) {
                    id = Long.parseLong(key);
                } else {
                    while (used.contains(next)) {
                        next++;
                    }
                    id = next++;
                    used.add(id);
                }
                String oldCode = String.valueOf(data.get("id") == null ? "" : data.get("id")).trim();
                if (!oldCode.isEmpty()) {
                    remap.put(oldCode, String.valueOf(id));
                }
                remap.put(key, String.valueOf(id));
                data.remove("seq");
                data.put("id", id);
                String source = String.valueOf(data.get("source") == null ? "" : data.get("source"));
                String stored = String.valueOf(data.get("file") == null ? "" : data.get("file"));
                if ("builtin".equals(source) && stored.startsWith("eagler-clients/")) {
                    data.put("file", "eagler/" + id);
                }
                rows.add(data);
            }
            yaml.set("client-packages", null);
            ConfigurationSection out = yaml.createSection("client-packages");
            for (Map<String, Object> row : rows) {
                out.createSection(String.valueOf(row.get("id")), row);
            }
            yaml.save(file);

            rewritePackageRefs(new File(dataDir, "eagler-clients.yml"), "eagler-clients", remap);
            rewritePackageRefs(new File(dataDir, "cloud-clients.yml"), "cloud-clients", remap);
            plugin.getLogger().info("已迁移 client-packages.yml：主键统一为 AUTO 数字 id，并更新引用");
        } catch (Throwable t) {
            plugin.getLogger().warning("迁移 client-packages.yml 失败: " + t);
        }
    }

    /** 把表内 package_id 的旧业务编号替换为新的数字 id；只改引用，不动其它列。 */
    private static void rewritePackageRefs(File file, String table, Map<String, String> remap) {
        if (!file.isFile() || remap.isEmpty()) {
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection section = yaml.getConfigurationSection(table);
            if (section == null) {
                return;
            }
            boolean changed = false;
            for (String key : section.getKeys(false)) {
                ConfigurationSection row = section.getConfigurationSection(key);
                if (row == null) {
                    continue;
                }
                String ref = row.getString("package_id", "");
                if (!ref.isEmpty() && remap.containsKey(ref)) {
                    row.set("package_id", remap.get(ref));
                    changed = true;
                }
            }
            if (changed) {
                yaml.save(file);
            }
        } catch (Throwable ignored) {
            // 引用文件损坏不应阻断启动；后续由服务层的 missing 判定兜底。
        }
    }

    /** 已有 MySQL 表补列；重复列错误直接忽略（新库由 init.sql 直接建好）。 */
    private static void ensurePackageColumns(WebGame plugin, SqlBackendExecutor sql) {
        String[] sqls = {
                "ALTER TABLE `eagler-clients` ADD COLUMN `package_id` BIGINT NULL",
                "ALTER TABLE `cloud-clients` ADD COLUMN `package_id` BIGINT NULL"
        };
        for (String statement : sqls) {
            try {
                sql.execSql(statement);
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
                if (!msg.contains("duplicate column") && !msg.contains("already exists")) {
                    plugin.getLogger().warning("补列失败（可忽略，若为重复列）: " + statement + " -> " + t.getMessage());
                }
            }
        }
    }

    private static void execSqlResource(WebGame plugin, SqlBackendExecutor sql, String resourcePath) {        InputStream in = plugin.getResource(resourcePath);
        if (in == null) {
            throw new IllegalStateException("jar 内缺少 SQL 初始化脚本: " + resourcePath);
        }
        try (InputStream stream = in) {
            String script = new String(readAll(stream), StandardCharsets.UTF_8);
            for (String stmt : splitStatements(script)) {
                sql.execSql(stmt);
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行 " + resourcePath + " 失败: " + e.getMessage(), e);
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        byte[] buffer = new byte[8192];
        int n;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** 简易 split：忽略整行注释；当前脚本均为单行 CREATE TABLE。 */
    private static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        for (String raw : script.split(";")) {
            StringBuilder clean = new StringBuilder();
            for (String line : raw.split("\\R")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("--") && !t.startsWith("#")) {
                    clean.append(line).append('\n');
                }
            }
            String stmt = clean.toString().trim();
            if (!stmt.isEmpty()) {
                out.add(stmt);
            }
        }
        return out;
    }

    /** 内置 Eagler 客户端条目：只保留版本 id、制品引用、协议与排序。 */
    public static ClientVersion builtinClient(String packageId) {
        ClientVersion v = new ClientVersion();
        v.setId(ClientVersion.BUILTIN_ID);
        v.setPackageId(packageId == null ? "" : packageId);
        List<McVersionOption> versions = new ArrayList<>();
        McVersionOption option = McVersionOption.of(ClientVersion.BUILTIN_ID, 340);
        versions.add(option);
        v.setMcVersions(versions);
        v.setMcVersionData(ClientVersion.BUILTIN_ID + "|340");
        v.setEaglerProtocol(4);
        v.setEnabled(true);
        v.setOrder(0);
        return v;
    }

    /** 内置 Eagler 制品：文件由首次启动从 jar 释放到 packages/eagler/&lt;id&gt;/。 */
    public static ClientPackage builtinPackage() {
        ClientPackage p = new ClientPackage();
        p.setType(ClientPackage.TYPE_EAGLER);
        p.setName("1.12.2 内置客户端包");
        p.setVersion(ClientVersion.BUILTIN_ID);
        p.setFile("eagler/1");
        p.setEntryPath("index.html");
        p.setSource(ClientPackage.SOURCE_BUILTIN);
        p.setEnabled(true);
        p.setUploadedAt("");
        p.setNotes("随插件内置的 Eagler 1.12.2 客户端");
        return p;
    }
}
