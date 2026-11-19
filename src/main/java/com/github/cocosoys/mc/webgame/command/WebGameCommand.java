package com.github.cocosoys.mc.webgame.command;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.service.CloudClientService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code /webgame cloud ...} —— 云游戏客户端运行态查看/刷新/密钥登记。
 *
 * <p>HTTP 探测会阻塞数秒，因此 refresh/key 一律扔到异步线程；结果再切回主线程
 * 回话（Bukkit 的 {@code sendMessage} 不保证异步线程安全）。</p>
 */
public final class WebGameCommand implements CommandExecutor, TabCompleter {

    private final WebGame plugin;

    public WebGameCommand(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !"cloud".equalsIgnoreCase(args[0])) {
            usage(sender, label);
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("status".equals(sub)) {
            status(sender);
            return true;
        }
        if ("refresh".equals(sub)) {
            refresh(sender, args.length >= 3 ? args[2] : "all");
            return true;
        }
        if ("key".equals(sub)) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "用法：/" + label + " cloud key <id> <密钥>");
                return true;
            }
            applyKey(sender, args[2], args[3]);
            return true;
        }
        usage(sender, label);
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " cloud status");
        sender.sendMessage(ChatColor.YELLOW + "      /" + label + " cloud refresh <id|all>");
        sender.sendMessage(ChatColor.YELLOW + "      /" + label + " cloud key <id> <密钥>");
    }

    private void status(CommandSender sender) {
        CloudClientService service = plugin.getCloudClientService();
        if (service == null) {
            sender.sendMessage(ChatColor.RED + "云游戏客户端服务未初始化");
            return;
        }
        List<CloudClient> list = service.versions();
        if (list.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "还没有登记任何云游戏客户端");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "云游戏客户端（" + list.size() + "）：");
        for (CloudClient c : list) {
            String addr = c.address().isEmpty() ? "未配置地址" : c.address();
            String status = colorOf(c.getStatus()) + c.getStatus() + ChatColor.RESET;
            StringBuilder line = new StringBuilder()
                    .append(ChatColor.GRAY).append('#').append(c.getId()).append(' ')
                    .append(ChatColor.WHITE).append(c.getName())
                    .append(ChatColor.DARK_GRAY).append(" · ")
                    .append(ChatColor.AQUA).append(addr)
                    .append(ChatColor.DARK_GRAY).append(" · ")
                    .append(status);
            if (!c.getVersion().isEmpty()) {
                line.append(ChatColor.DARK_GRAY).append(" · v").append(c.getVersion());
            }
            if (!c.getLastOk().isEmpty()) {
                line.append(ChatColor.DARK_GRAY).append(" · 最后成功 ").append(c.getLastOk());
            }
            sender.sendMessage(line.toString());
        }
    }

    private void refresh(CommandSender sender, String target) {
        CloudClientService service = plugin.getCloudClientService();
        if (service == null) {
            sender.sendMessage(ChatColor.RED + "云游戏客户端服务未初始化");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "正在刷新" + ("all".equalsIgnoreCase(target) ? "全部设备" : "设备 " + target) + "…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AjaxResult result;
            try {
                result = "all".equalsIgnoreCase(target) ? service.refreshAll() : service.refresh(target);
            } catch (Throwable t) {
                result = AjaxResult.error("刷新失败：" + t.getMessage());
            }
            reply(sender, result);
        });
    }

    private void applyKey(CommandSender sender, String id, String key) {
        CloudClientService service = plugin.getCloudClientService();
        if (service == null) {
            sender.sendMessage(ChatColor.RED + "云游戏客户端服务未初始化");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "正在验证密钥并更新设备 " + id + "…");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AjaxResult result;
            try {
                result = service.applyKey(id, key);
            } catch (Throwable t) {
                result = AjaxResult.error("更新密钥失败：" + t.getMessage());
            }
            reply(sender, result);
        });
    }

    /** 异步结果切回主线程回话。 */
    private void reply(CommandSender sender, AjaxResult result) {
        Object code = result == null ? null : result.get("code");
        Object msg = result == null ? null : result.get("msg");
        boolean ok = code instanceof Number && ((Number) code).intValue() == AjaxResult.SUCCESS;
        String text = msg == null ? (ok ? "完成" : "失败") : String.valueOf(msg);
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                (ok ? ChatColor.GREEN : ChatColor.RED) + text));
    }

    private static String colorOf(String status) {
        if (CloudClient.STATUS_ONLINE.equals(status)) {
            return ChatColor.GREEN.toString();
        }
        if (CloudClient.STATUS_OFFLINE.equals(status)) {
            return ChatColor.RED.toString();
        }
        if (CloudClient.STATUS_UNAUTHORIZED.equals(status)) {
            return ChatColor.LIGHT_PURPLE.toString();
        }
        return ChatColor.YELLOW.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        CloudClientService service = plugin.getCloudClientService();
        if (args.length == 1) {
            return filter(Arrays.asList("cloud"), args[0]);
        }
        if (args.length == 2) {
            return filter(Arrays.asList("status", "refresh", "key"), args[1]);
        }
        if (args.length == 3 && "refresh".equalsIgnoreCase(args[1])) {
            List<String> ids = new ArrayList<>();
            ids.add("all");
            if (service != null) {
                for (CloudClient c : service.versions()) {
                    ids.add(c.getId());
                }
            }
            return filter(ids, args[2]);
        }
        if (args.length == 3 && "key".equalsIgnoreCase(args[1]) && service != null) {
            List<String> ids = new ArrayList<>();
            for (CloudClient c : service.versions()) {
                ids.add(c.getId());
            }
            return filter(ids, args[2]);
        }
        return java.util.Collections.emptyList();
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(c);
            }
        }
        return out;
    }
}
