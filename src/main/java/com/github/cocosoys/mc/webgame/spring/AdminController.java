package com.github.cocosoys.mc.webgame.spring;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.DeleteMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.PostMapping;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestBody;
import com.github.cocosoys.mc.soyshttpovermc.annotations.RequestParam;
import com.github.cocosoys.mc.soyshttpovermc.web.ApiRequestContext;
import com.github.cocosoys.mc.webgame.web.admin.AdminExecutor;
import org.bukkit.entity.Player;

/**
 * WebGame 管理员控制台后端（SOYS 主插件代理注册 → 非豁免路径 /api/admin/*）。
 *
 * <p><b>鉴权三层</b>：</p>
 * <ol>
 *   <li><b>SOYS 网关</b>：/api/admin/* 不在 auth.yml exempt 白名单内，
 *       未携带有效凭证 → 401（X-API-Key / Bearer / Cookie 均由网关校验）；</li>
 *   <li><b>SOYS 权限层</b>：@ApiPublic 声明端点可被访问（避免"默认拒绝"），
 *       但鉴权仍由第 1 层网关强制；</li>
 *   <li><b>仅 OP</b>（本控制器）：操作者必须是<b>当前在线</b>的 OP
 *       （getSyncPlayer 实时解析 + isOp），非 OP / 不在线 → 403。</li>
 * </ol>
 *
 * <p><b>能力</b>：终端（指令转发执行面）、文件管理（模板容器文件
 * 列表/上传/删除）、系统设置（切换 Java）。执行面已从 WSL/Xvfb 移除，
 * 当前各能力返回「执行面未就绪」，待 GFN 方案重建执行通道。</p>
 */
public final class AdminController {

    private final AdminExecutor executor;
    private final com.github.cocosoys.mc.webgame.config.WebGameConfig config;
    private final com.github.cocosoys.mc.webgame.web.cloud.CloudSessionManager cloudManager;

    public AdminController(AdminExecutor executor,
                           com.github.cocosoys.mc.webgame.config.WebGameConfig config,
                           com.github.cocosoys.mc.webgame.web.cloud.CloudSessionManager cloudManager) {
        this.executor = executor;
        this.config = config;
        this.cloudManager = cloudManager;
    }

    // ================= 鉴权辅助 =================

    /**
     * 校验当前请求是否来自在线 OP。
     *
     * <p>安全口径：服务器为免密码登录模式（login-provider 为空），任意玩家名
     * 可被签发令牌，故收紧为<b>在线玩家 + isOp</b>：攻击者无法同时以同名
     * 在线进服，显著降低免密码被冒用 OP 名的风险。</p>
     *
     * @return null=通过；非 null=拒绝原因（已构造 403 响应体）
     */
    private String denyIfNotOp(ApiRequestContext ctx) {
        String name = ctx == null ? null : ctx.getPlayerName();
        if (name == null || name.isEmpty()) {
            return "{\"code\":403,\"msg\":\"未认证或凭证无效，无法识别操作者\",\"data\":null}";
        }
        Player online = ctx.getSyncPlayer();
        if (online == null) {
            return "{\"code\":403,\"msg\":\"仅限在线 OP 操作：当前凭证玩家 " + esc(name)
                    + " 不在服务器内\",\"data\":null}";
        }
        if (!online.isOp()) {
            return "{\"code\":403,\"msg\":\"无权限：仅 OP 可进入管理员控制台（当前: " + esc(name) + "）\",\"data\":null}";
        }
        return null;
    }

    // ================= 状态 =================

    @ApiPublic
    @GetMapping(value = "/admin/status", path = "/admin/status")
    @ApiName("管理员控制台状态")
    public String status(ApiRequestContext ctx) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String javaHome = executor.readJavaConf();
        String[] mc = executor.mcVersions();
        int used = cloudManager != null ? cloudManager.usedInstances() : 0;
        int max = config != null ? config.computeMaxInstances() : 0;
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"operator\":\"").append(esc(ctx.getPlayerName()))
                .append("\",\"instances\":").append(used)
                .append(",\"usedCapacity\":").append(used)
                .append(",\"maxCapacity\":").append(max)
                .append(",\"javaHome\":").append(javaHome == null ? "null" : "\"" + esc(javaHome) + "\"")
                .append(",\"javaVersion\":\"").append(esc(executor.javaVersion(javaHome)))
                .append("\",\"mcVersion\":\"").append(esc(mc[0]))
                .append("\",\"forgeVersion\":\"").append(esc(mc[1]))
                .append("\",\"mcSize\":\"").append(esc(executor.mcSize()))
                .append("\",\"ok\":true}");
        return sb.toString();
    }

    // ================= 容器状态 =================

    /**
     * 容器状态详情（管理员）：执行面连接 + 机器资源（内存/磁盘/负载/进程）
     * + 实例列表（会话参数 + 实例黑盒 + 采集进程）。
     *
     * <p>实例采集进程匹配由执行面快照提供（管控通道 STATUS_RPT）。</p>
     */
    @ApiPublic
    @GetMapping(value = "/admin/containers", path = "/admin/containers")
    @ApiName("管理员容器状态")
    public String containers(ApiRequestContext ctx) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        boolean cc = cloudManager != null && cloudManager.isControlConnected();
        if (cloudManager != null) {
            cloudManager.requestStatus();
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        String snap = executor.containerSnapshot();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"code\":200,\"msg\":\"ok\",\"data\":{")
                .append("\"controlConnected\":").append(cc)
                .append(",\"snapshot\":").append(snap)
                .append(",\"instances\":[");
        boolean first = true;
        if (cloudManager != null) {
            for (com.github.cocosoys.mc.webgame.web.cloud.CloudSession s : cloudManager.allSessions()) {
                com.github.cocosoys.mc.webgame.control.InstanceHandle h = s.getInstance();
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"id\":\"").append(esc(s.getId()))
                        .append("\",\"user\":\"").append(esc(s.getUsername()))
                        .append("\",\"deviceIp\":\"").append(esc(s.getDeviceIp()))
                        .append("\",\"cores\":").append(s.getCores())
                        .append(",\"xmx\":\"").append(esc(s.getXmx()))
                        .append("\",\"fps\":").append(s.getFps())
                        .append(",\"profileId\":\"").append(esc(s.getProfileId()))
                        .append("\",\"clientId\":\"").append(esc(s.getClientId()))
                        .append("\",\"closed\":").append(s.isClosed())
                        .append(",\"lastActive\":").append(s.getLastActivity());
                if (h != null) {
                    sb.append(",\"instanceId\":\"").append(esc(h.getInstanceId()))
                            .append("\",\"state\":\"").append(esc(h.getState()))
                            .append("\",\"display\":\"").append(esc(h.getDisplay()))
                            .append(",\"windowTitle\":\"").append(esc(h.getWindowTitle()))
                            .append("\",\"createdAt\":").append(h.getCreatedAt())
                            .append(",\"readyAt\":").append(h.getReadyAt());
                }
                sb.append("}");
            }
        }
        sb.append("]}}");
        return sb.toString();
    }

    /**
     * 踢出/重启实例：按用户名释放云游戏会话（KILL 实例 + 释放设备名额）。
     * 前端"重启实例"语义 = 踢出并释放，用户重连后由执行面重建全新实例。
     */
    @ApiPublic
    @PostMapping(value = "/admin/containers/kick", path = "/admin/containers/kick")
    @ApiName("管理员踢出/重启实例")
    public String containerKick(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String user = jsonField(body, "user");
        String reason = jsonField(body, "reason");
        if (user == null || user.trim().isEmpty()) {
            return "{\"code\":400,\"msg\":\"缺少 user 参数\",\"data\":null}";
        }
        boolean kicked = cloudManager != null && cloudManager.kickUser(user.trim(), reason);
        return kicked
                ? "{\"code\":200,\"msg\":\"ok\",\"data\":{\"kicked\":\"" + esc(user.trim()) + "\"}}"
                : "{\"code\":404,\"msg\":\"未找到该用户的活跃云会话\",\"data\":null}";
    }

    /**
     * 重启执行面守护进程：杀守护 + 幂等自启（不杀实例）。
     * 插件管控连接由 ControlClient 自动重连。
     */
    @ApiPublic
    @PostMapping(value = "/admin/exec-side/restart", path = "/admin/exec-side/restart")
    @ApiName("管理员重启执行面守护")
    public String execSideRestart(ApiRequestContext ctx) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        return executor.restartControlClient();
    }

    // ================= 终端 =================

    @ApiPublic
    @PostMapping(value = "/admin/exec", path = "/admin/exec")
    @ApiName("管理员终端执行")
    public String exec(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String cmd = parseCmd(body);
        if (cmd == null) {
            return "{\"code\":400,\"msg\":\"缺少 cmd 参数\",\"data\":null}";
        }
        AdminExecutor.Result r = executor.exec(cmd);
        return "{\"code\":200,\"msg\":\"ok\",\"data\":{\"output\":" + escJson(r.output)
                + ",\"exitCode\":" + r.exitCode + ",\"timedOut\":" + r.timedOut + "}}";
    }

    // ================= 文件管理 =================

    @ApiPublic
    @GetMapping(value = "/admin/fs", path = "/admin/fs")
    @ApiName("管理员文件列表")
    public String fsList(ApiRequestContext ctx,
                         @RequestParam(name = "path", required = false) String path) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        return "{\"code\":200,\"msg\":\"ok\",\"data\":" + executor.listDir(path) + "}";
    }

    @ApiPublic
    @DeleteMapping(value = "/admin/fs", path = "/admin/fs")
    @ApiName("管理员文件删除")
    public String fsDelete(ApiRequestContext ctx,
                           @RequestParam(name = "path", required = false) String path) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String err = executor.delete(path);
        return err == null
                ? "{\"code\":200,\"msg\":\"ok\",\"data\":{\"deleted\":true}}"
                : "{\"code\":500,\"msg\":" + escJson(err) + ",\"data\":null}";
    }

    @ApiPublic
    @PostMapping(value = "/admin/fs/upload", path = "/admin/fs/upload")
    @ApiName("管理员文件上传")
    public String fsUpload(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String path = jsonField(body, "path");
        String data = jsonField(body, "data");
        if (path == null || data == null) {
            return "{\"code\":400,\"msg\":\"缺少 path/data 参数\",\"data\":null}";
        }
        String err = executor.uploadStdin(path, data);
        return err == null
                ? "{\"code\":200,\"msg\":\"ok\",\"data\":{\"uploaded\":\"" + esc(path) + "\"}}"
                : "{\"code\":500,\"msg\":" + escJson(err) + ",\"data\":null}";
    }

    @ApiPublic
    @GetMapping(value = "/admin/fs/read", path = "/admin/fs/read")
    @ApiName("管理员文件读取(记事本)")
    public String fsRead(ApiRequestContext ctx,
                         @RequestParam(name = "path", required = false) String path) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String[] res = executor.readText(path);
        if (res[0] != null) {
            return "{\"code\":500,\"msg\":" + escJson(res[0]) + ",\"data\":null}";
        }
        return "{\"code\":200,\"msg\":\"ok\",\"data\":{\"content\":" + escJson(res[1]) + "}}";
    }

    @ApiPublic
    @GetMapping(value = "/admin/fs/download", path = "/admin/fs/download")
    @ApiName("管理员文件下载")
    public String fsDownload(ApiRequestContext ctx,
                             @RequestParam(name = "path", required = false) String path) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String[] res = executor.downloadB64(path);
        if (res[0] != null) {
            return "{\"code\":500,\"msg\":" + escJson(res[0]) + ",\"data\":null}";
        }
        return "{\"code\":200,\"msg\":\"ok\",\"data\":{\"name\":" + escJson(res[2])
                + ",\"size\":" + res[3] + ",\"b64\":" + escJson(res[1]) + "}}";
    }

    @ApiPublic
    @PostMapping(value = "/admin/fs/create", path = "/admin/fs/create")
    @ApiName("管理员新建目录/文件")
    public String fsCreate(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String path = jsonField(body, "path");
        String type = jsonField(body, "type");
        if (path == null || type == null) {
            return "{\"code\":400,\"msg\":\"缺少 path/type 参数\",\"data\":null}";
        }
        String err = executor.create(path, "dir".equalsIgnoreCase(type));
        return err == null
                ? "{\"code\":200,\"msg\":\"ok\",\"data\":{\"created\":\"" + esc(path) + "\"}}"
                : "{\"code\":500,\"msg\":" + escJson(err) + ",\"data\":null}";
    }

    @ApiPublic
    @PostMapping(value = "/admin/fs/rename", path = "/admin/fs/rename")
    @ApiName("管理员文件重命名")
    public String fsRename(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String from = jsonField(body, "from");
        String to = jsonField(body, "to");
        if (from == null || to == null) {
            return "{\"code\":400,\"msg\":\"缺少 from/to 参数\",\"data\":null}";
        }
        String err = executor.rename(from, to);
        return err == null
                ? "{\"code\":200,\"msg\":\"ok\",\"data\":{\"renamed\":\"" + esc(from) + "\"}}"
                : "{\"code\":500,\"msg\":" + escJson(err) + ",\"data\":null}";
    }

    // ================= 系统设置：Java 切换 =================

    @ApiPublic
    @PostMapping(value = "/admin/java", path = "/admin/java")
    @ApiName("管理员切换 Java 版本")
    public String setJava(ApiRequestContext ctx, @RequestBody String body) {
        String deny = denyIfNotOp(ctx);
        if (deny != null) {
            return deny;
        }
        String javaHome = jsonField(body, "javaHome");
        if (javaHome == null) {
            return "{\"code\":400,\"msg\":\"缺少 javaHome 参数\",\"data\":null}";
        }
        String err = executor.writeJavaConf(javaHome);
        if (err != null) {
            return "{\"code\":500,\"msg\":" + escJson(err) + ",\"data\":null}";
        }
        String v = executor.javaVersion(javaHome);
        return "{\"code\":200,\"msg\":\"ok\",\"data\":{\"javaHome\":\"" + esc(javaHome)
                + "\",\"javaVersion\":\"" + esc(v) + "\"}}";
    }

    // ================= 工具 =================

    private static String parseCmd(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        return jsonField(body, "cmd");
    }

    private static String jsonField(String json, String field) {
        if (json == null) {
            return null;
        }
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        char c = json.charAt(start);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            boolean esc = false;
            for (int k = start + 1; k < json.length(); k++) {
                char ch = json.charAt(k);
                if (esc) {
                    if (ch == 'n') {
                        sb.append('\n');
                    } else if (ch == 't') {
                        sb.append('\t');
                    } else if (ch == 'r') {
                        sb.append('\r');
                    } else if (ch == 'u' && k + 4 < json.length()) {
                        try {
                            sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                            k += 4;
                        } catch (NumberFormatException ignored) {
                            sb.append('u');
                        }
                    } else {
                        sb.append(ch);
                    }
                    esc = false;
                } else if (ch == '\\') {
                    esc = true;
                } else if (ch == '"') {
                    return sb.toString();
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        if (c == 'n' && json.startsWith("null", start)) {
            return null;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
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
