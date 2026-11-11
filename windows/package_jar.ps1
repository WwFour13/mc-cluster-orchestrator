param(
    [Parameter(Mandatory=$true)][string]$SourceDir,
    [Parameter(Mandatory=$true)][string]$DestJar
)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$resolvedSource = (Resolve-Path $SourceDir).Path

# Ensure target directory exists
$destFolder = Split-Path -Parent $DestJar
if ($destFolder -and !(Test-Path $destFolder)) {
    New-Item -ItemType Directory -Path $destFolder -Force | Out-Null
}

$tempJar = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "mc_pack_$([System.Guid]::NewGuid().ToString('N')).jar")
if (Test-Path $tempJar) { Remove-Item $tempJar -Force }

$zip = [System.IO.Compression.ZipFile]::Open($tempJar, [System.IO.Compression.ZipArchiveMode]::Create)

Get-ChildItem -Path $resolvedSource -Recurse -File | ForEach-Object {
    $relPath = $_.FullName.Substring($resolvedSource.Length).TrimStart('\').Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relPath) | Out-Null
}

$zip.Dispose()

# Atomic replace with retry for Windows file handle locks
$moved = $false
for ($i = 0; $i -lt 5; $i++) {
    try {
        Move-Item -Path $tempJar -Destination $DestJar -Force -ErrorAction Stop
        $moved = $true
        break
    } catch {
        Start-Sleep -Milliseconds 250
    }
}
if (!$moved) {
    Copy-Item -Path $tempJar -Destination $DestJar -Force
    Remove-Item -Path $tempJar -Force -ErrorAction SilentlyContinue
}

$finalSize = (Get-Item $DestJar).Length
Write-Host "Packaged $DestJar successfully. Size: $finalSize bytes"

