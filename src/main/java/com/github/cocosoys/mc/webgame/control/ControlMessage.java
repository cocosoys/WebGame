package com.github.cocosoys.mc.webgame.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管控契约单条消息：类型 + 字段表。 */
public final class ControlMessage {

    public final int type;
    private final Map<String, String> fields;

    ControlMessage(int type, Map<String, String> fields) {
        this.type = type;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String get(String key) {
        return fields.get(key);
    }

    public String get(String key, String def) {
        String v = fields.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public int getInt(String key, int def) {
        String v = fields.get(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = fields.get(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    public Map<String, String> fields() {
        return fields;
    }

    @Override
    public String toString() {
        return "CtrlMsg{type=" + type + ", fields=" + fields + "}";
    }
}
