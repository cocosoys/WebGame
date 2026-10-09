# install-vmulti.ps1 — VMulti 虚拟 HID 驱动安装/卸载（路线 beta 输入通道）
# 前置：testsigning 已开启（bcdedit /set testsigning on + 重启）
# 用法：
#   安装: powershell -ExecutionPolicy Bypass -File install-vmulti.ps1
#   卸载: powershell -ExecutionPolicy Bypass -File install-vmulti.ps1 -Uninstall
# 文件：vmulti.sys / vmulti.inf / vmulti.cat / vmulti-test.pfx（同目录）

param(
    [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# ---- 1. 检查 testsigning ----
$bcd = bcdedit /enum "{current}" 2>$null | Out-String
if ($Uninstall) {
    Write-Host "[1/4] 卸载模式，跳过 testsigning 检查"
} elseif ($bcd -match "testsigning\s+Yes") {
    Write-Host "[1/4] testsigning 已开启 OK"
} else {
    Write-Host "=== FAIL: 未开启 testsigning。请以管理员运行: bcdedit /set testsigning on 并重启后重试。"
    exit 1
}

# ---- 2. 导入测试证书（Trusted Root + Trusted Publisher）----
$pfx = Join-Path $here "vmulti-test.pfx"
if (Test-Path $pfx) {
    try {
        $pwd = New-Object System.Security.SecureString
        foreach ($ch in "WebGame-VMulti-Test".ToCharArray()) { $pwd.AppendChar($ch) }
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($pfx, $pwd)
        $storeRoot = New-Object System.Security.Cryptography.X509Certificates.X509Store("Root", "LocalMachine")
        $storeRoot.Open("ReadWrite")
        $storeRoot.Add($cert)
        $storeRoot.Close()
        $storePub = New-Object System.Security.Cryptography.X509Certificates.X509Store("TrustedPublisher", "LocalMachine")
        $storePub.Open("ReadWrite")
        $storePub.Add($cert)
        $storePub.Close()
        Write-Host "[2/4] 证书已导入 Trusted Root + TrustedPublisher OK"
    } catch {
        Write-Host ("[2/4] 证书导入失败（继续尝试安装）: " + $_.Exception.Message)
    }
} else {
    Write-Host "[2/4] 未找到 pfx，跳过证书导入（testsigning 下仍可装）"
}

# ---- 3. 安装/卸载驱动 ----
$nefcon = Join-Path $here "..\nefcon\x64\nefconw.exe"
if (-not (Test-Path $nefcon)) { $nefcon = Join-Path $here "nefcon\x64\nefconw.exe" }
if (-not (Test-Path $nefcon)) {
    Write-Host "=== FAIL: 未找到 nefconw.exe（可从 VDD 驱动包复用）"
    exit 1
}

$inf = Join-Path $here "vmulti.inf"
if ($Uninstall) {
    Write-Host "[3/4] 卸载 VMulti..."
    & $nefcon uninstall $inf "Root\VMulti"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[4/4] 卸载完成 OK"
    } else {
        Write-Host ("[4/4] 卸载返回码: " + $LASTEXITCODE)
    }
} else {
    Write-Host "[3/4] 安装 VMulti..."
    & $nefcon install $inf "Root\VMulti"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[4/4] 安装完成 OK"
    } else {
        Write-Host ("=== FAIL: 安装返回码 " + $LASTEXITCODE + "，请检查驱动签名/testsigning")
        exit 1
    }
}

# ---- 4. 验证 ----
Write-Host "--- 设备验证 ---"
Get-PnpDevice | Where-Object { $_.FriendlyName -match "VMulti|Vmulti" } | Format-Table Status, Class, FriendlyName, InstanceId -AutoSize
Get-PnpDevice | Where-Object { $_.InstanceId -match "VMulti" } | Format-Table Status, Class, FriendlyName, InstanceId -AutoSize

Write-Host "--- 完成 ---"
