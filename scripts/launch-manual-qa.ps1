#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Session)
$ErrorActionPreference = 'Stop'
$qaWorkspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$qaSessions = [IO.Path]::GetFullPath((Join-Path $qaWorkspace 'build/manual-offset-qa'))
$qaSession = [IO.Path]::GetFullPath($Session)
if (-not $qaSession.StartsWith($qaSessions + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Manual session must be under build/manual-offset-qa.'
}
$qaRuntime = Join-Path $qaSession 'runtime'
$qaGame = Join-Path $qaWorkspace 'build/modpack-test/game'
$qaJava = 'C:/Program Files/Java/jdk-21/bin/javaw.exe'
foreach ($qaInput in @('manifest.json', 'classpath.txt', 'vm-args.txt', 'program-args.txt', 'runtime/unlockedcamera.manualqa.mixins.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $qaSession $qaInput))) { throw "Missing $qaInput" }
}
$qaManifest = Get-Content -LiteralPath (Join-Path $qaSession 'manifest.json') -Raw | ConvertFrom-Json
$qaRelease = Join-Path $qaSession 'unlockedcamera-1.0.3.jar'
if ((Get-FileHash -LiteralPath $qaRelease -Algorithm SHA256).Hash -ne $qaManifest.releaseSha256) {
    throw 'Frozen release hash does not match session manifest.'
}
foreach ($qaClass in $qaManifest.productionClasses.PSObject.Properties) {
    if ((Get-FileHash -LiteralPath (Join-Path $qaRuntime $qaClass.Name) -Algorithm SHA256).Hash -ne $qaClass.Value) {
        throw "Production class changed: $($qaClass.Name)"
    }
}
$qaProductionCount = @(Get-ChildItem -LiteralPath $qaRuntime -Recurse -Filter '*.class' |
    Where-Object { $_.FullName -notmatch '[\\/]qa[\\/]' }).Count
if ($qaProductionCount -ne $qaManifest.productionClassCount) { throw 'Unexpected production class count.' }
$qaFolders = 'unlockedcamera%%' + $qaRuntime
$qaExisting = @(Get-CimInstance Win32_Process -Filter "name='java.exe' or name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($qaRuntime) })
if ($qaExisting.Count) { throw 'This manual runtime already has a running process.' }
$qaLaunchStamp = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd_HHmmssZ')
$qaStdout = Join-Path $qaSession "client-$qaLaunchStamp.log"
$qaStderr = Join-Path $qaSession "client-$qaLaunchStamp.stderr.log"
$qaArguments = @(
    '"@' + (Join-Path $qaSession 'classpath.txt') + '"'
    '"@' + (Join-Path $qaSession 'vm-args.txt') + '"'
    '"-Dfml.modFolders=' + $qaFolders + '"'
    'net.neoforged.devlaunch.Main'
    '"@' + (Join-Path $qaSession 'program-args.txt') + '"'
)
$previousQaModClasses = [Environment]::GetEnvironmentVariable('MOD_CLASSES', 'Process')
try {
    $env:MOD_CLASSES = $qaFolders
    # A detached GUI process keeps the user's manual session independent of the
    # terminal tool session. The user explicitly requested an interactive game.
    $qaProcess = Start-Process -FilePath $qaJava -ArgumentList $qaArguments -WorkingDirectory $qaGame `
        -WindowStyle Normal -RedirectStandardOutput $qaStdout -RedirectStandardError $qaStderr -PassThru
    $qaLaunch = @{ processId = $qaProcess.Id; executable = $qaJava; stdout = $qaStdout; stderr = $qaStderr; startedUtc = $qaLaunchStamp }
    $qaLaunch | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $qaSession 'latest-launch.json') -Encoding utf8
    $qaLaunch | ConvertTo-Json
} finally {
    [Environment]::SetEnvironmentVariable('MOD_CLASSES', $previousQaModClasses, 'Process')
}
