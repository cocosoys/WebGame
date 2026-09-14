@echo off
title WebGame 云游戏执行面 - 一键卸载
echo ============================================
echo    WebGame 云游戏执行面 - 一键卸载
echo ============================================
echo.
echo  默认保留运行数据（用户/实例/MC 客户端）。
echo  如需连数据一起删除，请手动执行：
echo     wsl -d Ubuntu-22.04 -u root -- bash .../uninstall.sh --purge
echo.
for /f "delims=" %%i in ('wsl -d Ubuntu-22.04 -- wslpath -u "%~dp0."') do set "WSLROOT=%%i"
if "%WSLROOT%"=="" (
  echo [错误] 无法解析 WSL 路径，请确认已安装 WSL 且发行版名为 Ubuntu-22.04
  pause
  exit /b 1
)
set "EXEC=%WSLROOT%executor"
echo [1/2] 停止服务并清理进程（保留数据） ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/uninstall.sh"
echo.
echo [2/2] 残留检查 ...
wsl -d Ubuntu-22.04 -u root -- bash "%EXEC%/status.sh" 2>nul
echo.
echo 卸载完成。如需彻底删除请使用 --purge 参数。
pause
