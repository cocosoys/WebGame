import java.io.*;

/** 分析 H.264 Annex-B 文件的 NALU 序列：统计每个 IDR 帧前是否带 SPS(7)/PPS(8)。 */
public class H264Analyzer {
    public static void main(String[] args) throws Exception {
        String path = args[0];
        byte[] data = readAll(new File(path));
        System.out.println("file=" + path + " size=" + data.length);

        int pos = 0;
        int idrCount = 0;
        int idrWithParams = 0;
        boolean sawSpsBeforeIdr = false, sawPpsBeforeIdr = false;
        int frameCount = 0;
        StringBuilder firstFrames = new StringBuilder();

        while (pos < data.length) {
            int sc = findStartCode(data, pos);
            if (sc < 0) break;
            int hdr = sc + (data[sc + 2] == 1 ? 3 : 4);
            int next = findStartCode(data, hdr);
            int end = next < 0 ? data.length : next;
            if (end <= hdr) { pos = end; break; }
            int type = data[hdr] & 0x1F;
            int nalSize = end - hdr;
            if (firstFrames.length() < 300) {
                firstFrames.append("[").append(type).append(":").append(nalSize).append("]");
            }
            if (type == 7) sawSpsBeforeIdr = true;
            if (type == 8) sawPpsBeforeIdr = true;
            if (type == 5) {
                idrCount++;
                frameCount++;
                if (sawSpsBeforeIdr && sawPpsBeforeIdr) idrWithParams++;
                sawSpsBeforeIdr = false;
                sawPpsBeforeIdr = false;
            } else if (type == 1) {
                frameCount++;
            }
            pos = end;
        }
        System.out.println("IDR count=" + idrCount + " withSpsPps=" + idrWithParams + " totalFrames=" + frameCount);
        System.out.println("first NALUs: " + firstFrames);
    }

    private static int findStartCode(byte[] d, int from) {
        for (int i = from; i <= d.length - 3; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i;
            if (i + 3 < d.length && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) return i;
        }
        return -1;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
