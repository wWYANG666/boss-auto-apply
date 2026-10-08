import express, { type NextFunction, type Request, type Response } from 'express'
import { z, ZodError } from 'zod'
import {
  ApplicationPlanSchema,
  DiscoverCommandSchema,
  PlatformSchema,
  PrepareCommandSchema,
  SubmitCommandSchema,
} from './domain.js'
import { EventHub } from './core/events.js'
import { TaskEngine } from './core/task-engine.js'
import { LiepinMcpAdapter } from './adapters/liepin-mcp.js'
import { BossBrowserAdapter } from './adapters/boss-browser.js'
import { DeviceAuth } from './core/device-auth.js'
import { AdapterRegistry } from './adapters/registry.js'

function asyncRoute(handler: (request: Request, response: Response) => Promise<void>) {
  return (request: Request, response: Response, next: NextFunction) => {
    handler(request, response).catch(next)
  }
}

export function createRunnerApp(engine: TaskEngine, events: EventHub, adapters: AdapterRegistry, auth: DeviceAuth) {
  const app = express()
  app.disable('x-powered-by')
  app.use(express.json({ limit: '1mb', verify: (request, _response, buffer) => {
    (request as Request & {rawBody?:string}).rawBody=buffer.toString('utf8')
  } }))
  app.use((request, response, next) => {
    const allowedOrigins = new Set(['http://127.0.0.1:8888', 'http://localhost:8888'])
    const origin = request.headers.origin
    if (origin && !allowedOrigins.has(origin)) return response.status(403).json({error:{code:'ORIGIN_DENIED'}})
    if (origin && allowedOrigins.has(origin)) response.setHeader('Access-Control-Allow-Origin', origin)
    response.setHeader('Access-Control-Allow-Headers', 'Content-Type, Idempotency-Key')
    response.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS')
    if (request.method === 'OPTIONS') return response.sendStatus(204)
    next()
  })

  app.get('/health', (_request, response) => {
    response.json({ status: 'UP', service: 'career-runner', mode: process.env.RUNNER_MODE ?? 'fake', time: new Date().toISOString() })
  })

  app.post('/v1/pair', asyncRoute(async (request,response)=>{
    const body=z.object({ownerId:z.string().uuid(),code:z.string().min(20).max(100)}).parse(request.body)
    try { response.json({data:{token:await auth.pair(body.ownerId,body.code)}}) }
    catch {response.status(403).json({error:{code:'PAIRING_DENIED',message:'Pairing expired, used, or rejected.'}})}
  }))
  app.use('/v1', async (request,response,next)=>{
    const bearer=request.headers.authorization ?? ''
    if (!auth.authorize(String(request.headers['x-runner-owner-id']??''),bearer.startsWith('Bearer ')?bearer.slice(7):''))
      return response.status(401).json({error:{code:'RUNNER_AUTH_REQUIRED',message:'Pair this device with the current user.'}})
    try {
      const valid=await auth.verifyRequest(
        String(request.headers['x-runner-owner-id']??''),bearer.slice(7),request.method,request.originalUrl,
        (request as Request & {rawBody?:string}).rawBody??'',
        String(request.headers['x-runner-timestamp']??''),String(request.headers['x-runner-nonce']??''),
        String(request.headers['x-runner-signature']??''))
      if(!valid)return response.status(401).json({error:{code:'RUNNER_SIGNATURE_REQUIRED',message:'Invalid, expired or replayed command.'}})
      next()
    }catch(error){next(error)}
  })

  app.post('/v1/device/rotate',asyncRoute(async(request,response)=>{
    const {token}=z.object({token:z.string().min(32).max(100)}).parse(request.body)
    await auth.beginRotation(token);response.json({data:{pending:true}})
  }))
  app.post('/v1/device/rotate/commit',asyncRoute(async(request,response)=>{
    await auth.commitRotation(request.headers.authorization!.slice(7));response.json({data:{rotated:true}})
  }))
  app.post('/v1/device/revoke',asyncRoute(async(_request,response)=>{
    if(engine.list().some(t=>['queued','running'].includes(t.status))){
      response.status(409).json({error:{code:'DEVICE_BUSY',message:'Wait for in-flight operations to finish before revoking.'}});return
    }
    await auth.revoke();response.json({data:{revoked:true}})
  }))

  app.get('/v1/platforms/liepin/resume',asyncRoute(async(_request,response)=>{
    const adapter=adapters.get('liepin')
    if(!(adapter instanceof LiepinMcpAdapter)){response.status(409).json({error:{code:'REAL_PLATFORM_REQUIRED'}});return}
    response.json({data:await adapter.resumeSnapshot()})
  }))
  app.get('/v1/platforms/boss/diagnostics', asyncRoute(async (_request, response) => {
    const adapter = adapters.get('boss')
    if (!(adapter instanceof BossBrowserAdapter)) {
      response.json({ data: { ready: false, browserFound: false, browserStarted: false,
        signedIn: false, cityCodeCount: 0, issues: ['Runner当前处于测试模式'] } })
      return
    }
    response.json({ data: await adapter.diagnostics() })
  }))
  app.get('/v1/platforms/boss/profiles',asyncRoute(async(_request,response)=>{
    const adapter=adapters.get('boss')
    if(!(adapter instanceof BossBrowserAdapter)){response.status(409).json({error:{code:'REAL_PLATFORM_REQUIRED'}});return}
    response.json({data:await adapter.profiles()})
  }))
  app.get('/v1/platforms/boss/cities',asyncRoute(async(_request,response)=>{
    const adapter=adapters.get('boss')
    if(!(adapter instanceof BossBrowserAdapter)){response.status(409).json({error:{code:'REAL_PLATFORM_REQUIRED'}});return}
    response.json({data:await adapter.cityDirectory()})
  }))
  app.post('/v1/platforms/boss/profiles/:name/activate',asyncRoute(async(request,response)=>{
    const adapter=adapters.get('boss')
    if(!(adapter instanceof BossBrowserAdapter)){response.status(409).json({error:{code:'REAL_PLATFORM_REQUIRED'}});return}
    response.json({data:await adapter.switchProfile(String(request.params.name))})
  }))
  app.get('/v1/platforms', asyncRoute(async (_request, response) => {
    const states = await Promise.all(adapters.entries().filter(([platform]) => platform !== 'fake').map(async ([platform, adapter]) => ({ requestedPlatform: platform, ...(await adapter.status()) })))
    response.json({ data: states })
  }))

  app.post('/v1/platforms/:platform/connect', asyncRoute(async (request, response) => {
    const platform = PlatformSchema.parse(request.params.platform)
    response.json({ data: await adapters.get(platform).connect() })
  }))

  app.delete('/v1/platforms/:platform/connect', asyncRoute(async (request, response) => {
    const platform = PlatformSchema.parse(request.params.platform)
    response.json({ data: await adapters.get(platform).disconnect() })
  }))

  app.post('/v1/discover', asyncRoute(async (request, response) => {
    const command = DiscoverCommandSchema.parse(request.body)
    const task = await engine.discover(command)
    response.status(202).json({ data: task })
  }))

  app.post('/v1/applications/prepare', asyncRoute(async (request, response) => {
    const command = PrepareCommandSchema.parse(request.body)
    const task = await engine.prepare(command)
    response.status(202).json({ data: task })
  }))

  app.post('/v1/approvals', (request, response) => {
    const plan = ApplicationPlanSchema.parse(request.body)
    response.status(201).json({ data: engine.issueApproval(plan) })
  })

  app.post('/v1/applications/submit', asyncRoute(async (request, response) => {
    const command = SubmitCommandSchema.parse(request.body)
    const task = await engine.submit(command)
    response.status(202).json({ data: task })
  }))

  app.post('/v1/applications/reconcile', asyncRoute(async (request, response) => {
    const command = PrepareCommandSchema.parse(request.body)
    const task = await engine.reconcile(command)
    response.status(202).json({ data: task })
  }))

  app.get('/v1/tasks', (_request, response) => response.json({ data: engine.list() }))
  app.get('/v1/storage',asyncRoute(async(_request,response)=>{response.json({data:{...await engine.storageStatistics(),runnerMemoryBytes:process.memoryUsage().rss}})}))
  app.post('/v1/storage/cleanup-legacy',asyncRoute(async(_request,response)=>{response.json({data:await engine.cleanupLegacyJournal()})}))

  app.get('/v1/tasks/by-command/:commandId', (request, response) => {
    const task = engine.byCommand(String(request.params.commandId))
    if (!task) return response.status(404).json({ error: { code: 'TASK_NOT_FOUND', message: 'Task not found.' } })
    return response.json({ data: task })
  })

  app.get('/v1/tasks/:taskId', (request, response) => {
    const task = engine.get(String(request.params.taskId))
    if (!task) return response.status(404).json({ error: { code: 'TASK_NOT_FOUND', message: 'Task not found.' } })
    return response.json({ data: task })
  })
  app.post('/v1/tasks/:taskId/cancel',asyncRoute(async(request,response)=>{response.json({data:await engine.cancel(String(request.params.taskId))})}))

  app.post('/v1/tasks/:taskId/human-action/resolved', asyncRoute(async (request, response) => {
    response.json({ data: await engine.resolveHumanAction(String(request.params.taskId)) })
  }))
  app.post('/v1/tasks/:taskId/human-action/auto-resolve',asyncRoute(async(request,response)=>{
    response.json({data:await engine.autoResolveHumanAction(String(request.params.taskId))})
  }))

  app.get('/v1/events', (request, response) => {
    response.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
    })
    response.write(': connected\n\n')
    const remove = events.addClient(response)
    request.on('close', remove)
  })

  app.use((error: unknown, _request: Request, response: Response, _next: NextFunction) => {
    if (error instanceof ZodError) {
      return response.status(400).json({ error: { code: 'VALIDATION_ERROR', message: 'Request validation failed.', details: z.treeifyError(error) } })
    }
    const message = error instanceof Error ? error.message : 'Unexpected runner error.'
    process.stderr.write(`Runner request failed: ${message}\n`)
    const conflict = message.startsWith('IDEMPOTENCY_KEY_REUSED') || message.includes('Approval')
    return response.status(conflict ? 409 : 500).json({ error: { code: conflict ? 'COMMAND_CONFLICT' : 'RUNNER_ERROR', message } })
  })

  return app
}
