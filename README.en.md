# CareerLens

[中文 README](README.md)

A personal job-search workspace for resume editing, job matching, grounded suggestions, and application follow-up.

The web interface uses Vue 3 and TypeScript. The business API uses Spring Boot 3.4 and Java 21. FastAPI handles document processing and scoring, while the local Runner connects to recruiting platforms.

## Support the Project

If this project helps you, please consider giving it a Star on GitHub.

This repository contains sanitized source code, configuration templates, and synthetic test fixtures. Register a new account on first use and create or import your own resume. Accounts, jobs, and application records start empty.

## Features

| Module | Features |
| --- | --- |
| Resume workspace | Dynamic sections, autosave, published versions, history comparison and restore, PDF export |
| Document import | TXT, PDF, and DOCX parsing with source spans and human review |
| Job matching | JD requirement extraction, skill and experience evidence, scoring, and missing-item display |
| Suggestions | No-JD draft review, job-targeted optimization, item-by-item review, and apply-as-new-version |
| Job discovery | Platform connections, keyword and salary filters, recruiter and company filters, optional commute filtering |
| Task management | Approval, pause, resume, stop, persisted execution state, and result reconciliation |
| Follow-up | Manual records, stage board, timeline, next actions, and business backup/restore |

### Typical workflow

1. Register an account, create or import a resume, and review the parsed fields.
2. Edit and publish a resume version, then select a target job or enter a JD.
3. Review matching evidence and suggestions, then apply approved changes to a new version.
4. Configure the local Runner, connect a platform, and discover and filter jobs.
5. Review the job, resume version, and greeting before approving execution.
6. Reconcile the result and continue follow-up from the application board.

## Architecture

~~~mermaid
flowchart LR
    Web[Vue 3 Web UI] --> Core[Spring Boot Core API]
    Core --> DB[(H2 / PostgreSQL)]
    Core --> AI[FastAPI AI Worker]
    Core --> Runner[Local Career Runner]
    Runner --> Platforms[BOSS / Liepin]
~~~

| Component | Responsibility |
| --- | --- |
| Web | Resume editing, match reports, review, and task management |
| Core API | Account isolation, business data, resume versions, approvals, and background tasks |
| AI Worker | Document parsing, rule-based scoring, suggestions, and PDF generation |
| Career Runner | Local browser sessions, platform adapters, serial execution, and receipt verification |
| Database | H2 for Windows local mode; PostgreSQL for Docker mode |

Redis is included in the Docker environment as reserved infrastructure. Background work is driven by the database outbox.

## Quick Start: Windows

### Requirements

| Tool | Requirement |
| --- | --- |
| Git | Required to clone the repository |
| Node.js | 24 or later; CI uses 24 |
| Java | JDK 21; set JAVA_HOME when needed |
| Python | 3.11 or later; CI uses 3.12 |

The first startup downloads npm, Python, and Maven dependencies. The repository includes the Maven Wrapper, so Maven does not need to be installed separately.

### Clone and start

Open PowerShell in the directory where you want to store the project. Copy only the commands inside the code block, without the surrounding Markdown fences.

~~~powershell
git clone https://github.com/wWYANG666/BOSS.git
Set-Location .\BOSS
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -OpenBrowser
~~~

If the source is already downloaded, enter the project root and run only the startup command. The root should contain package.json, scripts/, and README.md.

The script installs frontend and Runner dependencies, creates a Python virtual environment, and starts the services. The default uses local H2, offline rules, and the fake Runner, so resume editing, JD matching, and manual application tracking can be used first.

| Service | Windows local address |
| --- | --- |
| Web UI | http://127.0.0.1:8888 |
| Core API docs | http://127.0.0.1:18080/swagger-ui.html |
| AI Worker docs | http://127.0.0.1:8001/docs |
| Runner health | http://127.0.0.1:43120/health |

### Check status and stop

Run these commands from the project root:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -Action status
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -Action stop
~~~

You can also double-click 启动CareerLens.bat or 停止CareerLens.bat. The startup batch file uses the fake Runner by default.

After dependencies are installed, -SkipInstall can speed up startup. Stop and restart the services after changing service environment variables.

## Docker

Install and start Docker Engine or Docker Desktop. From the project root, create a local environment file if one does not exist:

~~~powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
~~~

Edit .env, change the development database password, and set independent random values of at least 32 characters for RUNNER_APPROVAL_SECRET and RUNNER_ENCRYPTION_KEY. Then start the stack:

~~~powershell
docker compose up --build -d
~~~

The Docker Web UI is available at http://127.0.0.1. Core API documentation remains on port 18080 at http://127.0.0.1:18080/swagger-ui.html. Database and service files are stored in named volumes.

~~~powershell
docker compose ps
docker compose logs --tail 100
docker compose down
~~~

The Compose Runner is fixed to fake. The real workspace rejects jobs and receipts from test mode; real BOSS browser execution requires the Windows local Runner.

## Configuration

The Windows startup script reads environment variables from the current PowerShell process. A regular .env file is not automatically loaded for the local backend or Runner. Docker Compose uses the root .env for variable substitution.

| Variable | Purpose |
| --- | --- |
| RUNNER_APPROVAL_SECRET | Runner approval-signing secret; real mode requires at least 32 characters |
| RUNNER_ENCRYPTION_KEY | Core encryption key for device credentials; pairing requires at least 32 characters and the value must remain stable |
| BROWSER_EXECUTABLE_PATH | Optional Chrome or Edge executable path |
| BOSS_BROWSER_MODE | Defaults to auto; visible browser for login and background browser for normal execution |
| BOSS_CITY_CODES | Optional city-name to platform-code mapping for cities not recognized automatically |
| AMAP_WEB_SERVICE_KEY | Optional Amap Web Service key for geocoding and commute calculations |
| LIEPIN_MCP_TOKEN | Liepin MCP access token |
| LLM_MODE | Defaults to offline; set to online to enable online suggestions |
| LLM_BASE_URL | Online model API base URL; the client appends /chat/completions |
| LLM_API_KEY / LLM_MODEL | Online model service key and model name |
| PDF_FONT_PATH | Path to an embeddable Chinese TTF or TTC font |

Configuration templates are available in the root and service directories as .env.example files. Replace development passwords and placeholders for your environment.

### Online suggestions

The default uses local rules and needs no model service key. Online suggestions require a service compatible with Chat Completions and JSON Schema output. Set LLM_MODE=online, LLM_BASE_URL, LLM_API_KEY, and LLM_MODEL, then restart the services.

When online mode is enabled, relevant resume fragments and job requirements are sent to the configured model service. Review model suggestions before applying them. If the service is unavailable or the response is invalid, the worker falls back to offline rules. Document parsing, rule-based scoring, and PDF generation remain local.

## Enable the Real Platform Runner

Complete the initial local startup and dependency installation before configuring platform execution:

1. Configure two independent random secrets: RUNNER_APPROVAL_SECRET and RUNNER_ENCRYPTION_KEY.
2. Stop the default services and start the real Runner.
3. Read the local pairing file and complete pairing in Settings.
4. Select Connect / Check for the platform and complete browser login or any required human verification.
5. Select a published resume with project evidence, discover jobs, and review the execution plan.

<details>
<summary>Generate and save random secrets on Windows</summary>

Run this only for first-time setup. Keep existing values stable; changing the encryption key affects stored device credentials. This PowerShell 5.1-compatible example uses two separate random values:

~~~powershell
function New-CareerLensSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        [Convert]::ToBase64String($bytes)
    } finally {
        $rng.Dispose()
    }
}

$env:RUNNER_APPROVAL_SECRET = New-CareerLensSecret
$env:RUNNER_ENCRYPTION_KEY = New-CareerLensSecret
[Environment]::SetEnvironmentVariable('RUNNER_APPROVAL_SECRET', $env:RUNNER_APPROVAL_SECRET, 'User')
[Environment]::SetEnvironmentVariable('RUNNER_ENCRYPTION_KEY', $env:RUNNER_ENCRYPTION_KEY, 'User')
~~~

The secrets are saved for the current Windows user and can be read by newly opened terminals. Never commit them to the repository.

</details>

From a PowerShell session with the secrets configured, run these commands from the project root:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -Action stop
powershell -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1 -RealRunner -SkipInstall -OpenBrowser
~~~

An unpaired Runner creates apps/career-runner/.runner-data/pairing.json. The pairing code is valid for two minutes; restart the Runner to generate a new code after expiry. One Runner is bound to one Core user.

Real mode can also read allowlisted settings from the Git-ignored apps/career-runner/.runner-data/start-real.env.cmd; current process environment variables take precedence.

### Platform behavior and scope

- **BOSS**: Sends the reviewed greeting and starts a conversation; the current flow does not automatically send resume attachments or continue chatting.
- **Liepin**: Uses the platform-side resume through the MCP adapter; the live interface and receipt contract still require verification on the actual account.
- **Human steps**: Login, phone confirmation, CAPTCHA, and platform page changes may require user action.
- **Reconciliation**: An uncertain send result is recorded as unknown and checked before a retry.
- **Runtime**: Intended for a personal computer or controlled private environment. The Runner listens on loopback by default.

## Data and privacy

| Data | Storage |
| --- | --- |
| Windows local database | services/core-api/data/careerlens.mv.db |
| Browser sessions, Runner secrets, and task journals | apps/career-runner/.runner-data/ |
| Docker data | Named volumes for PostgreSQL, Core, and Runner |
| Parsing vocabulary | services/ai-worker/app/data/skill_aliases.json, included as source data |

The repository does not contain real resumes, business databases, accounts, application records, browser sessions, or actual secrets. Tests use synthetic data.

JSON business backups contain resumes, JDs, reports, and application records; restore targets must be empty accounts. Sessions, device credentials, active task commands, and binary files are excluded. A complete production backup must include both the database and artifact files.

The .gitignore excludes common runtime and sensitive files. Check the file list before committing. Upload instructions are in UPLOAD.md.

## Development and verification

Run the complete verification from the project root. Use -SkipInstall only when dependencies are already installed:

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1 -SkipInstall
~~~

The verification script covers the frontend build, Core API tests, AI Worker tests and static checks, Runner tests, Compose configuration, and browser flows against an isolated database. CI is defined in .github/workflows/ci.yml.

This sanitized release passed 6 resume-parser regression tests and 23 Core API integration tests. Other verification records and known limitations are in docs/verification.md. Scanned PDFs need OCR first; complex documents, online model quality, and live platform compatibility require validation with real inputs.

## Project layout

~~~text
src/                              Vue UI and state management
services/core-api/                Spring Boot business API and migrations
services/ai-worker/               FastAPI parsing, scoring, suggestions, and PDF service
apps/career-runner/               Local platform Runner
apps/careerlens-browser-extension/ BOSS page entry extension
scripts/                          Startup, verification, and acceptance scripts
infra/                            Nginx and related infrastructure
docs/                             Implementation and verification notes
~~~

Further reading: services/core-api/README.md · services/ai-worker/README.md · docs/implementation-progress.md.
