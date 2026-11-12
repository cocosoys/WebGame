package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameStatusVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameStatusService;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;
import org.bukkit.Bukkit;

import java.util.List;

/**
 * 运行状态实现：全部取自运行期对象（插件实例 + 网关 + Bukkit），不读数据库。
 */
public class WebGameStatusServiceImpl implements WebGameStatusService {

    private final WebGame plugin;

    public WebGameStatusServiceImpl(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public AjaxResult overview() {
        try {
            WebGameStatusVo vo = new WebGameStatusVo();
            WebGameConfig cfg = plugin.getWebGameConfig();
            WsGateway gateway = plugin.getWsGateway();
            int serverPort = plugin.getServer().getPort();
            int publicPort = cfg.getPublicPort(serverPort);

            vo.setEnabled(plugin.isEnabled());
            vo.setUptimeSeconds(plugin.getUptimeSeconds());
            vo.setPluginVersion(plugin.getDescription().getVersion());
            vo.setServerPort(serverPort);
            vo.setPublicHost(cfg.getPublicHost());
            vo.setPublicPort(publicPort);
            vo.setPageUrl(cfg.getPageFullUrl());
            vo.setOnlinePlayers(Bukkit.getOnlinePlayers().size());

            if (gateway != null) {
                vo.setWsPath(gateway.getWsPath());
                vo.setMaxConnectionsPerDevice(gateway.getMaxConnectionsPerDevice());
                List<WsFrameHandler> sessions = gateway.snapshotSessions();
                vo.setSessionCount(sessions.size());
                int playing = 0;
                for (WsFrameHandler h : sessions) {
                    if (h.isPlaying()) {
                        playing++;
                    }
                }
                vo.setPlayingCount(playing);
            } else {
                vo.setWsPath(cfg.getWsPath());
                vo.setMaxConnectionsPerDevice(cfg.getMaxConnectionsPerDevice());
            }
            vo.setWsUrl("ws://" + cfg.getPublicHost() + ":" + publicPort + vo.getWsPath());
            return AjaxResult.success(vo);
        } catch (Throwable t) {
            return AjaxResult.error("运行状态查询失败：" + t.getMessage());
        }
    }
}
