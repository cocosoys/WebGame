# keep-wsl-alive.ps1
# Keep a persistent wsl.exe client connection so WSL2 does not auto-shutdown
# when idle (observed: WSL powers off ~3-4 min after the last wsl.exe client
# disconnects, killing the cloud-game executor mid-session).
#
# Usage:  powershell -ExecutionPolicy Bypass -File keep-wsl-alive.ps1
# Loop:   hold `wsl.exe -e bash -c "sleep huge"` (blocking, keeps the client
#         session alive); if it exits (WSL restarted/crashed), wait a few
#         seconds and re-open, which boots WSL again.
while ($true) {
    Write-Host ("[{0}] holding WSL client connection..." -f (Get-Date -Format 'HH:mm:ss'))
    & wsl.exe -e bash -c "sleep 2147483647"
    Write-Host ("[{0}] wsl.exe exited; WSL may have restarted. Reconnecting..." -f (Get-Date -Format 'HH:mm:ss'))
    Start-Sleep -Seconds 8
}
