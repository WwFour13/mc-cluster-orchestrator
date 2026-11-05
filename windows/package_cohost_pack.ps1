param(
    [string]$ServerDir = (Join-Path (Resolve-Path "$PSScriptRoot\..").Path "minecraft-server"),
    [string]$OutputDir = (Resolve-Path "$PSScriptRoot\..").Path
)

$ServerDir = (Resolve-Path $ServerDir).Path
$TemplatesDir = Join-Path (Resolve-Path "$PSScriptRoot\..").Path "templates"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "[Cohost Packager] Preparing pre-configured starter packs..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Validation
if (!(Test-Path "$ServerDir\ServerLauncher.jar")) {
    Write-Host "[ERROR] ServerLauncher.jar not found in $ServerDir. Run windows\build.bat first!" -ForegroundColor Red
    exit 1
}
if (!(Test-Path "$ServerDir\bin\core.dat")) {
    Write-Host "[ERROR] core.dat not found in $ServerDir\bin. Ensure your Paper jar is in bin/core.dat." -ForegroundColor Red
    exit 1
}
if (!(Test-Path "$ServerDir\config.json")) {
    Write-Host "[ERROR] config.json not found in $ServerDir. Please configure your Backblaze keys first!" -ForegroundColor Red
    exit 1
}

function Copy-SharedRuntimeFiles($destServer) {
    $rootFiles = @(
        "rclone-filter.txt", "ServerLauncher.jar",
        "config.json", "eula.txt", "server.properties",
        "bukkit.yml", "spigot.yml", "commands.yml",
        "ops.json", "whitelist.json", "banned-ips.json", "banned-players.json"
    )
    foreach ($f in $rootFiles) {
        $src = Join-Path $ServerDir $f
        if (Test-Path $src) {
            Copy-Item -Path $src -Destination $destServer -Force
        }
    }
    if (Test-Path "$ServerDir\plugins") {
        Copy-Item -Path "$ServerDir\plugins" -Destination $destServer -Recurse -Force
    }
    if (Test-Path "$ServerDir\config") {
        Copy-Item -Path "$ServerDir\config" -Destination $destServer -Recurse -Force
    }
}

# ----------------------------------------------------
# 1. Package Windows Starter Pack (cohost-windows.zip)
# ----------------------------------------------------
Write-Host "`n[1/2] Creating Windows pack (cohost-windows.zip)..." -ForegroundColor Yellow
$stagingWin = Join-Path $env:TEMP "mc_win_staging_$([guid]::NewGuid().ToString().Substring(0,8))"
New-Item -ItemType Directory -Path $stagingWin | Out-Null
$targetWin = Join-Path $stagingWin "minecraft-server"
New-Item -ItemType Directory -Path $targetWin | Out-Null

try {
    Copy-SharedRuntimeFiles $targetWin

    # Binaries for Windows
    $stagingBin = Join-Path $targetWin "bin"
    New-Item -ItemType Directory -Path $stagingBin | Out-Null
    if (Test-Path "$ServerDir\bin\core.dat") { Copy-Item -Path "$ServerDir\bin\core.dat" -Destination $stagingBin -Force }
    if (Test-Path "$ServerDir\bin\rclone.exe") { Copy-Item -Path "$ServerDir\bin\rclone.exe" -Destination $stagingBin -Force }
    if (Test-Path "$ServerDir\bin\icon.ico") { Copy-Item -Path "$ServerDir\bin\icon.ico" -Destination $stagingBin -Force }

    # Launchers & Tools for Windows
    Copy-Item -Path "$TemplatesDir\windows\START_SERVER.bat" -Destination $targetWin -Force
    Copy-Item -Path "$TemplatesDir\windows\Setup_Desktop_Shortcut.bat" -Destination $targetWin -Force
    $toolsDir = Join-Path $targetWin "tools"
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
    Copy-Item -Path "$TemplatesDir\windows\tools\*" -Destination $toolsDir -Force

    $outWin = Join-Path $OutputDir "cohost-windows.zip"
    if (Test-Path $outWin) { Remove-Item $outWin -Force }
    Compress-Archive -Path "$stagingWin\*" -DestinationPath $outWin -CompressionLevel Optimal
    $sizeWin = [math]::Round((Get-Item $outWin).Length / 1MB, 2)
    Write-Host "  [OK] cohost-windows.zip created ($sizeWin MB)" -ForegroundColor Green
} finally {
    if (Test-Path $stagingWin) { Remove-Item -Path $stagingWin -Recurse -Force -ErrorAction SilentlyContinue }
}

# ----------------------------------------------------
# 2. Package macOS / Linux Starter Pack (cohost-mac.zip)
# ----------------------------------------------------
Write-Host "`n[2/2] Creating macOS pack (cohost-mac.zip)..." -ForegroundColor Yellow
$stagingMac = Join-Path $env:TEMP "mc_mac_staging_$([guid]::NewGuid().ToString().Substring(0,8))"
New-Item -ItemType Directory -Path $stagingMac | Out-Null
$targetMac = Join-Path $stagingMac "minecraft-server"
New-Item -ItemType Directory -Path $targetMac | Out-Null

try {
    Copy-SharedRuntimeFiles $targetMac

    # Binaries for Mac
    $stagingBin = Join-Path $targetMac "bin"
    New-Item -ItemType Directory -Path $stagingBin | Out-Null
    if (Test-Path "$ServerDir\bin\core.dat") { Copy-Item -Path "$ServerDir\bin\core.dat" -Destination $stagingBin -Force }
    if (Test-Path "$ServerDir\bin\rclone") { Copy-Item -Path "$ServerDir\bin\rclone" -Destination $stagingBin -Force }
    if (Test-Path "$ServerDir\bin\icon.ico") { Copy-Item -Path "$ServerDir\bin\icon.ico" -Destination $stagingBin -Force }

    # Launchers & Tools for Mac
    Copy-Item -Path "$TemplatesDir\mac-linux\START_SERVER.command" -Destination $targetMac -Force
    Copy-Item -Path "$TemplatesDir\mac-linux\Setup_Mac_App.command" -Destination $targetMac -Force
    $toolsDir = Join-Path $targetMac "tools"
    New-Item -ItemType Directory -Path $toolsDir | Out-Null
    Copy-Item -Path "$TemplatesDir\mac-linux\tools\*" -Destination $toolsDir -Force

    $outMac = Join-Path $OutputDir "cohost-mac.zip"
    if (Test-Path $outMac) { Remove-Item $outMac -Force }
    Compress-Archive -Path "$stagingMac\*" -DestinationPath $outMac -CompressionLevel Optimal
    $sizeMac = [math]::Round((Get-Item $outMac).Length / 1MB, 2)
    Write-Host "  [OK] cohost-mac.zip created ($sizeMac MB)" -ForegroundColor Green
} finally {
    if (Test-Path $stagingMac) { Remove-Item -Path $stagingMac -Recurse -Force -ErrorAction SilentlyContinue }
}

# Clean legacy unified pack if present
$oldPack = Join-Path $OutputDir "cohost-starter-pack.zip"
if (Test-Path $oldPack) { Remove-Item $oldPack -Force }

Write-Host "`n========================================================" -ForegroundColor Green
Write-Host "[SUCCESS] Starter packs created successfully!" -ForegroundColor Green
Write-Host "  Windows Pack : $outWin ($sizeWin MB)" -ForegroundColor Green
Write-Host "  Mac Pack     : $outMac ($sizeMac MB)" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host "Distribution Instructions:" -ForegroundColor White
Write-Host "  * Windows Friends: Send cohost-windows.zip (they run START_SERVER.bat)" -ForegroundColor White
Write-Host "  * Mac Friends:     Send cohost-mac.zip (they double-click START_SERVER.command)" -ForegroundColor White
