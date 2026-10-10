package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.control.InstanceHandle;

/**
 * 云游戏执行后端抽象（ADR-12：远程执行面仅预留接口，暂不实现）。
 *
 * <p>把"实例 spawn → 采集编码 → 输入注入 → 状态上报"整条执行面链路抽象为可切换后端，
 * 使云游戏执行面可在以下两种形态间切换（config: cloud.executor）：</p>
 * <ul>
 *   <li><b>local</b>（默认）：插件内直接 spawn——GameInstance（manifest 驱动）+ EncoderProcess
 *       （镜像 ffmpeg/gdigrab）+ AgentInputInjector（javaagent）+ IsolationPolicy 加固。
 *       对应 S2-S4 单机路线。</li>
 *   <li><b>remote</b>（S5 预留，暂不实现）：执行面部署在另一台跨局域网服务器（CloudAgent 主动连
 *       主服 A），管控通道 TLS + Token 认证；帧经 A 中转下行浏览器（v2 预留直连）。
 *       对应架构文档 §12 远程执行面。</li>
 * </ul>
 *
 * <p>当前代码中 <b>仅定义接口与 config 预留</b>（ADR-12），不实现 remote 分支；
 * local 分支由 S2 阶段接入 CloudSessionManager 会话生命周期。</p>
 */
public interface ExecutorBackend {

    /**
     * 启动一个游戏实例（进程级多实例：独立实例目录 + 基准盘共享）。
     *
     * @param session 云会话（含用户名/设备/URL 参数）
     * @param img     已解析的镜像（manifest）
     * @param params  会话级覆盖参数（cores/xmx/分辨率/fps 等）
     * @return 实例句柄（local = GameInstance 引用；remote = 执行面实例 id）
     */
    InstanceHandle spawn(CloudSession session, CloudImage img, SpawnParams params);

    /** 停止实例并回收资源（进程树 + 实例目录）。 */
    void kill(String instanceId);

    /** 转发浏览器上行键鼠事件到执行面（local = javaagent 注入；remote = 管控通道转发）。 */
    void forwardInput(String instanceId, byte[] eventsBody);

    /** 请求执行面上报容量/镜像清单/会话数（local = 本机直读；remote = STATUS 帧）。 */
    void reportStatus();

    /** 关闭全部实例并回收资源（插件卸载 / 会话管理 uninstall 时调用）。 */
    default void closeAll() {
    }

    /** 后端类型标记（local | remote），供 UI/日志/调度使用。 */
    String type();

    /** 会话级启动参数（镜像默认值可被 URL 参数覆盖）。 */
    final class SpawnParams {
        public final String username;
        public final String xmx;
        public final int width;
        public final int height;
        public final int fps;

        public SpawnParams(String username, String xmx, int width, int height, int fps) {
            this.username = username;
            this.xmx = xmx;
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }
}
