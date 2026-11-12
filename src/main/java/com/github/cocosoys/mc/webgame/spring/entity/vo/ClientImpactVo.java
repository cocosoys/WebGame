package com.github.cocosoys.mc.webgame.spring.entity.vo;

/**
 * 关闭某个客户端版本前的影响面统计（GET /erp/clients/impact）。
 */
public class ClientImpactVo {

    private String id;
    /** 使用该版本的全部 WS 会话数。 */
    private int total;
    /** 其中已进入游戏（PLAY 阶段）的会话数。 */
    private int playing;

    public ClientImpactVo() {
    }

    public ClientImpactVo(String id, int total, int playing) {
        this.id = id;
        this.total = total;
        this.playing = playing;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPlaying() {
        return playing;
    }

    public void setPlaying(int playing) {
        this.playing = playing;
    }
}
