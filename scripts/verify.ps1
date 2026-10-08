[CmdletBinding()]
param(
    [switch]$SkipInstall,
    [switch]$SkipBrowserQa
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$aiDirectory = Join-Path $projectRoot 'services\ai-worker'
$runnerDirectory = Join-Path $projectRoot 'apps\career-runner'
$coreDirectory = Join-Path $projectRoot 'services\core-api'
$runtimeBase = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA 'CareerLens\verify'
} else {
    Join-Path ([System.IO.Path]::GetTempPath()) 'CareerLens\verify'
}
New-Item -ItemType Directory -Path $runtimeBase -Force | Out-Null

$results = [System.Collections.Generic.List[object]]::new()

function Resolve-RequiredCommand {
    param([string]$Name, [string]$InstallHint)

    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        throw "缺少 $Name。$InstallHint"
    }
    return $command.Source
}

function Invoke-CheckedCommand {
    param(
        [string]$FilePath,
        [string[]]$CommandArguments,
        [string]$WorkingDirectory
    )

    Push-Location $WorkingDirectory
    try {
        & $FilePath @CommandArguments
        if ($LASTEXITCODE -ne 0) {
            throw "退出码 $LASTEXITCODE：$FilePath $($CommandArguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-VerificationStep {
    param(
        [string]$Name,
        [scriptblock]$Run
    )

    Write-Host "`n==> $Name" -ForegroundColor Cyan
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $Run
        $timer.Stop()
        $results.Add([pscustomobject]@{
            Step = $Name
            Status = 'PASS'
            Seconds = [Math]::Round($timer.Elapsed.TotalSeconds, 1)
            Detail = ''
        })
        Write-Host "[通过] $Name" -ForegroundColor Green
    } catch {
        $timer.Stop()
        $results.Add([pscustomobject]@{
            Step = $Name
            Status = 'FAIL'
            Seconds = [Math]::Round($timer.Elapsed.TotalSeconds, 1)
            Detail = $_.Exception.Message
        })
        Write-Host "[失败] $Name：$($_.Exception.Message)" -ForegroundColor Red
    }
}

function Add-SkippedStep {
    param([string]$Name, [string]$Reason)
    $results.Add([pscustomobject]@{
        Step = $Name
        Status = 'SKIP'
        Seconds = 0
        Detail = $Reason
    })
    Write-Host "`n[跳过] $Name：$Reason" -ForegroundColor Yellow
}

function Test-HttpReady {
    param([string]$Uri)
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 300
    } catch {
        return $false
    }
}

function Wait-HttpReady {
    param([string]$Uri, [int]$TimeoutSeconds)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpReady $Uri) { return }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "测试 Web 未在 $TimeoutSeconds 秒内就绪：$Uri"
}

function Find-JavaHome21 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source)))
    }

    @(
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Amazon Corretto')
    ) | ForEach-Object {
        if (Test-Path -LiteralPath $_) {
            Get-ChildItem -LiteralPath $_ -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable)) { continue }
        $savedErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $versionText = (& $javaExecutable -version 2>&1 | Out-String)
            $javaExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $savedErrorPreference
        }
        if ($javaExitCode -eq 0 -and $versionText -match 'version "21(?:\.|\-)') {
            return $candidate
        }
    }
    throw '未找到 JDK 21。请安装 JDK 21，或将 JAVA_HOME 指向 JDK 21。'
}

$node = Resolve-RequiredCommand 'node.exe' '请安装 Node.js 24。'
$npm = Resolve-RequiredCommand 'npm.cmd' '请安装 Node.js 24（包含 npm）。'
$python = Resolve-RequiredCommand 'python.exe' '请安装 Python 3.11 或更高版本。'
$javaHome21 = Find-JavaHome21
$venvPython = Join-Path $aiDirectory '.venv\Scripts\python.exe'

if (-not $SkipInstall) {
    Invoke-VerificationStep '安装 Web 依赖（npm ci）' {
        Invoke-CheckedCommand $npm @('ci') $projectRoot
    }
    Invoke-VerificationStep '安装 Runner 依赖（npm ci）' {
        Invoke-CheckedCommand $npm @('ci') $runnerDirectory
    }
    Invoke-VerificationStep '安装 AI Worker 依赖' {
        if (-not (Test-Path -LiteralPath $venvPython)) {
            Invoke-CheckedCommand $python @('-m', 'venv', '.venv') $aiDirectory
        }
        Invoke-CheckedCommand $venvPython @('-m', 'pip', 'install', '--disable-pip-version-check', '-r', 'requirements-dev.txt') $aiDirectory
    }
}

$testPython = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { $python }

Invoke-VerificationStep 'Web 类型检查' {
    Invoke-CheckedCommand $npm @('run', 'typecheck') $projectRoot
}
Invoke-VerificationStep 'Web 生产构建' {
    Invoke-CheckedCommand $npm @('run', 'build') $projectRoot
}

Invoke-VerificationStep 'AI Worker 单元测试' {
    Invoke-CheckedCommand $testPython @('-m', 'pytest', '-q') $aiDirectory
}
Invoke-VerificationStep 'AI Worker Ruff' {
    Invoke-CheckedCommand $testPython @('-m', 'ruff', 'check', 'app', 'tests') $aiDirectory
}
Invoke-VerificationStep 'AI Worker mypy' {
    Invoke-CheckedCommand $testPython @('-m', 'mypy', 'app') $aiDirectory
}

Invoke-VerificationStep 'Runner 类型检查' {
    Invoke-CheckedCommand $npm @('run', 'typecheck') $runnerDirectory
}
Invoke-VerificationStep 'Runner 单元测试' {
    Invoke-CheckedCommand $npm @('test') $runnerDirectory
}
Invoke-VerificationStep 'Runner 生产构建' {
    Invoke-CheckedCommand $npm @('run', 'build') $runnerDirectory
}

Invoke-VerificationStep 'Core API 集成测试与打包' {
    $oldJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    $oldPath = [Environment]::GetEnvironmentVariable('Path', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $javaHome21, 'Process')
        [Environment]::SetEnvironmentVariable('Path', "$(Join-Path $javaHome21 'bin');$oldPath", 'Process')
        Invoke-CheckedCommand (Join-Path $coreDirectory 'mvnw.cmd') @('--batch-mode', '--no-transfer-progress', 'verify') $coreDirectory
    } finally {
        [Environment]::SetEnvironmentVariable('JAVA_HOME', $oldJavaHome, 'Process')
        [Environment]::SetEnvironmentVariable('Path', $oldPath, 'Process')
    }
}

$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dockerCommand) {
    Invoke-VerificationStep 'Docker Compose 配置' {
        Invoke-CheckedCommand $dockerCommand.Source @('compose', 'config', '--quiet') $projectRoot
    }
} else {
    Add-SkippedStep 'Docker Compose 配置' '本机未安装 Docker；其余验证不依赖 Docker。'
}

if ($SkipBrowserQa) {
    Add-SkippedStep '浏览器交互与响应式检查' '已指定 -SkipBrowserQa。'
} else {
    Invoke-VerificationStep '浏览器交互与响应式检查' {
        $oldQaJava = $env:JAVA_HOME
        try {
            $env:JAVA_HOME = $javaHome21
            Invoke-CheckedCommand $node @('scripts/fullstack-qa.mjs') $projectRoot
        } finally { $env:JAVA_HOME = $oldQaJava }
    }
}

Write-Host "`n================ 验证汇总 ================" -ForegroundColor Cyan
$results | Format-Table Step, Status, Seconds, Detail -Wrap -AutoSize

$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
if ($failed.Count -gt 0) {
    Write-Host "验证结束：$($failed.Count) 项失败。" -ForegroundColor Red
    exit 1
}

Write-Host '验证结束：所有已执行项目均通过。' -ForegroundColor Green
exit 0
