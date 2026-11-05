Set-Location $PSScriptRoot

Write-Host "[Emergency Unlock] Attempting cloud and local unlock..." -ForegroundColor Cyan
if (Test-Path "$PSScriptRoot\minecraft-server\ServerLauncher.jar") {
    & java -jar "$PSScriptRoot\minecraft-server\ServerLauncher.jar" unlock
} elseif (Test-Path "$PSScriptRoot\ServerLauncher.jar") {
    & java -jar "$PSScriptRoot\ServerLauncher.jar" unlock
} else {
    Remove-Item "$PSScriptRoot\minecraft-server\.server_active.lock" -Force -ErrorAction SilentlyContinue
    Remove-Item "$PSScriptRoot\.server_active.lock" -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] Local locks cleared." -ForegroundColor Green
}
