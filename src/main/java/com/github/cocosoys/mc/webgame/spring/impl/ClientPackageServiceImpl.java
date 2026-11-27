package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.data.WebGameDataBootstrap;
import com.github.cocosoys.mc.webgame.data.WebGameDataStore;
import com.github.cocosoys.mc.webgame.spring.entity.ClientPackage;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.vo.ClientPackageVo;
import com.github.cocosoys.mc.webgame.spring.service.ClientPackageService;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 统一客户端制品库实现。
 *
 * <p>元数据统一走 {@link WebGameDataStore}，文件仍在 {@code packages/}：
 * 云游戏包统一 {@code packages/clients/<id>/<zip>}；Eagler 包（含内置）{@code packages/eagler/<id>/}。</p>
 */
public class ClientPackageServiceImpl implements ClientPackageService {

    private static final Logger LOG = Logger.getLogger(ClientPackageServiceImpl.class.getName());
    public static final int CHUNK_SIZE = 512 * 1024;
    private static final String DEFAULT_TARGET_DIR = "1.12.2-Forge_14.23.5.2864";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebGame plugin;
    private final WebGameDataStore store;
    private final Map<String, ClientPackage> packages = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();

    public ClientPackageServiceImpl(WebGame plugin) {
        this.plugin = plugin;
        this.store = plugin.getDataStore();
        if (this.store == null) {
            throw new IllegalStateException("WebGameDataStore 未初始化，无法加载客户端制品");
        }
        ensureDir(packagesDir());
        ensureDir(uploadDir());
        load();
    }

    // ===== 查询 =====

    @Override
    public List<ClientPackage> packages() {
        return packages(null);
    }

    @Override
    public List<ClientPackage> packages(String type) {
        String want = normalizeType(type);
        List<ClientPackage> list = new ArrayList<>();
        for (ClientPackage p : packages.values()) {
            if (want.isEmpty() || want.equals(p.getType())) {
                list.add(p);
            }
        }
        list.sort((a, b) -> {
            int t = a.getType().compareTo(b.getType());
            if (t != 0) {
                return t;
            }
            return compareId(a.getId(), b.getId());
        });
        return list;
    }

    @Override
    public ClientPackage find(String id) {
        return id == null ? null : packages.get(id.trim());
    }

    @Override
    public String urlOf(ClientPackage p) {
        if (p == null || p.getPublicPath().isEmpty()) {
            return "";
        }
        // Eagler 包直接从 WebGame 托管的 dist 静态目录预览，不依赖云机上报 report_base。
        if (p.isEagler()) {
            return WebGameConfig.WEB_PLUGIN_PREFIX + p.getPublicPath();
        }
        String base = plugin.getCloudClientService() == null
                ? "" : plugin.getCloudClientService().publicBase();
        if (base == null || base.trim().isEmpty()) {
            return "";
        }
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + WebGameConfig.WEB_PLUGIN_PREFIX + p.getPublicPath();
    }

    @Override
    public String manifestJson() {
        // 不再存在全局默认包：代理只能收到管理员显式下发的期望包，manifest 不含默认包。
        return "{\"enabled\":false,\"version\":\"\",\"package\":\"\",\"size\":0,\"files\":0,"
                + "\"sha256\":\"\",\"targetDir\":\"\",\"url\":\"\"}";
    }

    @Override
    public AjaxResult list(String type) {
        try {
            List<ClientPackageVo> rows = toVos(type);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rows", rows);
            data.put("packages", rows);
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("客户端制品查询失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult list() {
        return list(ClientPackage.TYPE_CLOUD);
    }

    // ===== 管理 =====

    @Override
    public AjaxResult update(String id, Map<String, Object> body) {
        ClientPackage p = find(id);
        if (p == null) {
            return AjaxResult.error("制品不存在：" + id);
        }
        try {
            if (body != null) {
                String name = str(body.get("name")).trim();
                if (!name.isEmpty()) {
                    p.setName(name);
                }
                if (body.containsKey("version")) {
                    p.setVersion(str(body.get("version")).trim());
                }
                if (body.containsKey("notes")) {
                    p.setNotes(str(body.get("notes")).trim());
                }
                if (p.isCloud() && body.containsKey("targetDir")) {
                    String targetDir = str(body.get("targetDir")).trim();
                    if (!targetDir.isEmpty()) {
                        p.setTargetDir(validateTargetDir(targetDir));
                    }
                }
                if (p.isEagler() && body.containsKey("entryPath")) {
                    if (p.isBuiltin()) {
                        return AjaxResult.error("内置客户端包的入口固定为 index.html，不可修改");
                    }
                    String entry = str(body.get("entryPath")).trim();
                    if (!entry.isEmpty() && !entry.equals(p.getEntryPath())) {
                        String checked = validateEntryPath(p, entry);
                        if (checked.isEmpty()) {
                            return AjaxResult.error("入口文件必须位于该 Eagler 包内，且指向已存在的 .html/.htm 文件");
                        }
                        p.setEntryPath(checked);
                    }
                }
                if (p.isExternal() && body.containsKey("externalUrl")) {
                    String url = str(body.get("externalUrl")).trim();
                    if (!isHttpUrl(url)) {
                        return AjaxResult.error("外链地址必须以 http:// 或 https:// 开头");
                    }
                    p.setExternalUrl(url);
                }
                if (body.get("enabled") != null) {
                    boolean enabled = toBool(body.get("enabled"), p.isEnabled());
                    if (!enabled) {
                        String reason = disableReason(p);
                        if (!reason.isEmpty()) {
                            return AjaxResult.error(reason);
                        }
                    }
                    p.setEnabled(enabled);
                }
            }
            store.save(p);
            refreshPages();
            return AjaxResult.success("已保存客户端包 " + p.getName(), vo(p));
        } catch (Throwable t) {
            return AjaxResult.error("保存失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult setEnabled(String id, boolean enabled) {
        ClientPackage p = find(id);
        if (p == null) {
            return AjaxResult.error("制品不存在：" + id);
        }
        if (!enabled) {
            String reason = disableReason(p);
            if (!reason.isEmpty()) {
                return AjaxResult.error(reason);
            }
        }
        p.setEnabled(enabled);
        store.save(p);
        refreshPages();
        return AjaxResult.success(enabled ? "已启用" : "已停用");
    }

    @Override
    public AjaxResult delete(String id) {
        ClientPackage p = find(id);
        if (p == null) {
            return AjaxResult.error("制品不存在：" + id);
        }
        if (p.isBuiltin()) {
            return AjaxResult.error("内置客户端包不可删除");
        }
        if (p.isEagler() && countEaglerRefs(p.getId()) > 0) {
            return AjaxResult.error("该 Eagler 客户端包仍被 " + countEaglerRefs(p.getId())
                    + " 个 Eagler 客户端引用，请先删除或改绑这些客户端");
        }
        try {
            if (p.isEagler() && !p.isExternal()) {
                deleteRecursively(new File(plugin.getDataFolder(), "packages/" + p.getFile()));
            } else if (p.isCloud()) {
                File f = new File(packagesDir(), p.getFile());
                File parent = f.getParentFile();
                boolean nested = parent != null
                        && parent.getAbsolutePath().startsWith(packagesDir().getAbsolutePath() + File.separator);
                if (nested) {
                    deleteRecursively(parent);
                } else {
                    Files.deleteIfExists(f.toPath());
                }
            }
            packages.remove(id);
            store.delete(ClientPackage.class, p.getId());
            if (p.isCloud() && plugin.getCloudClientService() != null) {
                plugin.getCloudClientService().clearPackageReference(id);
            }
            refreshPages();
            return AjaxResult.success("已删除客户端包 " + p.getName());
        } catch (Throwable t) {
            return AjaxResult.error("删除失败：" + t.getMessage());
        }
    }

    // ===== 分块上传 =====

    @Override
    public AjaxResult uploadInit(Map<String, Object> body) {
        try {
            String type = normalizeType(str(body == null ? null : body.get("type")));
            if (type.isEmpty()) {
                return AjaxResult.error("请选择客户端类型（eagler / cloud）");
            }
            String fileName = safeFileName(str(body == null ? null : body.get("fileName")));
            if (fileName.isEmpty()) {
                return AjaxResult.error("文件名不合法");
            }
            if (ClientPackage.TYPE_EAGLER.equals(type)
                    && !(fileName.toLowerCase().endsWith(".html")
                    || fileName.toLowerCase().endsWith(".htm")
                    || fileName.toLowerCase().endsWith(".zip"))) {
                return AjaxResult.error("Eagler 客户端仅支持 .html/.htm/.zip");
            }
            if (ClientPackage.TYPE_CLOUD.equals(type)
                    && !fileName.toLowerCase().endsWith(".zip")) {
                return AjaxResult.error("云游戏客户端仅支持 .zip");
            }
            String name = str(body == null ? null : body.get("name")).trim();
            if (name.isEmpty()) {
                name = fileName;
            }
            String version = str(body == null ? null : body.get("version")).trim();
            String targetDir = str(body == null ? null : body.get("targetDir")).trim();
            if (ClientPackage.TYPE_CLOUD.equals(type)) {
                if (version.isEmpty()) {
                    version = targetDir.isEmpty() ? DEFAULT_TARGET_DIR : targetDir;
                }
                if (targetDir.isEmpty()) {
                    targetDir = version;
                }
                targetDir = validateTargetDir(targetDir);
            }
            long size = toLong(body == null ? null : body.get("size"), 0L);

            String uploadId = UUID.randomUUID().toString().replace("-", "");
            File part = new File(uploadDir(), uploadId + ".part");
            if (!part.createNewFile()) {
                return AjaxResult.error("无法创建上传临时文件");
            }
            uploads.put(uploadId, new UploadSession(uploadId, part, type, fileName, name,
                    version, targetDir, size));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uploadId", uploadId);
            data.put("chunkSize", CHUNK_SIZE);
            return AjaxResult.success(data);
        } catch (Throwable t) {
            return AjaxResult.error("上传初始化失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult uploadChunk(String uploadId, Map<String, Object> body) {
        UploadSession session = uploadId == null ? null : uploads.get(uploadId);
        if (session == null) {
            return AjaxResult.error("上传会话不存在或已结束");
        }
        try {
            int index = toInt(body == null ? null : body.get("index"), -1);
            if (index != session.nextIndex) {
                return AjaxResult.error("分块顺序错误，期望 " + session.nextIndex + "，收到 " + index);
            }
            String data = str(body == null ? null : body.get("data")).trim();
            if (data.isEmpty()) {
                return AjaxResult.error("分块内容为空");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException e) {
                return AjaxResult.error("分块 base64 解码失败");
            }
            if (session.size > 0 && (long) index * CHUNK_SIZE + bytes.length > session.size) {
                return AjaxResult.error("分块超出声明大小 " + session.size);
            }
            try (RandomAccessFile raf = new RandomAccessFile(session.part, "rw")) {
                raf.seek((long) index * CHUNK_SIZE);
                raf.write(bytes);
            }
            session.nextIndex = index + 1;
            Map<String, Object> data2 = new LinkedHashMap<>();
            data2.put("received", session.nextIndex);
            return AjaxResult.success(data2);
        } catch (Throwable t) {
            return AjaxResult.error("分块写入失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult uploadFinish(String uploadId) {
        UploadSession session = uploadId == null ? null : uploads.remove(uploadId);
        if (session == null) {
            return AjaxResult.error("上传会话不存在或已结束");
        }
        File moved = null;
        File createdDir = null;
        ClientPackage created = null;
        try {
            if (!session.part.isFile() || session.part.length() == 0) {
                deleteQuietly(session.part);
                return AjaxResult.error("上传内容为空");
            }
            if (session.size > 0 && session.part.length() != session.size) {
                deleteQuietly(session.part);
                return AjaxResult.error("上传大小不符：声明 " + session.size + "，实际 " + session.part.length());
            }
            String sha = sha256(session.part);
            long uploadedSize = session.part.length();
            if (ClientPackage.TYPE_CLOUD.equals(session.type)) {
                int files = validateCloudZip(session.part);
                ClientPackage p = new ClientPackage();
                p.setType(ClientPackage.TYPE_CLOUD);
                p.setName(session.name);
                p.setVersion(session.version);
                p.setTargetDir(session.targetDir);
                p.setSha256(sha);
                p.setSize(uploadedSize);
                p.setFiles(files);
                p.setUploadedAt(now());
                p.setEnabled(true);
                p.setSource(ClientPackage.SOURCE_UPLOADED);
                // 先落元数据拿 AUTO 数字主键，再用主键生成文件目录。
                String id = saveWithAutoId(p);
                created = p;
                File dir = new File(packagesDir(), "clients/" + id);
                ensureDir(dir);
                File target = new File(dir, session.fileName);
                Files.move(session.part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved = target;
                p.setFile("clients/" + id + "/" + session.fileName);
                p.setSize(target.length());
                store.save(p);
                packages.put(id, p);
                refreshPages();
                return AjaxResult.success("云游戏客户端包已上传：" + p.getName(), vo(p));
            }

            ClientPackage p = new ClientPackage();
            p.setType(ClientPackage.TYPE_EAGLER);
            p.setName(session.name);
            p.setVersion(session.version);
            p.setSha256(sha);
            p.setSize(uploadedSize);
            p.setUploadedAt(now());
            p.setEnabled(true);
            p.setSource(ClientPackage.SOURCE_UPLOADED);
            String id = saveWithAutoId(p);
            created = p;
            File dir = new File(packagesDir(), "eagler/" + id);
            ensureDir(dir);
            createdDir = dir;
            String entry;
            int files;
            if (isZip(session.fileName)) {
                files = extractZip(session.part, dir);
                entry = findEaglerEntry(dir);
                deleteQuietly(session.part);
                if (entry.isEmpty()) {
                    throw new IllegalStateException("Eagler 压缩包里找不到 index.html 或唯一的 .html 入口");
                }
            } else {
                File target = new File(dir, session.fileName);
                Files.move(session.part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved = target;
                entry = session.fileName;
                files = 1;
            }
            p.setFile("eagler/" + id);
            p.setEntryPath(entry);
            p.setFiles(files);
            store.save(p);
            packages.put(id, p);
            refreshPages();
            return AjaxResult.success("Eagler 客户端包已上传：" + p.getName(), vo(p));
        } catch (Throwable t) {
            if (moved != null) {
                deleteQuietly(moved);
            }
            if (createdDir != null) {
                deleteRecursively(createdDir);
            }
            if (created != null && created.getIdValue() > 0) {
                store.delete(ClientPackage.class, created.getId());
            }
            deleteQuietly(session.part);
            return AjaxResult.error("上传完成失败：" + t.getMessage());
        }
    }

    /**
     * 落元数据并拿到 SOYS AUTO 分配的数字主键。
     * 这是制品的唯一 id，不再生成 eagler-N / cloud-N 业务编号。
     */
    private String saveWithAutoId(ClientPackage p) {
        if (!store.save(p) || p.getIdValue() <= 0) {
            throw new IllegalStateException("客户端包元数据写入失败：未生成自增主键");
        }
        return p.getId();
    }

    @Override
    public AjaxResult uploadAbort(String uploadId) {
        UploadSession session = uploadId == null ? null : uploads.remove(uploadId);
        if (session != null) {
            deleteQuietly(session.part);
        }
        return AjaxResult.success("已放弃上传");
    }

    @Override
    public AjaxResult registerExternal(Map<String, Object> body) {
        try {
            String url = str(body == null ? null : body.get("externalUrl")).trim();
            if (!isHttpUrl(url)) {
                return AjaxResult.error("外链地址必须以 http:// 或 https:// 开头");
            }
            String name = str(body == null ? null : body.get("name")).trim();
            if (name.isEmpty()) {
                return AjaxResult.error("请填写客户端名称");
            }
            ClientPackage p = new ClientPackage();
            p.setType(ClientPackage.TYPE_EAGLER);
            p.setName(name);
            p.setVersion(str(body == null ? null : body.get("version")).trim());
            p.setExternalUrl(url);
            p.setNotes(str(body == null ? null : body.get("notes")).trim());
            p.setSource(ClientPackage.SOURCE_EXTERNAL);
            p.setEnabled(body == null || body.get("enabled") == null || toBool(body.get("enabled"), true));
            p.setUploadedAt(now());
            String id = saveWithAutoId(p);
            packages.put(id, p);
            refreshPages();
            return AjaxResult.success("已登记外链客户端：" + name, vo(p));
        } catch (Throwable t) {
            return AjaxResult.error("登记失败：" + t.getMessage());
        }
    }

    // ===== 持久化 =====

    private void load() {
        Map<String, ClientPackage> loaded = new TreeMap<>();
        try {
            for (ClientPackage p : store.select(ClientPackage.class)) {
                if (p == null || p.getId().isEmpty()) {
                    continue;
                }
                normalize(p);
                normalizeCloudStorage(p);
                loaded.put(p.getId(), p);
            }
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "读取客户端制品失败: " + t);
        }
        packages.putAll(loaded);

        ensureBuiltinPackage();
        if (packages(ClientPackage.TYPE_CLOUD).isEmpty()) {
            seedLegacyZip();
        }
        refreshPages();
    }

    /**
     * 云游戏压缩包统一迁到 {@code packages/clients/<id>/}。
     *
     * <p>兼容两类旧位置：{@code packages/cloud/<id>/<zip>} 与根目录
     * {@code packages/cloud-client.zip}。只在启动加载时执行，失败则保留原路径。</p>
     */
    private void normalizeCloudStorage(ClientPackage p) {
        if (p == null || !p.isCloud() || p.isExternal()) {
            return;
        }
        String file = p.getFile();
        if (file.isEmpty() || file.startsWith("clients/")) {
            return;
        }
        File source = new File(packagesDir(), file);
        if (!source.isFile()) {
            return;
        }
        try {
            String fileName = source.getName();
            File dir = new File(packagesDir(), "clients/" + p.getId());
            ensureDir(dir);
            File target = new File(dir, fileName);
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            p.setFile("clients/" + p.getId() + "/" + fileName);
            p.setSize(target.length());
            store.save(p);

            File parent = source.getParentFile();
            if (parent != null && !parent.equals(packagesDir())
                    && parent.getAbsolutePath().startsWith(packagesDir().getAbsolutePath() + File.separator)) {
                File[] left = parent.listFiles();
                if (left != null && left.length == 0) {
                    deleteRecursively(parent);
                }
            }
            LOG.info("云游戏包 " + p.getId() + " 已迁入 packages/clients/" + p.getId() + "/");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "迁移云游戏包目录失败 " + p.getId() + ": " + t);
        }
    }

    /** 保证内置 Eagler 制品存在；文件统一释放到 packages/eagler/&lt;数字 id&gt;/。 */
    private void ensureBuiltinPackage() {
        ClientPackage exist = null;
        for (ClientPackage p : packages.values()) {
            if (p.isBuiltin() && p.isEagler()) {
                exist = p;
                break;
            }
        }
        if (exist == null) {
            ClientPackage builtin = WebGameDataBootstrap.builtinPackage();
            String id = saveWithAutoId(builtin);
            String expected = "eagler/" + id;
            if (!expected.equals(builtin.getFile())) {
                builtin.setFile(expected);
                store.save(builtin);
            }
            packages.put(id, builtin);
            ensureBuiltinFiles(id);
            updateBuiltinMeta(builtin);
            return;
        }
        String id = exist.getId();
        String file = exist.getFile();
        if (file.startsWith("eagler-clients/") || file.isEmpty()) {
            exist.setFile("eagler/" + id);
            exist.setEntryPath("index.html");
            store.save(exist);
        }
        ensureBuiltinFiles(id);
        updateBuiltinMeta(exist);
    }

    /** 内置制品首次释放后补齐大小、文件数与入口 SHA-256，避免表里长期是 0。 */
    private void updateBuiltinMeta(ClientPackage p) {
        File index = new File(packagesDir(), "eagler/" + p.getId() + "/index.html");
        if (!index.isFile()) {
            return;
        }
        try {
            String sha = sha256(index);
            int files = 1;
            File sidecar = new File(index.getParentFile(), "eagler-bootstrap.js");
            if (sidecar.isFile()) {
                files++;
            }
            if (p.getSize() == index.length() && p.getFiles() == files && sha.equals(p.getSha256())) {
                return;
            }
            p.setSize(index.length());
            p.setFiles(files);
            p.setSha256(sha);
            store.save(p);
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "计算内置 Eagler 制品元数据失败: " + t);
        }
    }

    @Override
    public String builtinPackageId() {
        for (ClientPackage p : packages.values()) {
            if (p.isBuiltin() && p.isEagler()) {
                return p.getId();
            }
        }
        return "";
    }

    /** 内置 Eagler 的两份文件随 jar 发布；只在缺失时复制，不覆盖管理员替换。 */
    private void ensureBuiltinFiles(String id) {
        File dir = new File(packagesDir(), "eagler/" + id);
        for (String name : new String[]{"index.html", "eagler-bootstrap.js"}) {
            File target = new File(dir, name);
            if (target.isFile()) {
                continue;
            }
            try (InputStream in = plugin.getResource("packages/eagler/1/" + name)) {
                if (in == null) {
                    LOG.log(Level.WARNING, "jar 内缺少内置 Eagler 资源 packages/eagler/1/" + name);
                    continue;
                }
                ensureDir(dir);
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOG.info("已释放内置 Eagler 资源 " + target.getPath());
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "释放内置 Eagler 资源失败 " + name + ": " + t);
            }
        }
    }

    /** 没有云游戏包但存在 packages/cloud-client.zip 时，登记并迁入 packages/clients/&lt;id&gt;/。 */
    private void seedLegacyZip() {
        File legacy = new File(packagesDir(), "cloud-client.zip");
        if (!legacy.isFile()) {
            return;
        }
        try {
            ClientPackage p = new ClientPackage();
            p.setType(ClientPackage.TYPE_CLOUD);
            p.setName("旧 cloud-client.zip");
            p.setVersion(DEFAULT_TARGET_DIR);
            p.setTargetDir(DEFAULT_TARGET_DIR);
            p.setSha256(sha256(legacy));
            p.setSize(legacy.length());
            p.setFiles(countZipEntries(legacy));
            p.setUploadedAt(now());
            p.setEnabled(true);
            p.setSource(ClientPackage.SOURCE_UPLOADED);
            String id = saveWithAutoId(p);
            File dir = new File(packagesDir(), "clients/" + id);
            ensureDir(dir);
            File target = new File(dir, "cloud-client.zip");
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            p.setFile("clients/" + id + "/cloud-client.zip");
            p.setSize(target.length());
            store.save(p);
            packages.put(id, p);
            plugin.getLogger().info("已把既有 cloud-client.zip 迁入 packages/clients/" + id + "/");
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "登记既有 cloud-client.zip 失败: " + t);
        }
    }

    private void refreshPages() {
        if (plugin.getCloudClientPages() != null) {
            plugin.getCloudClientPages().refreshClientPackages();
        }
    }

    // ===== 展示 =====

    private ClientPackageVo vo(ClientPackage p) {
        if (p == null) {
            return null;
        }
        ClientPackageVo vo = new ClientPackageVo(p);
        vo.setUrl(urlOf(p));
        if (p.isEagler()) {
            vo.setReferenceCount(countEaglerRefs(p.getId()));
        }
        return vo;
    }

    private List<ClientPackageVo> toVos(String type) {
        List<ClientPackageVo> rows = new ArrayList<>();
        for (ClientPackage p : packages(type)) {
            ensureFingerprint(p);
            rows.add(vo(p));
        }
        return rows;
    }

    private int countEaglerRefs(String packageId) {
        int count = 0;
        try {
            for (ClientVersion v : store.select(ClientVersion.class)) {
                if (v != null && packageId.equals(v.getPackageId())) {
                    count++;
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }

    private String disableReason(ClientPackage p) {
        if (p.isBuiltin()) {
            return "内置客户端包不可停用";
        }
        if (p.isEagler() && countEaglerRefs(p.getId()) > 0) {
            return "该 Eagler 客户端包仍被 " + countEaglerRefs(p.getId())
                    + " 个 Eagler 客户端引用，不能停用";
        }
        return "";
    }

    private void normalize(ClientPackage p) {
        if (p.getType().isEmpty()) {
            p.setType(ClientPackage.TYPE_CLOUD);
        }
        if (p.getSource().isEmpty()) {
            p.setSource(p.isBuiltin() ? ClientPackage.SOURCE_BUILTIN : ClientPackage.SOURCE_UPLOADED);
        }
        if (p.getName().isEmpty()) {
            p.setName(p.getId());
        }
        if (p.isExternal() && p.getFile().isEmpty()) {
            p.setEnabled(p.isEnabled());
        }
    }

    private void ensureFingerprint(ClientPackage p) {
        if (p == null || p.isExternal() || p.isBuiltin()
                || (p.getSha256() != null && !p.getSha256().isEmpty())) {
            return;
        }
        File f = new File(packagesDir(), p.getFile());
        if (!f.isFile()) {
            return;
        }
        try {
            p.setSha256(sha256(f));
            p.setSize(f.length());
            if (p.isCloud()) {
                p.setFiles(countZipEntries(f));
            }
        } catch (Throwable ignored) {
        }
    }

    // ===== 工具 =====

    private File packagesDir() {
        return new File(plugin.getDataFolder(), "packages");
    }

    private File uploadDir() {
        return new File(packagesDir(), ".upload");
    }

    private static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase();
        if (type.isEmpty() || "all".equals(type)) {
            return "";
        }
        if (ClientPackage.TYPE_EAGLER.equals(type) || ClientPackage.TYPE_CLOUD.equals(type)) {
            return type;
        }
        return "";
    }

    private static String safeFileName(String raw) {
        String name = raw == null ? "" : raw.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (!name.matches("[A-Za-z0-9._-]{1,128}")) {
            return "";
        }
        return name;
    }

    private static String validateTargetDir(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (!v.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("解包目录只能包含字母、数字、下划线、点、短横线（1-64 位）");
        }
        return v;
    }

    private String validateEntryPath(ClientPackage p, String raw) {
        String entry = raw == null ? "" : raw.trim().replace('\\', '/');
        if (entry.isEmpty() || entry.contains("..") || entry.startsWith("/")) {
            return "";
        }
        String lower = entry.toLowerCase();
        if (!lower.endsWith(".html") && !lower.endsWith(".htm")) {
            return "";
        }
        File root = new File(new File(packagesDir(), p.getFile()), entry);
        return root.isFile() ? entry : "";
    }

    private static String findEaglerEntry(File dir) {
        List<String> html = new ArrayList<>();
        collectHtml(dir, "", html);
        if (html.isEmpty()) {
            return "";
        }
        for (String path : html) {
            if ("index.html".equalsIgnoreCase(path) || "index.htm".equalsIgnoreCase(path)) {
                return path;
            }
        }
        String best = "";
        int bestDepth = Integer.MAX_VALUE;
        for (String path : html) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if ("index.html".equalsIgnoreCase(fileName) || "index.htm".equalsIgnoreCase(fileName)) {
                int depth = path.split("/").length;
                if (depth < bestDepth) {
                    bestDepth = depth;
                    best = path;
                }
            }
        }
        if (!best.isEmpty()) {
            return best;
        }
        return html.size() == 1 ? html.get(0) : "";
    }

    private static void collectHtml(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                collectHtml(f, rel, out);
            } else {
                String lower = f.getName().toLowerCase();
                if (lower.endsWith(".html") || lower.endsWith(".htm")) {
                    out.add(rel);
                }
            }
        }
    }

    /** 云游戏 zip：根目录或唯一一层子目录里必须有 mods/versions。 */
    private static int validateCloudZip(File zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) {
            boolean mods = false;
            boolean versions = false;
            int count = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                count++;
                String n = e.getName().replace('\\', '/');
                if (n.startsWith("mods/") || n.contains("/mods/")) {
                    mods = true;
                }
                if (n.startsWith("versions/") || n.contains("/versions/")) {
                    versions = true;
                }
            }
            if (!mods && !versions) {
                throw new IllegalArgumentException("zip 里找不到 mods/ 或 versions/，不像 Minecraft 客户端目录");
            }
            return count;
        }
    }

    private static int extractZip(File zip, File dir) throws Exception {
        int count = 0;
        String base = dir.getCanonicalPath() + File.separator;
        java.io.InputStream raw = new java.io.BufferedInputStream(new java.io.FileInputStream(zip));
        try (ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                File out = new File(dir, name);
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new java.io.IOException("压缩包包含非法路径: " + name);
                }
                if (entry.isDirectory()) {
                    ensureDir(out);
                } else {
                    ensureDir(out.getParentFile());
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zin.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    count++;
                }
                zin.closeEntry();
            }
        }
        return count;
    }

    private static int countZipEntries(File zip) throws Exception {
        int count = 0;
        try (ZipFile zf = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                if (!en.nextElement().isDirectory()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(f.toPath())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private static void ensureDir(File d) {
        if (d != null && !d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
            throw new IllegalStateException("无法创建目录: " + d);
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) {
                Files.delete(f.toPath());
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int toInt(Object o, int def) {
        try {
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long toLong(Object o, long def) {
        try {
            if (o instanceof Number) {
                return ((Number) o).longValue();
            }
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean toBool(Object o, boolean def) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o).trim();
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
            return false;
        }
        return def;
    }

    private static boolean isZip(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".zip");
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static int suffix(String id) {
        String raw = id == null ? "" : id;
        int dash = raw.lastIndexOf('-');
        if (dash >= 0) {
            raw = raw.substring(dash + 1);
        }
        return toInt(raw, 0);
    }

    private static String sanitizeLegacyId(String id) {
        String raw = id == null ? "" : id.trim();
        return raw.matches("[A-Za-z0-9_.-]{1,32}") ? raw : "legacy";
    }

    private static int compareId(String a, String b) {
        int x = suffix(a);
        int y = suffix(b);
        return x != y ? Integer.compare(x, y) : String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static final class UploadSession {
        final String uploadId;
        final File part;
        final String type;
        final String fileName;
        final String name;
        final String version;
        final String targetDir;
        final long size;
        int nextIndex;

        UploadSession(String uploadId, File part, String type, String fileName, String name,
                      String version, String targetDir, long size) {
            this.uploadId = uploadId;
            this.part = part;
            this.type = type;
            this.fileName = fileName;
            this.name = name;
            this.version = version;
            this.targetDir = targetDir;
            this.size = size;
        }
    }
}
