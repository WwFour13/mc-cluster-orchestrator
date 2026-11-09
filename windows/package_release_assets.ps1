param(
    [string]$RootDir = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
$ReleaseOutDir = Join-Path $RootDir "out\release"
if (!(Test-Path $ReleaseOutDir)) {
    New-Item -ItemType Directory -Path $ReleaseOutDir -Force | Out-Null
}

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " [GitHub Release Packager] Assembling All Platform Bundles" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Run main build to ensure ServerLauncher.jar is current
Write-Host "`n[1/4] Compiling Java runtime sources..." -ForegroundColor Yellow
$buildBat = Join-Path $RootDir "windows\build.bat"
& cmd.exe /c "$buildBat < nul"
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Build failed." -ForegroundColor Red
    exit 1
}

# 2. Compile Windows Native Installer (MinecraftClusterSetup.exe)
Write-Host "`n[2/4] Compiling Windows Native Installer (MinecraftClusterSetup.exe)..." -ForegroundColor Yellow
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (!(Test-Path $csc)) {
    $csc = (Get-Command csc.exe -ErrorAction SilentlyContinue).Source
}

$csSource = Join-Path $RootDir "windows\installer\Installer.cs"
$iconPath = Join-Path $RootDir "templates\icon.ico"
$outExe = Join-Path $ReleaseOutDir "MinecraftClusterSetup.exe"

& $csc /nologo /target:winexe /win32icon:"$iconPath" /out:"$outExe" /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll "$csSource"
if ($LASTEXITCODE -eq 0 -and (Test-Path $outExe)) {
    $exeSize = [math]::Round((Get-Item $outExe).Length / 1KB, 1)
    Write-Host "  [OK] MinecraftClusterSetup.exe compiled successfully ($exeSize KB)" -ForegroundColor Green
    Copy-Item -Path $outExe -Destination (Join-Path $RootDir "minecraft-server\MinecraftClusterSetup.exe") -Force
} else {
    Write-Host "[ERROR] Failed to compile MinecraftClusterSetup.exe" -ForegroundColor Red
    exit 1
}

# 3. Assemble macOS Release Bundle (Minecraft-Cluster-macOS.zip)
Write-Host "`n[3/4] Packaging macOS Bundle (Minecraft-Cluster-macOS.zip)..." -ForegroundColor Yellow
$stagingMac = Join-Path $env:TEMP "mc_mac_rel_$([guid]::NewGuid().ToString().Substring(0,8))"
New-Item -ItemType Directory -Path $stagingMac | Out-Null
$targetMac = Join-Path $stagingMac "Minecraft-Cluster"
New-Item -ItemType Directory -Path $targetMac | Out-Null

try {
    # Copy ServerLauncher.jar and templates
    Copy-Item -Path "$RootDir\minecraft-server\ServerLauncher.jar" -Destination $targetMac -Force
    Copy-Item -Path "$RootDir\templates\mac-linux\START_SERVER.command" -Destination $targetMac -Force
    Copy-Item -Path "$RootDir\templates\mac-linux\Setup_Mac_App.command" -Destination $targetMac -Force
    Copy-Item -Path "$RootDir\templates\rclone-filter.txt" -Destination $targetMac -Force
    Copy-Item -Path "$RootDir\templates\admin-dist-filter.txt" -Destination $targetMac -Force
    if (Test-Path "$RootDir\templates\config.example.json") {
        Copy-Item -Path "$RootDir\templates\config.example.json" -Destination (Join-Path $targetMac "[template]config.json") -Force
    }

    # Tools
    $toolsDir = Join-Path $targetMac "tools"
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
    Copy-Item -Path "$RootDir\templates\mac-linux\tools\*" -Destination $toolsDir -Force

    # Binaries
    $binDir = Join-Path $targetMac "bin"
    New-Item -ItemType Directory -Path $binDir | Out-Null
    if (Test-Path "$RootDir\minecraft-server\bin\core.dat") { Copy-Item -Path "$RootDir\minecraft-server\bin\core.dat" -Destination $binDir -Force }
    if (Test-Path "$RootDir\minecraft-server\bin\rclone") { Copy-Item -Path "$RootDir\minecraft-server\bin\rclone" -Destination $binDir -Force }
    if (Test-Path "$RootDir\minecraft-server\bin\playit") { Copy-Item -Path "$RootDir\minecraft-server\bin\playit" -Destination $binDir -Force }
    if (Test-Path "$RootDir\minecraft-server\bin\icon.ico") { Copy-Item -Path "$RootDir\minecraft-server\bin\icon.ico" -Destination $binDir -Force }

    $outMacZip = Join-Path $ReleaseOutDir "Minecraft-Cluster-macOS.zip"
    if (Test-Path $outMacZip) { Remove-Item $outMacZip -Force }
    Compress-Archive -Path "$stagingMac\*" -DestinationPath $outMacZip -CompressionLevel Optimal
    $macSize = [math]::Round((Get-Item $outMacZip).Length / 1MB, 2)
    Write-Host "  [OK] Minecraft-Cluster-macOS.zip packaged ($macSize MB)" -ForegroundColor Green
} finally {
    if (Test-Path $stagingMac) { Remove-Item -Path $stagingMac -Recurse -Force -ErrorAction SilentlyContinue }
}

# 4. Stage Linux installer and standalone core binary
Write-Host "`n[4/4] Staging Linux installer and ServerLauncher.jar..." -ForegroundColor Yellow
Copy-Item -Path "$RootDir\install.sh" -Destination (Join-Path $ReleaseOutDir "install.sh") -Force
Copy-Item -Path "$RootDir\minecraft-server\ServerLauncher.jar" -Destination (Join-Path $ReleaseOutDir "ServerLauncher.jar") -Force

Write-Host "  [OK] install.sh staged" -ForegroundColor Green
Write-Host "  [OK] ServerLauncher.jar staged" -ForegroundColor Green

# Print Manifest
Write-Host "`n========================================================" -ForegroundColor Green
Write-Host " [SUCCESS] All GitHub Release Assets Ready in out\release\!" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green

Get-ChildItem -Path $ReleaseOutDir | ForEach-Object {
    $sizeStr = if ($_.Length -gt 1MB) { "$([math]::Round($_.Length / 1MB, 2)) MB" } else { "$([math]::Round($_.Length / 1KB, 1)) KB" }
    Write-Host ("  * {0,-30} ({1})" -f $_.Name, $sizeStr) -ForegroundColor White
}

Write-Host "`nGitHub Release Instructions:" -ForegroundColor Cyan
Write-Host "  1. On your GitHub repo, create a new Release (e.g. tag 'v2.0.0')." -ForegroundColor White
Write-Host "  2. Drag and drop all 4 files from 'out\release\' into the Release Attachments." -ForegroundColor White
Write-Host "  3. Click 'Publish Release'. That's all there is to it!" -ForegroundColor White
