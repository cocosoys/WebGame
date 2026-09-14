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
 *   <li>路径 {@code /kasm/{token}/{port}/...} → 接管 KasmVNC 反代
 *       （HTTP 资源转发 + WS 升级桥接）；</li>
 *   <li>其他普通 HTTP 请求 → 放行但<b>保留自身</b>（keep-alive 连接上的后续
 *       /kasm/ 请求仍能被接管——否则 iframe 复用 cloud 页面连接时会直达
 *       SOYS 路由而 404）；</li>
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
            }
        }
        if (path != null && path.startsWith("/kasm/") && gateway.getCloudManager() != null) {
            takeOverKasm(ctx);
            return;
        }
        if (!isGet || !upgradeWs || path == null) {
            passKeep(ctx);
            return;
        }
        if (path.equals(gateway.getWsPath())) {
            takeOverEagler(ctx);
            return;
        }
        if (gateway.matchesCloudPath(path)) {
            takeOverCloud(ctx);
            return;
        }
        passKeep(ctx);
    }

    /** 接管 EaglerX 通道：插入 WS 帧处理器、移除自身、重放缓冲。 */
    private void takeOverEagler(ChannelHandlerContext ctx) {
        decided = true;
        ctx.pipeline().addAfter(ctx.name(), "webgame-ws-frame",
                new WsFrameHandler(gateway, gateway.getWsPath(), gateway.getLoopbackGroup()));
        replay(ctx);
    }

    /** 接管 Cloud 云游戏通道：插入 cloud 帧处理器、移除自身、重放缓冲。 */
    private void takeOverCloud(ChannelHandlerContext ctx) {
        decided = true;
        ctx.pipeline().addAfter(ctx.name(), "webgame-cloud-frame",
                new com.github.cocosoys.mc.webgame.web.cloud.CloudFrameHandler(
                        gateway.getCloudManager(), gateway.getCloudConfig()));
        replay(ctx);
    }

    /** 接管 KasmVNC 反代通道：插入 kasm 代理处理器（HTTP 资源转发 + WS 升级桥接）。 */
    private void takeOverKasm(ChannelHandlerContext ctx) {
        decided = true;
        ctx.pipeline().addAfter(ctx.name(), "webgame-kasm-proxy",
                new com.github.cocosoys.mc.webgame.web.kasm.KasmProxyHandler(
                        gateway.getCloudManager()));
        replay(ctx);
    }

    private void replay(ChannelHandlerContext ctx) {
        ctx.pipeline().remove(this);
        ByteBuf replay = buffer;
        buffer = null;
        ctx.pipeline().fireChannelRead(replay);
    }

    /**
     * 放行 HTTP 请求但保留自身：keep-alive 连接上的后续 /kasm/ 请求仍可被接管。
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
