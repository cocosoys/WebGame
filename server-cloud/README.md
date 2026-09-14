# WebGame 云游戏执行面（server-cloud）

本目录沉淀 WebGame「路线 C 云游戏」**全部可部署内容**：执行面（云游戏载体）的
一键部署、一键更新、一键卸载、状态检查、MC 客户端迁移，以及配套的 systemd
单元与 KasmVNC 配置模板。目标是让云游戏执行面**可快速安装到任何 Linux/WSL2
服务器**，而不是一次性为本机定制。

> 浏览器入口在**服务器插件侧**（`/api/plugins/WebGame/cloud/?user=xxx`），
> 本执行面不对外暴露端口；KasmVNC 只监听本机回环，由插件经 25574 反代。

---

## 1. 架构总览

```
┌─ 玩家浏览器 ─────────────────────────────────────────────┐
│  /api/plugins/WebGame/cloud/?user=xxx                     │
│  cloud.html（重连自愈 / 铺满 / 退出）                      │
│  └─ WS 信令(25574) → 插件 CloudSessionManager             │
└───────────────────────────────────────────────────────────┘
                        │
            HTTP/WS 同端口 25574（SOYSHTTPOverMC 反代）
                        │  /kasm/{sid}/{port}/  →  KasmVNC 画面+输入
                        ▼
┌─ Minecraft 服务器（Spigot 1.12.2）───────────────────────┐
│  WebGame 插件                                            │
│  ├─ cloud 包：CloudWsHandler / CloudSessionManager       │
│  └─ ControlClient：TCP 25576 下行指令                     │
└───────────────────────────────────────────────────────────┘
                        │  TCP 25576（帧协议 0x81~0x86）
                        ▼
┌─ 执行面（本目录部署目标：Linux / WSL2）───────────────────┐
│  control_client.py（root 运行，systemd 托管）              │
│  ├─ 实例分配：display :99 起，KasmVNC 端口 8542 起         │
│  ├─ 拉起 Xvnc（KasmVNC）+ openbox（systemd 托管）         │
│  ├─ 拉起 Forge 1.12.2 客户端（独立实例目录，连 127.0.0.1）│
│  └─ 注入：X 层键盘 / 鼠标（openbox EWMH + MC grab）        │
└───────────────────────────────────────────────────────────┘
```

**端口与资源**

| 项 | 值 | 说明 |
|---|---|---|
| 管控 TCP | `25576` | 插件 ControlClient ⇄ 执行面，帧协议 0x81~0x86 |
| HTTP/WS 网关 | `25574` | SOYSHTTPOverMC，浏览器入口 + KasmVNC 反代 |
| KasmVNC 端口 | `8542` 起 | 每个实例 +1，仅监听本机（0.0.0.0 但由插件鉴权） |
| X display | `:99` 起 | 每个实例 +1 |
| 虚拟屏分辨率 | `1024x768` | `vncserver -geometry`，可由 control_client 调整 |
| 实例上限 | `capacity=4` | control_client.py 内可配 |

## 2. 目录结构

```
server-cloud/
├── README.md                  # 本文档
├── executor/                  # 执行面（部署到 Linux/WSL2）
│   ├── control_client.py      # 管控服务主程序（与 control-client/ 同源）
│   ├── deploy.sh              # ★ 一键部署（新机从零）
│   ├── update.sh              # ★ 一键更新（同步代码 + 重启）
│   ├── uninstall.sh           # ★ 一键卸载（停服务 + 清理）
│   ├── status.sh              # 一键状态检查（只读）
│   ├── mc-pack.sh             # 打包 MC 客户端（迁移/备份）
│   ├── mc-import.sh           # 导入 MC 客户端
│   ├── systemd/
│   │   ├── webgame-cc.service # 管控服务单元（root 运行）
│   │   └── openbox@.service   # openbox 单元模板（%i=display 号）
│   └── kasmvnc/
│       ├── kasmvnc.yaml       # KasmVNC 配置模板
│       └── xstartup           # X 会话启动脚本（exec openbox）
└── windows/
    └── README.md              # Windows 宿主侧说明（WSL 安装/进入）
```

## 3. 快速开始（新服务器）

前置条件：一台 Linux 主机或 Windows + WSL2（Ubuntu 22.04），有 root/sudo，
可访问互联网（或准备 KasmVNC deb 与 MC 客户端 tar 包离线安装）。

```bash
# ① 进入执行面目录
cd server-cloud/executor

# ② 一键部署（创建用户、装依赖/KasmVNC、写 systemd、启动服务）
sudo bash deploy.sh

# ③ 导入 MC 客户端（Forge 1.12.2，含 versions/libraries/assets/natives）
#    在源机器先执行: sudo bash mc-pack.sh            # 产出 mc-backup.tar.gz
sudo bash mc-import.sh /path/to/mc-backup.tar.gz

# ④ 检查
bash status.sh
```

部署完成后，把执行面所在机器的回环 `25576` 与服务器（运行插件）连通即可
（同机部署时天然连通）。玩家浏览器访问：

```
http://<服务器>:25574/api/plugins/WebGame/cloud/?user=玩家名
```

## 4. 日常维护

| 操作 | 命令 |
|---|---|
| 查看状态 | `bash status.sh` |
| 更新执行面 | `sudo bash update.sh`（默认同步本目录 `control_client.py`） |
| 更新指定仓库 | `sudo bash update.sh --repo /path/to/WebGame/server-cloud/executor` |
| 只同步不重启 | `sudo bash update.sh --skip-restart` |
| 卸载（保留数据） | `sudo bash uninstall.sh` |
| 卸载 + 删数据 | `sudo bash uninstall.sh --purge` |
| 保留 KasmVNC 包 | `sudo bash uninstall.sh --keep-kasmvnc` |
| 查看管控日志 | `journalctl -u webgame-cc --no-pager` |

## 5. 环境变量与自定义

所有脚本支持环境变量覆盖，便于多实例/多机部署：

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_USER` | `webgame` | 执行面运行用户 |
| `MC_BASE` | `/home/<user>/mc` | MC 客户端根目录 |
| `INSTANCES` | `/home/<user>/instances` | 实例目录 |
| `CTRL_PORT` | `25576` | 管控 TCP 端口 |
| `KASM_VER` | `1.5.0` | KasmVNC 版本 |
| `VNC_PASS` | `webgame` | KasmVNC 密码（deploy 时写入） |

例：`APP_USER=clouduser CTRL_PORT=25577 sudo bash deploy.sh`

## 6. 故障排查

| 现象 | 排查 |
|---|---|
| 管控端口不通 | `bash status.sh`；`journalctl -u webgame-cc --no-pager` |
| 浏览器 Bad Gateway | 实例会话残留；重启 `webgame-cc` 后由插件 resume 重建 |
| 黑屏 / 无画面 | 查实例日志 `ls ~/instances/*/logs`；确认 Xvnc 端口已监听 |
| 键盘鼠标无反应 | openbox 是否 active（`systemctl status openbox-99`）；MC 是否进服 |
| MC 无法启动 | `java -version` 是否为 1.8；natives-linux 是否存在 |

## 7. 与插件侧的对接要点

- 插件 `CloudSessionManager` 通过 TCP `25576` 连接执行面；
- 实例 READY 后插件把 KasmVNC 端点（`/kasm/{sid}/{port}/`）下发给前端；
- 断线重连：前端 WS 断开后插件悬挂会话（默认 60s），重连 resume 同实例；
- 设备配额：`config.yml` 的 `cloud.max-connections-per-device`（默认 2）。
