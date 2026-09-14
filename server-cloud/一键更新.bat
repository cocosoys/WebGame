@echo off
title WebGame 云游戏执行面 - 一键更新
echo ============================================
echo    WebGame 云游戏执行面 - 一键更新
echo ============================================
echo.
for /f "delims=" %%i in ('wsl -d Ubuntu-22.04 -- wslpath -u "%~dp0."') do set "WSLROOT=%%i"
if "%WSLROOT%"=="" (
  echo [错误] 无法解析 WSL 路径，请确认已安装 WSL 且发行版名为 Ubuntu-22.04
  pause
  exit /b 1
)
set "EXEC=%WSLROOT%executor"
echo [1/2] 同步最新执行面代码并重启服务 ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/update.sh" --repo "%EXEC%"
echo.
echo [2/2] 更新后状态检查 ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/status.sh"
echo.
echo 更新完成。
pause
