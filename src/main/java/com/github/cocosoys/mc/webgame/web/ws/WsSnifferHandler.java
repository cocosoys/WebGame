package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;

/**
 * 子连接首包嗅探器：位于 SOYSHTTPOverMC 嗅探器之前（WsGateway addFirst）。
 *
 * <p>分类规则：</p>
 * <ul>
 *   <li>首包为 {@code GET ... Upgrade: websocket} 且路径为 WS 路径 → 接管：
 *       移除自身、插入 {@link WsFrameHandler}、重放缓冲；</li>
 *   <li>其他（普通 HTTP / TLS / MC 流量）→ 放行：fireChannelRead + 移除自身
 *       （下游 SOYSHTTPOverMC 嗅探器与 Spigot MC 解码器照常工作）。</li>
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
            passthrough(ctx);
            return;
        }
        // 尝试解析 HTTP 头
        String head = buffer.toString(StandardCharsets.US_ASCII);
        int headerEnd = head.indexOf("\r\n\r\n");
        if (headerEnd < 0) {
            if (len > 16384) {
                passthrough(ctx); // 太大且无完整头 → 非 WS
            }
            return; // 等待完整请求
        }
        String[] lines = head.split("\r\n");
        boolean isGet = lines.length > 0 && lines[0].startsWith("GET ");
        boolean upgradeWs = false;
        String path = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    int sp2 = line.indexOf(' ', sp + 1);
                    path = sp2 > 0 ? line.substring(sp + 1, sp2) : line.substring(sp + 1);
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
            }
        }
        if (!isGet || !upgradeWs || path == null || !path.equals(gateway.getWsPath())) {
            passthrough(ctx);
            return;
        }
        takeOver(ctx);
    }

    /** 接管：插入 WS 帧处理器、移除自身、重放缓冲。 */
    private void takeOver(ChannelHandlerContext ctx) {
        decided = true;
        ctx.pipeline().addAfter(ctx.name(), "webgame-ws-frame", new WsFrameHandler(gateway, gateway.getWsPath(), gateway.getLoopbackGroup()));
        ctx.pipeline().remove(this);
        ByteBuf replay = buffer;
        buffer = null;
        ctx.pipeline().fireChannelRead(replay);
    }

    /** 放行：fire 缓冲 + 移除自身。 */
    private void passthrough(ChannelHandlerContext ctx) {
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
