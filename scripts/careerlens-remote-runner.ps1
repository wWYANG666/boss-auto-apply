$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$runnerDirectory = Join-Path $projectRoot 'apps\career-runner'
$runnerConfig = Join-Path $runnerDirectory '.runner-data\start-real.env.cmd'
$runtimeDirectory = Join-Path $env:LOCALAPPDATA 'CareerLens\remote-runner'
$runnerLog = Join-Path $runtimeDirectory 'runner.log'
$runnerErrorLog = Join-Path $runtimeDirectory 'runner.err.log'
$tunnelLog = Join-Path $runtimeDirectory 'tunnel.log'

New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
$mutex = [Threading.Mutex]::new($false, 'Local\CareerLensRemoteRunner')
if (-not $mutex.WaitOne(0)) { exit 0 }

function Import-RunnerEnvironment {
    if (-not (Test-Path -LiteralPath $runnerConfig)) {
        throw "Runner configuration not found: $runnerConfig"
    }
    $lines = & $env:ComSpec /d /s /c "call `"$runnerConfig`" && set"
    foreach ($line in $lines) {
        $separator = $line.IndexOf('=')
        if ($separator -le 0) { continue }
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    $env:RUNNER_HOST = '127.0.0.1'
    $env:RUNNER_PORT = '43120'
    $env:RUNNER_MODE = 'real'
    $env:RUNNER_REAL_PLATFORM = 'true'
    $env:RUNNER_DATA_DIR = '.runner-data'
}

function Test-Runner {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:43120/health' -TimeoutSec 3
        return $health.mode -eq 'real'
    } catch { return $false }
}

try {
    Import-RunnerEnvironment
    while ($true) {
        if (-not (Test-Runner)) {
            Start-Process -FilePath 'npm.cmd' -ArgumentList @('start') -WorkingDirectory $runnerDirectory `
                -WindowStyle Hidden -RedirectStandardOutput $runnerLog -RedirectStandardError $runnerErrorLog
            for ($attempt = 0; $attempt -lt 30 -and -not (Test-Runner); $attempt++) {
                Start-Sleep -Seconds 1
            }
        }
        Add-Content -LiteralPath $tunnelLog -Value "$(Get-Date -Format o) connecting"
        $tunnel = Start-Process -FilePath 'ssh.exe' -ArgumentList @(
            '-N', '-T', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=30',
            '-o', 'ServerAliveCountMax=3', '-R', '127.0.0.1:43120:127.0.0.1:43120', 'myserver'
        ) -WindowStyle Hidden -PassThru -Wait
        Add-Content -LiteralPath $tunnelLog -Value "$(Get-Date -Format o) disconnected code=$($tunnel.ExitCode)"
        Start-Sleep -Seconds 5
    }
} finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
