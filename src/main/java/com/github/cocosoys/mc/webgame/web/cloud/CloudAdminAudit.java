package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.WebGame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 管理动作审计：追加写 {@code plugins/WebGame/cloud-admin-audit.log}。
 *
 * <p>只记管理员主动发起的高风险动作（强制结束、重启、删除）以及可选的
 * 范围变更；不记录玩家会话的正常开始/结束，避免日志被淹没。</p>
 *
 * <p>当前 SOYS 的鉴权上下文没有把操作者身份透传到控制器，admin 字段先记
 * {@code -}；等网关补上 principal 后只需改这一处。</p>
 */
public final class CloudAdminAudit {

    private static final Logger LOG = Logger.getLogger(CloudAdminAudit.class.getName());
    private static final String FILE = "cloud-admin-audit.log";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private CloudAdminAudit() {
    }

    public static void log(WebGame plugin, String action, String clientId, String clientName,
                           String sessionId, String result, String reason) {
        if (plugin == null) {
            return;
        }
        String line = FMT.format(new Date())
                + "\t" + "-"
                + "\t" + clean(action)
                + "\t" + clean(clientId)
                + "\t" + clean(clientName)
                + "\t" + clean(sessionId)
                + "\t" + clean(result)
                + "\t" + clean(reason)
                + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Path dir = plugin.getDataFolder().toPath();
                Files.createDirectories(dir);
                Path file = dir.resolve(FILE);
                Files.write(file, line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "写管理审计失败: " + e.getMessage());
            }
        }
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        return v.length() > 200 ? v.substring(0, 200) : v;
    }
}
