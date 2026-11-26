package com.github.cocosoys.mc.webgame.data;

import com.github.cocosoys.mc.soyshttpovermc.api.SoysExpansion;
import com.github.cocosoys.mc.soyshttpovermc.api.SoysHttpOverMcApi;
import com.github.cocosoys.mc.soyshttpovermc.orm.DataSpec;
import com.github.cocosoys.mc.soyshttpovermc.orm.SQL;
import com.github.cocosoys.mc.soyshttpovermc.orm.YamlPojo;
import com.github.cocosoys.mc.soyshttpovermc.orm.executor.SqlBackendExecutor;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.McVersionOption;
import com.github.cocosoys.mc.soyshttpovermc.web.DataHandle;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public static WebGameDataStore initialize(WebGame plugin, WebGameConfig config) {
        String mode = config.getDataMode();
        if ("mysql".equalsIgnoreCase(mode)) {
            return mysql(plugin, config);
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

        YamlPojo yaml = new YamlPojo();
        yaml.init(dataDir);
        boolean hasValidClient = false;
        for (ClientVersion v : yaml.select(ClientVersion.class)) {
            if (v != null && v.getId() != null && !v.getId().trim().isEmpty()) {
                hasValidClient = true;
                break;
            }
        }
        if (!hasValidClient) {
            yaml.updateById(builtinClient());
        }
        boolean hasBuiltinPackage = false;
        for (ClientPackage p : yaml.select(ClientPackage.class)) {
            if (p != null && builtinPackage().getId().equals(p.getId())) {
                hasBuiltinPackage = true;
                break;
            }
        }
        if (!hasBuiltinPackage) {
            yaml.updateById(builtinPackage());
        }
        plugin.getLogger().info("WebGame 数据模式: YAML（" + dataDir.getPath() + "）");
        return new WebGameDataStore(WebGameDataStore.Mode.YAML, yaml);
    }

    private static WebGameDataStore mysql(WebGame plugin, WebGameConfig config) {
        if (!SQL.Pojo.isAvailable()) {
            throw new IllegalStateException(
                    "data.mode=mysql，但 SOYS 的 storage.backends.mysql 未启用或装配失败");
        }

        // 先用 init.sql 保证 tls_cert_pem 等长文本列是 TEXT；ORM 的自动建表会把 String 当 VARCHAR(255)。
        SqlBackendExecutor sql = SqlBackendExecutor.get();
        if (sql == null) {
            throw new IllegalStateException("data.mode=mysql，但无法取得 SOYS SQL 执行器");
        }
        if (!"mysql".equalsIgnoreCase(sql.name())) {
            throw new IllegalStateException(
                    "data.mode=mysql，但当前 SOYS SQL 方言是 " + sql.name() + "，请启用 storage.backends.mysql");
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
        spec.setSeedData(Arrays.asList(builtinClient(), builtinPackage()));
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

    /** 已有 MySQL 表补列；重复列错误直接忽略（新库由 init.sql 直接建好）。 */
    private static void ensurePackageColumns(WebGame plugin, SqlBackendExecutor sql) {
        String[] sqls = {
                "ALTER TABLE `eagler-clients` ADD COLUMN `package_id` VARCHAR(64) NULL",
                "ALTER TABLE `cloud-clients` ADD COLUMN `package_id` VARCHAR(64) NULL"
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

    public static ClientVersion builtinClient() {
        ClientVersion v = new ClientVersion();
        v.setId(ClientVersion.BUILTIN_ID);
        v.setName("1.12.2 内置客户端");
        v.setPath("eagler-clients/" + ClientVersion.BUILTIN_ID + "/index.html");
        List<McVersionOption> versions = new ArrayList<>();
        McVersionOption option = McVersionOption.of(ClientVersion.BUILTIN_ID, 340);
        versions.add(option);
        v.setMcVersions(versions);
        v.setMcVersionData(ClientVersion.BUILTIN_ID + "|340");
        v.setEaglerProtocol(4);
        v.setEnabled(true);
        v.setOrder(0);
        v.setSource(ClientVersion.SOURCE_BUILTIN);
        v.setPackageId("eagler-1");
        v.setSha256("");
        v.setSize(0L);
        v.setUploadedAt(0L);
        return v;
    }

    /** 内置 Eagler 制品：不复制文件，入口仍指向 eagler-clients/1.12.2/index.html。 */
    public static ClientPackage builtinPackage() {
        ClientPackage p = new ClientPackage();
        p.setId("eagler-1");
        p.setType(ClientPackage.TYPE_EAGLER);
        p.setName("1.12.2 内置客户端包");
        p.setVersion(ClientVersion.BUILTIN_ID);
        p.setFile("eagler-clients/" + ClientVersion.BUILTIN_ID);
        p.setEntryPath("index.html");
        p.setSource(ClientPackage.SOURCE_BUILTIN);
        p.setEnabled(true);
        p.setUploadedAt("");
        return p;
    }
}
