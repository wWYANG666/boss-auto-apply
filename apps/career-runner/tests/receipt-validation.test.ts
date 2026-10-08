import assert from 'node:assert/strict'
import { test } from 'node:test'
import { verifyBossObservation, verifyLiepinReceipt } from '../src/adapters/receipt-validation.js'
import type { ApplicationPlan } from '../src/domain.js'

const plan: ApplicationPlan = {
  planId:'review-001',platform:'liepin',externalJobId:'job-123',company:'Company',role:'Developer',
  actionType:'RESUME_SUBMIT',resumeVersionId:'version-1',resumeFileSha256:'a'.repeat(64),
  message:'Reviewed message',answers:{},jobContentHash:'b'.repeat(64),
}
const positive = { success:true, data:{status:'APPLICATION_SUBMITTED',jobId:'job-123',applicationId:'platform-receipt'} }

test('Liepin only accepts explicit matching platform confirmation', () => {
  const result=verifyLiepinReceipt({},positive,plan)
  assert.equal(result.platformReceiptId,'platform-receipt')
  assert.equal(result.receiptSource,'platform')
})
test('HTTP success, error payload, missing receipt and wrong job are not success', () => {
  for(const payload of [null, {}, {success:true}, {success:false}, {...positive,data:{...positive.data,jobId:'wrong'}},
    {...positive,data:{...positive.data,applicationId:undefined}}, {...positive,data:{...positive.data,status:'PENDING'}}]) {
    assert.throws(()=>verifyLiepinReceipt({},payload,plan), /receipt|error/)
  }
  assert.throws(()=>verifyLiepinReceipt({isError:true},positive,plan),/error/)
})
test('BOSS checks job, company, role, exact text and delivery status', () => {
  const observed={jobId:plan.externalJobId,company:plan.company,role:plan.role,message:plan.message,deliveryStatus:'sent'}
  assert.equal(verifyBossObservation(observed,plan).receiptSource,'observation')
  assert.equal(verifyBossObservation(observed,plan).platformReceiptId,undefined)
  for(const field of ['jobId','company','role','message','deliveryStatus'] as const)
    assert.throws(()=>verifyBossObservation({...observed,[field]:'wrong'},plan),/evidence/)
  assert.throws(()=>verifyBossObservation({...observed,message:''},{...plan,message:''}),/evidence/)
})
