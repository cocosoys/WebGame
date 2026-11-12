package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.TableDataInfo;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameSessionVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;

import java.util.ArrayList;
import java.util.List;

/**
 * WS 会话查询实现：读取 {@link WsGateway} 的内存会话表，只做只读快照。
 */
public class WebGameSessionServiceImpl implements WebGameSessionService {

    private final WebGame plugin;

    public WebGameSessionServiceImpl(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public AjaxResult list() {
        try {
            WsGateway gateway = plugin.getWsGateway();
            List<WebGameSessionVo> rows = new ArrayList<>();
            if (gateway != null) {
                long now = System.currentTimeMillis();
                for (WsFrameHandler h : gateway.snapshotSessions()) {
                    WebGameSessionVo vo = new WebGameSessionVo();
                    vo.setSessionId(h.getSessionId());
                    vo.setUsername(h.getUsername() == null ? "" : h.getUsername());
                    vo.setUuid(h.getOfflineUuid() == null ? "" : h.getOfflineUuid().toString());
                    vo.setIp(h.getDeviceIp());
                    vo.setProtocolVersion(h.getClientProtocolVersion());
                    vo.setPlaying(h.isPlaying());
                    vo.setConnectedAt(h.getConnectedAtMillis());
                    long since = h.getConnectedAtMillis() > 0
                            ? Math.max(0, (now - h.getConnectedAtMillis()) / 1000L) : 0;
                    vo.setDurationSeconds(since);
                    rows.add(vo);
                }
            }
            return TableDataInfo.success(rows, rows.size());
        } catch (Throwable t) {
            return TableDataInfo.error("会话查询失败：" + t.getMessage());
        }
    }
}
