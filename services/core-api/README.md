# CareerLens Core API

Java 21 / Spring Boot modular-monolith API for CareerLens. PostgreSQL is the system of record; Flyway owns the schema. The AI worker and local runner are replaceable integrations behind typed clients.

## Modules

- `auth`: registration, login and hashed bearer tokens
- `resume`: drafts, optimistic revision checks, immutable versions and restore/diff
- `job`: versioned job descriptions and editable extracted requirements
- `match`: deterministic, versioned scoring and evidence records
- `suggestion`: grounded suggestion batches and apply-as-new-version workflow
- `application`: job-search pipeline stages, events and analytics
- `automation`: platform accounts, discovery, review plans, approval window, tasks, human actions and audit
- `integration`: AI worker and local runner clients with offline fallback

## Local services

```text
PostgreSQL  localhost:5432
AI worker  http://localhost:8001
Runner     http://localhost:43120
Core API   http://localhost:8080
```

Copy `.env.example` values into your shell or start everything from the repository root with Docker Compose.

## Development

Java 21 and Maven 3.9+:

```powershell
.\mvnw.cmd test
$env:SPRING_PROFILES_ACTIVE = 'local'
.\mvnw.cmd spring-boot:run
```

The `local` profile uses a persistent file-backed H2 database. The default/Compose configuration uses PostgreSQL.

API documentation:

```text
http://localhost:8080/swagger-ui.html
http://localhost:8080/v3/api-docs
```

Health:

```text
http://localhost:8080/api/v1/health
http://localhost:8080/actuator/health
```

## Accounts

Register a new account through the web UI. Built-in demo initialization has been removed.

## Runner execution flow

After plans are approved, the browser starts and monitors each local task through the Core API:

```text
POST /api/v1/automation-tasks/{id}/start
POST /api/v1/automation-tasks/{id}/sync
POST /api/v1/automation-tasks/{id}/human-action/resolved
POST /api/v1/automation-tasks/{id}/reconcile
```

`reconcile` is reserved for `unknown_outcome`: it checks platform history before any retry, preventing an uncertain submission from being sent twice.

## Important automation boundary

The core service stores plans, explicit approvals, state, receipts and audit data. It never stores recruiting-site passwords, cookies, SMS codes or browser storage. Those belong to the local `career-runner` process. Runner unavailability returns an explicit error. Test-mode runner data is rejected by the real workspace.
