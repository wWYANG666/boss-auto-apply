import { sha256 } from '../core/crypto.js'
import { UnknownOutcomeError } from './contract.js'
import type { ApplicationPlan, SubmitReceipt } from '../domain.js'

type RecordValue = Record<string, unknown>
function record(value: unknown): RecordValue {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as RecordValue : {}
}

/**
 * Conservative normalized response contract. A platform adapter must map a documented
 * response to these fields. Unrecognized responses are never guessed to be successful.
 */
export function verifyLiepinReceipt(result: unknown, payload: unknown, plan: ApplicationPlan): SubmitReceipt {
  const envelope = record(payload)
  const data = record(envelope.data ?? payload)
  if (record(result).isError === true || envelope.success === false || data.success === false)
    throw new UnknownOutcomeError('Liepin returned an error; reconcile platform history before retrying.')
  const status = String(data.status ?? '')
  const positive = data.success === true || envelope.success === true
  const receiptId = typeof data.applicationId === 'string' ? data.applicationId : data.receiptId
  if (!positive || !['APPLIED', 'APPLICATION_SUBMITTED', 'ALREADY_APPLIED'].includes(status)
      || String(data.jobId ?? '') !== plan.externalJobId
      || typeof receiptId !== 'string' || !receiptId.trim()) {
    throw new UnknownOutcomeError('Liepin response has no matching job and explicit application receipt.')
  }
  return {
    outcome: status === 'ALREADY_APPLIED' ? 'ALREADY_APPLIED' : 'APPLICATION_SUBMITTED',
    receiptId,
    receiptSource: 'platform',
    platformReceiptId: receiptId,
    verifiedAt: new Date().toISOString(),
    evidence: JSON.stringify({ jobId: plan.externalJobId, status, receiptId }),
  }
}

export interface BossObservation {
  jobId: string
  company: string
  role: string
  message: string
  deliveryStatus: string
}

export function verifyBossObservation(observation: BossObservation, plan: ApplicationPlan): SubmitReceipt {
  if (!plan.message.trim() || observation.jobId !== plan.externalJobId
      || observation.company.trim() !== plan.company.trim() || observation.role.trim() !== plan.role.trim()
      || observation.message.trim() !== plan.message.trim()
      || !['sent', 'delivered', 'read'].includes(observation.deliveryStatus)) {
    throw new UnknownOutcomeError('No exact sent-message evidence in the approved job conversation.')
  }
  const evidence = { jobId: observation.jobId, messageHash: sha256(plan.message), deliveryStatus: observation.deliveryStatus }
  return {
    outcome: 'MESSAGE_SENT',
    receiptId: 'OBSERVATION-' + sha256(evidence).slice(0, 32),
    receiptSource: 'observation',
    verifiedAt: new Date().toISOString(),
    evidence: JSON.stringify(evidence),
  }
}
