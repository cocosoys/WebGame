package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.platform.PlatformYaml;
import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.ConfigUtil;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.WebGameConfigFile;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameConfigFileVo;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameConfigSaveVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameConfigService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 插件配置文件编辑实现：目标目录 = owner 插件（WebGame）自己的数据目录。
 *
 * <p>构造器注入 owner 插件实例，按 {@code plugin.getDataFolder()} 解析，不写死任何插件名。</p>
 *
 * <p>读写全部复用平台层：{@link PlatformYaml#load}/{@link PlatformYaml#save}
 * （统一接管编码差异，低版本 UTF-8 不乱码）+ {@link ConfigUtil#toMap}/{@link ConfigUtil#toYaml}
 * （YAML ↔ Map 递归转换）。本实现不做任何文件层解析，不绑定具体版本。
 * 全量写回（注释不保留）；隧道/页面参数在插件启用时装配，故提示重启服务器生效。</p>
 */
public class WebGameConfigServiceImpl implements WebGameConfigService {

    private final WebGame plugin;

    public WebGameConfigServiceImpl(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public AjaxResult list() {
        try {
            List<WebGameConfigFileVo> rows = new ArrayList<>();
            for (WebGameConfigFile f : WebGameConfigFile.values()) {
                WebGameConfigFileVo vo = new WebGameConfigFileVo();
                vo.setId(f.getId());
                vo.setName(f.getName());
                vo.setFile(f.getFile());
                vo.setDesc(f.getDesc());
                rows.add(vo);
            }
            return AjaxResult.success(rows);
        } catch (Throwable t) {
            return AjaxResult.error("配置清单查询失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult load(String fileId) {
        try {
            WebGameConfigFile f = requireFile(fileId);
            File file = resolve(f);
            if (!file.isFile()) {
                return AjaxResult.error("配置文件不存在，请先启动一次 WebGame 生成默认配置: "
                        + file.getPath());
            }
            return AjaxResult.success(ConfigUtil.toMap(PlatformYaml.load(file)));
        } catch (Throwable t) {
            return AjaxResult.error("配置读取失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult save(String fileId, Map<String, Object> data) {
        try {
            WebGameConfigFile f = requireFile(fileId);
            File file = resolve(f);
            if (!file.isFile()) {
                return AjaxResult.error("配置文件不存在，请先启动一次 WebGame 生成默认配置: "
                        + file.getPath());
            }
            if (data == null) {
                return AjaxResult.error("配置内容为空");
            }
            PlatformYaml.save(ConfigUtil.toYaml(data), file);
            plugin.reloadConfig();
            return AjaxResult.success(WebGameConfigSaveVo.of(f.getFile(),
                    "已保存。隧道/页面参数在插件启用时装配，请重启服务器后生效"));
        } catch (Throwable t) {
            return AjaxResult.error("配置保存失败：" + t.getMessage());
        }
    }

    // ===== 内部 =====

    private static WebGameConfigFile requireFile(String fileId) {
        WebGameConfigFile f = WebGameConfigFile.byId(fileId);
        if (f == null) {
            throw new IllegalArgumentException("未知配置文件 id: " + fileId);
        }
        return f;
    }

    /** owner 插件自己的数据目录下解析目标文件。 */
    private File resolve(WebGameConfigFile f) {
        if (plugin == null) {
            throw new IllegalStateException("owner 插件实例未注入");
        }
        return new File(plugin.getDataFolder(), f.getFile());
    }
}
