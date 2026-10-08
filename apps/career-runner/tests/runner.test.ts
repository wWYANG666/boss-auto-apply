import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { randomBytes, randomUUID } from 'node:crypto'
import { AdapterRegistry } from '../src/adapters/registry.js'
import { ApprovalService } from '../src/core/approval.js'
import { EventHub } from '../src/core/events.js'
import { JsonlJournal } from '../src/core/journal.js'
import { TaskEngine, discoveryTaskStatus } from '../src/core/task-engine.js'
import type { ApplicationPlan, RunnerTask } from '../src/domain.js'

process.env.RUNNER_MODE = 'fake'

test('partial discovery metadata is never reported as complete',()=>{
  assert.equal(discoveryTaskStatus('discover',{partial:true}),'partial')
  assert.equal(discoveryTaskStatus('discover',{partial:false}),'succeeded')
})

function fixturePlan(): ApplicationPlan {
  return {
    planId: 'plan-fixture-001',
    platform: 'fake',
    externalJobId: 'FIXTURE-1001',
    canonicalUrl: 'https://example.invalid/jobs/FIXTURE-1001',
    company: '星河科技',
    role: 'Java 后端工程师',
    actionType: 'RESUME_SUBMIT',
    resumeVersionId: 'resume-v6',
    resumeFileSha256: 'a'.repeat(64),
    message: '您好，我具备与岗位相关的 Java 后端项目经验，期待交流。',
    answers: {},
    jobContentHash: 'fixture-job-hash',
  }
}

async function engine() {
  const directory = await mkdtemp(join(tmpdir(), 'careerlens-runner-'))
  const approvals = new ApprovalService(randomBytes(32).toString('hex'))
  const taskEngine = new TaskEngine(new JsonlJournal(directory), new EventHub(), new AdapterRegistry(), approvals)
  await taskEngine.initialize()
  return { taskEngine, approvals }
}

async function waitForTerminal(taskEngine: TaskEngine, taskId: string): Promise<RunnerTask> {
  for (let attempt = 0; attempt < 100; attempt += 1) {
    const task = taskEngine.get(taskId)
    if (task && ['succeeded', 'partial', 'failed', 'waiting_human', 'unknown_outcome'].includes(task.status)) return task
    await new Promise((resolve) => setTimeout(resolve, 10))
  }
  throw new Error('Task did not reach a terminal test state.')
}

test('discover is deterministic and command id is idempotent', async () => {
  const { taskEngine } = await engine()
  const command = {
    commandId: randomUUID(),
    platform: 'fake' as const,
    scenario: 'happy_path' as const,
    spec: { keyword: 'Java 后端', cities: ['杭州'], maxPages: 1,includeKeywords:[],minKeywordMatch:0,
      excludeKeywords:[],excludeCompanies:[],excludeHeadhunters:false,excludeContacted:true,recruiterActivity:'any' as const,maxJobs:50,
      sameCompanyDays:0,sameRecruiterDays:0,homeAddress:'',commuteMode:'none' as const,maxCommuteDistanceKm:0,maxCommuteMinutes:0 },
  }
  const first = await taskEngine.discover(command)
  const second = await taskEngine.discover(command)
  assert.equal(first.id, second.id)
  const completed = await waitForTerminal(taskEngine, first.id)
  assert.equal(completed.status, 'succeeded')
  assert.equal(Array.isArray(completed.result), true)
})

test('queued discovery can be cancelled before platform work starts',async()=>{
  const {taskEngine}=await engine()
  const command={commandId:'cancel-discovery-command',platform:'fake' as const,scenario:'happy_path' as const,spec:{keyword:'Java',cities:['南京'],maxPages:1,includeKeywords:[],minKeywordMatch:0,excludeKeywords:[],excludeCompanies:[],excludeHeadhunters:false,excludeContacted:true,recruiterActivity:'any' as const,maxJobs:10,sameCompanyDays:0,sameRecruiterDays:0,homeAddress:'',commuteMode:'none' as const,maxCommuteDistanceKm:0,maxCommuteMinutes:0}}
  const task=await taskEngine.discover(command)
  await taskEngine.cancel(task.id)
  assert.equal(taskEngine.get(task.id)?.status,'cancelled')
})

test('two hundred simulated discovery commands complete without queue leakage',async()=>{
  const {taskEngine}=await engine()
  const ids:string[]=[]
  for(let index=0;index<200;index++){
    const task=await taskEngine.discover({commandId:`soak-discovery-${index}`,platform:'fake',scenario:'happy_path',spec:{keyword:'Java',cities:['南京'],maxPages:1,includeKeywords:[],minKeywordMatch:0,excludeKeywords:[],excludeCompanies:[],excludeHeadhunters:false,excludeContacted:true,recruiterActivity:'any',maxJobs:2,sameCompanyDays:0,sameRecruiterDays:0,homeAddress:'',commuteMode:'none',maxCommuteDistanceKm:0,maxCommuteMinutes:0}})
    ids.push(task.id)
  }
  for(let attempt=0;attempt<1500&&!ids.every(id=>taskEngine.get(id)?.status==='succeeded');attempt++)await new Promise(resolve=>setTimeout(resolve,10))
  assert.equal(ids.filter(id=>taskEngine.get(id)?.status==='succeeded').length,200)
})

test('approval is bound to an exact plan and is single use', () => {
  const approvals = new ApprovalService(randomBytes(32).toString('hex'))
  const plan = fixturePlan()
  const proof = approvals.issue(plan)
  approvals.verifyAndConsume(plan, proof)
  assert.throws(() => approvals.verifyAndConsume(plan, proof), /already been consumed/)
})

test('submit requires a valid approval before queueing', async () => {
  const { taskEngine, approvals } = await engine()
  const plan = fixturePlan()
  const approval = approvals.issue(plan)
  const command = { commandId: randomUUID(), scenario: 'happy_path' as const, plan, approval }
  const task = await taskEngine.submit(command)
  assert.equal((await taskEngine.submit(command)).id, task.id)
  const completed = await waitForTerminal(taskEngine, task.id)
  assert.equal(completed.status, 'succeeded')
  assert.match(JSON.stringify(completed.result), /APPLICATION_SUBMITTED/)
})

test('prepare lane is not blocked by a rate-limited submit lane', async () => {
  const { taskEngine, approvals } = await engine()
  const plan = fixturePlan()
  const submit = await taskEngine.submit({
    commandId: randomUUID(),
    scenario: 'happy_path',
    plan,
    approval: approvals.issue(plan),
  })
  const prepare = await taskEngine.prepare({ commandId: randomUUID(), scenario: 'happy_path', plan })
  const [submitted, prepared] = await Promise.all([
    waitForTerminal(taskEngine, submit.id),
    waitForTerminal(taskEngine, prepare.id),
  ])
  const submitFinishedAt = submitted.events.find((event) => event.title === 'Task succeeded')?.at
  const prepareStartedAt = prepared.events.find((event) => event.title === 'Task started')?.at
  assert.ok(submitFinishedAt && prepareStartedAt)
  assert.ok(prepareStartedAt <= submitFinishedAt)
})

test('browser work for the same platform is serialized across lanes',async()=>{
  const {taskEngine}=await engine()
  const plan=fixturePlan()
  const discover=await taskEngine.discover({commandId:randomUUID(),platform:'fake',scenario:'happy_path',spec:{keyword:'Java',cities:['南京'],maxPages:1,includeKeywords:[],minKeywordMatch:0,excludeKeywords:[],excludeCompanies:[],excludeHeadhunters:false,excludeContacted:true,recruiterActivity:'any',maxJobs:2,sameCompanyDays:0,sameRecruiterDays:0,homeAddress:'',commuteMode:'none',maxCommuteDistanceKm:0,maxCommuteMinutes:0}})
  const prepare=await taskEngine.prepare({commandId:randomUUID(),scenario:'happy_path',plan})
  const [discovered,prepared]=await Promise.all([waitForTerminal(taskEngine,discover.id),waitForTerminal(taskEngine,prepare.id)])
  const discoveryFinished=discovered.events.find(event=>event.title==='Task succeeded')?.at
  const prepareObserved=prepared.events.find(event=>event.title==='Result observed')?.at
  assert.ok(discoveryFinished&&prepareObserved&&prepareObserved>=discoveryFinished)
})

test('captcha pauses and can resume from a fresh preflight', async () => {
  const { taskEngine } = await engine()
  const task = await taskEngine.prepare({ commandId: randomUUID(), scenario: 'captcha_required', plan: fixturePlan() })
  const waiting = await waitForTerminal(taskEngine, task.id)
  assert.equal(waiting.status, 'waiting_human')
  assert.equal(waiting.humanAction?.kind, 'CAPTCHA')
  await taskEngine.resolveHumanAction(task.id)
  const completed = await waitForTerminal(taskEngine, task.id)
  assert.equal(completed.status, 'succeeded')
})

test('login interruption is automatically verified before it is requeued',async()=>{
  const {taskEngine}=await engine()
  const task=await taskEngine.prepare({commandId:randomUUID(),scenario:'login_required',plan:fixturePlan()})
  assert.equal((await waitForTerminal(taskEngine,task.id)).status,'waiting_human')
  const verified=await taskEngine.autoResolveHumanAction(task.id)
  assert.equal(verified.outcome,'resolved')
  assert.equal((await waitForTerminal(taskEngine,task.id)).status,'succeeded')
})

test('unknown submit result is never reported as success', async () => {
  const { taskEngine, approvals } = await engine()
  const plan = fixturePlan()
  const task = await taskEngine.submit({
    commandId: randomUUID(),
    scenario: 'unknown_after_submit',
    plan,
    approval: approvals.issue(plan),
  })
  const completed = await waitForTerminal(taskEngine, task.id)
  assert.equal(completed.status, 'unknown_outcome')
  assert.equal(completed.errorCode, 'UNKNOWN_OUTCOME')
})
