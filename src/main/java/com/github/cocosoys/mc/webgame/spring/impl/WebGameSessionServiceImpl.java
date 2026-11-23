package com.github.cocosoys.mc.webgame.spring.impl;

import com.github.cocosoys.mc.soyshttpovermc.util.AjaxResult;
import com.github.cocosoys.mc.soyshttpovermc.util.TableDataInfo;
import com.github.cocosoys.mc.webgame.WebGame;
import com.github.cocosoys.mc.webgame.spring.entity.ClientVersion;
import com.github.cocosoys.mc.webgame.spring.entity.CloudClient;
import com.github.cocosoys.mc.webgame.spring.entity.vo.WebGameSessionVo;
import com.github.cocosoys.mc.webgame.spring.service.WebGameSessionService;
import com.github.cocosoys.mc.webgame.web.cloud.CloudPlayService;
import com.github.cocosoys.mc.webgame.web.ws.WsFrameHandler;
import com.github.cocosoys.mc.webgame.web.ws.WsGateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话查询实现：eagler 读取 {@link WsGateway} 内存会话，云游戏读取
 * {@link CloudPlayService} 的会话表（槽位/阶段/进服判定都只有这一份真相），
 * 两者合并后按开始时间倒序。
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

            CloudPlayService play = plugin.getCloudPlayService();
            if (play != null) {
                for (CloudPlayService.Session s : play.snapshotSessions()) {
                    // 只展示还在跑的：ended 的会话槽位已释放，列表里留着只会误导"机器被占"
                    if (CloudPlayService.Session.STATE_ENDED.equals(s.getState())) {
                        continue;
                    }
                    WebGameSessionVo vo = new WebGameSessionVo();
                    vo.setSessionId(s.getSessionId());
                    vo.setUsername(s.getPlayer());
                    vo.setUuid("");
                    // 云游戏会话的"来源"是云机自己的地址（进服判定用的也是它）
                    vo.setIp(s.getJoinIp().isEmpty() ? clientIpOf(s.getClientId()) : s.getJoinIp());
                    vo.setClientId(s.getClientId());
                    vo.setClientType("cloud");
                    vo.setClientName(cloudClientName(s.getClientId()));
                    vo.setProtocolVersion(0);
                    // playing = 玩家已真的进服；进程起来了但人还没进来，状态列显示"启动中"
                    vo.setPlaying(s.isJoinedConfirmed());
                    vo.setStage(s.getStage());
                    vo.setConnectedAt(s.getCreatedAt());
                    vo.setDurationSeconds(s.getCreatedAt() > 0
                            ? Math.max(0, (now - s.getCreatedAt()) / 1000L) : 0);
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
            // 云游戏会话走真实收尾：关直播前缀 + 让代理 end + 释放槽位，
            // 而不是"删一行记录"（只删记录会让机器一直显示被占用）
            CloudPlayService play = plugin.getCloudPlayService();
            if (play != null) {
                for (CloudPlayService.Session s : play.snapshotSessions()) {
                    if (s.getSessionId().equals(sessionId)
                            && !CloudPlayService.Session.STATE_ENDED.equals(s.getState())) {
                        play.end(sessionId);
                        return AjaxResult.success("已结束该云游戏会话（云机已释放）", null);
                    }
                }
            }
            return AjaxResult.error("会话不存在或已结束");
        } catch (Throwable t) {
            return AjaxResult.error("踢出失败：" + t.getMessage());
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

    /** 云游戏客户端 id → 展示名称（找不到就空串，前端回落到 id）。 */
    private String cloudClientName(String clientId) {
        if (plugin.getCloudClientService() == null) {
            return "";
        }
        for (CloudClient c : plugin.getCloudClientService().versions()) {
            if (clientId.equals(c.getId())) {
                return c.getName();
            }
        }
        return "";
    }

    /** 未进服时的来源 IP：用云机登记的地址，便于运维判断是哪台机器。 */
    private String clientIpOf(String clientId) {
        if (plugin.getCloudClientService() == null) {
            return "";
        }
        for (CloudClient c : plugin.getCloudClientService().versions()) {
            if (clientId.equals(c.getId())) {
                return c.getIp();
            }
        }
        return "";
    }
}
