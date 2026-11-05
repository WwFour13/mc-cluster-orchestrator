param(
    [string]$ServerDir = "$PSScriptRoot\minecraft-server",
    [string]$OutputFile = "$PSScriptRoot\cohost-starter-pack.zip"
)

$ServerDir = (Resolve-Path $ServerDir).Path
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "[Cohost Packager] Preparing pre-configured starter pack..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

# 1. Validation
if (!(Test-Path "$ServerDir\ServerLauncher.jar")) {
    Write-Host "[ERROR] ServerLauncher.jar not found in $ServerDir. Run build.bat first!" -ForegroundColor Red
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

# 2. Setup Staging Directory
$staging = Join-Path $env:TEMP "mc_cohost_staging_$([guid]::NewGuid().ToString().Substring(0,8))"
New-Item -ItemType Directory -Path $staging | Out-Null
$targetServer = Join-Path $staging "minecraft-server"
New-Item -ItemType Directory -Path $targetServer | Out-Null

try {
    Write-Host "[1/4] Staging runtime and launch scripts..." -ForegroundColor Yellow
    $rootFiles = @(
        "Setup_Desktop_Shortcut.bat", "setup_shortcut.ps1", "Setup_Mac_App.sh",
        "run_server.bat", "run_server.sh",
        "sync_pull.bat", "sync_pull.sh",
        "sync_push.bat", "sync_push.sh",
        "rclone-filter.txt", "ServerLauncher.jar",
        "config.json", "eula.txt", "server.properties",
        "bukkit.yml", "spigot.yml", "commands.yml",
        "ops.json", "whitelist.json", "banned-ips.json", "banned-players.json"
    )
    foreach ($f in $rootFiles) {
        $src = Join-Path $ServerDir $f
        if (Test-Path $src) {
            Copy-Item -Path $src -Destination $targetServer -Force
        }
    }

    Write-Host "[2/4] Staging binaries (bin/core.dat, bin/rclone.exe, bin/icon.ico)..." -ForegroundColor Yellow
    $stagingBin = Join-Path $targetServer "bin"
    New-Item -ItemType Directory -Path $stagingBin | Out-Null
    if (Test-Path "$ServerDir\bin\core.dat") {
        Copy-Item -Path "$ServerDir\bin\core.dat" -Destination $stagingBin -Force
    }
    if (Test-Path "$ServerDir\bin\rclone.exe") {
        Copy-Item -Path "$ServerDir\bin\rclone.exe" -Destination $stagingBin -Force
    }
    if (Test-Path "$ServerDir\bin\icon.ico") {
        Copy-Item -Path "$ServerDir\bin\icon.ico" -Destination $stagingBin -Force
    }

    Write-Host "[3/4] Staging plugins and configurations..." -ForegroundColor Yellow
    if (Test-Path "$ServerDir\plugins") {
        Copy-Item -Path "$ServerDir\plugins" -Destination $targetServer -Recurse -Force
    }
    if (Test-Path "$ServerDir\config") {
        Copy-Item -Path "$ServerDir\config" -Destination $targetServer -Recurse -Force
    }

    # World, logs, cache, and backups are purposefully omitted so the zip stays lean
    # and the co-host always downloads the newest world on first launch.
    Write-Host "[4/4] Compressing starter package into zip..." -ForegroundColor Yellow
    if (Test-Path $OutputFile) {
        Remove-Item $OutputFile -Force
    }
    Compress-Archive -Path "$staging\*" -DestinationPath $OutputFile -CompressionLevel Optimal

    $zipItem = Get-Item $OutputFile
    $mb = [math]::Round($zipItem.Length / 1MB, 2)
    Write-Host "========================================================" -ForegroundColor Green
    Write-Host "[SUCCESS] Cohost starter pack created successfully!" -ForegroundColor Green
    Write-Host "  Location : $OutputFile" -ForegroundColor Green
    Write-Host "  Zip Size : $mb MB" -ForegroundColor Green
    Write-Host "========================================================" -ForegroundColor Green
    Write-Host "Send this zip to your co-hosts (via Google Drive, Discord, etc.)!" -ForegroundColor White
    Write-Host "All they have to do is:" -ForegroundColor White
    Write-Host "  1. Extract the zip to any folder." -ForegroundColor White
    Write-Host "  2. Double-click run_server.bat." -ForegroundColor White
    Write-Host "The launcher will connect to Backblaze, pull the newest world, and start!" -ForegroundColor White
} finally {
    if (Test-Path $staging) {
        Remove-Item -Path $staging -Recurse -Force -ErrorAction SilentlyContinue
    }
}

