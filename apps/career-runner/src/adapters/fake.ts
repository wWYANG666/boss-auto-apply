import { randomUUID } from 'node:crypto'
import type {
  ApplicationPlan,
  NormalizedJob,
  PlatformState,
  PreparedApplication,
  SearchSpec,
  SubmitReceipt,
} from '../domain.js'
import { planHash, sha256 } from '../core/crypto.js'
import {
  HumanActionRequiredError,
  PageChangedError,
  UnknownOutcomeError,
  type AdapterContext,
  type JobPlatformAdapter,
} from './contract.js'

const wait = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds))

export class FakeAdapter implements JobPlatformAdapter {
  readonly platform = 'fake' as const
  private connected = true

  async status(): Promise<PlatformState> {
    return {
      platform: this.platform,
      connected: this.connected,
      mode: 'fake',
      capabilities: ['DISCOVER', 'READ_JOB', 'CHAT_INITIATED', 'APPLICATION_SUBMITTED'],
      lastCheckedAt: new Date().toISOString(),
      message: 'Deterministic local fixture adapter',
    }
  }

  async connect(): Promise<PlatformState> {
    this.connected = true
    return this.status()
  }

  async disconnect(): Promise<PlatformState> {
    this.connected = false
    return this.status()
  }

  async verifyHumanAction(kind:'LOGIN'|'CAPTCHA'|'QUESTION'|'EXTERNAL_HANDOFF'):Promise<'resolved'|'required'|'unknown'>{
    return kind==='LOGIN'&&this.connected?'resolved':'required'
  }

  private guard(context: AdapterContext, stage: 'connect' | 'prepare' | 'submit'): void {
    if (!this.connected || (context.scenario === 'login_required' && stage !== 'submit')) {
      throw new HumanActionRequiredError('LOGIN', 'Open the visible browser and sign in to the platform.')
    }
    if (stage === 'prepare' && context.scenario === 'captcha_required') {
      throw new HumanActionRequiredError('CAPTCHA', 'Complete the platform verification in the visible browser.')
    }
    if (stage === 'prepare' && context.scenario === 'page_changed') throw new PageChangedError()
    if (stage === 'submit' && context.scenario === 'unknown_after_submit') throw new UnknownOutcomeError()
  }

  async discover(spec: SearchSpec, context: AdapterContext): Promise<NormalizedJob[]> {
    this.guard(context, 'connect')
    await wait(30)
    return [
      {
        platform: 'fake',
        externalJobId: 'FIXTURE-1001',
        canonicalUrl: 'https://example.invalid/jobs/FIXTURE-1001',
        company: '星河科技',
        role: `${spec.keyword}工程师`,
        location: spec.cities[0] ?? '杭州',
        salary: spec.salary ?? '15–30K',
        description: '负责 Java 服务端、数据库优化、缓存设计和自动化测试。',
        recruiter: '测试招聘经理',
        publishedAt: new Date().toISOString(),
      },
      {
        platform: 'fake',
        externalJobId: 'FIXTURE-1002',
        canonicalUrl: 'https://example.invalid/jobs/FIXTURE-1002',
        company: '青云数据',
        role: '服务端开发工程师',
        location: spec.cities[1] ?? spec.cities[0] ?? '上海',
        salary: '18–32K',
        description: '参与 Spring Boot 服务、Redis 缓存与消息队列建设。',
      },
    ]
  }

  async prepare(plan: ApplicationPlan, context: AdapterContext): Promise<PreparedApplication> {
    this.guard(context, 'prepare')
    await wait(20)
    return {
      planHash: planHash(plan),
      pageFingerprint: sha256({ platform: plan.platform, job: plan.externalJobId, company: plan.company }),
      actionSummary: plan.actionType === 'CHAT' ? 'Initiate chat and send reviewed greeting' : 'Submit platform resume',
      fields: [
        { key: 'company', label: '公司', value: plan.company, source: 'JOB' },
        { key: 'role', label: '岗位', value: plan.role, source: 'JOB' },
        { key: 'resume', label: '简历版本', value: plan.resumeVersionId, source: 'RESUME' },
        { key: 'message', label: '招呼语', value: plan.message, source: 'USER_REVIEWED' },
      ],
      warnings: [],
    }
  }

  async submit(plan: ApplicationPlan, context: AdapterContext): Promise<SubmitReceipt> {
    this.guard(context, 'submit')
    await wait(25)
    return {
      outcome: plan.actionType === 'CHAT' ? 'MESSAGE_SENT' : 'APPLICATION_SUBMITTED',
      receiptId: `FAKE-${randomUUID()}`,
      verifiedAt: new Date().toISOString(),
      evidence: `Fixture receipt for ${plan.externalJobId}`,
    }
  }

  async reconcile(plan: ApplicationPlan): Promise<SubmitReceipt | null> {
    return {
      outcome: plan.actionType === 'CHAT' ? 'MESSAGE_SENT' : 'APPLICATION_SUBMITTED',
      receiptId: `FAKE-RECONCILED-${plan.externalJobId}`,
      verifiedAt: new Date().toISOString(),
      evidence: 'Reconciled against the deterministic fixture history.',
    }
  }
}
