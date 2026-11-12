package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

/**
 * 连接嗅探器：位于 SOYSHTTPOverMC 嗅探器之前（WsGateway addFirst）。
 *
 * <p>分类规则（每个 HTTP 请求裁决一次，而非仅首包）：</p>
 * <ul>
 *   <li>{@code GET ... Upgrade: websocket} 且路径为 WS 路径 → 接管：
 *       移除自身、插入 {@link WsFrameHandler}、重放缓冲；</li>
 *   <li>其他普通 HTTP 请求 → 放行但<b>保留自身</b>（keep-alive 连接上的后续
 *       升级请求仍能被接管）；</li>
 *   <li>非 HTTP 首字节（TLS / MC 流量）→ 一次性放行并移除自身。</li>
 * </ul>
 */
public final class WsSnifferHandler extends ChannelInboundHandlerAdapter {

    private final WsGateway gateway;
    private ByteBuf buffer;
    private boolean decided = false;

    public WsSnifferHandler(WsGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        buffer = ctx.alloc().buffer(1024);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (decided) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf in = (ByteBuf) msg;
        buffer.writeBytes(in);
        in.release();

        if (!decided) {
            decide(ctx);
        }
    }

    private void decide(ChannelHandlerContext ctx) {
        int len = buffer.readableBytes();
        if (len == 0) {
            return;
        }
        // 快速预判：必须是以 GET 开头的 HTTP 请求
        int first = buffer.getUnsignedByte(buffer.readerIndex());
        if (first != 'G' && first != 'P' && first != 'H' && first != 'D' && first != 'O' && first != 'C' && first != 'T') {
            releaseAndRemove(ctx);
            return;
        }
        // 尝试解析 HTTP 头
        String head = buffer.toString(StandardCharsets.US_ASCII);
        int headerEnd = head.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            if (len > 16384) {
                releaseAndRemove(ctx); // 太大且无完整头 → 非 WS
            }
            return; // 等待完整请求
        }
        String[] lines = head.split("\r\n");
        boolean isGet = lines.length > 0 && lines[0].startsWith("GET ");
        boolean upgradeWs = false;
        String path = null;
        String referer = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    int sp2 = line.indexOf(' ', sp + 1);
                    path = sp2 > 0 ? line.substring(sp + 1, sp2) : line.substring(sp + 1);
                    // 剥离 query string：浏览器 WS 地址可携带 ?user=xxx
                    int q = path.indexOf('?');
                    if (q >= 0) {
                        path = path.substring(0, q);
                    }
                }
                continue;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String name = line.substring(0, c).trim().toLowerCase();
            String value = line.substring(c + 1).trim();
            if ("upgrade".equals(name) && value.toLowerCase().contains("websocket")) {
                upgradeWs = true;
            } else if ("referer".equals(name)) {
                referer = value;
            }
        }
        if (!isGet || !upgradeWs || path == null) {
            passKeep(ctx);
            return;
        }
        String wsPath = gateway.getWsPath();
        boolean pathMatch = path.equals(wsPath) || path.startsWith(wsPath + "/");
        if (!pathMatch) {
            passKeep(ctx);
            return;
        }
        String clientId = resolveClientId(path, wsPath, referer);
        if (!gateway.isClientAllowed(clientId)) {
            // 版本不存在或已被管理员关闭：不接管，交给下游按普通 HTTP 处理
            passKeep(ctx);
            return;
        }
        takeOverEagler(ctx, clientId);
    }

    /**
     * 归属客户端版本：WS 子路径（{@code /eagler/<id>}）优先，其次 Referer 中的
     * {@code /eagler/<id>/}，都无法判定时归内置版本（兼容内置客户端的 {@code /eagler}）。
     */
    private String resolveClientId(String path, String wsPath, String referer) {
        if (path.length() > wsPath.length()) {
            String sub = path.substring(wsPath.length() + 1);
            if (!sub.isEmpty() && sub.indexOf('/') < 0) {
                return sub;
            }
        }
        String fromReferer = parseRefererClient(referer);
        if (fromReferer != null) {
            return fromReferer;
        }
        return gateway.getBuiltinClientId();
    }

    /** 从 Referer（…/eagler/&lt;id&gt;/…）中提取版本 id。 */
    private static String parseRefererClient(String referer) {
        if (referer == null || referer.isEmpty()) {
            return null;
        }
        int i = referer.indexOf("/eagler/");
        if (i < 0) {
            return null;
        }
        String rest = referer.substring(i + "/eagler/".length());
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        if (rest.isEmpty() || "index.html".equals(rest)) {
            return null;
        }
        return rest;
    }

    /** 接管 EaglerX 通道：插入 WS 帧处理器、移除自身、重放缓冲。 */
    private void takeOverEagler(ChannelHandlerContext ctx, String clientId) {
        decided = true;
        ctx.pipeline().addAfter(ctx.name(), "webgame-ws-frame",
                new WsFrameHandler(gateway, gateway.getWsPath(), gateway.getLoopbackGroup(), clientId));
        replay(ctx);
    }

    private void replay(ChannelHandlerContext ctx) {
        ctx.pipeline().remove(this);
        ByteBuf replay = buffer;
        buffer = null;
        ctx.pipeline().fireChannelRead(replay);
    }

    /**
     * 放行 HTTP 请求但保留自身：keep-alive 连接上的后续升级请求仍可被接管。
     * fire 全部缓冲（含请求体）后重新分配缓冲，继续裁决下一请求。
     */
    private void passKeep(ChannelHandlerContext ctx) {
        ByteBuf replay = buffer;
        buffer = ctx.alloc().buffer(1024);
        ctx.fireChannelRead(replay);
    }

    /** 彻底放行（TLS / MC 流量 / 无法识别的连接）：fire 缓冲 + 移除自身，不再裁决。 */
    private void releaseAndRemove(ChannelHandlerContext ctx) {
        decided = true;
        ByteBuf replay = buffer;
        buffer = null;
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(replay);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (buffer != null) {
            ReferenceCountUtil.safeRelease(buffer);
            buffer = null;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (decided) {
            ctx.fireExceptionCaught(cause);
        } else {
            ctx.close();
        }
    }
}
