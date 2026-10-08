import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { ApprovalService } from '../src/core/approval.js'
import type { ApplicationPlan } from '../src/domain.js'
test('consumed approval cannot be replayed after service restart',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'approval-store-'))
 const plan:ApplicationPlan={planId:'test-plan',platform:'fake',externalJobId:'job',company:'company',role:'role',
   actionType:'CHAT',resumeVersionId:'version',resumeFileSha256:'a'.repeat(64),message:'hello',answers:{},jobContentHash:'b'.repeat(64)}
 const first=new ApprovalService('s'.repeat(32),directory)
 const proof=first.issue(plan)
 first.verifyAndConsume(plan,proof)
 const restarted=new ApprovalService('s'.repeat(32),directory)
 assert.throws(()=>restarted.verifyAndConsume(plan,proof),/already been consumed/)
})
