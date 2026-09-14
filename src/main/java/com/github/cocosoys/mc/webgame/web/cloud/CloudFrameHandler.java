package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.web.ws.EaglerXProtocol;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cloud 通道 WS 帧处理器：接管已判定为 {@code Upgrade: websocket} 且路径为
 * {@code /cloud} 的连接（由 {@link com.github.cocosoys.mc.webgame.web.ws.WsSnifferHandler}
 * 分流进来）。
 *
 * <ol>
 *   <li>收到 Upgrade 请求后回 101；</li>
 *   <li>解码 RFC6455 帧（复用 {@link EaglerXProtocol} 编解码）；</li>
 *   <li>升级成功后创建 {@link CloudSession}（设备限流 + 同名检查），启动编码器；</li>
 *   <li>上行：PONG 更新心跳、INPUT 解析后交给 {@link InputInjector}；</li>
 *   <li>下行：编码器回调（配置帧/视频帧）+ 心跳调度（{@link CloudSessionManager} tick）。</li>
 * </ol>
 */
public final class CloudFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOG = Logger.getLogger(CloudFrameHandler.class.getName());

    private final CloudSessionManager manager;
    private final WebGameConfig config;

    private final ByteBuf frameBuffer = Unpooled.buffer(8192);
    private boolean httpParsed = false;

    private String username = "CloudPlayer";
    private String deviceIp = "unknown";
    private volatile CloudSession session;

    public CloudFrameHandler(CloudSessionManager manager, WebGameConfig config) {
        this.manager = manager;
        this.config = config;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        try {
            if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
                deviceIp = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        frameBuffer.writeBytes(msg);
        processBuffer(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        plugin().getLogger().info("Cloud 连接断开 user=" + username + " session="
                + (session == null ? "none" : session.getId()));
        // 重连机制：连接断开仅悬挂会话（实例保留，宽限期内可凭 sessionId 恢复），
        // 而非立即销毁——F5 刷新/短时掉线后同一用户可直接恢复同一实例。
        CloudSession s = session;
        if (s != null && !s.isClosed()) {
            s.detachIfMine(ctx);
        }
        session = null;
        if (frameBuffer.refCnt() > 0) {
            ReferenceCountUtil.safeRelease(frameBuffer);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "cloud frame handler error: " + cause, cause);
        CloudSession s = session;
        if (s != null && !s.isClosed()) {
            s.detachIfMine(ctx); // 连接异常：悬挂保留实例，宽限期内可恢复
        }
        session = null;
        ctx.close();
    }

    private org.bukkit.plugin.java.JavaPlugin plugin() {
        return manager.plugin();
    }

    private void processBuffer(ChannelHandlerContext ctx) {
        while (frameBuffer.isReadable()) {
            if (!httpParsed) {
                if (!parseAndRespondUpgrade(ctx)) {
                    return; // 等更多数据或已处理
                }
                httpParsed = true;
                removeMcReadTimeout(ctx);
                if (session == null) {
                    // 升级成功后立即建立会话（不依赖客户端首个上行帧：浏览器 onopen 后
                    // 不会主动发帧，若等首个 BINARY 则服务器永远不下发配置/心跳）
                    establishSession(ctx);
                }
                continue;
            }
            EaglerXProtocol.WsFrame frame = EaglerXProtocol.decodeFrame(frameBuffer);
            if (frame == null) {
                return; // 帧不完整
            }
            handleFrame(ctx, frame);
        }
    }

    // ===== HTTP 升级 =====

    private boolean parseAndRespondUpgrade(ChannelHandlerContext ctx) {
        String head = frameBuffer.toString(java.nio.charset.StandardCharsets.US_ASCII);
        int headerEnd = head.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            if (frameBuffer.readableBytes() > 16384) {
                ctx.close();
            }
            return false;
        }
        String key = null;
        String[] lines = head.split("\r\n");
        if (lines.length > 0 && !lines[0].startsWith("GET ")) {
            ctx.close();
            return false;
        }
        if (lines.length > 0) {
            // 缓存请求行 URL（含 query），供会话建立时解析 ?user=
            String reqLine = lines[0];
            int sp = reqLine.indexOf(' ');
            int sp2 = sp > 0 ? reqLine.indexOf(' ', sp + 1) : -1;
            if (sp2 > 0) {
                upgradeRequestUrl = reqLine.substring(sp + 1, sp2);
            } else if (sp > 0) {
                upgradeRequestUrl = reqLine.substring(sp + 1);
            }
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String name = line.substring(0, c).trim().toLowerCase();
            String value = line.substring(c + 1).trim();
            if ("sec-websocket-key".equals(name)) {
                key = value;
            }
        }
        if (key == null || key.isEmpty()) {
            ctx.close();
            return false;
        }
        frameBuffer.skipBytes(headerEnd + 4);
        ctx.writeAndFlush(EaglerXProtocol.build101Response(key));
        return true;
    }

    /**
     * 移除 craftbukkit 的 ReadTimeoutHandler（名称 "timeout"）：cloud 连接是纯浏览器
     * WS 长连接，craftbukkit 的 30s 读超时会导致周期性断线。
     */
    private void removeMcReadTimeout(ChannelHandlerContext ctx) {
        try {
            io.netty.channel.ChannelPipeline cp = ctx.pipeline();
            io.netty.channel.ChannelHandler h = cp.get("timeout");
            if (h != null) {
                cp.remove(h);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "remove timeout handler failed: " + t);
        }
    }

    // ===== WS 帧处理 =====

    private void handleFrame(ChannelHandlerContext ctx, EaglerXProtocol.WsFrame frame) {
        switch (frame.opcode) {
            case EaglerXProtocol.WS_BINARY:
                handleBinary(ctx, frame.payload);
                return;
            case EaglerXProtocol.WS_TEXT:
                return;
            case EaglerXProtocol.WS_PING:
                ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_PONG, frame.payload));
                return;
            case EaglerXProtocol.WS_PONG:
                return;
            case EaglerXProtocol.WS_CLOSE: {
                ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_CLOSE, frame.payload));
                // 主动退出（前端 quit 按钮 close(1000,"quit")）→ 立即销毁实例；
                // 刷新/断线（浏览器 close 1000/1001 等）→ 悬挂会话（宽限期内可恢复同一实例）
                boolean quit = isQuitClose(frame.payload);
                CloudSession cs = session;
                if (quit) {
                    cleanup(ctx);
                } else if (cs != null && !cs.isClosed()) {
                    cs.detachIfMine(ctx);
                }
                ctx.close();
                return;
            }
            default:
                ctx.close();
        }
    }

    private void handleBinary(ChannelHandlerContext ctx, byte[] payload) {
        CloudSession s = session;
        if (s == null) {
            // 兜底：极端时序下升级后首个二进制帧先于会话建立到达
            establishSession(ctx);
            s = session;
            if (s == null) {
                return;
            }
        }
        int type = CloudProtocol.peekType(payload);
        if (type < 0) {
            return;
        }
        switch (type) {
            case CloudProtocol.TYPE_PONG:
                s.touch();
                return;
            case CloudProtocol.TYPE_INPUT: {
                // v1（路线 C）：浏览器直连执行面 KasmVNC，输入不经插件转发，仅刷新活动时间。
                // S2（25574 反代）起：此处改为把输入事件经隧道转发到执行面实例。
                List<CloudProtocol.InputEvent> events = CloudProtocol.parseInput(payload);
                if (!events.isEmpty()) {
                    s.touch();
                }
                return;
            }
            default:
                return;
        }
    }

    /**
     * 会话建立：从 URL query 提取用户名与 sessionId（?user=xxx&sid=yyy）。
     * 有 sessionId 先尝试恢复（复用同一实例），失败或无可恢复会话则新建
     * （设备限流 + 同名检查 + 登记实例黑盒并下发 SPAWN）。
     */
    private void establishSession(ChannelHandlerContext ctx) {
        username = parseParam("user", "CloudPlayer");
        String sid = parseParam("sid", null);
        if (sid != null && !sid.isEmpty()) {
            CloudSession resumed = manager.resume(ctx, username, sid, deviceIp);
            if (resumed != null) {
                session = resumed;
                resumed.touch();
                return;
            }
        }
        CloudSession s = manager.create(ctx, username, deviceIp);
        if (s == null) {
            ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY,
                    CloudProtocol.buildError("本设备会话已达上限或用户名已在线，请更换用户名")));
            ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_CLOSE, new byte[0]));
            cleanup(ctx);
            ctx.close();
            return;
        }
        session = s;
        s.touch();
        // 实例就绪后由 CloudSessionManager.onInstanceReady 下发 KasmVNC 采集端点 URL，
        // 浏览器收到后跳转直连（v1）；S2 起由插件 25574 反代。
    }

    /** 从升级请求 URL query 提取参数（升级头已在 frameBuffer 中被消费，需先保存）。 */
    private String parseParam(String name, String fallback) {
        String u = upgradeRequestUrl;
        if (u != null) {
            int q = u.indexOf('?');
            if (q >= 0) {
                String query = u.substring(q + 1);
                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && name.equals(pair.substring(0, eq))) {
                        String v = pair.substring(eq + 1).trim();
                        if (!v.isEmpty()) {
                            return v;
                        }
                    }
                }
            }
        }
        return fallback;
    }

    /** 升级请求的原始 URL（由 parseAndRespondUpgrade 解析并缓存）。 */
    private String upgradeRequestUrl;

    /** WS CLOSE 帧是否为显式退出（reason="quit"）：关闭帧载荷 = 2 字节 code + UTF-8 reason。 */
    private static boolean isQuitClose(byte[] payload) {
        if (payload == null || payload.length <= 2) {
            return false;
        }
        try {
            String reason = new String(payload, 2, payload.length - 2,
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return "quit".equalsIgnoreCase(reason);
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 清理 =====

    private void cleanup(ChannelHandlerContext ctx) {
        CloudSession s = session;
        if (s != null) {
            session = null;
            s.close();
        }
        if (frameBuffer.refCnt() > 0) {
            ReferenceCountUtil.safeRelease(frameBuffer);
        }
    }
}
