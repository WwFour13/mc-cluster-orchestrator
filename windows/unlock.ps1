$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
Set-Location $repoRoot

Write-Host "[Emergency Unlock] Attempting cloud and local unlock..." -ForegroundColor Cyan
if (Test-Path "$repoRoot\minecraft-server\ServerLauncher.jar") {
    & java -jar "$repoRoot\minecraft-server\ServerLauncher.jar" unlock
} elseif (Test-Path "$repoRoot\ServerLauncher.jar") {
    & java -jar "$repoRoot\ServerLauncher.jar" unlock
} else {
    Remove-Item "$repoRoot\minecraft-server\.server_active.lock" -Force -ErrorAction SilentlyContinue
    Remove-Item "$repoRoot\.server_active.lock" -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] Local locks cleared." -ForegroundColor Green
}

