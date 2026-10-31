#requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$Instance,
    [string]$TestWorld,
    [switch]$DisableDistantHorizons
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = (Resolve-Path -LiteralPath $Instance).Path
$qaRoot = Join-Path $repoRoot 'build/modpack-test'
$gameRoot = Join-Path $qaRoot 'game'
$sourcePrefix = $sourceRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($gameRoot.Equals($sourceRoot, [StringComparison]::OrdinalIgnoreCase) -or
        $gameRoot.StartsWith($sourcePrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The QA destination must be outside the source instance.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot 'mods') -PathType Container)) {
    throw "The source instance has no mods directory: $sourceRoot"
}
$worldSource = if ($TestWorld) { (Resolve-Path -LiteralPath $TestWorld).Path } else { $null }
if ($worldSource -and -not (Test-Path -LiteralPath $worldSource -PathType Container)) {
    throw "The test world must be a directory: $worldSource"
}
if ($worldSource) {
    $worldPrefix = $worldSource.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($gameRoot.Equals($worldSource, [StringComparison]::OrdinalIgnoreCase) -or
            $gameRoot.StartsWith($worldPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The QA destination must be outside the source test world.'
    }
}
if (Test-Path -LiteralPath $gameRoot) {
    throw "The QA copy already exists at $gameRoot. Preserve or rename it before preparing another copy."
}
New-Item -ItemType Directory -Path $gameRoot -Force | Out-Null
$manifest = @()
foreach ($folder in @('mods', 'config', 'defaultconfigs', 'kubejs', 'datapacks', 'resourcepacks', 'shaderpacks')) {
    $from = Join-Path $sourceRoot $folder
    if (-not (Test-Path -LiteralPath $from)) { continue }
    Copy-Item -LiteralPath $from -Destination $gameRoot -Recurse
    foreach ($file in Get-ChildItem -LiteralPath $from -Recurse -File) {
        $manifest += [pscustomobject]@{
            path = [IO.Path]::GetRelativePath($sourceRoot, $file.FullName).Replace('\', '/')
            size = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
}
if (Test-Path -LiteralPath (Join-Path $sourceRoot 'options.txt')) {
    $optionsSource = Join-Path $sourceRoot 'options.txt'
    Copy-Item -LiteralPath $optionsSource -Destination $gameRoot
    $manifest += [pscustomobject]@{
        path = 'options.txt'
        size = (Get-Item -LiteralPath $optionsSource).Length
        sha256 = (Get-FileHash -LiteralPath $optionsSource -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $qaRoot 'source-manifest.json')
foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $gameRoot 'mods') -Filter 'unlockedcamera-*.jar') {
    Move-Item -LiteralPath $jar.FullName -Destination (Join-Path $qaRoot ('installed-' + $jar.Name))
}
if ($DisableDistantHorizons) {
    foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $gameRoot 'mods') -Filter 'DistantHorizons-*.jar') {
        Rename-Item -LiteralPath $jar.FullName -NewName ($jar.Name + '.disabled')
    }
}
if ($worldSource) {
    $worldTarget = Join-Path $gameRoot 'saves/Camera QA'
    New-Item -ItemType Directory -Path $worldTarget -Force | Out-Null
    foreach ($item in Get-ChildItem -LiteralPath $worldSource -Force) {
        if ($item.Name -ne 'session.lock') { Copy-Item -LiteralPath $item.FullName -Destination $worldTarget -Recurse }
    }
}
Write-Output "Copied $($manifest.Count) files to $gameRoot. The source instance was read only."
