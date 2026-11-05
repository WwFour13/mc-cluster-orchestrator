$wsh = New-Object -ComObject WScript.Shell
$dir = $PSScriptRoot
$targetBat = Join-Path $dir "run_server.bat"
$iconPath = Join-Path $dir "bin\icon.ico"
$desktop = [Environment]::GetFolderPath('Desktop')
$programs = [Environment]::GetFolderPath('Programs')

# 1. Desktop Shortcut
$scDesk = $wsh.CreateShortcut((Join-Path $desktop "Minecraft Cluster Server.lnk"))
$scDesk.TargetPath = $env:ComSpec
$scDesk.Arguments = "/c `"`"$targetBat`"`""
$scDesk.WorkingDirectory = $dir
if (Test-Path $iconPath) { $scDesk.IconLocation = "$iconPath,0" }
$scDesk.Description = "Start the Minecraft Cluster Server"
$scDesk.Save()

# 2. Start Menu / App Search Shortcut
$scStart = $wsh.CreateShortcut((Join-Path $programs "Minecraft Cluster Server.lnk"))
$scStart.TargetPath = $env:ComSpec
$scStart.Arguments = "/c `"`"$targetBat`"`""
$scStart.WorkingDirectory = $dir
if (Test-Path $iconPath) { $scStart.IconLocation = "$iconPath,0" }
$scStart.Description = "Start the Minecraft Cluster Server"
$scStart.Save()

Write-Host "========================================================" -ForegroundColor Green
Write-Host " [SUCCESS] Minecraft Cluster Server button installed!" -ForegroundColor Green
Write-Host "========================================================" -ForegroundColor Green
Write-Host ""
Write-Host " 1. Desktop: Check your Desktop for the 'Minecraft Cluster Server' icon." -ForegroundColor White
Write-Host " 2. Windows Search: Press the Windows Key and type 'Minecraft Cluster' anytime." -ForegroundColor White
Write-Host " 3. Taskbar: Right-click the Desktop icon and choose 'Pin to taskbar'!" -ForegroundColor White
