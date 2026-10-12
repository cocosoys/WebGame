@echo off
rem =============================================
rem  VMulti Virtual HID Driver Installer (Admin)
rem  Right-click -> Run as administrator
rem  If UAC asks for credentials, use the local
rem  administrator account (191) credentials.
rem =============================================
cd /d "%~dp0"
echo [1/3] Running elevated PowerShell installer...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-vmulti-devcon.ps1"
echo.
echo [2/3] Install log:
type "C:\Users\user\Desktop\WebGame\tools\vmulti_install_log.txt"
echo.
echo [3/3] Verifying VMulti device...
powershell -NoProfile -Command "Get-PnpDevice | Where-Object { $_.FriendlyName -match 'VMulti|vmulti' } | Format-Table Status,Class,FriendlyName,InstanceId -AutoSize"
echo.
echo Done. Press any key to close.
pause >nul
