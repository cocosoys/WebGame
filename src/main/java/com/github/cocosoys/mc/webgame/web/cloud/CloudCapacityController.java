package com.github.cocosoys.mc.webgame.web.cloud;

import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiName;
import com.github.cocosoys.mc.soyshttpovermc.annotations.ApiPublic;
import com.github.cocosoys.mc.soyshttpovermc.annotations.GetMapping;
import com.github.cocosoys.mc.webgame.config.WebGameConfig;

/**
 * 设备选择页容量接口：GET /api/plugins/WebGame/devices/capacity
 *
 * <p>返回 JSON：{ "max": 全局最大可启动实例数（静态 min），"used": 当前已用数，
 * "totalCores": 执行面总核数，"totalMemoryMb": 执行面总内存 MB，"maxInstances": 实例硬上限，
 * "profiles": [ 预置设备型号（每项含本型号静态容量 max）] }。
 * 型号容量口径（用户拍板 2026-09-15）：
 *   型号上限 = min(⌊总核/型号核⌋, ⌊总内存/型号堆(MB)⌋, max-instances)
 *   标准型 2核4G → min(8, 2, 4) = 2；流畅型 1核2G → min(16, 5, 4) = 4</p>
 */
public final class CloudCapacityController {

    private final WebGameConfig config;
    private final CloudSessionManager manager;

    public CloudCapacityController(WebGameConfig config, CloudSessionManager manager) {
        this.config = config;
        this.manager = manager;
    }

    @GetMapping(value = "/devices/capacity", path = "/devices/capacity")
    @ApiName("云游戏设备容量")
    @ApiPublic
    public String capacity() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"max\":").append(config.computeMaxInstances())
                .append(",\"used\":").append(manager.usedInstances())
                .append(",\"totalCores\":").append(config.getCapacityTotalCores())
                .append(",\"totalMemoryMb\":").append(config.getCapacityTotalMemoryMb())
                .append(",\"maxInstances\":").append(config.getCapacityMaxInstances())
                .append(",\"profiles\":[");
        boolean first = true;
        for (WebGameConfig.DeviceProfile p : config.getDeviceProfiles()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(esc(p.getId()))
                    .append("\",\"name\":\"").append(esc(p.getName()))
                    .append("\",\"cpu\":").append(p.getCpuCores())
                    .append(",\"xmx\":\"").append(esc(p.getXmx()))
                    .append("\",\"width\":").append(p.getWidth())
                    .append(",\"height\":").append(p.getHeight())
                    .append(",\"fps\":").append(p.getFps())
                    .append(",\"gfx\":\"").append(esc(p.getGfx()))
                    .append("\",\"enabled\":").append(p.isEnabled())
                    .append(",\"max\":").append(config.computeProfileMax(p))
                    .append(",\"desc\":\"").append(esc(p.getDesc()))
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }
}
