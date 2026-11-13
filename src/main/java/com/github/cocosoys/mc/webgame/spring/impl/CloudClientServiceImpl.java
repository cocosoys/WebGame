package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.platform.PlatformYaml;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.ConfigUtil;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.shttp.CloudClientPages;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.vo.CloudClientVo;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 云游戏客户端管理实现。
 *
 * <p>元数据：{@code cloud-clients.yml}（与 config.yml 同目录）；条目可直接指向
 * 外部 URL 或任意本地文件，不提供上传。id 由服务端自增，删除后不复用。</p>
 */
public class CloudClientServiceImpl implements CloudClientService {

    private static final Logger LOG = Logger.getLogger(CloudClientServiceImpl.class.getName());

    private static final String META_FILE = "cloud-clients.yml";
    private static final String PAGE_PATH = "/cloud/";

    private final WebGame plugin;

    /** 按数字 id 排序、并发安全。 */
    private volatile Map<String, CloudClient> clients = new ConcurrentSkipListMap<>();
    private volatile String defaultId = "";
    private volatile boolean allowPlayerSelect = true;
    private volatile int nextId = 1;

    private static final Comparator<String> ID_COMPARATOR = (a, b) -> {
        int cmp = Integer.compare(toInt(a, 0), toInt(b, 0));
        return cmp != 0 ? cmp : String.valueOf(a).compareTo(String.valueOf(b));
    };

    public CloudClientServiceImpl(WebGame plugin) {
        this.plugin = plugin;
        load();
    }

    // ===== 查询 =====

    @Override
    public AjaxResult list() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("defaultId", defaultId);
            data.put("allowPlayerSelect", allowPlayerSelect);
            data.put("rows", toVos());
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("云游戏客户端查询失败：" + t.getMessage());
        }
    }

    // ===== 管理 =====

    @Override
    public AjaxResult create(Map<String, Object> body) {
        try {
            String path = str(body == null ? null : body.get("path")).trim();
            if (path.isEmpty()) {
                return AjaxResult.error("请填写客户端路径（URL 或本地文件）");
            }
            String id = String.valueOf(nextId++);
            CloudClient c = new CloudClient();
            c.setId(id);
            applyBody(c, body);
            c.setOrder(maxOrder() + 1);
            c.setSource(deriveSource(c));
            c.setMissing(!entryExists(c));
            clients.put(id, c);
            if (!isServableQuiet(defaultId)) {
                defaultId = id;
            }
            save();
            publishPages();
            return AjaxResult.success("已新增云游戏客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("新增失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult update(String id, Map<String, Object> body) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            Boolean enabled = toBool(body == null ? null : body.get("enabled"));
            if (Boolean.FALSE.equals(enabled) && id.equals(defaultId)) {
                return AjaxResult.error("默认客户端不可关闭，请先将其它客户端设为默认");
            }
            applyBody(c, body);
            if (c.getPath().isEmpty()) {
                return AjaxResult.error("请填写客户端路径（URL 或本地文件）");
            }
            c.setSource(deriveSource(c));
            c.setMissing(!entryExists(c));
            save();
            publishPages();
            return AjaxResult.success("已保存云游戏客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("保存失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setEnabled(String id, boolean enabled) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (!enabled && id.equals(defaultId)) {
                return AjaxResult.error("默认客户端不可关闭，请先将其它客户端设为默认");
            }
            c.setEnabled(enabled);
            save();
            publishPages();
            return AjaxResult.success((enabled ? "已启用 " : "已关闭 ") + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("操作失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setDefault(String id) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (c.isMissing()) {
                return AjaxResult.error("该客户端入口缺失，无法设为默认");
            }
            if (!c.isEnabled()) {
                return AjaxResult.error("该客户端已关闭，无法设为默认");
            }
            defaultId = id;
            save();
            publishPages();
            return AjaxResult.success("默认客户端已设为 " + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("设置默认客户端失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult delete(String id) {
        try {
            CloudClient c = clients.get(id);
            if (c == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            clients.remove(id);
            if (id.equals(defaultId)) {
                defaultId = pickFallbackDefault();
            }
            save();
            publishPages();
            return AjaxResult.success("已删除 " + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("删除失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult reorder(List<String> ids) {
        try {
            if (ids == null || ids.isEmpty()) {
                return AjaxResult.error("排序列表不能为空");
            }
            List<String> applied = new ArrayList<>();
            int next = 0;
            for (String id : ids) {
                CloudClient c = id == null ? null : clients.get(id);
                if (c == null || applied.contains(id)) {
                    continue;
                }
                c.setOrder(next++);
                applied.add(id);
            }
            for (CloudClient c : versions()) {
                if (!applied.contains(c.getId())) {
                    c.setOrder(next++);
                }
            }
            save();
            publishPages();
            Map<String, Object> data = new HashMap<>();
            data.put("rows", toVos());
            return AjaxResult.success("排序已保存", data);
        } catch (Throwable t) {
            return AjaxResult.error("排序保存失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setAllowPlayerSelect(boolean allow) {
        try {
            if (!allow && !isServableQuiet(defaultId)) {
                return AjaxResult.error("默认客户端不可用（已关闭或入口缺失），请先切换默认客户端");
            }
            allowPlayerSelect = allow;
            save();
            publishPages();
            return AjaxResult.success(allow
                    ? "已允许玩家自行选择客户端"
                    : "已禁止玩家自行选择客户端，/cloud/ 将直接进入默认客户端", null);
        } catch (Throwable t) {
            return AjaxResult.error("设置失败：" + t.getMessage());
        }
    }

    // ===== 供页面使用 =====

    @Override
    public List<CloudClient> versions() {
        List<CloudClient> list = new ArrayList<>(clients.values());
        list.sort((a, b) -> {
            int cmp = Integer.compare(a.getOrder(), b.getOrder());
            return cmp != 0 ? cmp : ID_COMPARATOR.compare(a.getId(), b.getId());
        });
        return list;
    }

    @Override
    public String defaultId() {
        return defaultId;
    }

    @Override
    public boolean allowPlayerSelect() {
        return allowPlayerSelect;
    }

    @Override
    public File resolveClientFile(CloudClient client) {
        if (client == null || CloudClient.isUrl(client.getPath())) {
            return null;
        }
        String path = client.getPath().trim();
        if (path.isEmpty()) {
            return null;
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return file.isFile() ? file : null;
        }
        File inData = new File(plugin.getDataFolder(), path);
        if (inData.isFile()) {
            return inData;
        }
        File dataDir = plugin.getDataFolder();
        File serverRoot = dataDir.getParentFile() == null ? null : dataDir.getParentFile().getParentFile();
        if (serverRoot != null) {
            File inServer = new File(serverRoot, path);
            if (inServer.isFile()) {
                return inServer;
            }
        }
        return null;
    }

    @Override
    public void publishPages() {
        CloudClientPages pages = plugin.getCloudClientPages();
        if (pages == null) {
            return;
        }
        try {
            pages.publish();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "云游戏客户端页面发布失败: " + t);
        }
    }

    // ===== 元数据 =====

    private synchronized void load() {
        Map<String, CloudClient> loaded = new TreeMap<>(ID_COMPARATOR);
        String def = "";
        int storedNextId = 0;
        File meta = metaFile();
        if (meta.isFile()) {
            try {
                YamlConfiguration yaml = PlatformYaml.load(meta);
                String d = yaml.getString("default-id", "");
                if (d != null && !d.trim().isEmpty()) {
                    def = d.trim();
                }
                allowPlayerSelect = yaml.getBoolean("allow-player-select", true);
                storedNextId = Math.max(storedNextId, yaml.getInt("next-id", 0));
                ConfigurationSection section = yaml.getConfigurationSection("clients");
                if (section != null) {
                    for (String key : section.getKeys(false)) {
                        if (!isValidId(key)) {
                            continue;
                        }
                        ConfigurationSection c = section.getConfigurationSection(key);
                        if (c == null) {
                            continue;
                        }
                        CloudClient client = new CloudClient();
                        client.setId(key);
                        client.setName(c.getString("name", ""));
                        client.setPath(c.getString("path", ""));
                        client.setMcVersion(c.getString("mc-version", ""));
                        client.setEnabled(c.getBoolean("enabled", true));
                        client.setOrder(c.getInt("order", -1));
                        client.setSource(c.getString("source", CloudClient.SOURCE_LOCAL));
                        loaded.put(key, client);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "读取 " + META_FILE + " 失败: " + t);
            }
        }

        int maxId = 0;
        for (CloudClient c : loaded.values()) {
            int n = toInt(c.getId(), 0);
            if (n > maxId) {
                maxId = n;
            }
        }
        nextId = Math.max(Math.max(storedNextId, maxId + 1), 1);

        for (CloudClient c : loaded.values()) {
            c.setMissing(!entryExists(c));
        }

        // 排序迁移：未指定顺序的条目按 id 顺序追加到已有序号之后
        int nextOrder = 0;
        for (CloudClient c : loaded.values()) {
            if (c.getOrder() >= 0 && c.getOrder() >= nextOrder) {
                nextOrder = c.getOrder() + 1;
            }
        }
        for (CloudClient c : loaded.values()) {
            if (c.getOrder() < 0) {
                c.setOrder(nextOrder++);
            }
        }

        if (!loaded.containsKey(def)) {
            def = pickFallbackDefault(loaded);
        }
        this.clients = new ConcurrentSkipListMap<>(loaded);
        this.defaultId = def;
        save();
    }

    private synchronized void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("default-id", defaultId);
            root.put("allow-player-select", allowPlayerSelect);
            root.put("next-id", nextId);
            Map<String, Object> items = new LinkedHashMap<>();
            for (CloudClient c : clients.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.getName());
                m.put("path", c.getPath());
                m.put("mc-version", c.getMcVersion());
                m.put("enabled", c.isEnabled());
                m.put("order", c.getOrder());
                m.put("source", c.getSource());
                items.put(c.getId(), m);
            }
            root.put("clients", items);
            PlatformYaml.save(ConfigUtil.toYaml(root), metaFile());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "写入 " + META_FILE + " 失败: " + t);
        }
    }

    private File metaFile() {
        return new File(plugin.getDataFolder(), META_FILE);
    }

    // ===== 辅助 =====

    private List<CloudClientVo> toVos() {
        List<CloudClientVo> rows = new ArrayList<>();
        String prefix = WebGameConfig.WEB_PLUGIN_PREFIX + PAGE_PATH;
        for (CloudClient c : versions()) {
            CloudClientVo vo = new CloudClientVo();
            vo.setId(c.getId());
            vo.setName(c.getName());
            vo.setPath(c.getPath());
            vo.setMcVersion(c.getMcVersion());
            vo.setEnabled(c.isEnabled());
            vo.setOrder(c.getOrder());
            vo.setDefaultVersion(c.getId().equals(defaultId));
            vo.setSource(c.getSource());
            vo.setMissing(c.isMissing());
            vo.setPagePath(prefix + c.getId() + "/");
            rows.add(vo);
        }
        return rows;
    }

    private CloudClientVo findVo(String id) {
        for (CloudClientVo vo : toVos()) {
            if (vo.getId().equals(id)) {
                return vo;
            }
        }
        return null;
    }

    private boolean isServableQuiet(String id) {
        CloudClient c = id == null ? null : clients.get(id);
        return c != null && c.isEnabled() && !c.isMissing();
    }

    /** URL → url；其余 → local。 */
    private String deriveSource(CloudClient c) {
        return CloudClient.isUrl(c.getPath()) ? CloudClient.SOURCE_URL : CloudClient.SOURCE_LOCAL;
    }

    private boolean entryExists(CloudClient c) {
        return CloudClient.isUrl(c.getPath()) || resolveClientFile(c) != null;
    }

    private void applyBody(CloudClient c, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        String name = str(body.get("name")).trim();
        c.setName(name);
        String path = str(body.get("path")).trim();
        if (!path.isEmpty()) {
            c.setPath(path);
        }
        c.setMcVersion(str(body.get("mcVersion")).trim());
        Boolean enabled = toBool(body.get("enabled"));
        if (enabled != null) {
            c.setEnabled(enabled);
        }
    }

    private int maxOrder() {
        int max = -1;
        for (CloudClient c : clients.values()) {
            if (c.getOrder() > max) {
                max = c.getOrder();
            }
        }
        return max;
    }

    private String pickFallbackDefault() {
        return pickFallbackDefault(clients);
    }

    private static String pickFallbackDefault(Map<String, CloudClient> map) {
        CloudClient best = null;
        for (CloudClient c : map.values()) {
            if (!c.isEnabled() || c.isMissing()) {
                continue;
            }
            if (best == null || c.getOrder() < best.getOrder()
                    || (c.getOrder() == best.getOrder() && ID_COMPARATOR.compare(c.getId(), best.getId()) < 0)) {
                best = c;
            }
        }
        return best == null ? "" : best.getId();
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("\\d{1,9}");
    }

    private static Boolean toBool(Object o) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o != null) {
            String s = String.valueOf(o).trim();
            if ("true".equalsIgnoreCase(s)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(s)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
