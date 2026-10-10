package com.github.cocosoys.mc.webgame.web.cloud;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 云游戏镜像仓库管理器（"特定软件启动镜像"的插件侧入口）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>{@link #scan()} 扫描镜像仓库目录（config: cloud.images.dir，默认
 *       server-cloud/images）解析全部 image.json → 可用镜像列表（供 devices 页渲染）；</li>
 *   <li>{@link #find(String)} 按镜像 id 或目录名查镜像；</li>
 *   <li>{@link #defaultImage()} 返回配置指定默认镜像（config: cloud.images.default），
 *       未指定取扫描结果第一项；</li>
 *   <li>镜像缺失 / manifest 损坏时跳过并告警，不影响其余镜像可用性。</li>
 * </ul>
 *
 * <p>镜像目录为版本化目录（如 mc-1.12.2-v2），插件不缓存目录内容——每次 scan 重新
 * 读 manifest，保证更新（换目录/换 manifest）即时生效，无需 reload。</p>
 */
public final class CloudImageManager {

    private final Logger log;
    private final File imagesDir;
    private final String defaultImageId;

    /** 最近一次 scan 结果（按镜像目录名升序；不可变）。 */
    private volatile List<CloudImage> cache = Collections.emptyList();
    /** id → 镜像 快速索引（scan 时重建）。 */
    private final Map<String, CloudImage> byId = new ConcurrentHashMap<>();

    /**
     * @param imagesDir 镜像仓库目录（绝对路径）
     * @param defaultImageId 默认镜像 id（可空；空则取扫描第一项）
     */
    public CloudImageManager(Logger log, File imagesDir, String defaultImageId) {
        this.log = log;
        this.imagesDir = imagesDir;
        this.defaultImageId = defaultImageId == null ? "" : defaultImageId.trim();
    }

    /** 镜像仓库目录。 */
    public File getImagesDir() {
        return imagesDir;
    }

    /** 配置指定默认镜像 id（可空）。 */
    public String getDefaultImageId() {
        return defaultImageId;
    }

    /** 镜像目录是否存在且非空（设备选择页"可用性"提示用）。 */
    public boolean isAvailable() {
        return imagesDir.isDirectory();
    }

    /**
     * 扫描镜像仓库：遍历 imagesDir 一级子目录，解析各目录 image.json。
     * 跳过缺失/损坏 manifest 的子目录（记一条 warning）。返回按目录名排序的镜像列表。
     */
    public List<CloudImage> scan() {
        List<CloudImage> out = new ArrayList<>();
        byId.clear();
        if (!imagesDir.isDirectory()) {
            if (log != null) {
                log.warning("[WebGame] 镜像仓库目录不存在：" + imagesDir.getAbsolutePath());
            }
            cache = Collections.emptyList();
            return cache;
        }
        File[] subs = imagesDir.listFiles();
        if (subs != null) {
            java.util.Arrays.sort(subs, java.util.Comparator.comparing(File::getName));
            for (File sub : subs) {
                if (!sub.isDirectory()) {
                    continue;
                }
                CloudImage img = CloudImage.parse(sub);
                if (img == null) {
                    if (log != null) {
                        log.warning("[WebGame] 跳过无效镜像目录（缺 image.json 或解析失败）：" + sub.getName());
                    }
                    continue;
                }
                out.add(img);
                byId.put(img.getId(), img);
                byId.put(sub.getName(), img); // 目录名也可作别名
            }
        }
        cache = Collections.unmodifiableList(out);
        return cache;
    }

    /** 最近一次 scan 结果；未 scan 过则先 scan。 */
    public List<CloudImage> list() {
        List<CloudImage> cur = cache;
        if (cur.isEmpty() && imagesDir.isDirectory()) {
            return scan();
        }
        return cur;
    }

    /** 按镜像 id（或目录名）查镜像；未命中返回 null。 */
    public CloudImage find(String idOrDir) {
        if (idOrDir == null || idOrDir.isEmpty()) {
            return null;
        }
        CloudImage hit = byId.get(idOrDir);
        if (hit != null) {
            return hit;
        }
        // 兜底：重新扫描一次（镜像可能刚放入目录）
        scan();
        return byId.get(idOrDir);
    }

    /** 默认镜像：配置指定 id 命中则用之；否则扫描结果第一项；仓库为空返回 null。 */
    public CloudImage defaultImage() {
        if (!defaultImageId.isEmpty()) {
            CloudImage hit = find(defaultImageId);
            if (hit != null) {
                return hit;
            }
        }
        List<CloudImage> cur = list();
        return cur.isEmpty() ? null : cur.get(0);
    }
}
