package com.github.cocosoys.mc.webgame.spring.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minecraft 版本 ↔ 协议号对照表（仅用于 ERP 展示与记录，不参与握手协商）。
 *
 * <p>按版本从新到旧排列，供 ERP 下拉多选；客户端条目保存时同时冗余协议号。</p>
 */
public final class McVersionOption {

    private final String version;
    private final int protocol;

    private McVersionOption(String version, int protocol) {
        this.version = version;
        this.protocol = protocol;
    }

    public String getVersion() {
        return version;
    }

    public int getProtocol() {
        return protocol;
    }

    private static final List<McVersionOption> ALL;

    static {
        List<McVersionOption> list = new ArrayList<>();
        add(list, "1.21.10", 773);
        add(list, "1.21.9", 773);
        add(list, "1.21.8", 772);
        add(list, "1.21.7", 772);
        add(list, "1.21.6", 771);
        add(list, "1.21.5", 770);
        add(list, "1.21.4", 769);
        add(list, "1.21.3", 768);
        add(list, "1.21.2", 768);
        add(list, "1.21.1", 767);
        add(list, "1.21", 767);
        add(list, "1.20.6", 766);
        add(list, "1.20.5", 766);
        add(list, "1.20.4", 765);
        add(list, "1.20.3", 765);
        add(list, "1.20.2", 764);
        add(list, "1.20.1", 763);
        add(list, "1.20", 763);
        add(list, "1.19.4", 762);
        add(list, "1.19.3", 761);
        add(list, "1.19.2", 760);
        add(list, "1.19.1", 760);
        add(list, "1.19", 759);
        add(list, "1.18.2", 758);
        add(list, "1.18.1", 757);
        add(list, "1.18", 757);
        add(list, "1.17.1", 756);
        add(list, "1.17", 755);
        add(list, "1.16.5", 754);
        add(list, "1.16.4", 754);
        add(list, "1.16.3", 753);
        add(list, "1.16.2", 751);
        add(list, "1.16.1", 736);
        add(list, "1.16", 735);
        add(list, "1.15.2", 578);
        add(list, "1.15.1", 575);
        add(list, "1.15", 573);
        add(list, "1.14.4", 498);
        add(list, "1.14.3", 490);
        add(list, "1.14.2", 485);
        add(list, "1.14.1", 480);
        add(list, "1.14", 477);
        add(list, "1.13.2", 404);
        add(list, "1.13.1", 401);
        add(list, "1.13", 393);
        add(list, "1.12.2", 340);
        add(list, "1.12.1", 338);
        add(list, "1.12", 335);
        add(list, "1.11.2", 316);
        add(list, "1.11.1", 316);
        add(list, "1.11", 315);
        add(list, "1.10.2", 210);
        add(list, "1.10.1", 210);
        add(list, "1.10", 210);
        add(list, "1.9.4", 110);
        add(list, "1.9.3", 110);
        add(list, "1.9.2", 109);
        add(list, "1.9.1", 108);
        add(list, "1.9", 107);
        add(list, "1.8.9", 47);
        add(list, "1.8.8", 47);
        add(list, "1.8.7", 47);
        add(list, "1.8.6", 47);
        add(list, "1.8.5", 47);
        add(list, "1.8.4", 47);
        add(list, "1.8.3", 47);
        add(list, "1.8.2", 47);
        add(list, "1.8.1", 47);
        add(list, "1.8", 47);
        add(list, "1.7.10", 5);
        add(list, "1.7.9", 5);
        add(list, "1.7.8", 5);
        add(list, "1.7.7", 5);
        add(list, "1.7.6", 5);
        add(list, "1.7.5", 4);
        add(list, "1.7.4", 4);
        add(list, "1.7.3", 4);
        add(list, "1.7.2", 4);
        ALL = Collections.unmodifiableList(list);
    }

    private static void add(List<McVersionOption> list, String version, int protocol) {
        list.add(new McVersionOption(version, protocol));
    }

    public static List<McVersionOption> all() {
        return ALL;
    }

    /** 按版本名查找；未收录时返回 null。 */
    public static McVersionOption find(String version) {
        if (version == null) {
            return null;
        }
        for (McVersionOption o : ALL) {
            if (o.version.equalsIgnoreCase(version.trim())) {
                return o;
            }
        }
        return null;
    }

    /** 构造条目（用于兼容历史配置里的自定义版本名）。 */
    public static McVersionOption of(String version, int protocol) {
        return new McVersionOption(version == null ? "" : version.trim(), protocol);
    }

    /** 按协议号查找（取最新版本）；未收录时返回 null。 */
    public static McVersionOption findByProtocol(int protocol) {
        for (McVersionOption o : ALL) {
            if (o.protocol == protocol) {
                return o;
            }
        }
        return null;
    }
}
