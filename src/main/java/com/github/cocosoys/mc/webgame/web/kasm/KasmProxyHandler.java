package com.github.cocosoys.mc.webgame.web.kasm;

import com.github.cocosoys.mc.webgame.web.cloud.CloudSessionManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * KasmVNC 反代处理器（S2）：被 {@link com.github.cocosoys.mc.webgame.web.ws.WsSnifferHandler}
 * 分流进来的 {@code /kasm/{token}/{kasmPort}/...} 连接。
 *
 * <p>同类端口 25574 上完成两件事：</p>
 * <ol>
 *   <li>普通 HTTP GET → 用 {@link HttpURLConnection} 转发到执行面 KasmVNC
 *       （vnc.html 及 /webapp/... 静态资源）；</li>
 *   <li>{@code Upgrade: websocket} → Netty 客户端连执行面 websockify，
 *       101 后 RFC6455 帧字节级双向透传（不做编解码，浏览器 masked 帧原样可达）。</li>
 * </ol>
 *
 * <p>安全：token 必须是活跃云游戏会话 id，且 kasmPort 必须等于该会话实例的上报端口，
 * 防 SSRF 与越权访问其它端口。</p>
 */
public final class KasmProxyHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOG = Logger.getLogger(KasmProxyHandler.class.getName());

    private final CloudSessionManager manager;

    private final ByteBuf frameBuffer = Unpooled.buffer(16384);
    private boolean httpParsed = false;

    private String token;
    private int kasmPort = -1;
    private String restPath = "";
    private boolean upgradeWs = false;
    private String wsKey;
    private String wsProtocol = "binary";
    private String origin;

    /** WS 桥接目标通道（升级成功后建立）。 */
    private volatile Channel kasmChannel;
    private volatile ChannelHandlerContext browserCtx;
    private volatile boolean bridged = false;

    public KasmProxyHandler(CloudSessionManager manager) {
        this.manager = manager;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (bridged) {
            // 升级完成后：浏览器字节原样转发执行面 websockify（RFC6455 帧级透传）
            Channel kc = kasmChannel;
            if (kc != null && kc.isActive()) {
                kc.writeAndFlush(msg.retain());
            } else {
                ctx.close();
            }
            return;
        }
        frameBuffer.writeBytes(msg);
        processBuffer(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Channel kc = kasmChannel;
        if (kc != null) {
            kc.close();
        }
        releaseBuffer();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.FINE, "kasm proxy error: " + cause);
        Channel kc = kasmChannel;
        if (kc != null) {
            kc.close();
        }
        ctx.close();
    }

    private void releaseBuffer() {
        if (frameBuffer.refCnt() > 0) {
            ReferenceCountUtil.safeRelease(frameBuffer);
        }
    }

    private void processBuffer(ChannelHandlerContext ctx) {
        while (frameBuffer.isReadable()) {
            if (!httpParsed) {
                if (!parseAndDispatch(ctx)) {
                    return;
                }
                httpParsed = true;
                continue;
            }
            // HTTP 头已消费：剩余字节（仅升级场景有）应透传
            if (kasmChannel != null && kasmChannel.isActive()) {
                bridged = true;
                ByteBuf rest = frameBuffer.copy();
                frameBuffer.clear();
                if (rest.isReadable()) {
                    kasmChannel.writeAndFlush(rest);
                } else {
                    rest.release();
                }
            } else {
                return;
            }
        }
    }

    // ===== HTTP 解析与分发 =====

    /** 解析首包 HTTP 头，返回 true 表示已处理（无需继续读缓冲）。 */
    private boolean parseAndDispatch(ChannelHandlerContext ctx) {
        String head = frameBuffer.toString(StandardCharsets.US_ASCII);
        int headerEnd = head.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            if (frameBuffer.readableBytes() > 65536) {
                reject(ctx, "Bad Request");
            }
            return false;
        }
        String[] lines = head.split("\r\n");
        if (lines.length == 0 || !(lines[0].startsWith("GET ") || lines[0].startsWith("POST "))) {
            reject(ctx, "Bad Request");
            return true;
        }
        String reqLine = lines[0];
        int sp = reqLine.indexOf(' ');
        int sp2 = sp > 0 ? reqLine.indexOf(' ', sp + 1) : -1;
        String url = sp2 > 0 ? reqLine.substring(sp + 1, sp2) : reqLine.substring(sp + 1);
        int q = url.indexOf('?');
        String pathOnly = q >= 0 ? url.substring(0, q) : url;
        String query = q >= 0 ? url.substring(q) : "";

        // /kasm/{token}/{kasmPort}/{rest...}
        String[] seg = pathOnly.split("/");
        if (seg.length < 4 || !"kasm".equals(seg[1])) {
            reject(ctx, "Not Found");
            return true;
        }
        token = seg[2];
        try {
            kasmPort = Integer.parseInt(seg[3]);
        } catch (NumberFormatException e) {
            reject(ctx, "Not Found");
            return true;
        }
        StringBuilder rest = new StringBuilder();
        for (int i = 4; i < seg.length; i++) {
            if (rest.length() > 0) {
                rest.append('/');
            }
            rest.append(seg[i]);
        }
        restPath = "/" + (rest.length() == 0 ? "" : rest.toString());
        if (!query.isEmpty()) {
            restPath += query;
        }

        if (!manager.authorizeKasm(token, kasmPort)) {
            reject(ctx, "Forbidden");
            return true;
        }

        // 头字段：upgrade / key
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String name = line.substring(0, c).trim().toLowerCase();
            String value = line.substring(c + 1).trim();
            if ("upgrade".equals(name) && value.toLowerCase().contains("websocket")) {
                upgradeWs = true;
            }
            if ("sec-websocket-key".equals(name)) {
                wsKey = value;
            }
            if ("sec-websocket-protocol".equals(name)) {
                wsProtocol = value;
            }
            if ("origin".equals(name)) {
                origin = value;
            }
        }

        frameBuffer.skipBytes(headerEnd + 4);
        if (upgradeWs) {
            wsBridge(ctx);
        } else {
            httpForward(ctx);
        }
        return true;
    }

    // ===== 普通 HTTP 转发 =====

    private void httpForward(ChannelHandlerContext ctx) {
        String url = "http://127.0.0.1:" + kasmPort + restPath;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            String contentType = conn.getContentType();
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            if (contentType != null) {
                head.append("Content-Type: ").append(contentType).append("\r\n");
            }
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            ctx.write(Unpooled.copiedBuffer(head.toString(), StandardCharsets.US_ASCII));
            // 关键：writeAndFlush 完成后才能 close——否则大文件（如 ui.js 548KB）
            // 在异步写出过程中被 FIN 截断，浏览器报 ERR_CONTENT_LENGTH_MISMATCH
            ChannelFuture cf = ctx.writeAndFlush(Unpooled.wrappedBuffer(body));
            cf.addListener(f -> ctx.close());
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "kasm http forward failed " + url + ": " + t);
            reject(ctx, "Bad Gateway");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ===== WS 升级桥接（字节级透传） =====

    private void wsBridge(ChannelHandlerContext ctx) {
        browserCtx = ctx;
        Bootstrap b = new Bootstrap();
        b.group(ctx.channel().eventLoop());
        b.channel(NioSocketChannel.class);
        b.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext kctx, ByteBuf in) {
                        // 执行面回包（首个为 101，随后为 RFB 数据）→ 原样转发浏览器
                        ChannelHandlerContext bc = browserCtx;
                        if (bc != null && bc.channel().isActive()) {
                            bc.writeAndFlush(in.retain());
                        } else {
                            kctx.close();
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext kctx) {
                        ChannelHandlerContext bc = browserCtx;
                        if (bc != null) {
                            bc.close();
                        }
                        kctx.fireChannelInactive();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext kctx, Throwable cause) {
                        ChannelHandlerContext bc = browserCtx;
                        if (bc != null) {
                            bc.close();
                        }
                        kctx.close();
                    }
                });
            }
        });
        ChannelFuture f = b.connect("127.0.0.1", kasmPort);
        f.addListener(future -> {
            if (!future.isSuccess()) {
                LOG.log(Level.WARNING, "kasm ws connect failed port=" + kasmPort, future.cause());
                reject(ctx, "Bad Gateway");
                return;
            }
            kasmChannel = ((ChannelFuture) future).channel();
            // 改造请求行指向 websockify，头字段原样转发（保留 Sec-WebSocket-Key）
            StringBuilder req = new StringBuilder();
            req.append("GET /websockify HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(kasmPort).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            if (wsKey != null && !wsKey.isEmpty()) {
                req.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
            }
            req.append("Sec-WebSocket-Version: 13\r\n");
            // KasmVNC websockify 强制要求这两个头，缺失返回 404：
            //  * Sec-WebSocket-Protocol: binary（前端 RFB 协议协商）
            //  * Origin（反代场景必须存在；浏览器页面源即合法值）
            if (wsProtocol != null && !wsProtocol.isEmpty()) {
                req.append("Sec-WebSocket-Protocol: ").append(wsProtocol).append("\r\n");
            }
            if (origin != null && !origin.isEmpty()) {
                req.append("Origin: ").append(origin).append("\r\n");
            }
            req.append("\r\n");
            kasmChannel.writeAndFlush(Unpooled.copiedBuffer(req.toString(), StandardCharsets.US_ASCII));
            bridged = true;
            // 缓冲中可能还有浏览器在升级后立即发送的帧
            if (frameBuffer.isReadable()) {
                ByteBuf rest = frameBuffer.copy();
                frameBuffer.clear();
                if (rest.isReadable()) {
                    kasmChannel.writeAndFlush(rest);
                } else {
                    rest.release();
                }
            }
            // 关闭联动：执行面通道断开 → 关闭浏览器连接
            kasmChannel.closeFuture().addListener(ff -> {
                ChannelHandlerContext bc = browserCtx;
                if (bc != null && bc.channel().isActive()) {
                    bc.close();
                }
            });
        });
    }

    // ===== 拒绝 =====

    private void reject(ChannelHandlerContext ctx, String reason) {
        String code = "400 Bad Request";
        if ("Forbidden".equals(reason)) {
            code = "403 Forbidden";
        } else if ("Not Found".equals(reason)) {
            code = "404 Not Found";
        } else if ("Bad Gateway".equals(reason)) {
            code = "502 Bad Gateway";
        }
        try {
            String body = "WebGame Kasm Proxy: " + reason;
            ctx.writeAndFlush(Unpooled.copiedBuffer(
                    "HTTP/1.1 " + code + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                            + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                            + "\r\nConnection: close\r\n\r\n" + body,
                    StandardCharsets.UTF_8));
        } finally {
            ctx.close();
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            default: return "Status";
        }
    }
}
