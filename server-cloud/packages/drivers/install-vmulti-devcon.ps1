# install-vmulti-devcon.ps1 - VMulti install via devcon (replaces nefcon dependency)
# Run as Administrator.
param(
    [switch]$Uninstall
)
$ErrorActionPreference = 'Continue'
$log = "C:\Users\user\Desktop\WebGame\tools\vmulti_install_log.txt"
$dir = "C:\Users\user\Desktop\WebGame\server-cloud\packages\drivers\vmulti"
$devcon = "C:\Users\user\Desktop\WebGame\drivers\wdk-nuget-probe\unpack26100\pkg\c\tools\10.0.26100.0\x64\devcon.exe"
"=== vmulti install start $(Get-Date) ===" | Out-File $log

# 1. testsigning check
$bcd = bcdedit /enum "{current}" 2>$null | Out-String
if ($bcd -match "testsigning\s+Yes") {
    "testsigning: Yes" | Add-Content $log
} else {
    "testsigning: NO - install may fail for unsigned kernel driver" | Add-Content $log
}

# 2. import test cert (Trusted Root + TrustedPublisher)
$pfx = Join-Path $dir "vmulti-test.pfx"
try {
    $pwd = New-Object System.Security.SecureString
    foreach ($ch in "WebGame-VMulti-Test".ToCharArray()) { $pwd.AppendChar($ch) }
    $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($pfx, $pwd)
    $st1 = New-Object System.Security.Cryptography.X509Certificates.X509Store("Root","LocalMachine"); $st1.Open("ReadWrite"); $st1.Add($cert); $st1.Close()
    $st2 = New-Object System.Security.Cryptography.X509Certificates.X509Store("TrustedPublisher","LocalMachine"); $st2.Open("ReadWrite"); $st2.Add($cert); $st2.Close()
    "cert: imported OK" | Add-Content $log
} catch {
    "cert: import failed (continue) " + $_.Exception.Message | Add-Content $log
}

# 3. install / uninstall
Set-Location $dir
if ($Uninstall) {
    & $devcon remove "djpnewton\vmulti" *>> $log 2>&1
    "devcon remove exit: $LASTEXITCODE" | Add-Content $log
} else {
    & $devcon install vmulti.inf djpnewton\vmulti *>> $log 2>&1
    "devcon install exit: $LASTEXITCODE" | Add-Content $log
}

# 4. verify devices
"--- devices ---" | Add-Content $log
Get-PnpDevice | Where-Object { $_.FriendlyName -match "VMulti|vmulti" } | ForEach-Object { "$($_.Status) | $($_.Class) | $($_.FriendlyName) | $($_.InstanceId)" } | Add-Content $log
"--- done ---" | Add-Content $log
