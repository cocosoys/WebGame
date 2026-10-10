# ============================================================
# input-agent 构建脚本（JDK8 + ASM 5.2）
# 输出：tools\input-agent\input-agent.jar
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1
# ============================================================
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$src = "$root\src"
$out = "$root\out"
$asm = "C:\Users\user\.m2\repository\org\ow2\asm\asm\5.2\asm-5.2.jar"
$java = "C:\dev\jdk8\bin\java.exe"
$javac = "C:\dev\jdk8\bin\javac.exe"
$jar = "C:\dev\jdk8\bin\jar.exe"

if (-not (Test-Path $asm)) { Write-Error "missing asm: $asm" }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

# 编译（source/target 8 兼容 LWJGL 2 / MC 1.12.2 运行环境）
& $javac -encoding UTF-8 -source 8 -target 8 -cp $asm -d $out (Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { Write-Error "javac failed" }

# 打包
$mf = "$root\MANIFEST.MF"
$jarPath = "$root\input-agent.jar"
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }
& $jar cfm $jarPath $mf -C $out .
if ($LASTEXITCODE -ne 0) { Write-Error "jar failed" }
Write-Output "built: $jarPath ($([math]::Round((Get-Item $jarPath).Length/1KB,1)) KB)"
& $jar tf $jarPath | Select-String "WebGameInputAgent|MANIFEST"
