package com.github.cocosoys.mc.webgame.web.admin;

import com.github.cocosoys.mc.soyshttpovermc.HttpOverMcPlugin;
import com.github.cocosoys.mc.soyshttpovermc.command.SubCommand;
import com.github.cocosoys.mc.webgame.control.InstanceHandle;
import com.github.cocosoys.mc.webgame.web.cloud.BanStore;
import com.github.cocosoys.mc.webgame.web.cloud.CloudSession;
import com.github.cocosoys.mc.webgame.web.cloud.CloudSessionManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * WebGame 云游戏容器管理指令（仅 OP，经 /soyshttp 注册）：
 * <ul>
 *   <li>{@code /soyshttp cloudstatus} —— 检测容器机器（执行面/会话/实例）运行状态；</li>
 *   <li>{@code /soyshttp cloudkick <user> [reason]} —— 踢出指定用户的云游戏会话；</li>
 *   <li>{@code /soyshttp cloudban <user> [reason]} —— 封禁用户（云游戏 + 同步服务器 BanList）；</li>
 *   <li>{@code /soyshttp cloudunban <user>} —— 解封用户。</li>
 * </ul>
 *
 * <p>云游戏未启用（cloudManager 为 null）时指令提示不可用。所有指令 requireOp 默认 true。</p>
 */
public final class CloudAdminSubCommands {

    private static final String NO_CLOUD = "WebGame 云游戏通道未启用（config.yml cloud.enabled=false）";

    private CloudAdminSubCommands() {
    }

    /** 状态指令：容器机器（执行面/会话/实例/容量/封禁）运行状态检测。 */
    public static final class CloudStatusCommand extends SubCommand {

        private final CloudSessionManager manager;

        public CloudStatusCommand(HttpOverMcPlugin plugin, CloudSessionManager manager) {
            super(plugin);
            this.manager = manager;
        }

        @Override
        public String name() {
            return "cloudstatus";
        }

        @Override
        public String usage() {
            return "/soyshttp cloudstatus —— 检测云游戏容器机器运行状态（执行面/会话/实例/封禁，仅 OP）";
        }

        @Override
        public String detail() {
            return "/soyshttp cloudstatus\n"
                    + "  输出 WebGame 云游戏容器运行状态：\n"
                    + "  · 执行面控制客户端连接状态\n"
                    + "  · 会话数 / 全局容量上限\n"
                    + "  · 每个实例：实例 id、用户、状态、KasmVNC 采集端点、设备型号、运行时长\n"
                    + "  · 封禁列表（用户名/原因/时间）";
        }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (manager == null) {
                msg(sender, NO_CLOUD);
                return;
            }
            sendColored(sender, ChatColor.AQUA + "=== WebGame 云游戏容器状态 ===");
            // 执行面连接 + 容量
            String conn = manager.isControlConnected()
                    ? ChatColor.GREEN + "已连接" : ChatColor.RED + "未连接";
            int used = manager.usedInstances();
            int max = manager.configOf().computeMaxInstances();
            sendColored(sender, "执行面: " + conn + ChatColor.RESET
                    + "  |  会话 " + used + "/" + max
                    + "  |  封禁 " + manager.getBanStore().list().size() + " 人");

            // 实例与会话明细
            List<CloudSession> sessions = manager.allSessions();
            if (sessions.isEmpty()) {
                sendColored(sender, "（无活跃云游戏会话）");
            } else {
                for (CloudSession s : sessions) {
                    if (s.isClosed()) {
                        continue;
                    }
                    sendColored(sender, describeSession(s));
                }
            }

            // 封禁列表
            List<BanStore.Ban> bans = manager.getBanStore().list();
            if (!bans.isEmpty()) {
                sendColored(sender, ChatColor.YELLOW + "--- 封禁列表 ---");
                SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
                for (BanStore.Ban b : bans) {
                    sendColored(sender, ChatColor.YELLOW + "  " + b.getUsername()
                            + (b.getReason().isEmpty() ? "" : ChatColor.RESET + "（" + b.getReason() + "）")
                            + ChatColor.GRAY + " @" + fmt.format(new Date(b.getAt())));
                }
            }
        }
    }

    /** 踢出指令：踢出指定用户的云游戏会话（+ MC 在线同名玩家）。 */
    public static final class CloudKickCommand extends SubCommand {

        private final CloudSessionManager manager;

        public CloudKickCommand(HttpOverMcPlugin plugin, CloudSessionManager manager) {
            super(plugin);
            this.manager = manager;
        }

        @Override
        public String name() {
            return "cloudkick";
        }

        @Override
        public String usage() {
            return "/soyshttp cloudkick <用户名> [原因] —— 踢出该用户的云游戏会话（仅 OP）";
        }

        @Override
        public String detail() {
            return "/soyshttp cloudkick <用户名> [原因]\n"
                    + "  踢出指定用户的云游戏会话：关闭会话并销毁容器实例（释放设备名额）。\n"
                    + "  若该用户名同时在线（Eagler 进服），也会一并踢出 MC 客户端。\n"
                    + "  例：/soyshttp cloudkick Alice 违规挂机";
        }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (manager == null) {
                msg(sender, NO_CLOUD);
                return;
            }
            if (args.length < 2) {
                msg(sender, "用法: /soyshttp cloudkick <用户名> [原因]");
                return;
            }
            String user = args[1];
            String reason = joinArgs(args, 2);
            boolean kickedCloud = manager.kickUser(user, reason);

            // 同步踢 MC 在线同名玩家（Eagler 通道）
            boolean kickedMc = false;
            org.bukkit.entity.Player p = Bukkit.getPlayerExact(user);
            if (p == null) {
                for (org.bukkit.entity.Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().equalsIgnoreCase(user)) {
                        p = online;
                        break;
                    }
                }
            }
            if (p != null) {
                p.kickPlayer(reason == null || reason.isEmpty()
                        ? "你已被管理员踢出" : "你已被踢出：" + reason);
                kickedMc = true;
            }
            if (kickedCloud || kickedMc) {
                msg(sender, "已踢出 " + user
                        + (kickedCloud ? "（云游戏会话）" : "")
                        + (kickedMc ? "（MC 在线）" : ""));
            } else {
                msg(sender, user + " 没有活跃的云游戏会话或 MC 在线会话");
            }
        }
    }

    /** 封禁指令：WebGame 封禁库 + 踢会话 + 同步 Bukkit BanList（拦 Eagler）。 */
    public static final class CloudBanCommand extends SubCommand {

        private final CloudSessionManager manager;

        public CloudBanCommand(HttpOverMcPlugin plugin, CloudSessionManager manager) {
            super(plugin);
            this.manager = manager;
        }

        @Override
        public String name() {
            return "cloudban";
        }

        @Override
        public String usage() {
            return "/soyshttp cloudban <用户名> [原因] —— 封禁该用户进入云游戏（仅 OP）";
        }

        @Override
        public String detail() {
            return "/soyshttp cloudban <用户名> [原因]\n"
                    + "  封禁用户：写入 WebGame 封禁库（持久化 bans.yml）+ 立即踢出该用户云游戏会话\n"
                    + "  + 同步服务器 BanList（Eagler 同名进服也会被拒绝）。\n"
                    + "  解封请用 /soyshttp cloudunban <用户名>。\n"
                    + "  例：/soyshttp cloudban Alice 恶意刷屏";
        }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (manager == null) {
                msg(sender, NO_CLOUD);
                return;
            }
            if (args.length < 2) {
                msg(sender, "用法: /soyshttp cloudban <用户名> [原因]");
                return;
            }
            String user = args[1];
            String reason = joinArgs(args, 2);
            if (manager.isBanned(user)) {
                BanStore.Ban old = manager.getBanStore().getBan(user);
                msg(sender, user + " 已在封禁名单中"
                        + (old == null || old.getReason().isEmpty() ? "" : "（原因：" + old.getReason() + "）")
                        + "，如需修改请先解封后重新封禁");
                return;
            }
            manager.ban(user, reason);
            msg(sender, ChatColor.RED + "已封禁 " + user
                    + (reason == null || reason.isEmpty() ? "" : "（原因：" + reason + "）")
                    + "，其云游戏会话已踢出，Eagler 同名进服也将被拒绝");
        }
    }

    /** 解封指令：移除 WebGame 封禁库记录 + 解除 Bukkit BanList。 */
    public static final class CloudUnbanCommand extends SubCommand {

        private final CloudSessionManager manager;

        public CloudUnbanCommand(HttpOverMcPlugin plugin, CloudSessionManager manager) {
            super(plugin);
            this.manager = manager;
        }

        @Override
        public String name() {
            return "cloudunban";
        }

        @Override
        public String usage() {
            return "/soyshttp cloudunban <用户名> —— 解除该用户的云游戏封禁（仅 OP）";
        }

        @Override
        public String detail() {
            return "/soyshttp cloudunban <用户名>\n"
                    + "  解除用户封禁：移除 WebGame 封禁库记录 + 解除服务器 BanList，\n"
                    + "  该用户可重新进入云游戏 / Eagler。";
        }

        @Override
        public void execute(CommandSender sender, String label, String[] args) {
            if (manager == null) {
                msg(sender, NO_CLOUD);
                return;
            }
            if (args.length < 2) {
                msg(sender, "用法: /soyshttp cloudunban <用户名>");
                return;
            }
            String user = args[1];
            boolean removed = manager.unban(user);
            if (removed) {
                msg(sender, ChatColor.GREEN + "已解封 " + user + "，可重新进入云游戏 / Eagler");
            } else {
                msg(sender, user + " 没有 WebGame 云游戏封禁记录"
                        + (Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(user)
                        ? "（但存在服务器 BanList 记录，已一并尝试解除）" : ""));
            }
        }
    }

    // ===== 工具 =====

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    /** 会话 + 实例明细行（状态着色，控制台自动剥色）。 */
    private static String describeSession(CloudSession s) {
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GRAY).append("会话 ").append(s.getId()).append(ChatColor.RESET)
                .append(" user=").append(ChatColor.WHITE).append(s.getUsername()).append(ChatColor.RESET)
                .append(" ip=").append(s.getDeviceIp())
                .append(" 型号=").append(s.getProfileId().isEmpty() ? "default" : s.getProfileId())
                .append(" ").append(s.getCores()).append("核").append(s.getXmx());
        InstanceHandle h = s.getInstance();
        if (h != null) {
            sb.append("  |  实例 ").append(h.getInstanceId())
                    .append(" state=").append(stateColor(h.getState())).append(h.getState()).append(ChatColor.RESET);
            if (h.isReady()) {
                sb.append(" kasm=:").append(h.getKasmPort())
                        .append(" display=").append(h.getDisplay())
                        .append(" ").append(fmtDur(System.currentTimeMillis() - h.getReadyAt()));
            }
        }
        if (s.isDetached()) {
            sb.append(ChatColor.YELLOW).append(" [悬挂中]").append(ChatColor.RESET);
        }
        return sb.toString();
    }

    private static String stateColor(String state) {
        if ("ready".equals(state)) {
            return ChatColor.GREEN + "";
        }
        if ("failed".equals(state) || "stopped".equals(state)) {
            return ChatColor.RED + "";
        }
        return ChatColor.YELLOW + "";
    }

    private static String fmtDur(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) {
            return sec + "s";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + "m" + (sec % 60) + "s";
        }
        return (min / 60) + "h" + (min % 60) + "m";
    }
}
