package com.github.cocosoys.mc.webgame.web.admin;

/**
 * 管理员入口执行通道：插件（Windows Spigot）→ 执行面。
 *
 * <p>执行面（原 WSL/Xvfb 路线）已按项目决策彻底移除，本类保留公开方法签名
 * （AdminController 编译依赖）与返回结构，全部操作返回「执行面未就绪」，
 * 待 GFN Windows + CreateDesktop + 虚拟 HID 方案落地后重写为新的执行通道。</p>
 *
 * <p>安全边界：本类仅被 AdminController 调用，而 AdminController 已由 SOYS 网关
 * 完成凭证鉴权 + 插件侧 OP 校验，因此命令内容视为管理员可信输入。</p>
 */
public final class AdminExecutor {

    /** 执行面未就绪提示（统一返回给管理面板）。 */
    private static final String NOT_READY =
            "执行面未就绪：WSL/Xvfb 执行面已移除，待 GFN 方案重建后恢复";

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

    /** 终端：执行面未就绪。 */
    public Result exec(String cmd) {
        return new Result(1, NOT_READY, false);
    }

    /** 文件列表：执行面未就绪。 */
    public String listDir(String path) {
        return "{\"error\":\"" + esc(NOT_READY) + "\"}";
    }

    /** 删除：执行面未就绪。 */
    public String delete(String path) {
        return NOT_READY;
    }

    /** 读取文本：执行面未就绪。 */
    public String[] readText(String path) {
        return new String[]{NOT_READY, null};
    }

    /** 下载：执行面未就绪。 */
    public String[] downloadB64(String path) {
        return new String[]{NOT_READY, null, null, null};
    }

    /** 新建：执行面未就绪。 */
    public String create(String path, boolean isDir) {
        return NOT_READY;
    }

    /** 重命名：执行面未就绪。 */
    public String rename(String from, String to) {
        return NOT_READY;
    }

    /** 上传：执行面未就绪。 */
    public String uploadStdin(String target, String base64Data) {
        return NOT_READY;
    }

    /** 读取 Java 配置：执行面未就绪（返回 null，前端显示为空）。 */
    public String readJavaConf() {
        return null;
    }

    /** 写入 Java 配置：执行面未就绪。 */
    public String writeJavaConf(String javaHome) {
        return NOT_READY;
    }

    /** 查询 Java 版本：执行面未就绪。 */
    public String javaVersion(String javaHome) {
        return "执行面未就绪";
    }

    /** 查询 MC/Forge 版本：执行面未就绪。 */
    public String[] mcVersions() {
        return new String[]{"未知", "未知"};
    }

    /** 模板容器占用空间：执行面未就绪。 */
    public String mcSize() {
        return "未知";
    }

    /**
     * 容器状态快照：执行面未就绪（返回空快照，前端正常渲染空列表）。
     */
    public synchronized String containerSnapshot() {
        return "{\"procs\":[],\"mem\":null,\"disk\":null,\"load\":\"\",\"ccPid\":0}";
    }

    /** 重启执行面守护：执行面未就绪。 */
    public String restartControlClient() {
        return "{\"error\":" + escJson(NOT_READY) + "}";
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
