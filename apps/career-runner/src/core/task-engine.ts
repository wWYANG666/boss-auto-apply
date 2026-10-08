import { DiscoverCommandSchema, PrepareCommandSchema } from '../domain.js'
import { randomUUID } from 'node:crypto'
import type {
  ApplicationPlan,
  NormalizedJob,
  Platform,
  PreparedApplication,
  RunnerEvent,
  RunnerTask,
  Scenario,
  SubmitReceipt,
} from '../domain.js'
import { sha256 } from './crypto.js'
import { JsonlJournal } from './journal.js'
import { EventHub } from './events.js'
import { ApprovalService } from './approval.js'
import { AdapterRegistry } from '../adapters/registry.js'
import {
  DailyQuotaReachedError,
  HumanActionRequiredError,
  PageChangedError,
  UnknownOutcomeError,
  type JobPlatformAdapter,
} from '../adapters/contract.js'

type WorkResult = NormalizedJob[] | PreparedApplication | SubmitReceipt | null
type WorkFactory = (adapter: JobPlatformAdapter, scenario: Scenario) => Promise<WorkResult>

interface QueuedWork {
  taskId: string
  scenario: Scenario
  run: WorkFactory
}

type WorkLane = 'prepare' | 'discover' | 'submit'

const LANE_LIMITS: Record<WorkLane, number> = {
  prepare: 4,
  discover: 1,
  submit: 1,
}

function now(): string {
  return new Date().toISOString()
}

export class TaskEngine {
  private readonly tasksById = new Map<string, RunnerTask<WorkResult>>()
  private readonly tasksByCommand = new Map<string, RunnerTask<WorkResult>>()
  private readonly workByTask = new Map<string, QueuedWork>()
  private readonly queue: string[] = []
  private readonly activeByLane: Record<WorkLane, number> = { prepare: 0, discover: 0, submit: 0 }
  private readonly abortControllers=new Map<string,AbortController>()
  private readonly cancelledTaskIds=new Set<string>()
  private readonly platformTails=new Map<Platform,Promise<void>>()

  constructor(
    private readonly journal: JsonlJournal,
    private readonly events: EventHub,
    private readonly adapters: AdapterRegistry,
    private readonly approvals: ApprovalService,
  ) {}

  async initialize(): Promise<void> {
    const persisted = await this.journal.loadLatest()
    for (const task of persisted.values()) {
      const restored = task as RunnerTask<WorkResult>
      let changed=false
      if (['queued', 'running'].includes(restored.status)) {
        restored.status = restored.type === 'submit' ? 'unknown_outcome' : restored.input ? 'queued' : 'cancelled'
        restored.errorCode = 'RUNNER_RESTARTED'
        restored.errorMessage = 'The runner restarted before this in-memory command could complete.'
        changed=true
      }
      this.tasksByCommand.set(restored.commandId, restored)
      this.tasksById.set(restored.id, restored)
      if(restored.input && restored.type!=='submit'){
        try{
          const input=restored.type==='discover'?DiscoverCommandSchema.parse(restored.input):PrepareCommandSchema.parse(restored.input)
          const run:WorkFactory=async(adapter,scenario)=>{
            if('spec' in input){
              const controller=new AbortController();this.abortControllers.set(restored.commandId,controller)
              const onProgress=async(update:Record<string,unknown>)=>{
                restored.metadata={...(restored.metadata??{}),...update}
                const completed=Number(update.pagesCompleted??0),limit=Number(update.pageLimit??0)
                if(limit>0)restored.progress=Math.max(15,Math.min(85,15+Math.round(completed/limit*70)))
                await this.record(restored,'Discovery progress after restart',`Checked ${completed} of ${limit} pages; detected ${Number(update.detectedCount??0)} candidates.`)
              }
              try{return await adapter.discover(input.spec,{scenario,signal:controller.signal,onProgress})}
              finally{this.abortControllers.delete(restored.commandId)}
            }
            return restored.type==='reconcile'?adapter.reconcile(input.plan,{scenario}):adapter.prepare(input.plan,{scenario})
          }
          this.workByTask.set(restored.id,{taskId:restored.id,scenario:input.scenario,run})
          if(restored.status==='queued')this.queue.push(restored.id)
        }catch{restored.status='failed';restored.errorCode='RESTORE_INPUT_INVALID';changed=true}
      }
      if(restored.type==='submit' && restored.status==='waiting_human'){
        restored.status='unknown_outcome';restored.errorCode='RECONCILIATION_REQUIRED_AFTER_RESTART';changed=true
      }
      if(changed)await this.journal.append(restored)
    }
    this.processQueue()
  }

  list(): RunnerTask<WorkResult>[] {
    return [...this.tasksById.values()].sort((left, right) => right.createdAt.localeCompare(left.createdAt))
  }

  get(taskId: string): RunnerTask<WorkResult> | undefined {
    return this.tasksById.get(taskId)
  }

  byCommand(commandId: string): RunnerTask<WorkResult> | undefined {
    return this.tasksByCommand.get(commandId)
  }

  storageStatistics(){return this.journal.statistics()}
  cleanupLegacyJournal(){return this.journal.cleanupLegacy()}

  private async record(task: RunnerTask<WorkResult>, title: string, detail: string, eventType: RunnerEvent['type'] = 'task.updated'): Promise<void> {
    task.updatedAt = now()
    task.stateVersion += 1
    const event: RunnerEvent = { id: randomUUID(), taskId: task.id, at: task.updatedAt, type: eventType, title, detail }
    task.events.unshift(event)
    await this.journal.append(task)
    this.events.publish(event)
  }

  private async create(
    commandId: string,
    platform: Platform,
    type: RunnerTask['type'],
    input: unknown,
    scenario: Scenario,
    work: WorkFactory,
  ): Promise<RunnerTask<WorkResult>> {
    const existing = this.tasksByCommand.get(commandId)
    if (existing) {
      if (existing.inputHash !== sha256(input)) throw new Error('IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_INPUT')
      return existing
    }

    const timestamp = now()
    const task: RunnerTask<WorkResult> = {
      id: randomUUID(),
      commandId,
      platform,
      type,
      status: 'queued',
      phase: type === 'discover' ? 'connecting' : 'opening_page',
      progress: 0,
      stateVersion: 0,
      createdAt: timestamp,
      updatedAt: timestamp,
      input,
      inputHash: sha256(input),
      events: [],
    }
    this.tasksById.set(task.id, task)
    this.tasksByCommand.set(commandId, task)
    this.workByTask.set(task.id, { taskId: task.id, scenario, run: work })
    this.queue.push(task.id)
    await this.record(task, 'Task queued', `Command ${commandId} accepted.`)
    this.processQueue()
    return task
  }

  async discover(command: { commandId: string; platform: Platform; scenario: Scenario; spec: Parameters<JobPlatformAdapter['discover']>[0] }): Promise<RunnerTask<WorkResult>> {
    return this.create(command.commandId, command.platform, 'discover', command, command.scenario, async (adapter, scenario) => {
      const controller=new AbortController();this.abortControllers.set(command.commandId,controller)
      const onProgress=async(update:Record<string,unknown>)=>{
        const active=this.tasksByCommand.get(command.commandId)
        if(!active||active.status!=='running')return
        active.metadata={...(active.metadata??{}),...update}
        const completed=Number(update.pagesCompleted??0),limit=Number(update.pageLimit??0)
        if(limit>0)active.progress=Math.max(active.progress,Math.min(85,15+Math.round(completed/limit*70)))
        await this.record(active,'Discovery progress',`Checked ${completed} of ${limit} pages; detected ${Number(update.detectedCount??0)} candidates.`)
      }
      try{return await adapter.discover(command.spec,{scenario,signal:controller.signal,onProgress})}finally{this.abortControllers.delete(command.commandId)}
    })
  }

  async cancel(taskId:string):Promise<RunnerTask<WorkResult>>{
    const task=this.tasksById.get(taskId)
    if(!task)throw new Error('Task not found.')
    if(!['queued','running','waiting_human'].includes(task.status))return task
    this.abortControllers.get(task.commandId)?.abort()
    this.cancelledTaskIds.add(taskId)
    task.status='cancelled';task.progress=Math.min(task.progress,99)
    this.queue.splice(0,this.queue.length,...this.queue.filter(id=>id!==taskId))
    await this.record(task,'Task cancelled','The command was cancelled before completion.')
    return task
  }

  async prepare(command: { commandId: string; scenario: Scenario; plan: ApplicationPlan }): Promise<RunnerTask<WorkResult>> {
    return this.create(command.commandId, command.plan.platform, 'prepare', command, command.scenario, async (adapter, scenario) => adapter.prepare(command.plan, { scenario }))
  }

  async submit(command: { commandId: string; scenario: Scenario; plan: ApplicationPlan; approval: Parameters<ApprovalService['verifyAndConsume']>[1] }): Promise<RunnerTask<WorkResult>> {
    const existing = this.byCommand(command.commandId)
    if (existing) {
      if (existing.inputHash !== sha256(command)) throw new Error('IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_INPUT')
      return existing
    }
    this.approvals.verifyAndConsume(command.plan, command.approval)
    return this.create(command.commandId, command.plan.platform, 'submit', command, command.scenario, async (adapter, scenario) => {
      if(Date.parse(command.approval.expiresAt)<=Date.now()) throw new Error('Approval expired before execution.')
      return adapter.submit(command.plan, { scenario })
    })
  }

  async reconcile(command: { commandId: string; scenario: Scenario; plan: ApplicationPlan }): Promise<RunnerTask<WorkResult>> {
    return this.create(command.commandId, command.plan.platform, 'reconcile', command, command.scenario, async (adapter, scenario) => adapter.reconcile(command.plan, { scenario }))
  }

  issueApproval(plan: ApplicationPlan) {
    return this.approvals.issue(plan)
  }

  private laneFor(task: RunnerTask<WorkResult>): WorkLane {
    if (task.type === 'prepare') return 'prepare'
    if (task.type === 'discover') return 'discover'
    return 'submit'
  }

  private processQueue(): void {
    for (const lane of ['prepare', 'discover', 'submit'] as const) {
      while (this.activeByLane[lane] < LANE_LIMITS[lane]) {
        const index = this.queue.findIndex((taskId) => {
          const task = this.tasksById.get(taskId)
          return Boolean(task && task.status === 'queued' && this.laneFor(task) === lane)
        })
        if (index < 0) break
        const [taskId] = this.queue.splice(index, 1)
        const task = taskId ? this.tasksById.get(taskId) : undefined
        const queuedWork = taskId ? this.workByTask.get(taskId) : undefined
        if (!task || !queuedWork || task.status !== 'queued') continue
        this.activeByLane[lane] += 1
        void this.execute(task, queuedWork).finally(() => {
          this.activeByLane[lane] -= 1
          this.processQueue()
        })
      }
    }
  }

  private async execute(task: RunnerTask<WorkResult>, work: QueuedWork): Promise<void> {
    const adapter = this.adapters.get(task.platform)
    task.status = 'running'
    task.phase = task.type === 'discover' ? 'searching' : task.type === 'reconcile' ? 'reconciling' : 'preflight'
    task.progress = 15
    await this.record(task, 'Task started', `${task.platform} ${task.type} is running.`)

    try {
      task.progress = task.type === 'discover' ? 45 : 55
      await this.record(task, 'Preflight complete', 'Command shape, platform and local session boundary verified.')
      const result = await this.withPlatformLease(task.platform,()=>work.run(adapter,work.scenario))
      if(this.cancelledTaskIds.delete(task.id))return
      if(task.type==='discover'&&adapter.discoveryStats)task.metadata=adapter.discoveryStats()
      task.phase = task.type === 'discover' ? 'normalizing' : 'verifying'
      task.progress = 88
      await this.record(task, 'Result observed', 'Normalizing and verifying the adapter response.')
      task.result = result
      task.progress = 100
      task.status = discoveryTaskStatus(task.type,task.metadata)
      await this.record(task, task.status==='partial'?'Task partially completed':'Task succeeded',
        task.status==='partial'?'Verified results were saved, but discovery stopped before every scope completed.':'A verified result was written to the local journal.')
    } catch (error) {
      if(this.cancelledTaskIds.delete(task.id)||error instanceof Error&&error.message==='DISCOVERY_CANCELLED'){
        task.status='cancelled';await this.record(task,'Task cancelled','Discovery stopped before the next page request.');return
      }
      if (error instanceof Error && /Approval expired before execution|Approval has expired/i.test(error.message)) {
        task.status = 'failed'
        task.errorCode = 'APPROVAL_EXPIRED'
        task.errorMessage = '审批凭证在任务开始前过期，任务未执行任何平台动作。'
        await this.record(task, 'Task failed', task.errorMessage)
        return
      }
      if (error instanceof DailyQuotaReachedError) {
        task.status = 'failed'
        task.progress = 100
        task.errorCode = 'DAILY_QUOTA_REACHED'
        task.errorMessage = error.message
        await this.record(task, 'Daily quota reached', error.message)
        return
      }
      if (error instanceof HumanActionRequiredError) {
        task.status = 'waiting_human'
        task.progress = Math.max(task.progress, 60)
        task.humanAction = {
          id: randomUUID(),
          kind: error.kind,
          title: error.kind === 'CAPTCHA' ? 'Platform verification required' : error.kind === 'LOGIN' ? 'Platform login required' : 'Additional input required',
          description: error.message,
          status: 'pending',
        }
        await this.record(task, 'Human action required', error.message, 'human-action.created')
        return
      }
      if (error instanceof UnknownOutcomeError || task.type === 'submit' && !(error instanceof PageChangedError)) {
        task.status = 'unknown_outcome'
        task.phase = 'reconciling'
        task.errorCode = 'UNKNOWN_OUTCOME'
        task.errorMessage = error instanceof Error ? error.message : 'Submission response was ambiguous.'
        await this.record(task, 'Outcome requires reconciliation', task.errorMessage)
        return
      }
      task.status = 'failed'
      task.errorCode = error instanceof PageChangedError ? 'PAGE_CHANGED' : 'ADAPTER_ERROR'
      task.errorMessage = error instanceof Error ? error.message : String(error)
      await this.record(task, 'Task failed', task.errorMessage)
    }
  }

  private async withPlatformLease<T>(platform:Platform,work:()=>Promise<T>):Promise<T>{
    const previous=this.platformTails.get(platform)??Promise.resolve()
    let release!:()=>void
    const gate=new Promise<void>(resolve=>{release=resolve})
    const tail=previous.catch(()=>undefined).then(()=>gate)
    this.platformTails.set(platform,tail)
    await previous.catch(()=>undefined)
    try{return await work()}
    finally{
      release()
      if(this.platformTails.get(platform)===tail)this.platformTails.delete(platform)
    }
  }

  async resolveHumanAction(taskId: string): Promise<RunnerTask<WorkResult>> {
    const task = this.tasksById.get(taskId)
    const work = this.workByTask.get(taskId)
    if (!task || !work || task.status !== 'waiting_human' || !task.humanAction) throw new Error('No pending human action exists for this task.')
    task.humanAction.status = 'resolved'
    await this.record(task, 'Human action marked complete', task.humanAction.description, 'human-action.resolved')
    delete task.humanAction
    task.status = 'queued'
    work.scenario = 'happy_path'
    this.queue.push(task.id)
    await this.record(task, 'Task requeued', 'The task will restart from a verified preflight checkpoint.')
    this.processQueue()
    return task
  }

  async autoResolveHumanAction(taskId:string):Promise<{outcome:'resolved'|'required'|'unknown';task:RunnerTask<WorkResult>}>
  {
    const task=this.tasksById.get(taskId)
    if(!task||task.status!=='waiting_human'||task.humanAction?.kind!=='LOGIN')throw new Error('No automatic login verification is pending for this task.')
    const adapter=this.adapters.get(task.platform)
    const input=task.input as {plan?:{accountFingerprint?:string}}|undefined
    const outcome=adapter.verifyHumanAction?await adapter.verifyHumanAction('LOGIN',input?.plan?.accountFingerprint):'required'
    if(outcome==='resolved')return{outcome,task:await this.resolveHumanAction(taskId)}
    await this.record(task,'Automatic login verification',outcome==='required'?'Login is explicitly required.':'Login state is temporarily uncertain.')
    return{outcome,task}
  }
}

export function discoveryTaskStatus(type:RunnerTask['type'],metadata?:Record<string,unknown>):'partial'|'succeeded'{
  return type==='discover'&&metadata?.partial===true?'partial':'succeeded'
}
