[CmdletBinding()]
param(
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',
    [switch]$UsePostgres,
    [switch]$RealRunner,
    [switch]$SkipInstall,
    [switch]$OpenBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$runtimeBase = if ($env:LOCALAPPDATA) {
    Join-Path $env:LOCALAPPDATA 'CareerLens\dev'
} else {
    Join-Path ([System.IO.Path]::GetTempPath()) 'CareerLens\dev'
}
$logDirectory = Join-Path $runtimeBase 'logs'
$processFile = Join-Path $runtimeBase 'processes.json'

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

function Rotate-LogFile {
    param([string]$Path, [long]$MaximumBytes = 10485760, [int]$Keep = 8)
    if (-not (Test-Path -LiteralPath $Path) -or (Get-Item -LiteralPath $Path).Length -lt $MaximumBytes) {
        return
    }
    $resolvedDirectory = [System.IO.Path]::GetFullPath((Split-Path -Parent $Path))
    if ($resolvedDirectory -ne [System.IO.Path]::GetFullPath($logDirectory)) {
        throw "拒绝轮转工作目录之外的日志：$Path"
    }
    $leaf = [System.IO.Path]::GetFileNameWithoutExtension($Path)
    $archive = Join-Path $logDirectory "$leaf.$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')).archive.log"
    Move-Item -LiteralPath $Path -Destination $archive
    Get-ChildItem -LiteralPath $logDirectory -Filter "$leaf.*.archive.log" -File |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -Skip $Keep |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
}

function Write-Stage {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Resolve-RequiredCommand {
    param([string]$Name, [string]$InstallHint)

    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        throw "缺少 $Name。$InstallHint"
    }
    return $command.Source
}

function Import-RealRunnerEnvironment {
    if (-not $RealRunner) { return }
    $environmentFile = Join-Path $runnerDirectory '.runner-data\start-real.env.cmd'
    if (-not (Test-Path -LiteralPath $environmentFile)) { return }
    $allowed = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    @(
        'RUNNER_APPROVAL_SECRET', 'RUNNER_ENCRYPTION_KEY', 'BROWSER_EXECUTABLE_PATH',
        'BOSS_USER_DATA_DIR', 'BOSS_BROWSER_MODE', 'BOSS_CDP_PORT', 'BOSS_CDP_ENDPOINT',
        'BOSS_CITY_CODES', 'AMAP_WEB_SERVICE_KEY', 'LIEPIN_MCP_TOKEN', 'LIEPIN_MCP_ENDPOINT'
    ) | ForEach-Object { [void]$allowed.Add($_) }
    foreach ($line in Get-Content -LiteralPath $environmentFile) {
        if ($line -notmatch '^\s*set\s+"?([^=" ]+)=(.*?)"?\s*$') { continue }
        $name = $Matches[1]
        if (-not $allowed.Contains($name) -or [Environment]::GetEnvironmentVariable($name, 'Process')) { continue }
        [Environment]::SetEnvironmentVariable($name, $Matches[2].TrimEnd('"'), 'Process')
    }
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
            throw "命令执行失败（退出码 $LASTEXITCODE）：$FilePath $($CommandArguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
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
    param(
        [string]$Name,
        [string]$Uri,
        [int]$TimeoutSeconds = 180
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpReady $Uri) {
            Write-Host "[就绪] $Name -> $Uri" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)

    throw "$Name 未在 $TimeoutSeconds 秒内就绪。请查看 $logDirectory。"
}

function Read-ProcessRecords {
    if (-not (Test-Path -LiteralPath $processFile)) {
        return @()
    }
    try {
        $parsedRecords = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
        foreach ($parsedRecord in $parsedRecords) {
            Write-Output $parsedRecord
        }
    } catch {
        Write-Warning "进程记录损坏，将忽略：$processFile"
        return @()
    }
}

function Test-TrackedProcess {
    param($Record)

    try {
        $process = Get-Process -Id ([int]$Record.pid) -ErrorAction Stop
        $actual = $process.StartTime.ToUniversalTime()
        $expected = [DateTime]::Parse([string]$Record.startedAtUtc).ToUniversalTime()
        return [Math]::Abs(($actual - $expected).TotalSeconds) -lt 3
    } catch {
        return $false
    }
}

function Stop-VerifiedWorkspaceListener {
    param([int]$Port, [string[]]$CommandMarkers, [string]$Name, [string]$Taskkill)
    $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)" -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        $commandLine = [string]$process.CommandLine
        $matches = $true
        foreach ($marker in $CommandMarkers) {
            if ($commandLine -notlike "*$marker*") { $matches = $false; break }
        }
        if (-not $matches) {
            Write-Warning "端口 $Port 被非CareerLens进程占用，未停止：PID $($connection.OwningProcess)"
            continue
        }
        Write-Host "[停止遗留] $Name（PID $($connection.OwningProcess)）"
        & $Taskkill /PID ([string]$connection.OwningProcess) /T /F | Out-Null
    }
}

function Save-ProcessRecords {
    param([object[]]$Records)

    $json = ConvertTo-Json -InputObject @($Records) -Depth 4
    Set-Content -LiteralPath $processFile -Value $json -Encoding UTF8
}

function Sync-TrackedListenerPid {
    param([string]$Name,[int]$Port,[ref]$Records)
    $listener=Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Select-Object -First 1
    if(-not $listener){return}
    $record=@($Records.Value|Where-Object {$_.name -eq $Name}|Select-Object -First 1)
    if($record){$record | Add-Member -Force -NotePropertyName pid -NotePropertyValue ([int]$listener.OwningProcess);$Records.Value=@($Records.Value)}
}

function Start-TrackedProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$CommandArguments,
        [string]$WorkingDirectory,
        [hashtable]$EnvironmentVariables
    )

    $stdout = Join-Path $logDirectory "$Name.out.log"
    $stderr = Join-Path $logDirectory "$Name.err.log"
    Rotate-LogFile -Path $stdout
    Rotate-LogFile -Path $stderr
    $previousValues = @{}

    try {
        foreach ($key in $EnvironmentVariables.Keys) {
            $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, [string]$EnvironmentVariables[$key], 'Process')
        }

        $process = Start-Process `
            -FilePath $FilePath `
            -ArgumentList $CommandArguments `
            -WorkingDirectory $WorkingDirectory `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
    } finally {
        foreach ($key in $EnvironmentVariables.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
        }
    }

    Write-Host "[启动] $Name（PID $($process.Id)）" -ForegroundColor DarkCyan
    return [pscustomobject]@{
        name = $Name
        pid = $process.Id
        startedAtUtc = $process.StartTime.ToUniversalTime().ToString('O')
        stdout = $stdout
        stderr = $stderr
    }
}

function Find-JavaHome21 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javaCommand) {
        $candidates.Add((Split-Path -Parent (Split-Path -Parent $javaCommand.Source)))
    }

    $searchRoots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:ProgramFiles 'Amazon Corretto')
    )
    foreach ($searchRoot in $searchRoots) {
        if (Test-Path -LiteralPath $searchRoot) {
            Get-ChildItem -LiteralPath $searchRoot -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable)) {
            continue
        }
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

function Show-Status {
    Write-Stage '脚本进程'
    $records = @(Read-ProcessRecords)
    if ($records.Count -eq 0) {
        Write-Host '没有进程记录。'
    } else {
        $records | ForEach-Object {
            [pscustomobject]@{
                Service = $_.name
                PID = $_.pid
                Running = Test-TrackedProcess $_
                Log = $_.stdout
            }
        } | Format-Table -AutoSize
    }

    Write-Stage '服务健康状态'
    @(
        [pscustomobject]@{ Service = 'web'; Uri = 'http://127.0.0.1:8888'; Ready = Test-HttpReady 'http://127.0.0.1:8888' }
        [pscustomobject]@{ Service = 'core-api'; Uri = 'http://127.0.0.1:18080/api/v1/health'; Ready = Test-HttpReady 'http://127.0.0.1:18080/api/v1/health' }
        [pscustomobject]@{ Service = 'ai-worker'; Uri = 'http://127.0.0.1:8001/internal/v1/health'; Ready = Test-HttpReady 'http://127.0.0.1:8001/internal/v1/health' }
        [pscustomobject]@{ Service = 'career-runner'; Uri = 'http://127.0.0.1:43120/health'; Ready = Test-HttpReady 'http://127.0.0.1:43120/health' }
    ) | Format-Table -AutoSize
}

if ($Action -eq 'status') {
    Show-Status
    exit 0
}

if ($Action -eq 'stop') {
    Write-Stage '停止脚本启动的进程'
    $records = @(Read-ProcessRecords)
    $taskkill = Resolve-RequiredCommand 'taskkill.exe' '该命令应随 Windows 提供。'
    foreach ($record in $records) {
        if (Test-TrackedProcess $record) {
            Write-Host "[停止] $($record.name)（PID $($record.pid)）"
            & $taskkill /PID ([string]$record.pid) /T /F | Out-Null
        }
    }
    Stop-VerifiedWorkspaceListener 8001 @('uvicorn', 'app.main:app') 'ai-worker' $taskkill
    Stop-VerifiedWorkspaceListener 43120 @('dist/src/index.js') 'career-runner' $taskkill
    Stop-VerifiedWorkspaceListener 18080 @('CareerLensApplication') 'core-api' $taskkill
    Stop-VerifiedWorkspaceListener 8888 @('vite', '8888') 'web' $taskkill
    Save-ProcessRecords @()
    Write-Host '本地服务已停止。PostgreSQL/Redis 容器不会被自动停止。' -ForegroundColor Green
    exit 0
}

$node = Resolve-RequiredCommand 'node.exe' '请安装 Node.js 24。'
$npm = Resolve-RequiredCommand 'npm.cmd' '请安装 Node.js 24（包含 npm）。'
$python = Resolve-RequiredCommand 'python.exe' '请安装 Python 3.11 或更高版本。'
$javaHome21 = Find-JavaHome21

$nodeMajor = [int]((& $node -p 'parseInt(process.versions.node)').Trim())
if ($nodeMajor -lt 24) {
    throw "当前 Node.js 主版本为 $nodeMajor，项目要求 Node.js 24 或更高版本。"
}
$pythonVersion = (& $python -c 'import sys; print(sys.version.split()[0])').Trim()
if ([Version]$pythonVersion -lt [Version]'3.11') {
    throw "当前 Python 版本为 $pythonVersion，项目要求 Python 3.11 或更高版本。"
}

$aiDirectory = Join-Path $projectRoot 'services\ai-worker'
$runnerDirectory = Join-Path $projectRoot 'apps\career-runner'
$coreDirectory = Join-Path $projectRoot 'services\core-api'
$venvPython = Join-Path $aiDirectory '.venv\Scripts\python.exe'

if (-not $SkipInstall) {
    Write-Stage '检查并安装开发依赖'
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'node_modules\.package-lock.json'))) {
        Invoke-CheckedCommand $npm @('ci') $projectRoot
    }
    if (-not (Test-Path -LiteralPath (Join-Path $runnerDirectory 'node_modules\.package-lock.json'))) {
        Invoke-CheckedCommand $npm @('ci') $runnerDirectory
    }
    if (-not (Test-Path -LiteralPath $venvPython)) {
        Invoke-CheckedCommand $python @('-m', 'venv', '.venv') $aiDirectory
    }

    $dependencyMarker = Join-Path $aiDirectory '.venv\.careerlens-deps-ready'
    $requirements = Join-Path $aiDirectory 'requirements-dev.txt'
    $installPythonDependencies = -not (Test-Path -LiteralPath $dependencyMarker)
    if (-not $installPythonDependencies) {
        $installPythonDependencies = (Get-Item -LiteralPath $requirements).LastWriteTimeUtc -gt (Get-Item -LiteralPath $dependencyMarker).LastWriteTimeUtc
    }
    if ($installPythonDependencies) {
        Invoke-CheckedCommand $venvPython @('-m', 'pip', 'install', '--disable-pip-version-check', '-r', 'requirements-dev.txt') $aiDirectory
        Set-Content -LiteralPath $dependencyMarker -Value ([DateTime]::UtcNow.ToString('O')) -Encoding UTF8
    }
}

if (-not (Test-Path -LiteralPath $venvPython)) {
    throw 'AI Worker 虚拟环境不存在。请移除 -SkipInstall 后重试。'
}

Import-RealRunnerEnvironment

$coreProfile = 'local'
$coreEnvironment = @{
    'JAVA_HOME' = $javaHome21
    'SPRING_PROFILES_ACTIVE' = 'local'
    'AI_WORKER_URL' = 'http://127.0.0.1:8001'
    'RUNNER_URL' = 'http://127.0.0.1:43120'
    'DEMO_DATA_ENABLED' = 'false'
    'SERVER_PORT' = '18080'
}
if ($env:RUNNER_ENCRYPTION_KEY) { $coreEnvironment['RUNNER_ENCRYPTION_KEY'] = $env:RUNNER_ENCRYPTION_KEY }

if ($UsePostgres) {
    Write-Stage '启动 PostgreSQL 与 Redis'
    $docker = Resolve-RequiredCommand 'docker.exe' '请安装并启动 Docker Desktop。'
    Invoke-CheckedCommand $docker @('compose', 'up', '-d', 'postgres', 'redis') $projectRoot
    $databaseDeadline = [DateTime]::UtcNow.AddSeconds(90)
    do {
        & $docker compose exec -T postgres pg_isready -U careerlens -d careerlens *> $null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $databaseDeadline)
    if ($LASTEXITCODE -ne 0) {
        throw 'PostgreSQL 未在 90 秒内就绪。请运行 docker compose logs postgres 查看原因。'
    }

    $databasePassword = if ($env:POSTGRES_PASSWORD) { $env:POSTGRES_PASSWORD } else { 'careerlens_dev_password' }
    $coreProfile = 'postgres'
    $coreEnvironment['SPRING_PROFILES_ACTIVE'] = 'postgres'
    $coreEnvironment['DB_URL'] = 'jdbc:postgresql://127.0.0.1:5432/careerlens'
    $coreEnvironment['DB_USER'] = 'careerlens'
    $coreEnvironment['DB_PASSWORD'] = $databasePassword
}

$records = @(Read-ProcessRecords | Where-Object { Test-TrackedProcess $_ })

Write-Stage '启动 AI Worker'
if (-not (Test-HttpReady 'http://127.0.0.1:8001/internal/v1/health')) {
    $records += Start-TrackedProcess 'ai-worker' $venvPython @('-m', 'uvicorn', 'app.main:app', '--reload', '--host', '127.0.0.1', '--port', '8001') $aiDirectory @{
        'AI_WORKER_MODE' = 'offline'
    }
    Save-ProcessRecords $records
}
Wait-HttpReady 'AI Worker' 'http://127.0.0.1:8001/internal/v1/health' 90

$runnerMode = if ($RealRunner) { 'real' } else { 'fake' }
$runnerRealPlatform = if ($RealRunner) { 'true' } else { 'false' }
if ($RealRunner) {
    if (-not $env:RUNNER_APPROVAL_SECRET -or $env:RUNNER_APPROVAL_SECRET.Length -lt 32) {
        throw '真实Runner要求RUNNER_APPROVAL_SECRET至少32个字符。'
    }
    if (-not $env:RUNNER_ENCRYPTION_KEY -or $env:RUNNER_ENCRYPTION_KEY.Length -lt 32) {
        throw '真实Runner要求RUNNER_ENCRYPTION_KEY至少32个字符，并保持稳定。'
    }
}
$runnerEnvironment = @{
    'RUNNER_HOST' = '127.0.0.1'
    'RUNNER_PORT' = '43120'
    'RUNNER_MODE' = $runnerMode
    'RUNNER_DATA_DIR' = '.runner-data'
    'RUNNER_APPROVAL_SECRET' = $env:RUNNER_APPROVAL_SECRET
    'RUNNER_REAL_PLATFORM' = $runnerRealPlatform
}
foreach ($name in @(
    'BROWSER_EXECUTABLE_PATH', 'BOSS_USER_DATA_DIR', 'BOSS_BROWSER_MODE', 'BOSS_CDP_PORT', 'BOSS_CDP_ENDPOINT', 'BOSS_CITY_CODES', 'BOSS_ACCOUNT_SELECTOR',
    'BOSS_CONVERSATION_JOB_SELECTOR', 'BOSS_CONVERSATION_COMPANY_SELECTOR',
    'BOSS_CONVERSATION_ROLE_SELECTOR', 'BOSS_SENT_MESSAGE_SELECTOR',
    'BOSS_MAX_JOBS_PER_PAGE', 'BOSS_DETAIL_JOB_LIMIT', 'BOSS_DETAIL_DELAY_MIN_MS', 'BOSS_DETAIL_DELAY_MAX_MS',
    'BOSS_SUBMIT_DELAY_MIN_MS', 'BOSS_SUBMIT_DELAY_MAX_MS', 'BOSS_SUBMIT_WINDOW_MS', 'BOSS_SUBMIT_WINDOW_MAX',
    'BOSS_BROWSER_RECYCLE_TASKS',
    'AMAP_WEB_SERVICE_KEY',
    'BOSS_RESUME_INDEX', 'BOSS_RESUME_SEND_SELECTOR', 'BOSS_RESUME_PANEL_CONFIRM_SELECTOR',
    'BOSS_RESUME_LIST_SELECTOR', 'BOSS_RESUME_ITEM_SELECTOR', 'BOSS_RESUME_CONFIRM_SELECTOR',
    'BOSS_SENT_RESUME_SELECTOR',
    'LIEPIN_MCP_TOKEN', 'LIEPIN_MCP_ENDPOINT'
)) {
    $value = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ($value) { $runnerEnvironment[$name] = $value }
}

Write-Stage "启动 Career Runner（$runnerMode 模式）"
if ($RealRunner -and (Test-HttpReady 'http://127.0.0.1:43120/health')) {
    $currentRunner = Invoke-RestMethod -Uri 'http://127.0.0.1:43120/health' -TimeoutSec 3
    if ($currentRunner.mode -ne 'real') {
        throw '43120端口上的Runner仍是测试模式。请先运行 -Action stop，再使用 -RealRunner 启动。'
    }
}
if (-not (Test-HttpReady 'http://127.0.0.1:43120/health')) {
    $runnerArguments = if ($RealRunner) { @('start') } else { @('run', 'dev') }
    $records += Start-TrackedProcess 'career-runner' $npm $runnerArguments $runnerDirectory $runnerEnvironment
    Save-ProcessRecords $records
}
Wait-HttpReady 'Career Runner' 'http://127.0.0.1:43120/health' 90
Sync-TrackedListenerPid 'career-runner' 43120 ([ref]$records); Save-ProcessRecords $records

Write-Stage "启动 Core API（$coreProfile）"
if (-not (Test-HttpReady 'http://127.0.0.1:18080/api/v1/health')) {
    $records += Start-TrackedProcess 'core-api' $env:ComSpec @('/d', '/s', '/c', 'mvnw.cmd -q spring-boot:run') $coreDirectory $coreEnvironment
    Save-ProcessRecords $records
}
Wait-HttpReady 'Core API' 'http://127.0.0.1:18080/api/v1/health' 240
Sync-TrackedListenerPid 'core-api' 18080 ([ref]$records); Save-ProcessRecords $records

Write-Stage '启动 Web'
if (-not (Test-HttpReady 'http://127.0.0.1:8888')) {
    $records += Start-TrackedProcess 'web' $npm @('run', 'dev', '--', '--host', '127.0.0.1', '--port', '8888', '--strictPort') $projectRoot @{
        'VITE_API_BASE_URL' = 'http://127.0.0.1:18080/api/v1'
    }
    Save-ProcessRecords $records
}
Wait-HttpReady 'Web' 'http://127.0.0.1:8888' 90
Sync-TrackedListenerPid 'web' 8888 ([ref]$records); Save-ProcessRecords $records

Write-Host "`nCareerLens 本地开发环境已就绪。" -ForegroundColor Green
Write-Host 'Web:     http://127.0.0.1:8888'
Write-Host 'API:     http://127.0.0.1:18080/swagger-ui.html'
Write-Host 'AI:      http://127.0.0.1:8001/docs'
Write-Host 'Runner:  http://127.0.0.1:43120/health'
Write-Host "日志:    $logDirectory"
Write-Host '停止:    .\scripts\start-dev.ps1 -Action stop'

if ($OpenBrowser) {
    Start-Process 'http://127.0.0.1:8888'
}
