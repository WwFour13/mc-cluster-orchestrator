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

if (Test-Path $DestJar) {
    Remove-Item $DestJar -Force
}

$zip = [System.IO.Compression.ZipFile]::Open($DestJar, [System.IO.Compression.ZipArchiveMode]::Create)

Get-ChildItem -Path $resolvedSource -Recurse -File | ForEach-Object {
    $relPath = $_.FullName.Substring($resolvedSource.Length).TrimStart('\').Replace('\', '/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relPath) | Out-Null
}

$zip.Dispose()
$finalSize = (Get-Item $DestJar).Length
Write-Host "Packaged $DestJar successfully. Size: $finalSize bytes"
