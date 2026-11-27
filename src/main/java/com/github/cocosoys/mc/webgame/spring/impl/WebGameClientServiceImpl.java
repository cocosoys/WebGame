package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.data.WebGameDataStore;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;
import com.github.cocosoys.mc.webgame.spring.entity.McVersionOption;
import com.github.cocosoys.mc.webgame.spring.entity.vo.ClientImpactVo;
import com.github.cocosoys.mc.webgame.spring.entity.vo.ClientVersionVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;
import com.github.cocosoys.mc.webgame.shttp.WebGameClientPages;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端版本管理实现。
 *
 * <p>只负责“启用哪些 Eagler 入口、默认与排序”；名称、入口路径、SHA、体积等
 * 全部来自 {@code client-packages}，表 {@code eagler-clients} 不再复制这些列。</p>
 */
public class WebGameClientServiceImpl implements WebGameClientService {

    private static final Logger LOG = Logger.getLogger(WebGameClientServiceImpl.class.getName());

    private final WebGame plugin;
    private final WebGameDataStore store;
    private final com.github.cocosoys.mc.webgame.spring.service.ClientPackageService packageService;

    /** id → 版本（按 id 排序、并发安全）：WS 嗅探器会并发读取准入状态。 */
    private volatile Map<String, ClientVersion> versions = new ConcurrentSkipListMap<>();
    private volatile String defaultId = "";

    public WebGameClientServiceImpl(WebGame plugin) {
        this.plugin = plugin;
        this.store = plugin.getDataStore();
        if (this.store == null) {
            throw new IllegalStateException("WebGameDataStore 未初始化，无法加载 eagler 客户端数据");
        }
        this.packageService = plugin.getClientPackageService();
        if (this.packageService == null) {
            throw new IllegalStateException("ClientPackageService 未初始化，无法解析 eagler 客户端包");
        }
        load();
    }

    // ===== 查询 =====

    @Override
    public AjaxResult list() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("defaultId", defaultId);
            data.put("allowPlayerSelect", allowPlayerSelect());
            data.put("rows", toVos());
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("客户端版本查询失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult impact(String id) {
        try {
            WsGateway gw = plugin.getWsGateway();
            int total = 0;
            int playing = 0;
            if (gw != null && id != null) {
                for (WsFrameHandler h : gw.snapshotSessions()) {
                    if (id.equals(h.getClientId())) {
                        total++;
                        if (h.isPlaying()) {
                            playing++;
                        }
                    }
                }
            }
            return AjaxResult.success(new ClientImpactVo(id, total, playing));
        } catch (Throwable t) {
            return AjaxResult.error("影响面统计失败：" + t.getMessage());
        }
    }

    // ===== 管理 =====

    @Override
    public AjaxResult setEnabled(String id, boolean enabled, boolean kick) {
        try {
            ClientVersion v = versions.get(id);
            if (v == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (!enabled && id.equals(defaultId)) {
                return AjaxResult.error("默认版本不可关闭，请先将其它版本设为默认");
            }
            int closed = 0;
            if (!enabled && kick) {
                closed = closeSessions(id);
            }
            v.setEnabled(enabled);
            save();
            publishPages();
            String msg = enabled ? "已启用 " + id
                    : "已关闭 " + id + (closed > 0 ? "，已强制断开 " + closed + " 个连接" : "");
            return AjaxResult.success(msg, null);
        } catch (Throwable t) {
            return AjaxResult.error("操作失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setDefault(String id) {
        try {
            ClientVersion v = versions.get(id);
            if (v == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (v.isMissing()) {
                return AjaxResult.error("该版本目录缺失，无法设为默认");
            }
            if (!v.isEnabled()) {
                return AjaxResult.error("该版本已关闭，无法设为默认");
            }
            int minOrder = Integer.MAX_VALUE;
            for (ClientVersion item : versions.values()) {
                minOrder = Math.min(minOrder, item.getOrder());
            }
            v.setOrder(minOrder == Integer.MAX_VALUE ? 0 : minOrder - 1);
            save();
            publishPages();
            return AjaxResult.success("默认版本已设为 " + id, null);
        } catch (Throwable t) {
            return AjaxResult.error("设置默认版本失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult delete(String id) {
        try {
            ClientVersion v = versions.get(id);
            if (v == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            if (v.isBuiltin()) {
                return AjaxResult.error("内置客户端不可删除");
            }
            closeSessions(id);
            versions.remove(id);
            store.delete(ClientVersion.class, id);
            defaultId = pickFallbackDefault();
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
                ClientVersion v = id == null ? null : versions.get(id);
                if (v == null || applied.contains(id)) {
                    continue;
                }
                v.setOrder(next++);
                applied.add(id);
            }
            // 未出现在提交列表中的版本按当前顺序追加，避免序号冲突
            for (ClientVersion v : versions()) {
                if (!applied.contains(v.getId())) {
                    v.setOrder(next++);
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
                return AjaxResult.error("默认版本不可用（已关闭或目录缺失），请先在列表中调整排序");
            }
            if (plugin.getWebGameConfig() != null) {
                plugin.getWebGameConfig().setEaglerAllowPlayerSelect(allow);
            }
            save();
            publishPages();
            return AjaxResult.success(allow
                    ? "已允许玩家自行选择版本"
                    : "已禁止玩家自行选择版本，/eagler/ 将直接进入默认版本", null);
        } catch (Throwable t) {
            return AjaxResult.error("设置失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult create(Map<String, Object> body) {
        try {
            String id = str(body == null ? null : body.get("id")).trim();
            if (!isValidId(id)) {
                return AjaxResult.error("客户端 id 只能包含字母、数字、下划线、点、短横线（1-32 位）");
            }
            if (ClientVersion.BUILTIN_ID.equalsIgnoreCase(id)) {
                return AjaxResult.error("客户端 id " + ClientVersion.BUILTIN_ID + " 为内置客户端保留");
            }
            if (versions.containsKey(id)) {
                return AjaxResult.error("客户端已存在: " + id);
            }
            ClientVersion v = new ClientVersion();
            v.setId(id);
            applyBody(v, body);
            String packageError = applyPackage(v, true);
            if (!packageError.isEmpty()) {
                return AjaxResult.error(packageError);
            }
            v.setOrder(maxOrder() + 1);
            v.setUploadedAt(System.currentTimeMillis());
            v.setMissing(!entryExists(v));
            versions.put(id, v);
            save();
            publishPages();
            return AjaxResult.success("已新增 eagler 客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("新增失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult update(String id, Map<String, Object> body) {
        try {
            ClientVersion v = versions.get(id);
            if (v == null) {
                return AjaxResult.error("客户端不存在: " + id);
            }
            Boolean enabled = toBool(body == null ? null : body.get("enabled"));
            if (Boolean.FALSE.equals(enabled) && id.equals(defaultId)) {
                return AjaxResult.error("默认版本不可关闭，请先将其它版本设为默认");
            }
            applyBody(v, body);
            if (v.isBuiltin()) {
                // 内置客户端固定引用内置制品，只允许调整启用状态与排序
                v.setPackageId(packageService.builtinPackageId());
            }
            String packageError = applyPackage(v, true);
            if (!packageError.isEmpty()) {
                return AjaxResult.error(packageError);
            }
            v.setMissing(!entryExists(v));
            save();
            publishPages();
            return AjaxResult.success("已保存 eagler 客户端 " + id, findVo(id));
        } catch (Throwable t) {
            return AjaxResult.error("保存失败：" + t.getMessage());
        }
    }

    // ===== 供页面 / 嗅探器使用 =====

    @Override
    public List<ClientVersion> versions() {
        List<ClientVersion> list = new ArrayList<>(versions.values());
        list.sort((a, b) -> {
            int c = Integer.compare(a.getOrder(), b.getOrder());
            return c != 0 ? c : a.getId().compareTo(b.getId());
        });
        return list;
    }

    @Override
    public boolean isServable(String id) {
        if (id == null) {
            return false;
        }
        ClientVersion v = versions.get(id);
        if (v == null || !v.isEnabled() || v.isMissing()) {
            return false;
        }
        // 禁止自选版本时，新连接只允许默认版本（已建立的会话不受影响）
        return allowPlayerSelect() || id.equals(defaultId);
    }

    @Override
    public boolean allowPlayerSelect() {
        return plugin.getWebGameConfig() == null
                || plugin.getWebGameConfig().isEaglerAllowPlayerSelect();
    }

    @Override
    public String builtinId() {
        return ClientVersion.BUILTIN_ID;
    }

    @Override
    public String defaultId() {
        return defaultId;
    }

    @Override
    public void publishPages() {
        WebGameClientPages pages = plugin.getClientPages();
        if (pages == null) {
            return;
        }
        try {
            pages.publish();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "客户端页面发布失败: " + t);
        }
    }

    // ===== 元数据 / 目录 =====

    /** 从数据层重建内存视图；default-id / allow-player-select 从 config.yml 读取。 */
    private synchronized void load() {
        Map<String, ClientVersion> loaded = new TreeMap<>();
        try {
            for (ClientVersion v : store.select(ClientVersion.class)) {
                if (v == null) {
                    continue;
                }
                if (!isValidId(v.getId())) {
                    continue;
                }
                v.setMcVersions(parseMcVersionData(v.getMcVersionData()));
                bindLoadedPackage(v);
                if (v.getName() == null || v.getName().isEmpty()) {
                    v.setName(v.getId());
                }
                if (v.getSource() == null || v.getSource().isEmpty()) {
                    v.setSource(deriveSource(v));
                }
                loaded.put(v.getId(), v);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "读取 eagler 客户端数据失败: " + t);
        }

        for (ClientVersion v : loaded.values()) {
            v.setMissing(!entryExists(v));
        }

        this.versions = new ConcurrentSkipListMap<>(loaded);
        this.defaultId = pickFallbackDefault(loaded);
        save();
    }

    private synchronized void save() {
        try {
            for (ClientVersion v : versions.values()) {
                v.setMcVersionData(formatMcVersionData(v.getMcVersions()));
                store.save(v);
            }
            // 默认项永远取 order 最小的可用记录，不再单独保存默认 id。
            defaultId = pickFallbackDefault();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "写入 eagler 客户端数据失败: " + t);
        }
    }

    private static List<McVersionOption> parseMcVersionData(String data) {
        List<McVersionOption> out = new ArrayList<>();
        if (data == null || data.trim().isEmpty()) {
            return out;
        }
        for (String row : data.split(",")) {
            String item = row.trim();
            if (item.isEmpty()) {
                continue;
            }
            int sep = item.indexOf('|');
            String version = (sep >= 0 ? item.substring(0, sep) : item).trim();
            int protocol = sep >= 0 ? toInt(item.substring(sep + 1), 0) : 0;
            if (!version.isEmpty()) {
                out.add(McVersionOption.of(version, protocol));
            }
        }
        return out;
    }

    private static String formatMcVersionData(List<McVersionOption> options) {
        StringBuilder sb = new StringBuilder();
        if (options == null) {
            return "";
        }
        for (McVersionOption option : options) {
            if (option == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(option.getVersion()).append('|').append(option.getProtocol());
        }
        return sb.toString();
    }

    private List<ClientVersionVo> toVos() {
        List<ClientVersionVo> rows = new ArrayList<>();
        String prefix = plugin.getWebGameConfig() == null
                ? ""
                : plugin.getWebGameConfig().getPageFullUrl();
        for (ClientVersion v : versions()) {
            ClientVersionVo vo = new ClientVersionVo();
            vo.setId(v.getId());
            vo.setName(v.getName());
            vo.setAliases(v.getAliases());
            vo.setPackageId(v.getPackageId());
            ClientPackage pkg = v.getPackageId().isEmpty() ? null : packageService.find(v.getPackageId());
            vo.setPackageName(pkg == null ? "" : pkg.getName());
            vo.setPath(v.getPath());
            vo.setMcVersions(v.getMcVersions());
            vo.setEaglerProtocol(v.getEaglerProtocol());
            vo.setEnabled(v.isEnabled());
            vo.setOrder(v.getOrder());
            vo.setDefaultVersion(v.getId().equals(defaultId));
            vo.setSource(v.getSource());
            vo.setSha256(v.getSha256());
            vo.setSize(v.getSize());
            vo.setUploadedAt(v.getUploadedAt());
            vo.setMissing(v.isMissing());
            vo.setPagePath(prefix + v.getId() + "/");
            rows.add(vo);
        }
        return rows;
    }

    private ClientVersionVo findVo(String id) {
        for (ClientVersionVo vo : toVos()) {
            if (vo.getId().equals(id)) {
                return vo;
            }
        }
        return null;
    }

    private int closeSessions(String id) {
        WsGateway gw = plugin.getWsGateway();
        return gw == null ? 0 : gw.closeSessionsOfClient(id);
    }

    private boolean isServableQuiet(String id) {
        ClientVersion v = id == null ? null : versions.get(id);
        return v != null && v.isEnabled() && !v.isMissing();
    }

    @Override
    public File resolveClientFile(ClientVersion version) {
        if (version == null || ClientVersion.isUrl(version.getPath())) {
            return null;
        }
        String path = version.getPath().trim();
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

    /** URL 条目始终视为可用；本地条目要求文件存在。 */
    private boolean entryExists(ClientVersion v) {
        return ClientVersion.isUrl(v.getPath()) || resolveClientFile(v) != null;
    }

    private void applyBody(ClientVersion v, Map<String, Object> body) {
        if (body == null) {
            return;
        }
        String packageId = str(body.get("packageId")).trim();
        if (!packageId.isEmpty()) {
            v.setPackageId(packageId);
        }
        if (body.containsKey("aliases")) {
            v.setAliases(parseAliases(body.get("aliases")));
        }
        if (body.containsKey("mcVersions")) {
            v.setMcVersions(parseMcVersions(body.get("mcVersions")));
        }
        if (body.containsKey("eaglerProtocol")) {
            v.setEaglerProtocol(toInt(body.get("eaglerProtocol"), 0));
        }
        Boolean enabled = toBool(body.get("enabled"));
        if (enabled != null) {
            v.setEnabled(enabled);
        }
    }

    /**
     * 按 packageId 解析 Eagler 制品，把路径/指纹/来源回填到 ClientVersion。
     *
     * @return 空串表示成功；否则是给前端的错误文案。
     */
    private String applyPackage(ClientVersion v, boolean required) {
        String packageId = v.getPackageId();
        if (packageId.isEmpty()) {
            return required ? "请先从客户端管理选择 Eagler 客户端包" : "";
        }
        ClientPackage p = packageService.find(packageId);
        if (p == null) {
            return "客户端包不存在，请重新选择";
        }
        if (!p.isEagler()) {
            return "所选客户端包不是 Eagler 类型";
        }
        if (!p.isEnabled()) {
            return "该客户端包已停用，请先启用或选择其它包";
        }
        String path = p.getStoragePath();
        if (path.isEmpty()) {
            return "该客户端包没有可用的入口文件";
        }
        v.setName(p.getName());
        v.setPath(path);
        v.setSource(deriveSource(v));
        v.setSha256(p.getSha256());
        v.setSize(p.getSize());
        // 弹窗不再让管理员手填 MC 版本：新条目从制品版本推导，编辑时保留原值。
        if (v.getMcVersions().isEmpty()) {
            List<McVersionOption> derived = new ArrayList<>();
            addMcVersion(derived, resolveMcVersion(p.getVersion(), 0));
            v.setMcVersions(derived);
        }
        return "";
    }

    private void bindLoadedPackage(ClientVersion v) {
        if (v.getPackageId().isEmpty() && ClientVersion.BUILTIN_ID.equals(v.getId())) {
            v.setPackageId(packageService.builtinPackageId());
        }
        if (v.getPackageId().isEmpty()) {
            return;
        }
        ClientPackage p = packageService.find(v.getPackageId());
        if (p == null || !p.isEagler() || !p.isEnabled()) {
            v.setMissing(true);
            return;
        }
        String path = p.getStoragePath();
        if (path.isEmpty()) {
            v.setMissing(true);
            return;
        }
        v.setName(p.getName());
        v.setPath(path);
        v.setSource(p.isBuiltin() ? ClientVersion.SOURCE_BUILTIN
                : (p.isExternal() ? ClientVersion.SOURCE_URL : ClientVersion.SOURCE_UPLOADED));
        v.setSha256(p.getSha256());
        v.setSize(p.getSize());
    }

    /** 解析前端提交的 aliases：数组或逗号分隔字符串，去空、去重并截断超长项。 */
    private static List<String> parseAliases(Object raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) {
            return list;
        }
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                addAlias(list, item);
            }
        } else {
            for (String part : String.valueOf(raw).split("[,，]+")) {
                addAlias(list, part);
            }
        }
        return list;
    }

    private static void addAlias(List<String> list, Object raw) {
        String a = raw == null ? "" : String.valueOf(raw).trim();
        if (a.isEmpty() || a.length() > 32) {
            return;
        }
        if (!list.contains(a)) {
            list.add(a);
        }
    }

    /** 解析前端提交的 mcVersions（版本名数组或 {version,protocol} 数组），协议号以枚举为准。 */
    private static List<McVersionOption> parseMcVersions(Object raw) {
        List<McVersionOption> list = new ArrayList<>();
        if (!(raw instanceof List)) {
            return list;
        }
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) item;
                addMcVersion(list, resolveMcVersion(str(map.get("version")), toInt(map.get("protocol"), 0)));
            } else {
                addMcVersion(list, resolveMcVersion(str(item), 0));
            }
        }
        return list;
    }

    private static void addMcVersion(List<McVersionOption> list, McVersionOption opt) {
        if (opt == null || opt.getVersion().isEmpty()) {
            return;
        }
        for (McVersionOption exist : list) {
            if (exist.getVersion().equalsIgnoreCase(opt.getVersion())) {
                return;
            }
        }
        list.add(opt);
    }

    /** 版本名优先按枚举补全协议号；未收录时按协议号取对应版本；仍未知则保留自定义条目。 */
    private static McVersionOption resolveMcVersion(String version, int protocol) {
        String name = version == null ? "" : version.trim();
        McVersionOption found = McVersionOption.find(name);
        if (found != null) {
            return found;
        }
        if (protocol > 0) {
            found = McVersionOption.findByProtocol(protocol);
            if (found != null) {
                return found;
            }
        }
        return name.isEmpty() ? null : McVersionOption.of(name, protocol);
    }

    /** 制品来源映射：内置 → builtin；外链 → url；上传 → uploaded；旧条目再按路径猜。 */
    private String deriveSource(ClientVersion v) {
        ClientPackage p = v.getPackageId().isEmpty() ? null : packageService.find(v.getPackageId());
        if (p != null) {
            if (p.isBuiltin()) {
                return ClientVersion.SOURCE_BUILTIN;
            }
            return p.isExternal() ? ClientVersion.SOURCE_URL : ClientVersion.SOURCE_UPLOADED;
        }
        if (ClientVersion.isUrl(v.getPath())) {
            return ClientVersion.SOURCE_URL;
        }
        return ClientVersion.SOURCE_LOCAL;
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

    private int maxOrder() {
        int max = -1;
        for (ClientVersion v : versions.values()) {
            if (v.getOrder() > max) {
                max = v.getOrder();
            }
        }
        return max;
    }

    private String pickFallbackDefault() {
        return pickFallbackDefault(versions);
    }

    private static String pickFallbackDefault(Map<String, ClientVersion> map) {
        ClientVersion best = null;
        for (ClientVersion v : map.values()) {
            if (!v.isEnabled() || v.isMissing()) {
                continue;
            }
            if (best == null
                    || v.getOrder() < best.getOrder()
                    || (v.getOrder() == best.getOrder() && v.getId().compareTo(best.getId()) < 0)) {
                best = v;
            }
        }
        return best == null ? "" : best.getId();
    }

    private static boolean isValidId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,32}")
                && !id.startsWith(".") && !id.contains("..");
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
