import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { AdapterRegistry } from '../src/adapters/registry.js'
import { JsonlJournal } from '../src/core/journal.js'
import { TaskEngine } from '../src/core/task-engine.js'
import { EventHub } from '../src/core/events.js'
import { ApprovalService } from '../src/core/approval.js'
import type { ApplicationPlan } from '../src/domain.js'

test('preflight awaiting human can resume after Runner restart; in-flight submission remains unknown',async()=>{
 process.env.RUNNER_MODE='fake'
 const dir=await mkdtemp(join(tmpdir(),'runner-restart-'))
 const journal=new JsonlJournal(dir)
 const create=()=>new TaskEngine(journal,new EventHub(),new AdapterRegistry(),new ApprovalService('s'.repeat(32),join(dir,'consumed')))
 const first=create();await first.initialize()
 const plan:ApplicationPlan={planId:'plan',platform:'fake',externalJobId:'job',company:'company',role:'role',actionType:'CHAT',
   resumeVersionId:'version',resumeFileSha256:'a'.repeat(64),message:'hello',answers:{},jobContentHash:'b'.repeat(64)}
 const task=await first.prepare({commandId:'prepare-before-restart',scenario:'captcha_required',plan})
 for(let i=0;i<100&&first.get(task.id)?.status!=='waiting_human';i++)await new Promise(r=>setTimeout(r,10))
 assert.equal(first.get(task.id)?.status,'waiting_human')
 const restarted=create();await restarted.initialize()
 await restarted.resolveHumanAction(task.id)
 for(let i=0;i<100&&restarted.get(task.id)?.status!=='succeeded';i++)await new Promise(r=>setTimeout(r,10))
 assert.equal(restarted.get(task.id)?.status,'succeeded')
 await journal.append({...task,id:'uncertain-submit',commandId:'submit-before-restart',type:'submit',status:'running'})
 const afterSubmitRestart=create();await afterSubmitRestart.initialize()
 assert.equal(afterSubmitRestart.get('uncertain-submit')?.status,'unknown_outcome')
})

test('SQLite journal restores two hundred terminal task snapshots without JSONL growth',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'runner-sqlite-soak-'))
 const journal=new JsonlJournal(dir)
 for(let index=0;index<200;index++)await journal.append({
   id:`task-${index}`,commandId:`command-${index}`,platform:'fake',type:'discover',status:'succeeded',phase:'normalizing',progress:100,stateVersion:1,
   createdAt:new Date().toISOString(),updatedAt:new Date().toISOString(),events:[],inputHash:`hash-${index}`,
 })
 const restored=await new JsonlJournal(dir).loadLatest()
 assert.equal(restored.size,200)
 assert.equal(restored.get('command-199')?.status,'succeeded')
})
