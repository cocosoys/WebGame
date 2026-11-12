package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.platform.PlatformYaml;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.ConfigUtil;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.vo.ClientImpactVo;
import com.github.cocosoys.mc.webgame.spring.entity.vo.ClientVersionVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameClientService;
import com.github.cocosoys.mc.webgame.shttp.WebGameClientPages;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 客户端版本管理实现。
 *
 * <p>磁盘：{@code clients/<id>/index.html}；元数据：{@code clients/clients.yml}；
 * 上传临时文件：{@code clients/.upload/<uploadId>.part}。</p>
 */
public class WebGameClientServiceImpl implements WebGameClientService {

    private static final Logger LOG = Logger.getLogger(WebGameClientServiceImpl.class.getName());

    /** 分块大小：512KB（base64 后约 683KB，规避网关请求体上限）。 */
    public static final int CHUNK_SIZE = 512 * 1024;
    private static final String INDEX_FILE = "index.html";
    private static final String META_FILE = "clients.yml";
    private static final String UPLOAD_DIR = ".upload";

    private final WebGame plugin;
    private final File clientsDir;
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();

    /** id → 版本（按 id 排序、并发安全）：WS 嗅探器会并发读取准入状态。 */
    private volatile Map<String, ClientVersion> versions = new ConcurrentSkipListMap<>();
    private volatile String defaultId = ClientVersion.BUILTIN_ID;

    public WebGameClientServiceImpl(WebGame plugin) {
        this.plugin = plugin;
        this.clientsDir = new File(plugin.getDataFolder(), "clients");
        ensureDir(clientsDir);
        ensureBuiltin();
        load();
    }

    // ===== 查询 =====

    @Override
    public AjaxResult list() {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("defaultId", defaultId);
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
            if (v.isBuiltin() && !enabled) {
                return AjaxResult.error("内置客户端不可关闭");
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
            defaultId = id;
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
            deleteRecursively(new File(clientsDir, id));
            versions.remove(id);
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
    public AjaxResult rescan() {
        try {
            load();
            publishPages();
            Map<String, Object> data = new HashMap<>();
            data.put("defaultId", defaultId);
            data.put("rows", toVos());
            return AjaxResult.success("已重新扫描客户端目录", data);
        } catch (Throwable t) {
            return AjaxResult.error("重扫失败：" + t.getMessage());
        }
    }

    // ===== 分块上传 =====

    @Override
    public AjaxResult uploadInit(Map<String, Object> body) {
        try {
            String id = str(body == null ? null : body.get("id")).trim();
            if (!isValidId(id)) {
                return AjaxResult.error("版本 id 只能包含字母、数字、下划线、点、短横线（1-32 位）");
            }
            if (ClientVersion.BUILTIN_ID.equalsIgnoreCase(id)) {
                return AjaxResult.error("版本 id " + ClientVersion.BUILTIN_ID + " 为内置客户端保留");
            }
            String uploadId = UUID.randomUUID().toString().replace("-", "");
            File part = new File(uploadDir(), uploadId + ".part");
            ensureDir(uploadDir());
            if (!part.createNewFile()) {
                return AjaxResult.error("无法创建上传临时文件");
            }
            UploadSession session = new UploadSession(uploadId, id, part,
                    str(body.get("name")).trim(),
                    str(body.get("mcVersion")).trim(),
                    toInt(body.get("mcProtocol"), 0),
                    toInt(body.get("eaglerProtocol"), 0));
            uploads.put(uploadId, session);
            Map<String, Object> data = new HashMap<>();
            data.put("uploadId", uploadId);
            data.put("chunkSize", CHUNK_SIZE);
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("上传初始化失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult uploadChunk(String uploadId, Map<String, Object> body) {
        UploadSession session = uploads.get(uploadId);
        if (session == null) {
            return AjaxResult.error("上传会话不存在或已结束");
        }
        try {
            int index = toInt(body == null ? null : body.get("index"), -1);
            if (index != session.nextIndex) {
                return AjaxResult.error("分块顺序错误，期望 " + session.nextIndex + "，收到 " + index);
            }
            String data = str(body.get("data")).trim();
            if (data.isEmpty()) {
                return AjaxResult.error("分块内容为空");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                return AjaxResult.error("分块 base64 解码失败");
            }
            try (RandomAccessFile raf = new RandomAccessFile(session.part, "rw")) {
                raf.seek((long) index * CHUNK_SIZE);
                raf.write(bytes);
            }
            session.nextIndex = index + 1;
            Map<String, Object> data2 = new HashMap<>();
            data2.put("received", session.nextIndex);
            return AjaxResult.success(data2);
        } catch (Throwable t) {
            return AjaxResult.error("分块写入失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult uploadFinish(String uploadId) {
        UploadSession session = uploads.remove(uploadId);
        if (session == null) {
            return AjaxResult.error("上传会话不存在或已结束");
        }
        try {
            if (!session.part.isFile() || session.part.length() == 0) {
                deleteQuietly(session.part);
                return AjaxResult.error("上传内容为空");
            }
            String sha = sha256(session.part);
            File dir = new File(clientsDir, session.id);
            ensureDir(dir);
            File target = new File(dir, INDEX_FILE);
            Files.move(session.part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            ClientVersion v = versions.get(session.id);
            if (v == null || v.isBuiltin()) {
                v = new ClientVersion();
                v.setId(session.id);
                versions.put(session.id, v);
            }
            v.setSource(ClientVersion.SOURCE_UPLOADED);
            v.setName(session.name);
            v.setMcVersion(session.mcVersion);
            v.setMcProtocol(session.mcProtocol);
            v.setEaglerProtocol(session.eaglerProtocol);
            v.setEnabled(true);
            v.setSha256(sha);
            v.setSize(target.length());
            v.setUploadedAt(System.currentTimeMillis());
            v.setMissing(false);
            if (!isServableQuiet(defaultId)) {
                defaultId = session.id;
            }
            save();
            publishPages();
            return AjaxResult.success("上传完成：" + session.id, findVo(session.id));
        } catch (Throwable t) {
            deleteQuietly(session.part);
            return AjaxResult.error("上传完成失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult uploadAbort(String uploadId) {
        UploadSession session = uploads.remove(uploadId);
        if (session != null) {
            deleteQuietly(session.part);
        }
        return AjaxResult.success("已取消上传", null);
    }

    // ===== 供页面 / 嗅探器使用 =====

    @Override
    public List<ClientVersion> versions() {
        return new ArrayList<>(versions.values());
    }

    @Override
    public boolean isServable(String id) {
        if (id == null) {
            return false;
        }
        ClientVersion v = versions.get(id);
        return v != null && v.isEnabled() && !v.isMissing();
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

    /** 从 clients.yml + 目录扫描重建内存视图，并回写规范化后的元数据。 */
    private synchronized void load() {
        Map<String, ClientVersion> loaded = new TreeMap<>();
        String def = ClientVersion.BUILTIN_ID;
        File meta = metaFile();
        if (meta.isFile()) {
            try {
                YamlConfiguration yaml = PlatformYaml.load(meta);
                String d = yaml.getString("default-id", "");
                if (d != null && !d.trim().isEmpty()) {
                    def = d.trim();
                }
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
                        ClientVersion v = new ClientVersion();
                        v.setId(key);
                        v.setName(c.getString("name", key));
                        v.setMcVersion(c.getString("mc-version", ""));
                        v.setMcProtocol(c.getInt("mc-protocol", 0));
                        v.setEaglerProtocol(c.getInt("eagler-protocol", 0));
                        v.setEnabled(c.getBoolean("enabled", true));
                        v.setSource(c.getString("source", ClientVersion.SOURCE_UPLOADED));
                        v.setSha256(c.getString("sha256", ""));
                        v.setSize(c.getLong("size", 0L));
                        v.setUploadedAt(c.getLong("uploaded-at", 0L));
                        loaded.put(key, v);
                    }
                }
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "读取 clients.yml 失败: " + t);
            }
        }

        // 内置条目：始终存在、始终启用
        ClientVersion builtin = loaded.get(ClientVersion.BUILTIN_ID);
        if (builtin == null) {
            builtin = new ClientVersion();
            builtin.setId(ClientVersion.BUILTIN_ID);
            loaded.put(ClientVersion.BUILTIN_ID, builtin);
        }
        builtin.setSource(ClientVersion.SOURCE_BUILTIN);
        builtin.setEnabled(true);
        if (builtin.getName().isEmpty() || builtin.getName().equals(builtin.getId())) {
            builtin.setName("1.12.2 内置客户端");
        }
        if (builtin.getMcVersion().isEmpty()) {
            builtin.setMcVersion("1.12.2");
        }
        if (builtin.getMcProtocol() <= 0) {
            builtin.setMcProtocol(340);
        }
        if (builtin.getEaglerProtocol() <= 0) {
            builtin.setEaglerProtocol(4);
        }

        // 目录扫描：手工放进去的版本也能被发现
        File[] dirs = clientsDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                String id = dir.getName();
                if (!isValidId(id) || UPLOAD_DIR.equals(id)) {
                    continue;
                }
                File index = new File(dir, INDEX_FILE);
                if (!index.isFile()) {
                    continue;
                }
                ClientVersion v = loaded.get(id);
                if (v == null) {
                    v = new ClientVersion();
                    v.setId(id);
                    v.setName(id);
                    v.setSource(ClientVersion.SOURCE_UPLOADED);
                    v.setEnabled(true);
                    v.setSize(index.length());
                    v.setUploadedAt(index.lastModified());
                    loaded.put(id, v);
                }
            }
        }

        for (ClientVersion v : loaded.values()) {
            File index = new File(new File(clientsDir, v.getId()), INDEX_FILE);
            v.setMissing(!index.isFile());
            if (v.isBuiltin()) {
                v.setEnabled(true);
            }
        }

        if (!loaded.containsKey(def)) {
            def = pickFallbackDefault(loaded);
        }
        this.versions = new ConcurrentSkipListMap<>(loaded);
        this.defaultId = def;
        save();
    }

    private synchronized void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("default-id", defaultId);
            Map<String, Object> clients = new LinkedHashMap<>();
            for (ClientVersion v : versions.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", v.getName());
                m.put("mc-version", v.getMcVersion());
                m.put("mc-protocol", v.getMcProtocol());
                m.put("eagler-protocol", v.getEaglerProtocol());
                m.put("enabled", v.isEnabled());
                m.put("source", v.getSource());
                m.put("sha256", v.getSha256());
                m.put("size", v.getSize());
                m.put("uploaded-at", v.getUploadedAt());
                clients.put(v.getId(), m);
            }
            root.put("clients", clients);
            ensureDir(clientsDir);
            PlatformYaml.save(ConfigUtil.toYaml(root), metaFile());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "写入 clients.yml 失败: " + t);
        }
    }

    /** 首次启用时从 jar 释放内置客户端（已存在则不覆盖，避免抹掉管理员替换的文件）。 */
    private void ensureBuiltin() {
        File dir = new File(clientsDir, ClientVersion.BUILTIN_ID);
        File index = new File(dir, INDEX_FILE);
        if (index.isFile()) {
            return;
        }
        ensureDir(dir);
        try {
            plugin.saveResource("clients/" + ClientVersion.BUILTIN_ID + "/" + INDEX_FILE, false);
            LOG.info("已释放内置客户端到 " + index.getPath());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "释放内置客户端失败（jar 内缺少 clients/"
                    + ClientVersion.BUILTIN_ID + "/" + INDEX_FILE + "）: " + t);
        }
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
            vo.setMcVersion(v.getMcVersion());
            vo.setMcProtocol(v.getMcProtocol());
            vo.setEaglerProtocol(v.getEaglerProtocol());
            vo.setEnabled(v.isEnabled());
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

    private String pickFallbackDefault() {
        return pickFallbackDefault(versions);
    }

    private static String pickFallbackDefault(Map<String, ClientVersion> map) {
        ClientVersion builtin = map.get(ClientVersion.BUILTIN_ID);
        if (builtin != null && !builtin.isMissing()) {
            return ClientVersion.BUILTIN_ID;
        }
        for (ClientVersion v : map.values()) {
            if (v.isEnabled() && !v.isMissing()) {
                return v.getId();
            }
        }
        return ClientVersion.BUILTIN_ID;
    }

    private File metaFile() {
        return new File(clientsDir, META_FILE);
    }

    private File uploadDir() {
        return new File(clientsDir, UPLOAD_DIR);
    }

    private static void ensureDir(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("无法创建目录: " + dir.getPath());
        }
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

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    /** 一次上传会话（仅内存，重启即作废）。 */
    private static final class UploadSession {
        final String uploadId;
        final String id;
        final File part;
        final String name;
        final String mcVersion;
        final int mcProtocol;
        final int eaglerProtocol;
        int nextIndex;

        UploadSession(String uploadId, String id, File part, String name,
                      String mcVersion, int mcProtocol, int eaglerProtocol) {
            this.uploadId = uploadId;
            this.id = id;
            this.part = part;
            this.name = name;
            this.mcVersion = mcVersion;
            this.mcProtocol = mcProtocol;
            this.eaglerProtocol = eaglerProtocol;
        }
    }
}
