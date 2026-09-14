import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Cloud WS 协议链路探测：握手 / PONG / 下行帧类型 / 设备限流 */
public class CloudWsProbe {
    public static void main(String[] args) throws Exception {
        // 保持连接 20 秒：验证服务器不会主动断开（SOYS 1.4.0 兼容）
        System.out.println("HoldTest: " + test("HoldProbe1"));
    }

    static String test(String user) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", 25574), 3000);
            sock.setSoTimeout(5000);
            OutputStream os = sock.getOutputStream();
            InputStream is = sock.getInputStream();
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            String req = "GET /cloud?user=" + user + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:25574\r\n"
                    + "Origin: http://127.0.0.1:25574\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n"
                    + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36\r\n"
                    + "Accept-Encoding: gzip, deflate, br\r\n"
                    + "Accept-Language: zh-CN,zh;q=0.9\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.US_ASCII));
            os.flush();

            // 读 HTTP 头，保留 \r\n\r\n 之后的 WS 帧字节
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            byte[] tmp = new byte[2048];
            byte[] all = null;
            while (true) {
                int n = is.read(tmp);
                if (n <= 0) return "NO_101";
                headBuf.write(tmp, 0, n);
                all = headBuf.toByteArray();
                if (indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) >= 0) break;
            }
            int sep = indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String head = new String(all, 0, sep + 4, StandardCharsets.US_ASCII);
            String status = head.substring(0, head.indexOf("\r\n"));
            byte[] pending = Arrays.copyOfRange(all, sep + 4, all.length);

            // RAW dump：连接后首 96 字节（含 101 头之后的 WS 帧起始），检查垃圾字节
            StringBuilder rawHex = new StringBuilder();
            for (int i = 0; i < Math.min(all.length, 200); i++) {
                rawHex.append(String.format("%02X ", all[i] & 0xFF));
            }
            System.out.println("RAW[" + all.length + "] headEnd=" + (sep + 4) + " pending=" + pending.length
                    + " :: " + rawHex.toString().trim());

            // 发 PONG：0x04 + u32 counter=1
            byte[] pong = {0x04, 0x01, 0x00, 0x00, 0x00};
            os.write(maskedFrame((byte) 0x2, pong));
            os.flush();

            // RAW 采集：连接后读 3.2 秒所有字节，打印完整 hex（不解析）
            sock.setSoTimeout(3200);
            ByteArrayOutputStream rawAll = new ByteArrayOutputStream();
            rawAll.write(pending, 0, pending.length);
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 3200) {
                try {
                    byte[] buf = new byte[4096];
                    int n = is.read(buf);
                    if (n < 0) { rawAll.write("<<EOF>>".getBytes(StandardCharsets.US_ASCII)); break; }
                    rawAll.write(buf, 0, n);
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
            }
            byte[] raw = rawAll.toByteArray();
            StringBuilder hx = new StringBuilder();
            for (int i = 0; i < raw.length; i++) hx.append(String.format("%02X ", raw[i] & 0xFF));
            System.out.println("RAWALL len=" + raw.length + "\n" + hx.toString().trim());
            return "STATUS=" + status + " rawlen=" + raw.length;
        } catch (Exception e) {
            return "EX=" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static byte[] maskedFrame(byte opcode, byte[] payload) {
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        ms.write(0x80 | opcode);
        int len = payload.length;
        if (len < 126) {
            ms.write(0x80 | len);
        } else if (len < 65536) {
            ms.write(0x80 | 126);
            ms.write(len >> 8);
            ms.write(len);
        } else {
            ms.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) ms.write(len >> (i * 8));
        }
        ms.write(mask, 0, 4);
        for (int i = 0; i < len; i++) ms.write(payload[i] ^ mask[i % 4]);
        return ms.toByteArray();
    }

    static final class FrameReader {
        private final InputStream is;
        private byte[] pending;

        FrameReader(InputStream is, byte[] pending) {
            this.is = is;
            this.pending = pending;
        }

        int readByte() throws IOException {
            if (pending != null && pending.length > 0) {
                byte b = pending[0];
                pending = Arrays.copyOfRange(pending, 1, pending.length);
                return b & 0xFF;
            }
            pending = null;
            return is.read();
        }

        byte[] readFrame() throws IOException {
            int b0 = readByte();
            if (b0 < 0) return null;
            int b1 = readByte();
            if (b1 < 0) return null;
            int len = b1 & 0x7F;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            byte[] data = new byte[len];
            int off = 0;
            while (off < len) {
                int n = readByte();
                if (n < 0) break;
                data[off++] = (byte) n;
            }
            return off == len ? data : Arrays.copyOf(data, off);
        }
    }
}
