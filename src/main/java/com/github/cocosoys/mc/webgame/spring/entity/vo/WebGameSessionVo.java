package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * WS 会话行（GET /erp/session/list）。
 */
public class WebGameSessionVo {

    /** 会话标识（Netty channel 短 ID）。 */
    private String sessionId;
    /** EaglerX 登录用户名（握手前为空）。 */
    private String username;
    /** 离线 UUID（握手后才有值）。 */
    private String uuid;
    /** 来源 IP。 */
    private String ip;
    /** 归属的客户端版本 id（/eagler/&lt;id&gt; 或 Referer 兜底）。 */
    private String clientId;
    /** EaglerX 协商后的客户端协议版本（3/4，未协商为 0）。 */
    private int protocolVersion;
    /** 是否已进入游戏（PLAY 阶段）。 */
    private boolean playing;
    /** 连接建立时间（epoch millis）。 */
    private long connectedAt;
    /** 已保持连接秒数。 */
    private long durationSeconds;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public long getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(long connectedAt) {
        this.connectedAt = connectedAt;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
}
