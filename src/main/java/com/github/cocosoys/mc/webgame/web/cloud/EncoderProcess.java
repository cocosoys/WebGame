package com.github.cocosoys.mc.webgame.web.cloud;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 编码进程：spawn FFmpeg 抓屏（Windows gdigrab / Linux x11grab）→ libx264 低延迟
 * 编码 → stdout 读取 Annex-B H.264 → 按 NAL 切帧（参照 Carrot 帧协议）。
 *
 * <p>职责边界：仅负责「抓屏 → 编码 → 分帧回调」，传输由 {@link CloudSession}
 * 负责（WS 帧封装）；帧协议与传输解耦，未来 v2 分离端口直接复用本回调字节。</p>
 *
 * <p>FFmpeg 不可用 / 启动失败时回调 {@link Listener#onUnavailable(String)}，
 * 由会话下发 ERROR 提示浏览器「等待游戏实例」——游戏实例（Forge farm）落地后
 * 采集源改为容器内虚拟显示，本类逻辑不变（仅换输入参数）。</p>
 */
public final class EncoderProcess {

    /** 编码帧回调。 */
    public interface Listener {
        /** SPS/PPS 提取到（首帧前触发一次，之后仅在变化时触发）。 */
        void onConfig(byte[] sps, byte[] pps, int width, int height, int fps);

        /** 一帧 Annex-B H.264（若干 NALU，各带 start code）。 */
        void onFrame(long tsMs, boolean keyFrame, byte[] annexB);

        /** 编码器不可用（未找到 ffmpeg / 启动失败）。 */
        void onUnavailable(String reason);

        /** 编码器已退出（含被主动停止）。 */
        void onStopped(String reason);
    }

    private final String ffmpegPath;
    private final String captureMode;
    private final String scale;
    private final int fps;
    private final int bitrateKbps;
    private final int gopFrames;
    private final String windowTitle;
    private final Listener listener;

    private volatile Process process;
    private volatile Thread readerThread;
    private volatile boolean stopping = false;

    public EncoderProcess(String ffmpegPath, String captureMode, String scale,
                          int fps, int bitrateKbps, int gopFrames, Listener listener) {
        this(ffmpegPath, captureMode, scale, fps, bitrateKbps, gopFrames, null, listener);
    }

    /** windowTitle 非空时，gdigrab 改为抓取该窗口（容器客户端采集源）。 */
    public EncoderProcess(String ffmpegPath, String captureMode, String scale,
                          int fps, int bitrateKbps, int gopFrames,
                          String windowTitle, Listener listener) {
        this.ffmpegPath = ffmpegPath;
        this.captureMode = captureMode;
        this.scale = scale;
        this.fps = fps;
        this.bitrateKbps = bitrateKbps;
        this.gopFrames = gopFrames;
        this.windowTitle = windowTitle;
        this.listener = listener;
    }

    /** 启动编码器（异步：stdout 读取在线程中进行）。启动失败同步回调 onUnavailable。 */
    public synchronized void start() {
        if (process != null) {
            return;
        }
        if ("none".equalsIgnoreCase(captureMode)) {
            listener.onUnavailable("capture=none，编码已禁用");
            return;
        }
        List<String> cmd = buildCommand();
        String exe = cmd.get(0);
        if (!isExecutableAvailable(exe)) {
            listener.onUnavailable("未找到 FFmpeg（" + exe + "），请安装并配置 cloud.ffmpeg-path");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            process = pb.start();
        } catch (IOException e) {
            listener.onUnavailable("FFmpeg 启动失败: " + e.getMessage());
            return;
        }
        Thread stderr = new Thread(() -> drainStderr(process), "WebGame-Cloud-FFmpeg-stderr");
        stderr.setDaemon(true);
        stderr.start();

        Thread reader = new Thread(() -> readStdout(process), "WebGame-Cloud-FFmpeg");
        reader.setDaemon(true);
        readerThread = reader;
        reader.start();
    }

    /** 停止编码器（等待退出）。 */
    public synchronized void stop() {
        stopping = true;
        Process p = process;
        process = null;
        if (p != null) {
            try {
                p.destroy();
                // 等待 stdout 线程自然退出（最多 1s）
                if (readerThread != null) {
                    readerThread.join(1000);
                }
            } catch (Throwable ignored) {
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    // ================= FFmpeg 命令 =================

    private List<String> buildCommand() {
        String exe = ffmpegPath.isEmpty() ? detectFfmpeg() : ffmpegPath;
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        String mode = captureMode;
        if ("auto".equalsIgnoreCase(mode)) {
            mode = System.getProperty("os.name", "").toLowerCase().contains("win") ? "gdigrab" : "x11grab";
        }
        // 窗口采集：capture 显式 "window:<标题>" 优先，其次 windowTitle 参数（容器客户端）
        String win = null;
        if (mode.startsWith("window:")) {
            win = mode.substring("window:".length()).trim();
            mode = "gdigrab";
        } else if (windowTitle != null && !windowTitle.isEmpty() && "gdigrab".equalsIgnoreCase(mode)) {
            win = windowTitle;
        }
        if ("gdigrab".equalsIgnoreCase(mode)) {
            cmd.add("-f");
            cmd.add("gdigrab");
            cmd.add("-framerate");
            cmd.add(String.valueOf(fps));
            if (win != null) {
                // 抓取指定窗口：不加 -video_size（按窗口客户区原尺寸），用 scale 滤镜统一输出
                cmd.add("-i");
                cmd.add("title=" + win);
                cmd.add("-vf");
                cmd.add("scale=" + scale);
            } else {
                cmd.add("-video_size");
                cmd.add(scale);
                cmd.add("-i");
                cmd.add("desktop");
            }
        } else {
            cmd.add("-f");
            cmd.add("x11grab");
            cmd.add("-framerate");
            cmd.add(String.valueOf(fps));
            cmd.add("-video_size");
            cmd.add(scale);
            cmd.add("-i");
            cmd.add(":0");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-preset");
        cmd.add("ultrafast");
        cmd.add("-tune");
        cmd.add("zerolatency");
        cmd.add("-profile:v");
        cmd.add("baseline");
        cmd.add("-level");
        cmd.add("3.0");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-b:v");
        cmd.add(bitrateKbps + "k");
        cmd.add("-g");
        cmd.add(String.valueOf(gopFrames));
        cmd.add("-keyint_min");
        cmd.add(String.valueOf(gopFrames));
        cmd.add("-sc_threshold");
        cmd.add("0");
        // 每个 IDR 前重复 SPS/PPS（中途加入的浏览器 in-band 依赖关键帧自带参数集）；
        // 禁用 sliced-threads：zerolatency 预设会启用多 slice 切帧，导致按 slice 切帧的
        // 分帧逻辑把一帧拆成多个"帧"（帧数爆炸 + 单 slice 帧无法解码）
        cmd.add("-x264-params");
        cmd.add("repeat-headers=1:sliced-threads=0");
        cmd.add("-f");
        cmd.add("h264");
        cmd.add("pipe:1");
        return cmd;
    }

    private static String detectFfmpeg() {
        String win = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ffmpeg.exe" : "ffmpeg";
        return win;
    }

    private static boolean isExecutableAvailable(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-version")
                    .redirectErrorStream(true)
                    .start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            p.destroy();
            return n > 0;
        } catch (IOException e) {
            return false;
        }
    }

    // ================= stdout 读取与分帧 =================

    private void drainStderr(Process p) {
        try (InputStream err = p.getErrorStream()) {
            byte[] buf = new byte[1024];
            while (!stopping && err.read(buf) != -1) {
                // stderr 仅作诊断，忽略（避免管道阻塞）
            }
        } catch (Throwable ignored) {
        }
    }

    private void readStdout(Process p) {
        ByteArrayOutputStream acc = new ByteArrayOutputStream(256 * 1024);
        // 当前帧的 NALU 列表（内容不含 start code）
        List<byte[]> frame = new ArrayList<>();
        byte[] sps = null;
        byte[] pps = null;
        boolean configSent = false;
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while (!stopping && (n = in.read(buf)) != -1) {
                acc.write(buf, 0, n);
                byte[] data = acc.toByteArray();
                acc.reset();
                int pos = 0;
                while (pos < data.length) {
                    int sc = findStartCode(data, pos);
                    if (sc < 0) {
                        // 剩余不足一个完整 NALU：保留到下次
                        acc.write(data, pos, data.length - pos);
                        break;
                    }
                    int hdr = sc + (data[sc + 2] == 1 ? 3 : 4);
                    int next = findStartCode(data, hdr);
                    if (next < 0) {
                        acc.write(data, pos, data.length - pos);
                        break;
                    }
                    int naluLen = next - hdr;
                    if (naluLen > 0) {
                        byte[] nalu = new byte[naluLen];
                        System.arraycopy(data, hdr, nalu, 0, naluLen);
                        handleNalu(nalu, frame);
                        if ((nalu[0] & 0x1F) == 7) {
                            sps = stripEmulation(nalu);
                        } else if ((nalu[0] & 0x1F) == 8) {
                            pps = stripEmulation(nalu);
                        }
                        if (sps != null && pps != null && !configSent) {
                            configSent = true;
                            int[] wh = parseResolution(scale);
                            listener.onConfig(sps, pps, wh[0], wh[1], fps);
                        }
                    }
                    pos = next;
                }
            }
            // 流结束兜底：flush 最后一帧（仅当含 slice，避免孤立参数集）
            if (containsSlice(frame)) {
                flushFrame(frame);
            }
        } catch (Throwable t) {
            if (!stopping) {
                listener.onStopped("编码器读取异常: " + t.getMessage());
            }
        } finally {
            if (!stopping) {
                listener.onStopped("编码器已退出");
            }
        }
    }

    /** 处理一个 NALU：slice（type 1/5）为帧边界，flush 前一帧。
     *  关键帧保留 SPS/PPS/SEI/AUD（不清空），使 in-band 解码也能自给参数集。 */
    private void handleNalu(byte[] nalu, List<byte[]> frame) {
        int type = nalu[0] & 0x1F;
        if (type == 1 || type == 5) {
            if (containsSlice(frame)) {
                // 前一帧完成
                flushFrame(frame);
            }
            // 不 clear：AUD/SEI/SPS/PPS 与当前 slice 同帧（关键帧自带参数集，in-band 兼容）
        }
        frame.add(nalu);
    }

    private static boolean containsSlice(List<byte[]> frame) {
        for (byte[] nalu : frame) {
            int t = nalu[0] & 0x1F;
            if (t == 1 || t == 5) {
                return true;
            }
        }
        return false;
    }

    private void flushFrame(List<byte[]> frame) {
        if (frame.isEmpty()) {
            return;
        }
        boolean key = false;
        for (byte[] nalu : frame) {
            if ((nalu[0] & 0x1F) == 5) {
                key = true;
                break;
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128 * 1024);
        for (byte[] nalu : frame) {
            bos.write(0);
            bos.write(0);
            bos.write(1);
            bos.write(nalu, 0, nalu.length);
        }
        frame.clear();
        long ts = System.currentTimeMillis();
        listener.onFrame(ts, key, bos.toByteArray());
    }

    // ================= 工具 =================

    /** 从 offset 起找下一个 Annex-B start code（00 00 01 / 00 00 00 01），找不到返回 -1。 */
    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i <= data.length - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return i;
                }
                if (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 去除 SPS/PPS 中的 emulation prevention bytes（00 00 03 → 00 00）。 */
    private static byte[] stripEmulation(byte[] nalu) {
        int count = 0;
        for (int i = 2; i < nalu.length; i++) {
            if (nalu[i - 2] == 0 && nalu[i - 1] == 0 && nalu[i] == 3) {
                count++;
            }
        }
        if (count == 0) {
            return nalu;
        }
        byte[] out = new byte[nalu.length - count];
        int w = 0;
        for (int i = 0; i < nalu.length; i++) {
            if (i >= 2 && i < nalu.length - 1 && nalu[i - 2] == 0 && nalu[i - 1] == 0 && nalu[i] == 3) {
                continue;
            }
            out[w++] = nalu[i];
        }
        return out;
    }

    /** 解析 "1280x720" → {1280,720}。 */
    private static int[] parseResolution(String scale) {
        try {
            int x = scale.toLowerCase().indexOf('x');
            if (x > 0) {
                return new int[]{Integer.parseInt(scale.substring(0, x).trim()),
                        Integer.parseInt(scale.substring(x + 1).trim())};
            }
        } catch (Throwable ignored) {
        }
        return new int[]{1280, 720};
    }
}
