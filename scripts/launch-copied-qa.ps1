#requires -Version 7.0
param(
    [ValidateSet('modpack-test', 'modpack-block-test')]
    [string]$Instance = 'modpack-test'
)
$ErrorActionPreference = 'Stop'
$qaWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$qaGame = Join-Path $qaWorkspace "build/$Instance/game"
$qaJava = 'C:/Program Files/Java/jdk-21/bin/java.exe'
$qaClasspath = Join-Path $qaWorkspace 'build/moddev/modpackTestRunClasspath.txt'
$qaVm = Join-Path $qaWorkspace 'build/moddev/modpackTestRunVmArgs.txt'
$qaProgram = Join-Path $qaWorkspace 'build/moddev/modpackTestRunProgramArgs.txt'
$qaClasses = Join-Path $qaWorkspace 'build/classes/java/main'
$qaResources = Join-Path $qaWorkspace 'build/resources/main'
foreach ($inputPath in @($qaGame, $qaJava, $qaClasspath, $qaVm, $qaProgram,
        (Join-Path $qaClasses 'com/caleb/unlockedcamera/qa/ModpackQa.class'))) {
    if (-not (Test-Path -LiteralPath $inputPath)) { throw "Missing prepared QA input: $inputPath" }
}
$qaFolders = 'unlockedcamera%%' + $qaClasses + ';unlockedcamera%%' + $qaResources
$previousQaModClasses = [Environment]::GetEnvironmentVariable('MOD_CLASSES', 'Process')
Push-Location -LiteralPath $qaGame
try {
    $env:MOD_CLASSES = $qaFolders
    & $qaJava "@$qaClasspath" "@$qaVm" "-Dfml.modFolders=$qaFolders" `
        'net.neoforged.devlaunch.Main' "@$qaProgram"
    $qaExitCode = $LASTEXITCODE
} finally {
    [Environment]::SetEnvironmentVariable('MOD_CLASSES', $previousQaModClasses, 'Process')
    Pop-Location
}
exit $qaExitCode
