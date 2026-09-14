# Windows 宿主侧说明（云游戏执行面部署）

执行面推荐跑在 **WSL2 Ubuntu-22.04** 中。本说明覆盖 Windows 主机的准备步骤。

## 1. 安装 WSL2 + Ubuntu 22.04

```powershell
# PowerShell（管理员）
wsl --install -d Ubuntu-22.04
wsl --set-default -d Ubuntu-22.04
```

> 若已有其他发行版，至少保证 `wsl -l -v` 中 Ubuntu-22.04 存在且 VERSION=2。

## 2. 进入 WSL 并验证

```powershell
wsl -d Ubuntu-22.04 -- bash -lc "cat /etc/os-release | head -1 && whoami"
```

## 3. 把 server-cloud 目录放到 WSL 可访问位置

两种方式：

- **方式 A（推荐，利用 /mnt/c 挂载）**：仓库在 Windows 路径
  `D:\WorkTools\...\WebGame\server-cloud`，WSL 内可直接访问
  `/mnt/d/WorkTools/Project/Minecraft/plugins/spigot/1.12.2/WebGame/server-cloud`。
  但由于跨文件系统权限/换行问题，**建议先把脚本拷入 WSL 再执行**：

```bash
cp -r /mnt/d/WorkTools/Project/Minecraft/plugins/spigot/1.12.2/WebGame/server-cloud ~/server-cloud
cd ~/server-cloud/executor
```

- **方式 B（干净）**：从 git 拉取：
  `git clone git@github.com:cocosoys/WebGame.git && cd WebGame/server-cloud/executor`

## 4. 一键部署

```bash
cd ~/server-cloud/executor
sudo bash deploy.sh
```

脚本会：创建 `webgame` 用户、装依赖（openbox/xdotool 等）、
安装 Java 8（**优先用 packages/java8-openjdk-amd64.tar.gz 离线解包**，
否则尝试 apt，失败会明确提示）、安装 KasmVNC（**优先用
packages/kasmvncserver_jammy_1.5.0_amd64.deb**，否则从 GitHub 下载）、
部署 `control_client.py` 与 KasmVNC 配置、写 systemd 单元并启动
`webgame-cc.service`。

> 整个 `server-cloud/packages/` 目录（KasmVNC deb + Java8 + MC 客户端）
> 拷贝到新机器后即可**全离线一键部署**，无需任何外部下载。

## 5. 导入 MC 客户端

新机器直接使用本地离线包（推荐）：

```bash
sudo bash mc-import.sh          # 自动使用 packages/mc-backup.tar.gz
```

源机器生成/更新离线包（当 packages/ 里没有时）：

```bash
sudo bash mc-pack.sh            # 产出 mc-backup.tar.gz，拷贝到新机器 packages/
```

## 6. 开机自启（WSL2 注意）

WSL2 默认不随 Windows 启动 systemd。两种做法：

- **方式 A**：`/etc/wsl.conf` 写入 `[boot] systemd=true`（需 `wsl --shutdown`
  后生效）——本执行面的 `webgame-cc.service` 依赖 systemd，推荐启用。
- **方式 B**：手动启动：
  `wsl -d Ubuntu-22.04 -- sudo systemctl start webgame-cc.service`

```ini
# /etc/wsl.conf
[boot]
systemd=true
```

> 注意：WSL2 会话退出后，非 systemd 托管的 nohup 进程会丢失。执行面的
> `webgame-cc.service`（systemd 单元）与 openbox（systemd-run/单元）均被
> systemd 托管，不受此限制；但 **Windows 关机/重启后需重新 `wsl -d ...`**
> 进入一次以拉起 systemd（或配置 WSL 开机自启）。

## 7. Windows 侧辅助（可选）

- 服务器（Spigot）跑在 Windows 上、执行面跑在 WSL 内时，管控 TCP 25576
  走 WSL 回环即可（WSL2 与宿主共享回环，`127.0.0.1:25576` 双向可达）。
- 若服务器在其他主机：执行面 `webgame-cc.service` 默认监听回环，
  需改为监听对外网卡并放行端口（生产部署建议放到同一台机器或用隧道）。
