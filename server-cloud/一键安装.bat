@echo off
title WebGame 云游戏执行面 - 一键安装
echo ============================================
echo    WebGame 云游戏执行面 - 一键安装
echo ============================================
echo.
for /f "delims=" %%i in ('wsl -d Ubuntu-22.04 -- wslpath -u "%~dp0."') do set "WSLROOT=%%i"
if "%WSLROOT%"=="" (
  echo [错误] 无法解析 WSL 路径，请确认已安装 WSL 且发行版名为 Ubuntu-22.04
  pause
  exit /b 1
)
set "EXEC=%WSLROOT%executor"
echo [1/2] 进入 WSL (root) 执行一键部署 ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/deploy.sh" %*
echo.
echo [2/2] 部署后状态检查 ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/status.sh"
echo.
echo 部署完成。浏览器入口：http://127.0.0.1:25574/api/plugins/WebGame/cloud/?user=你的名字
pause
