package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * MC 1.12.2 回环客户端：连接 127.0.0.1:{server-port}，完成握手(0x00)+LoginStart(0x00)，
 * 之后 WS 二进制帧（[pktId varint][payload]）⇄ MC 包（[len varint][pktId varint][payload]）双向透传。
 *
 * <p>支持登录阶段 Set Compression（0x03）与 PLAY 阶段压缩包。登录阶段包 ID（340）：
 * C→S LoginStart=0x00；S→C Disconnect=0x00 / EncryptionRequest=0x01 / LoginSuccess=0x02 / SetCompression=0x03。</p>
 */
public final class McLoopbackClient {

    private static final int PROTOCOL_VERSION = 340; // MC 1.12.2

    public interface Listener {
        /** 登录成功（LoginSuccess 收到），进入 PLAY。 */
        void onPlay();

        /** 服务器拒绝/断线（Disconnect 或连接失败）。reason 可为 null。 */
        void onDisconnect(String reason);

        /** 服务器要求正版验证（EncryptionRequest，offline-mode=false 场景）。 */
        void onEncryptionRequired();

        /** 回环收到的 MC 包（已去 len 前缀，含 pktId）。由调用方转 WS 帧。 */
        void onPacket(byte[] packet);
    }

    private static final AtomicInteger GROUP_SEQ = new AtomicInteger();
    private final NioEventLoopGroup group;
    private final String host;
    private final int port;
    private final String username;
    private final Listener listener;

    private volatile Channel channel;
    private volatile int compressionThreshold = -1;
    private volatile boolean loginDone = false;

    public McLoopbackClient(NioEventLoopGroup group, String host, int port, String username, Listener listener) {
        this.group = group;
        this.host = host;
        this.port = port;
        this.username = username;
        this.listener = listener;
    }

    public void connect() {
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("wg-mc-frame", new McPacketDecoder());
                        ch.pipeline().addLast("wg-mc-handler", new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                sendHandshake(ctx);
                                sendLoginStart(ctx);
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                handleServerPacket(ctx, msg);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                System.out.println("[MCL] loopback INACTIVE loginDone=" + loginDone);
                                if (!loginDone) {
                                    listener.onDisconnect(null);
                                }
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                System.out.println("[MCL] loopback EXC loginDone=" + loginDone + " " + cause);
                                if (!loginDone) {
                                    listener.onDisconnect(cause.getMessage());
                                }
                                ctx.close();
                            }
                        });
                    }
                });
        ChannelFuture f = b.connect(host, port);
        f.addListener(fut -> {
            if (!fut.isSuccess()) {
                listener.onDisconnect(fut.cause() == null ? "connect failed" : String.valueOf(fut.cause().getMessage()));
            }
        });
        channel = f.channel();
    }

    // ===== 包切分解码器（处理 TCP 半包/多包合并） =====

    /** 按 [len varint][data] 切分 MC 包；半包等待，多包逐个输出。输出帧保留 [len varint][data] 完整结构。 */
    static final class McPacketDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            while (in.isReadable()) {
                int start = in.readerIndex();
                in.markReaderIndex();
                int len = readVarInt(in);
                if (len < 0) {
                    in.resetReaderIndex();
                    return;
                }
                if (len > 2 * 1024 * 1024) {
                    ctx.close();
                    return;
                }
                if (in.readableBytes() < len) {
                    in.resetReaderIndex();
                    return;
                }
                int varintLen = in.readerIndex() - start;
                in.resetReaderIndex();
                out.add(in.readRetainedSlice(varintLen + len));
            }
        }
    }

    // ===== 登录阶段 =====

    private void sendHandshake(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer();
        writeVarInt(buf, 0x00); // handshake
        writeVarInt(buf, PROTOCOL_VERSION);
        writeString(buf, host);
        buf.writeShort(port);
        writeVarInt(buf, 2); // next = login
        sendFramed(ctx, buf);
    }

    private void sendLoginStart(ChannelHandlerContext ctx) {
        ByteBuf buf = ctx.alloc().buffer();
        writeVarInt(buf, 0x00); // LoginStart
        writeString(buf, username);
        sendFramed(ctx, buf);
    }

    private void handleServerPacket(ChannelHandlerContext ctx, ByteBuf in) {
        // in = [len varint][content]
        int len = readVarInt(in);
        if (len < 0 || len > 2 * 1024 * 1024) {
            ctx.close();
            return;
        }
        if (in.readableBytes() < len) {
            ctx.close();
            return;
        }
        // 解压缩：若已启用压缩，content = [dataLen varint][data]
        ByteBuf content = in.readSlice(len);
        byte[] packet;
        if (compressionThreshold >= 0) {
            int dataLen = readVarInt(content);
            if (dataLen == 0) {
                packet = new byte[content.readableBytes()];
                content.readBytes(packet);
            } else {
                // dataLen = 解压后长度；剩余字节 = zlib 压缩数据
                byte[] compressed = new byte[content.readableBytes()];
                content.readBytes(compressed);
                packet = inflate(compressed);
                if (packet == null) {
                    ctx.close();
                    return;
                }
            }
        } else {
            packet = new byte[content.readableBytes()];
            content.readBytes(packet);
        }

        if (!loginDone) {
            handleLoginPacket(ctx, packet);
            return;
        }
        if (playPacketLog++ < 40) {
            int pid = packet.length > 0 ? packet[0] & 0xFF : -1;
            System.out.println("[MCL] S> pktId=" + pid + " len=" + packet.length + " (raw first="
                    + (packet.length > 0 ? (packet[0] & 0xFF) : -1) + ")");
        }
        listener.onPacket(packet);
    }

    private int playPacketLog;
    private int playPacketLogC;

    private void handleLoginPacket(ChannelHandlerContext ctx, byte[] packet) {
        if (packet.length < 1) {
            return;
        }
        ByteBuf p = Unpooled.wrappedBuffer(packet);
        int pktId = readVarInt(p);
        System.out.println("[MCL] LOGIN pkt=0x" + Integer.toHexString(pktId) + " len=" + packet.length);
        switch (pktId) {
            case 0x00: { // Disconnect
                String reason = p.isReadable() ? readString(p) : "Disconnected";
                loginDone = true;
                listener.onDisconnect(reason);
                ctx.close();
                break;
            }
            case 0x01: { // EncryptionRequest → 服务器要求正版（offline-mode=false）
                loginDone = true;
                listener.onEncryptionRequired();
                ctx.close();
                break;
            }
            case 0x02: { // LoginSuccess
                loginDone = true;
                listener.onPlay();
                break;
            }
            case 0x03: { // Set Compression
                if (p.isReadable()) {
                    compressionThreshold = readVarInt(p);
                }
                break;
            }
            default:
                // 未知登录包：进入 PLAY（部分服务器可能不发 LoginSuccess 顺序怪异）
                loginDone = true;
                listener.onPlay();
                listener.onPacket(packet);
                break;
        }
        p.release();
    }

    // ===== PLAY 透传 =====

    /** 客户端 WS 帧 → 回环（加 len 前缀，必要时压缩）。 */
    public void sendToServer(byte[] packet) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            return;
        }
        if (loginDone && playPacketLogC++ < 40) {
            int pid = packet.length > 0 ? packet[0] & 0xFF : -1;
            System.out.println("[MCL] C> pktId=" + pid + " len=" + packet.length);
        }
        ByteBuf content = Unpooled.wrappedBuffer(packet);
        ByteBuf framed = ch.alloc().buffer();
        try {
            if (compressionThreshold >= 0) {
                if (packet.length >= compressionThreshold) {
                    byte[] compressed = deflate(packet);
                    if (compressed != null && compressed.length < packet.length) {
                        writeVarInt(framed, packet.length); // dataLen = 解压后长度
                        framed.writeBytes(compressed);
                    } else {
                        writeVarInt(framed, 0);
                        framed.writeBytes(packet);
                    }
                } else {
                    writeVarInt(framed, 0);
                    framed.writeBytes(packet);
                }
            } else {
                framed.writeBytes(packet);
            }
            int payloadLen = framed.readableBytes();
            ByteBuf out = ch.alloc().buffer();
            writeVarInt(out, payloadLen);
            out.writeBytes(framed);
            ch.writeAndFlush(out);
        } finally {
            framed.release();
            content.release();
        }
    }

    public void close() {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.close();
        }
    }

    public boolean isActive() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    // ===== MC 编解码工具 =====

    private static void sendFramed(ChannelHandlerContext ctx, ByteBuf content) {
        ByteBuf out = ctx.alloc().buffer();
        writeVarInt(out, content.readableBytes());
        out.writeBytes(content);
        content.release();
        ctx.writeAndFlush(out);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > buf.readableBytes()) {
            return "";
        }
        CharSequence cs = buf.readCharSequence(len, StandardCharsets.UTF_8);
        return cs == null ? "" : cs.toString();
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    static int readVarInt(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            if (!buf.isReadable()) {
                return -1;
            }
            int b = buf.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        return -1;
    }

    private static byte[] deflate(byte[] data) {
        Deflater def = new Deflater();
        try {
            def.setInput(data);
            def.finish();
            ByteBuf out = Unpooled.buffer(data.length);
            try {
                byte[] tmp = new byte[8192];
                while (!def.finished()) {
                    int n = def.deflate(tmp);
                    if (n == 0) {
                        break;
                    }
                    out.writeBytes(tmp, 0, n);
                }
                byte[] res = new byte[out.readableBytes()];
                out.readBytes(res);
                return res;
            } finally {
                out.release();
            }
        } finally {
            def.end();
        }
    }

    private static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteBuf out = Unpooled.buffer(data.length * 2);
            try {
                byte[] tmp = new byte[8192];
                while (!inf.finished()) {
                    int n = inf.inflate(tmp);
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) {
                            break;
                        }
                    }
                    out.writeBytes(tmp, 0, n);
                }
                byte[] res = new byte[out.readableBytes()];
                out.readBytes(res);
                return res;
            } catch (Exception e) {
                return null;
            } finally {
                out.release();
            }
        } finally {
            inf.end();
        }
    }
}
