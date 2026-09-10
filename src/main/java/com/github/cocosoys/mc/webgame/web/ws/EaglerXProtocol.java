package com.github.cocosoys.mc.webgame.web.ws;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * EaglerX 握手协议（服务端侧）与 WebSocket RFC6455 帧编解码的纯静态工具。
 *
 * <p>握手帧格式已通过浏览器抓包与 EaglerXBungee 源码双向验证（见 docs/DESIGN-v1）。</p>
 */
public final class EaglerXProtocol {

    // ---------- EaglerX opcode ----------
    public static final int CLIENT_VERSION = 0x01;
    public static final int SERVER_VERSION = 0x02;
    public static final int VERSION_MISMATCH = 0x03;
    public static final int CLIENT_REQUEST_LOGIN = 0x04;
    public static final int SERVER_ALLOW_LOGIN = 0x05;
    public static final int SERVER_DENY_LOGIN = 0x06;
    public static final int CLIENT_PROFILE_DATA = 0x07;
    public static final int CLIENT_FINISH_LOGIN = 0x08;
    public static final int SERVER_FINISH_LOGIN = 0x09;
    public static final int SERVER_ERROR = 0xFF;

    // ---------- WebSocket opcode ----------
    public static final int WS_CONT = 0x0;
    public static final int WS_TEXT = 0x1;
    public static final int WS_BINARY = 0x2;
    public static final int WS_CLOSE = 0x8;
    public static final int WS_PING = 0x9;
    public static final int WS_PONG = 0xA;

    private EaglerXProtocol() {
    }

    // ================= WebSocket 101 升级 =================

    public static String websocketAccept(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(
                    (secWebSocketKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ByteBuf build101Response(String key) {
        String accept = websocketAccept(key);
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n"
                + "\r\n";
        return Unpooled.copiedBuffer(resp, StandardCharsets.US_ASCII);
    }

    // ================= RFC6455 帧编解码 =================

    /** 服务端→客户端：不掩码。opcode 支持 BINARY/TEXT/CLOSE/PING/PONG。 */
    public static ByteBuf encodeFrame(int opcode, byte[] payload) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(0x80 | (opcode & 0x0F));
        int len = payload == null ? 0 : payload.length;
        if (len < 126) {
            out.writeByte(len);
        } else if (len < 65536) {
            out.writeByte(126);
            out.writeShort(len);
        } else {
            out.writeByte(127);
            out.writeLong(len);
        }
        if (len > 0) {
            out.writeBytes(payload);
        }
        return out;
    }

    /** 从缓冲头部解析一帧；不完整返回 null（不消费）。 */
    public static WsFrame decodeFrame(ByteBuf buf) {
        if (buf.readableBytes() < 2) {
            return null;
        }
        int ri = buf.readerIndex();
        int b0 = buf.getUnsignedByte(ri);
        int b1 = buf.getUnsignedByte(ri + 1);
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        int headerLen = 2;
        if (len == 126) {
            if (buf.readableBytes() < 4) {
                return null;
            }
            len = buf.getUnsignedShort(ri + 2);
            headerLen = 4;
        } else if (len == 127) {
            if (buf.readableBytes() < 10) {
                return null;
            }
            len = buf.getLong(ri + 2);
            headerLen = 10;
        }
        if (masked) {
            headerLen += 4;
        }
        if (len < 0 || len > Integer.MAX_VALUE - headerLen) {
            return null;
        }
        int total = (int) (headerLen + len);
        if (buf.readableBytes() < total) {
            return null;
        }
        byte[] payload = new byte[(int) len];
        if (masked) {
            byte[] mask = new byte[4];
            buf.getBytes(ri + headerLen - 4, mask);
            buf.getBytes(ri + headerLen, payload);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        } else {
            buf.getBytes(ri + headerLen, payload);
        }
        buf.skipBytes(total);
        return new WsFrame(fin, opcode, payload);
    }

    public static final class WsFrame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;

        WsFrame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    // ================= EaglerX 握手包 =================

    /** 解析 CLIENT_VERSION（0x01）。返回 null 表示格式非法。 */
    public static ClientVersion parseClientVersion(ByteBuf buf) {
        if (buf.readableBytes() < 1) {
            return null;
        }
        int legacy = buf.readUnsignedByte();
        if (legacy != 2) {
            return null; // 仅支持 post-snapshot（legacy=2）
        }
        if (buf.readableBytes() < 4) {
            return null;
        }
        int protCount = buf.readUnsignedShort();
        if (protCount > 64 || buf.readableBytes() < protCount * 2L) {
            return null;
        }
        int[] protVersions = new int[protCount];
        for (int i = 0; i < protCount; i++) {
            protVersions[i] = buf.readUnsignedShort();
        }
        if (buf.readableBytes() < 2) {
            return null;
        }
        int gameCount = buf.readUnsignedShort();
        if (gameCount > 64 || buf.readableBytes() < gameCount * 2L) {
            return null;
        }
        int[] gameVersions = new int[gameCount];
        for (int i = 0; i < gameCount; i++) {
            gameVersions[i] = buf.readUnsignedShort();
        }
        if (buf.readableBytes() < 2) {
            return null;
        }
        int brandLen = buf.readUnsignedByte();
        if (buf.readableBytes() < brandLen + 1L) {
            return null;
        }
        String brand = buf.readCharSequence(brandLen, StandardCharsets.US_ASCII).toString();
        int verLen = buf.readUnsignedByte();
        if (buf.readableBytes() < verLen + 1L) {
            return null;
        }
        String version = buf.readCharSequence(verLen, StandardCharsets.US_ASCII).toString();
        if (buf.readableBytes() < 1) {
            return null;
        }
        boolean auth = buf.readBoolean();
        String authUsername = null;
        if (buf.readableBytes() >= 1) {
            int authLen = buf.readUnsignedByte();
            if (buf.readableBytes() >= authLen) {
                authUsername = buf.readCharSequence(authLen, StandardCharsets.US_ASCII).toString();
            }
        }
        return new ClientVersion(legacy, protVersions, gameVersions, brand, version, auth, authUsername);
    }

    public static final class ClientVersion {
        public final int legacy;
        public final int[] protVersions;
        public final int[] gameVersions;
        public final String brand;
        public final String version;
        public final boolean auth;
        public final String authUsername;

        ClientVersion(int legacy, int[] protVersions, int[] gameVersions, String brand, String version,
                      boolean auth, String authUsername) {
            this.legacy = legacy;
            this.protVersions = protVersions;
            this.gameVersions = gameVersions;
            this.brand = brand;
            this.version = version;
            this.auth = auth;
            this.authUsername = authUsername;
        }
    }

    /**
     * 版本协商（与 EaglerXBungee 一致）：服务器支持协议范围
     * min = allowV3 ? 2 : 4，max = allowV4 ? 4 : 3；game 固定 [minMc, maxMc]。
     * 返回 null 表示无交集（应发 VERSION_MISMATCH）。
     */
    public static int[] negotiate(ClientVersion cv, boolean allowV3, boolean allowV4, int minMc, int maxMc) {
        int minServer = allowV3 ? 2 : 4;
        int maxServer = allowV4 ? 4 : 3;
        int prot = -1;
        for (int j : cv.protVersions) {
            if (j >= minServer && j <= maxServer && j > prot) {
                prot = j;
            }
        }
        if (prot == -1) {
            return null;
        }
        int game = -1;
        for (int j : cv.gameVersions) {
            if (j >= minMc && j <= maxMc && j > game) {
                game = j;
            }
        }
        if (game == -1) {
            return null;
        }
        return new int[]{prot, game};
    }

    /** 构造 SERVER_VERSION（0x02，无认证）：op + prot(2) + game(2) + brand + ver + 0x00 + 0x0000。 */
    public static byte[] buildServerVersion(int prot, int game, String brand, String version) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(SERVER_VERSION);
        buf.writeShort(prot);
        buf.writeShort(game);
        buf.writeByte(brand.length());
        buf.writeCharSequence(brand, StandardCharsets.US_ASCII);
        buf.writeByte(version.length());
        buf.writeCharSequence(version, StandardCharsets.US_ASCII);
        buf.writeByte(0);       // 无需认证
        buf.writeShort(0);      // 无认证数据
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** 构造 VERSION_MISMATCH（0x03）：op + 支持prot数量 + [prot]* + game数量 + [game]* + msg。 */
    public static byte[] buildVersionMismatch(boolean allowV3, boolean allowV4, int minMc, int maxMc, String msg) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(VERSION_MISMATCH);
        int count = (allowV3 ? 2 : 0) + (allowV4 ? 1 : 0);
        buf.writeShort(count);
        if (allowV3) {
            buf.writeShort(2);
            buf.writeShort(3);
        }
        if (allowV4) {
            buf.writeShort(4);
        }
        buf.writeShort(2);
        buf.writeShort(minMc);
        buf.writeShort(maxMc);
        buf.writeByte(msg.length());
        buf.writeCharSequence(msg, StandardCharsets.US_ASCII);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** 解析 REQUEST_LOGIN（0x04）。 */
    public static RequestLogin parseRequestLogin(ByteBuf buf, int clientProtocolVersion) {
        if (buf.readableBytes() < 3) {
            return null;
        }
        int userLen = buf.readUnsignedByte();
        if (buf.readableBytes() < userLen + 2L) {
            return null;
        }
        String username = buf.readCharSequence(userLen, StandardCharsets.US_ASCII).toString();
        int serverLen = buf.readUnsignedByte();
        if (buf.readableBytes() < serverLen + 1L) {
            return null;
        }
        String server = buf.readCharSequence(serverLen, StandardCharsets.US_ASCII).toString();
        int pwLen = buf.readUnsignedByte();
        if (buf.readableBytes() < pwLen) {
            return null;
        }
        buf.skipBytes(pwLen);
        if (clientProtocolVersion >= 4) {
            if (buf.readableBytes() < 2) {
                return null;
            }
            boolean cookieEnabled = buf.readBoolean();
            int cookieLen = buf.readUnsignedByte();
            if (buf.readableBytes() < cookieLen) {
                return null;
            }
            if (cookieEnabled && cookieLen > 0) {
                buf.skipBytes(cookieLen);
            }
        }
        return new RequestLogin(username, server);
    }

    public static final class RequestLogin {
        public final String username;
        public final String server;

        RequestLogin(String username, String server) {
            this.username = username;
            this.server = server;
        }
    }

    /** 构造 ALLOW_LOGIN（0x05）：op + userLen + user + uuidMost(8) + uuidLeast(8)。 */
    public static byte[] buildAllowLogin(String username, java.util.UUID uuid) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(SERVER_ALLOW_LOGIN);
        buf.writeByte(username.length());
        buf.writeCharSequence(username, StandardCharsets.US_ASCII);
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** 构造 DENY_LOGIN（0x06）：op + userLen + user + reasonLen + reason。 */
    public static byte[] buildDenyLogin(String username, String reason) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(SERVER_DENY_LOGIN);
        buf.writeByte(username.length());
        buf.writeCharSequence(username, StandardCharsets.US_ASCII);
        buf.writeByte(reason.length());
        buf.writeCharSequence(reason, StandardCharsets.US_ASCII);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        buf.release();
        return out;
    }

    /** 跳过 PROFILE_DATA（0x07）：op + typeLen + type + dataLen(4) + data。 */
    public static boolean skipProfileData(ByteBuf buf) {
        if (buf.readableBytes() < 1) {
            return false;
        }
        int typeLen = buf.readUnsignedByte();
        if (buf.readableBytes() < typeLen + 4L) {
            return false;
        }
        buf.skipBytes(typeLen);
        long dataLen = buf.readUnsignedInt();
        if (buf.readableBytes() < dataLen) {
            return false;
        }
        buf.skipBytes((int) dataLen);
        return true;
    }

    /** FINISH_LOGIN（0x08）应为空包。 */
    public static boolean isFinishLogin(ByteBuf buf) {
        return !buf.isReadable();
    }

    /** offline UUID = uuid3("OfflinePlayer:" + username)（与 EaglerXBungee 一致）。 */
    public static java.util.UUID offlineUuid(String username) {
        byte[] hashSource = ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8);
        return java.util.UUID.nameUUIDFromBytes(hashSource);
    }
}
