@echo off
rem =============================================
rem  VMulti residue cleanup (run as admin)
rem  Removes driver package + sys + service to
rem  prevent auto-load / further BSOD.
rem =============================================
cd /d "%~dp0"
echo [1/4] Deleting driver package from store...
pnputil /delete-driver oem10.inf /uninstall /force
echo.
echo [2/4] Deleting vmulti.sys from System32\drivers...
del /f "C:\Windows\System32\drivers\vmulti.sys" 2>nul
del /f "C:\Windows\System32\drivers\hidkmdf.sys" 2>nul
echo.
echo [3/4] Deleting vmulti service if present...
sc.exe delete vmulti 2>nul
echo.
echo [4/4] Verifying residue...
powershell -NoProfile -Command "Get-PnpDevice | Where-Object { $_.InstanceId -match 'VMulti' -or $_.FriendlyName -match 'VMulti' } | Format-Table Status,Class,FriendlyName -AutoSize; pnputil /enum-drivers | Select-String -Pattern 'vmulti'"
echo.
echo Residue check done. Verify virtual display (usbmmidd) is still OK:
powershell -NoProfile -Command "Get-PnpDevice | Where-Object { $_.FriendlyName -match 'Mobile Monitor|Virtual Display' } | Format-Table Status,Class,FriendlyName -AutoSize"
echo.
echo Done. Press any key to close.
pause >nul
