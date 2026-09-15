package com.github.cocosoys.mc.webgame.web.admin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 管理员入口执行通道：插件（Windows Spigot）→ WSL 执行面。
 *
 * <p>所有管理操作（终端命令 / 模板容器文件 / Java 切换）经本类统一封装，
 * 通过 {@code wsl -e bash -lc "<command>"} 转发到执行面执行（WSL 默认用户 root，
 * 模板容器属主 webgame，文件操作后统一 chown 保持属主一致）。</p>
 *
 * <p>安全边界：本类仅被 AdminController 调用，而 AdminController 已由 SOYS 网关
 * 完成凭证鉴权 + 插件侧 OP 校验，因此命令内容视为管理员可信输入。</p>
 */
public final class AdminExecutor {

    /** 模板容器根（新实例由此派生）。 */
    public static final String MC_BASE = "/home/webgame/mc";
    /** Java 运行时配置文件（cc 生成 launch.sh 时读取）。 */
    public static final String JAVA_CONF = "/home/webgame/java.conf";
    /** 命令默认超时（毫秒）。 */
    private static final long CMD_TIMEOUT_MS = 30000L;

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean timedOut;

        Result(int exitCode, String output, boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }
    }

    /**
     * 在 WSL 执行面执行一条 shell 命令（root 身份）。
     *
     * @param cmd 完整 shell 命令串
     * @return 执行结果（stdout+stderr 合并、退出码、是否超时）
     */
    public Result exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return new Result(0, "", false);
        }
        return run("bash", "-lc", cmd);
    }

    /**
     * 读取模板容器目录列表。
     *
     * @param path 容器内绝对路径
     * @return JSON 数组串（name/type/size/path），异常时返回错误对象
     */
    public String listDir(String path) {
        String p = sanitizePath(path);
        String script = "find " + q(p) + " -maxdepth 1 -mindepth 1 -printf '%y|%s|%f\\n' 2>/dev/null | sort";
        Result r = run("bash", "-lc", script);
        if (r.exitCode != 0) {
            if (r.output.contains("__ERR__NOT_DIR__")) {
                return "{\"error\":\"目录不存在或不是目录: " + esc(p) + "\"}";
            }
            return "{\"error\":" + escJson(r.output.isEmpty() ? ("退出码 " + r.exitCode) : r.output) + "}";
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"path\":\"").append(esc(p)).append("\",\"entries\":[");
        boolean first = true;
        for (String line : r.output.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf('|');
            if (idx <= 0) {
                continue;
            }
            String type = line.substring(0, idx);
            String rest = line.substring(idx + 1);
            int idx2 = rest.indexOf('|');
            if (idx2 < 0) {
                continue;
            }
            String size = rest.substring(0, idx2);
            String name = rest.substring(idx2 + 1);
            String full = p.equals("/") ? p + name : p + "/" + name;
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":\"").append(esc(name))
                    .append("\",\"dir\":").append("d".equals(type))
                    .append(",\"size\":\"").append(esc(size))
                    .append("\",\"path\":\"").append(esc(full)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * 删除模板容器内文件/目录。
     *
     * @param path 容器内绝对路径
     * @return 错误信息（成功返回 null）
     */
    public String delete(String path) {
        String p = sanitizePath(path);
        if (p.equals(MC_BASE) || p.equals("/home/webgame") || p.equals("/")) {
            return "禁止删除根路径: " + p;
        }
        Result r = run("bash", "-lc", "rm -rf " + q(p));
        return r.exitCode == 0 ? null
                : (r.output.isEmpty() ? ("删除失败(退出码 " + r.exitCode + ")") : r.output);
    }

    /**
     * 读取文本文件内容（记事本查看/编辑），限 ≤ 512KB。
     *
     * @param path 容器内绝对路径
     * @return [0]=null 成功或 [0]=错误信息；成功时 [1]=内容
     */
    public String[] readText(String path) {
        String p = sanitizePath(path);
        if (p.equals(MC_BASE) || p.equals("/home/webgame") || p.equals("/")) {
            return new String[]{"禁止读取根路径: " + p, null};
        }
        String qp = q(p);
        Result r = run("bash", "-lc", "[ -f " + qp + " ] || { echo '__ERR__NOT_FILE__'; exit 3; }; "
                + "[ \"$(stat -c %s " + qp + " 2>/dev/null || echo 0)\" -le 524288 ] || { echo '__ERR__TOO_BIG__'; exit 4; }; cat " + qp);
        if (r.exitCode != 0) {
            if (r.output.contains("__ERR__NOT_FILE__")) {
                return new String[]{"不是文件或不存在: " + p, null};
            }
            if (r.output.contains("__ERR__TOO_BIG__")) {
                return new String[]{"文件超过 512KB，请用下载查看: " + p, null};
            }
            return new String[]{r.output.isEmpty() ? ("读取失败(退出码 " + r.exitCode + ")") : r.output, null};
        }
        return new String[]{null, r.output};
    }

    /**
     * 下载：以 base64 返回文件内容（浏览器端解码保存），限 ≤ 16MB。
     *
     * @param path 容器内绝对路径
     * @return [0]=null 成功或 [0]=错误信息；成功时 [1]=base64、[2]=文件名、[3]=字节数
     */
    public String[] downloadB64(String path) {
        String p = sanitizePath(path);
        if (p.equals(MC_BASE) || p.equals("/home/webgame") || p.equals("/")) {
            return new String[]{"禁止下载根路径: " + p, null, null, null};
        }
        String qp = q(p);
        Result r = run("bash", "-lc", "[ -f " + qp + " ] || { echo '__ERR__NOT_FILE__'; exit 3; }; "
                + "[ \"$(stat -c %s " + qp + " 2>/dev/null || echo 0)\" -le 16777216 ] || { echo '__ERR__TOO_BIG__'; exit 4; }; "
                + "base64 -w0 " + qp);
        if (r.exitCode != 0) {
            if (r.output.contains("__ERR__NOT_FILE__")) {
                return new String[]{"不是文件或不存在: " + p, null, null, null};
            }
            if (r.output.contains("__ERR__TOO_BIG__")) {
                return new String[]{"文件超过 16MB，暂不支持下载: " + p, null, null, null};
            }
            return new String[]{r.output.isEmpty() ? ("下载失败(退出码 " + r.exitCode + ")") : r.output, null, null, null};
        }
        String b64 = r.output.replaceAll("[\\r\\n]", "");
        String name = p.substring(p.lastIndexOf('/') + 1);
        return new String[]{null, b64, name, String.valueOf(b64.length() * 3L / 4)};
    }

    /**
     * 新建目录（含父目录链）或空白文件。
     *
     * @param path  容器内绝对路径
     * @param isDir true=目录，false=空白文件
     * @return 错误信息（成功返回 null）
     */
    public String create(String path, boolean isDir) {
        String p = sanitizePath(path);
        if (!p.startsWith(MC_BASE + "/") && !p.equals(MC_BASE)) {
            return "仅允许在模板容器内创建: " + MC_BASE;
        }
        if (p.equals(MC_BASE) || p.equals("/home/webgame") || p.equals("/")) {
            return "禁止操作根路径: " + p;
        }
        Result r = isDir
                ? run("bash", "-lc", "mkdir -p " + q(p) + " && chown -R webgame:webgame " + q(p) + " && echo OK")
                : run("bash", "-lc", "mkdir -p \"$(dirname " + q(p) + ")\" && touch " + q(p)
                + " && chown webgame:webgame " + q(p) + " && echo OK");
        return r.exitCode == 0 ? null
                : (r.output.isEmpty() ? ("创建失败(退出码 " + r.exitCode + ")") : r.output);
    }

    /**
     * 重命名/移动：仅限模板容器内同路径（mv）。
     *
     * @param from 原路径
     * @param to   目标路径
     * @return 错误信息（成功返回 null）
     */
    public String rename(String from, String to) {
        String p1 = sanitizePath(from);
        String p2 = sanitizePath(to);
        if (!p1.startsWith(MC_BASE + "/") || !p2.startsWith(MC_BASE + "/")) {
            return "仅允许在模板容器内重命名: " + MC_BASE;
        }
        if (p1.equals(MC_BASE) || p2.equals(MC_BASE)) {
            return "禁止重命名根路径";
        }
        Result r = run("bash", "-lc", "mv " + q(p1) + " " + q(p2) + " && chown -R webgame:webgame " + q(p2) + " && echo OK");
        return r.exitCode == 0 ? null
                : (r.output.isEmpty() ? ("重命名失败(退出码 " + r.exitCode + ")") : r.output);
    }

    /**
     * 上传（流式实现）：把 base64 解码后经 stdin 写入目标文件。
     */
    public String uploadStdin(String target, String base64Data) {
        if (target == null || base64Data == null) {
            return "参数缺失: path/data";
        }
        String p = sanitizePath(target);
        if (!p.startsWith(MC_BASE + "/") && !p.equals(MC_BASE)) {
            return "仅允许上传到模板容器内: " + MC_BASE;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            return "Base64 解码失败";
        }
        String dir = p.substring(0, Math.max(p.lastIndexOf('/'), 1));
        String script = "mkdir -p " + q(dir) + " && cat > " + q(p) + " && chown webgame:webgame " + q(p)
                + " && echo OK";
        Result r = runStdin("bash", script, raw);
        return r.exitCode == 0 ? null
                : (r.output.isEmpty() ? ("上传失败(退出码 " + r.exitCode + ")") : r.output);
    }

    /**
     * 读取 /home/webgame/java.conf（当前 JAVA_HOME 配置）。
     */
    public String readJavaConf() {
        Result r = run("bash", "-lc", "[ -f " + q(JAVA_CONF) + " ] && cat " + q(JAVA_CONF) + " || echo '__NONE__'");
        String out = r.output.trim();
        if ("__NONE__".equals(out) || out.isEmpty()) {
            return null;
        }
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("JAVA_HOME=")) {
                String v = t.substring("JAVA_HOME=".length()).trim();
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    /**
     * 写入 JAVA_HOME 配置（系统设置-切换 Java）。
     *
     * @param javaHome JVM 目录绝对路径（执行面内）
     * @return 错误信息（成功返回 null）
     */
    public String writeJavaConf(String javaHome) {
        if (javaHome == null || javaHome.trim().isEmpty()) {
            return "JAVA_HOME 不能为空";
        }
        String jh = javaHome.trim().replace("\"", "");
        // 校验路径存在且含 java 可执行文件
        Result check = run("bash", "-lc",
                "[ -x " + q(jh + "/bin/java") + " ] && echo OK || echo BAD");
        if (!check.output.trim().equals("OK")) {
            return "路径不存在或不是有效 JDK（未找到 " + jh + "/bin/java）";
        }
        Result r = run("bash", "-lc",
                "printf 'JAVA_HOME=%s\\n' " + q(jh) + " > " + q(JAVA_CONF)
                        + " && chown webgame:webgame " + q(JAVA_CONF) + " && echo OK");
        return r.exitCode == 0 && r.output.trim().equals("OK")
                ? null : (r.output.isEmpty() ? "写入失败(退出码 " + r.exitCode + ")" : r.output);
    }

    /** 查询执行面 Java 版本（当前生效）。 */
    public String javaVersion(String javaHome) {
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            Result r = run("bash", "-lc", q(javaHome.trim() + "/bin/java") + " -version 2>&1 | head -1");
            if (r.exitCode == 0) {
                return r.output.trim();
            }
        }
        Result r = run("bash", "-lc", "java -version 2>&1 | head -1");
        return r.exitCode == 0 ? r.output.trim() : "未知";
    }

    /** 查询模板容器 MC/Forge 版本。 */
    public String[] mcVersions() {
        Result r = run("bash", "-lc",
                "ls " + q(MC_BASE + "/.minecraft/versions") + " 2>/dev/null | head -5");
        String[] versions = r.output.trim().isEmpty() ? new String[0] : r.output.trim().split("\n");
        String mc = "未知", forge = "未知";
        if (versions.length > 0) {
            String v = versions[0];
            if (v.contains("-Forge_")) {
                mc = v.substring(0, v.indexOf("-Forge_"));
                forge = v.substring(v.indexOf("-Forge_") + 1);
            } else {
                mc = v;
            }
        }
        return new String[]{mc, forge};
    }

    /** 模板容器占用空间（人类可读）。 */
    public String mcSize() {
        Result r = run("bash", "-lc", "du -sh " + q(MC_BASE) + " 2>/dev/null | cut -f1");
        return r.exitCode == 0 && !r.output.trim().isEmpty() ? r.output.trim() : "未知";
    }

    // ================= 基础执行 =================

    private Result run(String... args) {
        return runWith(null, null, args);
    }

    private Result runWith(byte[] stdin, Long timeoutMs, String... args) {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            return new Result(-1, "启动进程失败: " + e.getMessage(), false);
        }
        if (stdin != null) {
            try {
                p.getOutputStream().write(stdin);
                p.getOutputStream().flush();
            } catch (IOException ignored) {
            }
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            InputStream in = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } catch (IOException ignored) {
        }
        boolean timedOut = false;
        try {
            long t = timeoutMs == null ? CMD_TIMEOUT_MS : timeoutMs;
            if (!p.waitFor(t, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        String out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        if (timedOut) {
            out = (out.isEmpty() ? "" : out + "\n") + "[命令超时 " + CMD_TIMEOUT_MS + "ms 已终止]";
        }
        return new Result(timedOut ? -1 : p.exitValue(), out, timedOut);
    }

    private Result runStdin(String shell, String script, byte[] stdin) {
        return runWith(stdin, null, shell, "-c", script);
    }

    // ================= 工具 =================

    private static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return MC_BASE;
        }
        String p = path.trim();
        // 防注入：只允许安全字符
        if (!p.matches("[A-Za-z0-9_./\\-]+")) {
            return MC_BASE;
        }
        if (p.contains("..")) {
            return MC_BASE;
        }
        return p;
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "\\n");
    }

    private static String escJson(String s) {
        return "\"" + esc(s) + "\"";
    }
}
