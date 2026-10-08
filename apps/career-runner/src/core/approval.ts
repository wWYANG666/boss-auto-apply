import { createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto'
import type { ApplicationPlan, ApprovalProof } from '../domain.js'
import { mkdirSync, openSync, writeSync, fsyncSync, closeSync, readdirSync, readFileSync, unlinkSync } from 'node:fs'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
import { planHash, stableStringify } from './crypto.js'

// A five-task serial batch can include human-like delays; keep the proof valid
// long enough for the queued task to reach the runner without reusing it later.
const DEFAULT_TTL_MS = 30 * 60 * 1_000

export class ApprovalService {
  private readonly consumed = new Set<string>()

  constructor(private readonly secret: string, private readonly consumedDirectory?: string) {
    if (secret.length < 32) throw new Error('RUNNER_APPROVAL_SECRET must contain at least 32 characters.')
    this.pruneExpiredProofs()
  }

  private pruneExpiredProofs():void{
    if(!this.consumedDirectory)return
    try{
      for(const name of readdirSync(this.consumedDirectory)){
        if(!/^[a-f0-9]{64}$/.test(name))continue
        const path=resolve(this.consumedDirectory,name)
        const expiresAt=Date.parse(readFileSync(path,'utf8'))
        if(Number.isFinite(expiresAt)&&expiresAt<Date.now()-24*60*60_000)unlinkSync(path)
      }
    }catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error}
  }

  private sign(value: Omit<ApprovalProof, 'signature'>): string {
    return createHmac('sha256', this.secret).update(stableStringify(value)).digest('hex')
  }

  issue(plan: ApplicationPlan, ttlMilliseconds = DEFAULT_TTL_MS): ApprovalProof {
    const unsigned: Omit<ApprovalProof, 'signature'> = {
      approvalId: randomUUID(),
      planHash: planHash(plan),
      expiresAt: new Date(Date.now() + ttlMilliseconds).toISOString(),
      nonce: randomBytes(18).toString('base64url'),
    }
    return { ...unsigned, signature: this.sign(unsigned) }
  }

  verifyAndConsume(plan: ApplicationPlan, proof: ApprovalProof): void {
    if (this.consumed.has(proof.approvalId)) throw new Error('Approval has already been consumed.')
    if (!Number.isFinite(Date.parse(proof.expiresAt)) || Date.parse(proof.expiresAt) <= Date.now()) throw new Error('Approval has expired.')
    if (proof.planHash !== planHash(plan)) throw new Error('Approval is bound to a different application plan.')

    const { signature, ...unsigned } = proof
    const expected = Buffer.from(this.sign(unsigned), 'hex')
    const actual = Buffer.from(signature, 'hex')
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) throw new Error('Approval signature is invalid.')
    if (this.consumedDirectory) {
      mkdirSync(this.consumedDirectory,{recursive:true,mode:0o700})
      const name=createHash('sha256').update(proof.approvalId).digest('hex')
      let fd: number
      try { fd=openSync(resolve(this.consumedDirectory,name),'wx',0o600) }
      catch(error) {
        if((error as NodeJS.ErrnoException).code==='EEXIST') throw new Error('Approval has already been consumed.')
        throw error
      }
      try {writeSync(fd,proof.expiresAt);fsyncSync(fd)} finally {closeSync(fd)}
    }
    this.consumed.add(proof.approvalId)
  }
}
