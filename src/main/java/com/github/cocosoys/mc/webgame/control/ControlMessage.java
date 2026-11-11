package com.github.cocosoys.mc.webgame.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 管控契约单条消息：类型 + 字段表（或二进制载荷）。 */
public final class ControlMessage {

    public final int type;
    private final Map<String, String> fields;
    /** 二进制载荷（视频配置/视频帧/输入帧）；字段帧为 null。 */
    private final byte[] binary;

    ControlMessage(int type, Map<String, String> fields) {
        this.type = type;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.binary = null;
    }

    /** 二进制消息（S_VIDEO_CONFIG / S_VIDEO_FRAME / C_INPUT）。 */
    ControlMessage(int type, byte[] binary) {
        this.type = type;
        this.fields = Collections.emptyMap();
        this.binary = binary;
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

    /** 二进制载荷（视频/输入帧）；字段帧返回 null。 */
    public byte[] binary() {
        return binary;
    }

    @Override
    public String toString() {
        return "CtrlMsg{type=" + type + ", fields=" + fields + "}";
    }
}
