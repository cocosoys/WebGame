package com.github.cocosoys.mc.webgame.web.admin;

import com.github.cocosoys.mc.soyshttpovermc.HttpOverMcPlugin;
import com.github.cocosoys.mc.soyshttpovermc.command.SubCommand;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /soyshttp webadmin —— WebGame 管理员控制台入口引导（仅 OP）。
 *
 * <p>执行后在聊天输出管理员控制台 URL。鉴权本身由 SOYS 网关（/api/admin/* 非豁免
 * 路径自动要求凭证）与 AdminController（OP 校验）完成，本指令仅提供入口发现能力。</p>
 */
public final class AdminSubCommand extends SubCommand {

    public AdminSubCommand(HttpOverMcPlugin plugin) {
        super(plugin);
    }

    @Override
    public String name() {
        return "webadmin";
    }

    @Override
    public String usage() {
        return "/soyshttp webadmin —— 输出 WebGame 管理员控制台入口（仅 OP）";
    }

    @Override
    public String detail() {
        return "/soyshttp webadmin\n"
                + "  在聊天中输出 WebGame 管理员控制台地址。\n"
                + "  打开后浏览器会弹出 SOYS 登录窗口，输入游戏内玩家名完成验证；\n"
                + "  仅 OP 账号可进入终端/文件管理/系统设置。";
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            msg(sender, "仅游戏内玩家可执行此指令（需要 OP）");
            return;
        }
        try {
            int port = plugin.getDelegate().getMcPort();
            String host = plugin.getDelegate().getMcHost();
            String url = "http://" + host + ":" + port + "/api/plugins/WebGame/admin/";
            msg(sender, "WebGame 管理员控制台（仅 OP，请勿外传）：");
            msg(sender, ChatColor.AQUA + "  " + url);
            msg(sender, "打开后按浏览器登录弹窗输入玩家名验证，即可进入终端/文件管理/系统设置。");
        } catch (Throwable t) {
            msg(sender, "获取入口地址失败: " + t.getMessage());
        }
    }
}
