import { resolve } from 'node:path'
import { localApprovalSecret } from './core/local-secret.js'
import { DeviceAuth } from './core/device-auth.js'
import { AdapterRegistry } from './adapters/registry.js'
import { ApprovalService } from './core/approval.js'
import { EventHub } from './core/events.js'
import { JsonlJournal } from './core/journal.js'
import { TaskEngine } from './core/task-engine.js'
import { createRunnerApp } from './server.js'

const host = process.env.RUNNER_HOST ?? '127.0.0.1'
const port = Number(process.env.RUNNER_PORT ?? 43120)
const dataDirectory = process.env.RUNNER_DATA_DIR ?? '.runner-data'
const approvalSecret = process.env.RUNNER_APPROVAL_SECRET || await localApprovalSecret(dataDirectory)

const events = new EventHub()
const adapters = new AdapterRegistry()
const engine = new TaskEngine(
  new JsonlJournal(dataDirectory),
  events,
  adapters,
  new ApprovalService(approvalSecret, resolve(dataDirectory, 'consumed-approvals')), 
)

await engine.initialize()

const deviceAuth = new DeviceAuth(dataDirectory)
await deviceAuth.initialize()
const app = createRunnerApp(engine, events, adapters, deviceAuth)
const server = app.listen(port, host, () => {
  process.stdout.write(`CareerLens runner listening on http://${host}:${port} (${process.env.RUNNER_MODE ?? 'fake'} mode)\n`)
})

const maintenance=setInterval(()=>{void deviceAuth.pruneNonces().catch(()=>process.stderr.write('Nonce retention cleanup failed.\n'))},60_000)
const heartbeat = setInterval(() => events.heartbeat(), 15_000)

async function shutdown() {
  clearInterval(maintenance)
  clearInterval(heartbeat)
  await Promise.all(adapters.entries().map(([, adapter]) => adapter.disconnect().catch(() => undefined)))
  server.close(() => process.exit(0))
}

process.on('SIGINT', () => void shutdown())
process.on('SIGTERM', () => void shutdown())
