package com.github.cocosoys.mc.webgame.data;

import com.github.cocosoys.mc.soyshttpovermc.orm.SQL;
import com.github.cocosoys.mc.soyshttpovermc.orm.YamlPojo;

import java.util.List;

/**
 * WebGame 的本地数据门面。
 *
 * <p>YAML 模式使用独立的 {@link YamlPojo}，数据目录是 {@code plugins/WebGame/data}，
 * 不使用 SOYS 的全局 {@code YAML.Pojo}；MySQL 模式使用 SOYS 已经装配的
 * {@link SQL#Pojo}。这样两套模式都不会影响 SOYS/ERP 自己的表。</p>
 */
public final class WebGameDataStore {

    public enum Mode {
        YAML,
        MYSQL
    }

    private final Mode mode;
    private final YamlPojo yaml;

    WebGameDataStore(Mode mode, YamlPojo yaml) {
        this.mode = mode;
        this.yaml = yaml;
    }

    public Mode mode() {
        return mode;
    }

    public <T> List<T> select(Class<T> type) {
        return mode == Mode.YAML ? yaml.select(type) : SQL.Pojo.select(type);
    }

    public <T> T get(Class<T> type, Object id) {
        return mode == Mode.YAML ? yaml.get(type, id) : SQL.Pojo.get(type, id);
    }

    /**
     * 保存一行：YAML 端整体覆盖即 upsert；MySQL 端先 update，未命中再 insert。
     */
    public <T> boolean save(T bean) {
        if (bean == null) {
            return false;
        }
        if (mode == Mode.YAML) {
            return yaml.updateById(bean);
        }
        return SQL.Pojo.updateById(bean) || SQL.Pojo.insert(bean);
    }

    public <T> boolean delete(Class<T> type, Object id) {
        return mode == Mode.YAML ? yaml.deleteById(type, id) : SQL.Pojo.deleteById(type, id);
    }
}
