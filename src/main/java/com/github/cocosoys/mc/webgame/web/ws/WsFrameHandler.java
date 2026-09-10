package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.ReferenceCountUtil;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * WebSocket 帧处理器：接管已判定为 {@code Upgrade: websocket} 的连接。
 *
 * <ol>
 *   <li>收到 Upgrade 请求后回 101（Sec-WebSocket-Accept）；</li>
 *   <li>解码 RFC6455 帧；ping→pong、close→回显并清理、binary→EaglerX 握手/PLAY 透传；</li>
 *   <li>EaglerX 握手状态机（CLIENT_VERSION→SERVER_VERSION→REQUEST_LOGIN→ALLOW_LOGIN→
 *       PROFILE_DATA→FINISH_LOGIN→SERVER_FINISH_LOGIN）；</li>
 *   <li>FINISH_LOGIN 后创建 {@link McLoopbackClient}，WS 二进制帧 ⇄ MC 包双向透传。</li>
 * </ol>
 */
public final class WsFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOG = Logger.getLogger(WsFrameHandler.class.getName());

    private final WsGateway gateway;
    private final String wsPath;
    private final NioEventLoopGroup loopbackGroup;

    private final ByteBuf frameBuffer = Unpooled.buffer(4096);
    private boolean httpParsed = false;
    private boolean upgraded = false;
    private boolean handshakeStarted = false;

    private int clientProtocolVersion = 3;
    private String username;
    private java.util.UUID offlineUuid;
    /** 来源 IP（设备维度，用于 max-connections-per-device 限制）。 */
    private volatile String deviceIp = "unknown";

    /** PLAY 阶段：true 后二进制帧直接透传回环。 */
    private volatile boolean playing = false;
    private volatile McLoopbackClient loopback;

    public WsFrameHandler(WsGateway gateway, String wsPath, NioEventLoopGroup loopbackGroup) {
        this.gateway = gateway;
        this.wsPath = wsPath;
        this.loopbackGroup = loopbackGroup;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        try {
            if (ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress) {
                deviceIp = ((java.net.InetSocketAddress) ctx.channel().remoteAddress())
                        .getAddress().getHostAddress();
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
        System.out.println("[WS] channelInactive playing=" + playing + " upgraded=" + upgraded);
        cleanup(ctx);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("[WS] exceptionCaught " + cause);
        LOG.log(Level.FINE, "ws frame handler error: " + cause);
        cleanup(ctx);
        ctx.close();
    }

    private void processBuffer(ChannelHandlerContext ctx) {
        while (frameBuffer.isReadable()) {
            if (!httpParsed) {
                if (!parseAndRespondUpgrade(ctx)) {
                    return; // 等更多数据或已处理
                }
                httpParsed = true;
                upgraded = true;
                removeMcReadTimeout(ctx);
                continue;
            }
            EaglerXProtocol.WsFrame frame = EaglerXProtocol.decodeFrame(frameBuffer);
            if (frame == null) {
                return; // 帧不完整，等待更多数据
            }
            handleFrame(ctx, frame);
        }
    }

    // ===== HTTP 升级 =====

    /** 解析 Upgrade 请求并回 101；请求不完整返回 false（等更多数据）。 */
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
        // 消费 HTTP 头（含 \r\n\r\n），剩余即 WS 帧
        frameBuffer.skipBytes(headerEnd + 4);
        ctx.writeAndFlush(EaglerXProtocol.build101Response(key));
        return true;
    }

    // ===== WS 帧处理 =====

    /**
     * WS 升级成功后，移除 craftbukkit 对该连接挂载的 ReadTimeoutHandler（名称 "timeout"）。
     * 原因：WS 连接的 MC 字节流由 WebGame 消费转发，craftbukkit 的 NetworkManager 在
     * 该连接上 30 秒读不到任何 MC 数据，ReadTimeoutHandler 会关闭连接（表现：约 30 秒
     * 整断线）。移除后该连接由 WebGame 全权管理。
     */
    private void removeMcReadTimeout(ChannelHandlerContext ctx) {
        try {
            io.netty.channel.ChannelPipeline cp = ctx.pipeline();
            io.netty.channel.ChannelHandler h = cp.get("timeout");
            if (h != null) {
                cp.remove(h);
                System.out.println("[WS] removed craftbukkit ReadTimeoutHandler (timeout)");
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "remove timeout handler failed: " + t);
        }
    }

    private void handleFrame(ChannelHandlerContext ctx, EaglerXProtocol.WsFrame frame) {
        switch (frame.opcode) {
            case EaglerXProtocol.WS_BINARY:
                if (!playing) {
                    handleHandshake(ctx, frame.payload);
                } else {
                    forwardToLoopback(frame.payload);
                }
                return;
            case EaglerXProtocol.WS_TEXT:
                // Eaglercraft 客户端极少发 text；忽略
                return;
            case EaglerXProtocol.WS_PING:
                ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_PONG, frame.payload));
                return;
            case EaglerXProtocol.WS_PONG:
                return;
            case EaglerXProtocol.WS_CLOSE:
                ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_CLOSE, frame.payload));
                cleanup(ctx);
                ctx.close();
                return;
            default:
                ctx.close();
        }
    }

    // ===== EaglerX 握手 =====

    private void handleHandshake(ChannelHandlerContext ctx, byte[] payload) {
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            if (!buf.isReadable()) {
                return;
            }
            int op = buf.getUnsignedByte(buf.readerIndex());
            switch (op) {
                case EaglerXProtocol.CLIENT_VERSION: {
                    if (handshakeStarted) {
                        return;
                    }
                    handshakeStarted = true;
                    buf.skipBytes(1);
                    EaglerXProtocol.ClientVersion cv = EaglerXProtocol.parseClientVersion(buf);
                    if (cv == null) {
                        sendErrorClose(ctx, "Invalid client version");
                        return;
                    }
                    int[] neg = EaglerXProtocol.negotiate(cv, gateway.isAllowV3(), gateway.isAllowV4(),
                            gateway.getMinMcProtocol(), gateway.getMaxMcProtocol());
                    gateway.getPlugin().getLogger().info("EaglerX CLIENT_VERSION brand=" + cv.brand
                            + " ver=" + cv.version + " authUser=" + cv.authUsername
                            + " prot=" + java.util.Arrays.toString(cv.protVersions));
                    if (neg == null) {
                        byte[] mm = EaglerXProtocol.buildVersionMismatch(gateway.isAllowV3(), gateway.isAllowV4(),
                                gateway.getMinMcProtocol(), gateway.getMaxMcProtocol(), "Unsupported Client Version");
                        ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, mm));
                        cleanup(ctx);
                        ctx.close();
                        return;
                    }
                    clientProtocolVersion = neg[0];
                    int gameProtocol = neg[1];
                    byte[] sv = EaglerXProtocol.buildServerVersion(clientProtocolVersion, gameProtocol,
                            gateway.getServerBrand(), gateway.getServerVersion());
                    ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, sv));
                    return;
                }
                case EaglerXProtocol.CLIENT_REQUEST_LOGIN: {
                    if (!handshakeStarted) {
                        return;
                    }
                    buf.skipBytes(1);
                    EaglerXProtocol.RequestLogin rl = EaglerXProtocol.parseRequestLogin(buf, clientProtocolVersion);
                    if (rl == null) {
                        sendErrorClose(ctx, "Invalid login request");
                        return;
                    }
                    String name = rl.username;
                    if (name == null || !name.matches("[A-Za-z0-9_]+") || name.length() < 3 || name.length() > 16) {
                        byte[] deny = EaglerXProtocol.buildDenyLogin(name == null ? "" : name,
                                "Invalid username");
                        ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, deny));
                        cleanup(ctx);
                        ctx.close();
                        return;
                    }
                    username = name;
                    offlineUuid = EaglerXProtocol.offlineUuid(username);
                    gateway.getPlugin().getLogger().info("EaglerX REQUEST_LOGIN user=" + username
                            + " server=" + rl.server + " -> offline uuid " + offlineUuid);
                    byte[] allow = EaglerXProtocol.buildAllowLogin(username, offlineUuid);
                    ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, allow));
                    return;
                }
                case EaglerXProtocol.CLIENT_PROFILE_DATA: {
                    if (!handshakeStarted || username == null) {
                        return;
                    }
                    buf.skipBytes(1);
                    EaglerXProtocol.skipProfileData(buf); // 忽略内容
                    return;
                }
                case EaglerXProtocol.CLIENT_FINISH_LOGIN: {
                    if (!handshakeStarted || username == null) {
                        return;
                    }
                    // 同一设备（按来源 IP）并发进服限制：超过 max-connections-per-device 拒绝
                    if (!gateway.tryAcquireDeviceSlot(deviceIp)) {
                        gateway.getPlugin().getLogger().info("EaglerX 设备限制拒绝 user=" + username
                                + " ip=" + deviceIp + " 超过每设备上限 " + gateway.getMaxConnectionsPerDevice());
                        sendErrorClose(ctx, "本设备同时进入游戏的浏览器已达上限");
                        return;
                    }
                    // 同名冲突：服务器已有同名在线玩家则拒绝，避免 craftbukkit 踢掉先登录者
                    try {
                        boolean nameOnline = false;
                        for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                            if (p.getName().equalsIgnoreCase(username)) {
                                nameOnline = true;
                                break;
                            }
                        }
                        if (nameOnline) {
                            gateway.releaseDeviceSlot(deviceIp);
                            gateway.getPlugin().getLogger().info("EaglerX 同名拒绝 user=" + username
                                    + " 该玩家已在线");
                            sendErrorClose(ctx, "该玩家已在线，请更换用户名");
                            return;
                        }
                    } catch (Throwable t) {
                        // Bukkit 查询失败不阻断进服
                    }
                    byte[] fin = new byte[]{EaglerXProtocol.SERVER_FINISH_LOGIN};
                    ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, fin));
                    playing = true;
                    startLoopback(ctx);
                    return;
                }
                default:
                    sendErrorClose(ctx, "Unknown packet #" + op);
            }
        } finally {
            buf.release();
        }
    }

    private void startLoopback(ChannelHandlerContext ctx) {
        String name = username;
        if (name == null || name.isEmpty()) {
            name = "WebPlayer";
        }
        final String finalName = name;
        McLoopbackClient mc = new McLoopbackClient(loopbackGroup,
                gateway.getLoopbackHost(), gateway.getLoopbackPort(), finalName,
                new McLoopbackClient.Listener() {
                    @Override
                    public void onPlay() {
                        // 登录成功，保持透传
                    }

                    @Override
                    public void onDisconnect(String reason) {
                        String msg = reason == null ? "Disconnected" : reason;
                        gateway.getPlugin().getLogger().info("回环登录被拒 " + finalName + ": " + msg);
                        sendWsClose(ctx, msg);
                        cleanup(ctx);
                        ctx.close();
                    }

                    @Override
                    public void onEncryptionRequired() {
                        gateway.getPlugin().getLogger().warning(
                                "回环服务器要求正版验证（online-mode=true），离线登录无法完成 " + finalName);
                        sendWsClose(ctx, "服务器要求正版登录（online-mode），请配置 offline-mode=true");
                        cleanup(ctx);
                        ctx.close();
                    }

                    @Override
                    public void onPacket(byte[] packet) {
                        ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, packet));
                    }
                });
        loopback = mc;
        mc.connect();
    }

    private void forwardToLoopback(byte[] payload) {
        McLoopbackClient mc = loopback;
        if (mc != null) {
            mc.sendToServer(payload);
        }
    }

    // ===== 工具 =====

    private void sendErrorClose(ChannelHandlerContext ctx, String msg) {
        byte[] err = new byte[2 + msg.length()];
        err[0] = (byte) EaglerXProtocol.SERVER_ERROR;
        err[1] = (byte) msg.length();
        System.arraycopy(msg.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, err, 2, msg.length());
        ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_BINARY, err));
        cleanup(ctx);
        ctx.close();
    }

    private void sendWsClose(ChannelHandlerContext ctx, String reason) {
        try {
            byte[] reasonBytes = reason.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] closePayload = new byte[2 + Math.min(reasonBytes.length, 123)];
            closePayload[0] = 0x03;
            closePayload[1] = (byte) 0xE8; // 1000 normal
            System.arraycopy(reasonBytes, 0, closePayload, 2, Math.min(reasonBytes.length, 123));
            ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_CLOSE, closePayload));
        } catch (Exception e) {
            ctx.writeAndFlush(EaglerXProtocol.encodeFrame(EaglerXProtocol.WS_CLOSE, new byte[0]));
        }
    }

    private void cleanup(ChannelHandlerContext ctx) {
        McLoopbackClient mc = loopback;
        if (mc != null) {
            mc.close();
            loopback = null;
        }
        if (frameBuffer.refCnt() > 0) {
            ReferenceCountUtil.safeRelease(frameBuffer);
        }
        if (playing && deviceIp != null) {
            gateway.releaseDeviceSlot(deviceIp);
        }
        gateway.removeSession(this);
    }
}
