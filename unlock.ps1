# Emergency Cluster Lock Release Tool
$locations = @(
    "$PSScriptRoot\minecraft-server\.server_active.lock",
    "$PSScriptRoot\.server_active.lock",
    "$PSScriptRoot\..\minecraft-server\.server_active.lock"
)

$found = $false
foreach ($loc in $locations) {
    if (Test-Path $loc) {
        Remove-Item $loc -Force
        Write-Host "[OK] Emergency cluster lock released at: $loc" -ForegroundColor Green
        $found = $true
    }
}

if (-not $found) {
    Write-Host "[INFO] No active cluster lock found (.server_active.lock)." -ForegroundColor Yellow
}

