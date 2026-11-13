package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.TableDataInfo;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameSessionVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;
import com.github.cocosoys.mc.webgame.web.cloud.CloudSessionRegistry;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话查询实现：eagler 读取 {@link WsGateway} 内存会话，云游戏读取
 * {@link CloudSessionRegistry} 的心跳会话，两者合并后按连接时间倒序。
 */
public class WebGameSessionServiceImpl implements WebGameSessionService {

    private final WebGame plugin;

    public WebGameSessionServiceImpl(WebGame plugin) {
        this.plugin = plugin;
    }

    @Override
    public AjaxResult list() {
        try {
            long now = System.currentTimeMillis();
            List<WebGameSessionVo> rows = new ArrayList<>();
            Map<String, String> clientNames = clientNames();

            WsGateway gateway = plugin.getWsGateway();
            if (gateway != null) {
                for (WsFrameHandler h : gateway.snapshotSessions()) {
                    WebGameSessionVo vo = new WebGameSessionVo();
                    vo.setSessionId(h.getSessionId());
                    vo.setUsername(h.getUsername() == null ? "" : h.getUsername());
                    vo.setUuid(h.getOfflineUuid() == null ? "" : h.getOfflineUuid().toString());
                    vo.setIp(h.getDeviceIp());
                    vo.setClientId(h.getClientId());
                    vo.setClientType("eagler");
                    vo.setClientName(clientNames.getOrDefault(h.getClientId(), ""));
                    vo.setProtocolVersion(h.getClientProtocolVersion());
                    vo.setPlaying(h.isPlaying());
                    vo.setConnectedAt(h.getConnectedAtMillis());
                    long since = h.getConnectedAtMillis() > 0
                            ? Math.max(0, (now - h.getConnectedAtMillis()) / 1000L) : 0;
                    vo.setDurationSeconds(since);
                    rows.add(vo);
                }
            }

            CloudSessionRegistry registry = plugin.getCloudSessionRegistry();
            if (registry != null) {
                for (CloudSessionRegistry.Session s : registry.snapshot()) {
                    WebGameSessionVo vo = new WebGameSessionVo();
                    vo.setSessionId(s.getSessionId());
                    vo.setUsername(s.getUsername());
                    vo.setUuid(s.getUuid());
                    vo.setIp(s.getIp());
                    vo.setClientId(s.getClientId());
                    vo.setClientType("cloud");
                    vo.setClientName(s.getClientName());
                    vo.setProtocolVersion(0);
                    vo.setPlaying(true);
                    vo.setConnectedAt(s.getConnectedAt());
                    vo.setDurationSeconds(s.getConnectedAt() > 0
                            ? Math.max(0, (now - s.getConnectedAt()) / 1000L) : 0);
                    rows.add(vo);
                }
            }

            rows.sort((a, b) -> Long.compare(b.getConnectedAt(), a.getConnectedAt()));
            return TableDataInfo.success(rows, rows.size());
        } catch (Throwable t) {
            return TableDataInfo.error("会话查询失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult kick(String sessionId) {
        try {
            WsGateway gateway = plugin.getWsGateway();
            if (gateway != null && gateway.closeSession(sessionId)) {
                return AjaxResult.success("已强制踢出该会话", null);
            }
            CloudSessionRegistry registry = plugin.getCloudSessionRegistry();
            if (registry != null && registry.remove(sessionId)) {
                return AjaxResult.success("已结束该云游戏会话", null);
            }
            return AjaxResult.error("会话不存在或已结束");
        } catch (Throwable t) {
            return AjaxResult.error("踢出失败：" + t.getMessage());
        }
    }

    @Override
    public AjaxResult heartbeat(Map<String, Object> body, String ip) {
        try {
            String clientId = str(body == null ? null : body.get("clientId")).trim();
            if (clientId.isEmpty()) {
                return AjaxResult.error("缺少 clientId");
            }
            CloudClient client = findCloudClient(clientId);
            if (client == null) {
                return AjaxResult.error("云游戏客户端不存在: " + clientId);
            }
            if (!client.isEnabled()) {
                return AjaxResult.error("云游戏客户端已关闭: " + clientId);
            }
            CloudSessionRegistry registry = plugin.getCloudSessionRegistry();
            if (registry == null) {
                return AjaxResult.error("云游戏会话未初始化");
            }
            CloudSessionRegistry.Session s = registry.heartbeat(
                    str(body.get("sessionId")),
                    clientId,
                    client.getName(),
                    str(body.get("username")),
                    str(body.get("uuid")),
                    ip);
            Map<String, Object> data = new HashMap<>();
            data.put("sessionId", s.getSessionId());
            data.put("ttl", CloudSessionRegistry.HEARTBEAT_TTL_MILLIS);
            return AjaxResult.success("ok", data);
        } catch (Throwable t) {
            return AjaxResult.error("心跳失败：" + t.getMessage());
        }
    }

    // ===== 辅助 =====

    /** eagler 客户端 id → 展示名称。 */
    private Map<String, String> clientNames() {
        Map<String, String> names = new HashMap<>();
        try {
            if (plugin.getClientService() != null) {
                for (ClientVersion v : plugin.getClientService().versions()) {
                    names.put(v.getId(), v.getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private CloudClient findCloudClient(String clientId) {
        if (plugin.getCloudClientService() == null) {
            return null;
        }
        for (CloudClient c : plugin.getCloudClientService().versions()) {
            if (clientId.equals(c.getId())) {
                return c;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
