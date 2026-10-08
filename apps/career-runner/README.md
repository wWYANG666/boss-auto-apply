# CareerLens Local Runner

## M1 authentication and evidence contract

All /v1 business routes now require a paired Core user, device token, timestamp,
nonce and HMAC signature. /health remains public; /v1/pair consumes a short-lived
local code. An unpaired runner writes pairing.json in RUNNER_DATA_DIR on startup.
Read it locally and submit the code in the CareerLens Settings page within two minutes.
Core requires an independently generated RUNNER_ENCRYPTION_KEY of at least 32 characters.
No pairing token is displayed by Core; it is encrypted at rest. Keep the encryption key stable.

The runner binds to one owner. Re-pairing, revocation UI and automatic key rotation
are not implemented yet; do not delete binding files to work around errors without a backup.
The nonce journal is persistent; retention/compaction will be implemented with durable scheduling.

Real BOSS sending requires a validated page evidence contract:
- BOSS_ACCOUNT_SELECTOR identifies an authenticated account element.
- BOSS_CONVERSATION_JOB_SELECTOR exposes the approved data-job-id.
- BOSS_CONVERSATION_COMPANY_SELECTOR and BOSS_CONVERSATION_ROLE_SELECTOR identify the conversation.
- BOSS_SENT_MESSAGE_SELECTOR matches the sender's message and exposes data-delivery-status
  as sent/delivered/read.

These are configuration requirements, not claims about the current BOSS DOM.
Until a real page adapter is validated, keep real execution disabled. Missing
selectors stop preflight; a send click alone is never reported as successful.
DOM observations use OBSERVATION identifiers, not invented platform receipt IDs.

Liepin accepts only a normalized result with success=true, a matching jobId,
an explicit applied status and an applicationId/receiptId. Any other schema is
UNKNOWN_OUTCOME until its real response contract is mapped and tested.

Local execution companion for CareerLens. It owns platform sessions, runs one external action at a time, journals every state transition, and exposes an HTTP/SSE API to the Spring Boot core service.

## Defaults

- `RUNNER_MODE=fake` is the default. It never opens or contacts a recruiting platform.
- Real adapters are opt-in with `RUNNER_MODE=real` and `RUNNER_REAL_PLATFORM=true` on the user's machine.
- Browser profiles, cookies, screenshots and journals live under `.runner-data/`, which is ignored by Git.
- The server listens on `127.0.0.1:43120` unless configured otherwise.
- Commands are typed; the runner does not accept arbitrary JavaScript, shell commands, or arbitrary URLs.

## Implemented adapters

### FakeAdapter

Fully deterministic development and CI adapter. It supports discovery, application preparation, approval-gated submission, CAPTCHA/login/page-change scenarios, unknown-outcome handling and reconciliation.

### LiepinMcpAdapter

Connects to a configurable Streamable HTTP MCP endpoint. The default endpoint is `https://open-agent.liepin.com/mcp/user`; the token is read from `LIEPIN_MCP_TOKEN` and is never returned by the API. Tool names are configurable because third-party manifests and the remote server may evolve.

Run an authenticated smoke test before enabling it. The GitHub wrapper that documented this endpoint is third-party metadata, and the referenced CLI repository does not currently provide a complete standard license for vendoring its source. This runner implements its own client instead.

### BossBrowserAdapter

Uses a visible local Chromium-family browser with an isolated persistent profile. It validates the allowed hostname, checks page identity before an action, pauses for login/CAPTCHA, and verifies an observable chat or message result. Selectors are intentionally narrow and page changes fail closed.

This adapter does not implement CAPTCHA solving, stealth plugins, private-token generation, arbitrary script injection, or server-side cookie storage. Review the current platform terms before enabling it.

## Run

```powershell
Copy-Item .env.example .env
npm install
npm run dev
```

Health:

```text
GET http://127.0.0.1:43120/health
```

## Main API

```text
GET    /v1/platforms
POST   /v1/platforms/:platform/connect
DELETE /v1/platforms/:platform/connect
POST   /v1/discover
POST   /v1/applications/prepare
POST   /v1/approvals
POST   /v1/applications/submit
POST   /v1/applications/reconcile
GET    /v1/tasks
GET    /v1/tasks/:taskId
POST   /v1/tasks/:taskId/human-action/resolved
GET    /v1/events                         # Server-Sent Events
```

An approval proof is short-lived, HMAC signed, bound to the complete application plan, and consumed once. A changed job, resume, message, answer set or action invalidates it.

## Verify

```powershell
npm run typecheck
npm test
npm run build
```
