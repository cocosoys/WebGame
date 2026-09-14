import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Cloud 视频帧采集：解析 WS 帧，把 type=0x02 视频帧的 Annex-B 数据追加保存到文件。
 *  用法: java CloudVideoDump [秒数] [输出文件]
 *  验证: ffmpeg -i probe-captured.h264 -vf "select=eq(n\,N)" -frames:v 1 out.png */
public class CloudVideoDump {
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        String outFile = args.length > 1 ? args[1] : "probe-captured.h264";
        String user = "Dump" + System.currentTimeMillis() % 100000;

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", 25574), 3000);
            sock.setSoTimeout(8000);
            OutputStream os = sock.getOutputStream();
            InputStream is = sock.getInputStream();
            String key = Base64.getEncoder().encodeToString(new byte[16]);
            String req = "GET /cloud?user=" + user + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:25574\r\n"
                    + "Origin: http://127.0.0.1:25574\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.US_ASCII));
            os.flush();

            // 读 HTTP 头
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            byte[] all = null;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 5000) {
                int n = is.read(tmp);
                if (n <= 0) { System.out.println("NO_101"); return; }
                headBuf.write(tmp, 0, n);
                all = headBuf.toByteArray();
                if (indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) >= 0) break;
            }
            int sep = indexOf(all, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            if (sep < 0) { System.out.println("NO_HEADER"); return; }
            String head = new String(all, 0, sep + 4, StandardCharsets.US_ASCII);
            String status = head.substring(0, head.indexOf("\r\n"));
            byte[] pending = Arrays.copyOfRange(all, sep + 4, all.length);
            System.out.println("STATUS=" + status + " pending=" + pending.length);

            // 发 PONG 保持活跃
            os.write(maskedFrame((byte) 0x2, new byte[]{0x04, 0x01, 0x00, 0x00, 0x00}));
            os.flush();

            FrameReader fr = new FrameReader(is, pending);
            FileOutputStream fos = new FileOutputStream(outFile);
            int videoFrames = 0, configFrames = 0, pingFrames = 0, otherFrames = 0;
            long start = System.currentTimeMillis();
            try {
                while (System.currentTimeMillis() - start < seconds * 1000L) {
                    byte[] frame = fr.readFrame();
                    if (frame == null) { System.out.println("EOF/STREAM_END"); break; }
                    if (frame.length == 0) continue;
                    int type = frame[0] & 0xFF;
                    switch (type) {
                        case 0x01: configFrames++; break;
                        case 0x02: {
                            if (frame.length > 6) {
                                fos.write(frame, 6, frame.length - 6); // Annex-B 数据
                                videoFrames++;
                            }
                            break;
                        }
                        case 0x03: pingFrames++; break;
                        default: otherFrames++;
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("TIMEOUT (socket read timeout)");
            } finally {
                fos.close();
            }
            System.out.println("video=" + videoFrames + " config=" + configFrames
                    + " ping=" + pingFrames + " other=" + otherFrames
                    + " file=" + new File(outFile).length() + " bytes");
        } catch (Exception e) {
            System.out.println("EX=" + e.getClass().getSimpleName() + ":" + e.getMessage());
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
