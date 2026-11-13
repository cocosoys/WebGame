package com.github.cocosoys.mc.webgame.web.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云游戏会话注册表（预留心跳接入）。
 *
 * <p>云游戏客户端只是网页/URL，不建立 WebSocket，因此会话由心跳接口维护：
 * 页面按固定间隔上报 {@code clientId}（可选 sessionId / 玩家名 / UUID / IP），
 * 超过 {@link #HEARTBEAT_TTL_MILLIS} 未上报即视为结束。</p>
 */
public class CloudSessionRegistry {

    /** 心跳有效期：30 秒（建议页面每 10 秒上报一次）。 */
    public static final long HEARTBEAT_TTL_MILLIS = 30_000L;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 上报一次心跳；返回刷新后的会话。 */
    public Session heartbeat(String sessionId, String clientId, String clientName,
                             String username, String uuid, String ip) {
        prune();
        String key = sessionId == null || sessionId.trim().isEmpty()
                ? defaultKey(clientId, username, ip)
                : sessionId.trim();
        long now = System.currentTimeMillis();
        Session s = sessions.computeIfAbsent(key, k -> {
            Session created = new Session();
            created.sessionId = k;
            created.connectedAt = now;
            return created;
        });
        s.clientId = clientId == null ? "" : clientId;
        s.clientName = clientName == null ? "" : clientName;
        s.username = username == null ? "" : username;
        s.uuid = uuid == null ? "" : uuid;
        s.ip = ip == null ? "" : ip;
        s.lastSeen = now;
        return s;
    }

    /** 当前有效会话（自动清理过期项）。 */
    public List<Session> snapshot() {
        prune();
        return new ArrayList<>(sessions.values());
    }

    /** 结束一个会话（ERP 踢出）。 */
    public boolean remove(String sessionId) {
        prune();
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    public int size() {
        prune();
        return sessions.size();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().lastSeen > HEARTBEAT_TTL_MILLIS);
    }

    private static String defaultKey(String clientId, String username, String ip) {
        return "cloud:" + (clientId == null ? "" : clientId)
                + "|" + (username == null ? "" : username)
                + "|" + (ip == null ? "" : ip);
    }

    /** 一条云游戏会话。 */
    public static class Session {
        private String sessionId = "";
        private String clientId = "";
        private String clientName = "";
        private String username = "";
        private String uuid = "";
        private String ip = "";
        private long connectedAt;
        private long lastSeen;

        public String getSessionId() {
            return sessionId;
        }

        public String getClientId() {
            return clientId;
        }

        public String getClientName() {
            return clientName;
        }

        public String getUsername() {
            return username;
        }

        public String getUuid() {
            return uuid;
        }

        public String getIp() {
            return ip;
        }

        public long getConnectedAt() {
            return connectedAt;
        }

        public long getLastSeen() {
            return lastSeen;
        }
    }
}
