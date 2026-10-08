import type {
  ApplicationPlan,
  NormalizedJob,
  Platform,
  PlatformState,
  PreparedApplication,
  Scenario,
  SearchSpec,
  SubmitReceipt,
} from '../domain.js'

export class HumanActionRequiredError extends Error {
  constructor(
    readonly kind: 'LOGIN' | 'CAPTCHA' | 'QUESTION' | 'EXTERNAL_HANDOFF',
    message: string,
  ) {
    super(message)
    this.name = 'HumanActionRequiredError'
  }
}

export class PageChangedError extends Error {
  constructor(message = 'The platform page no longer matches the tested contract.') {
    super(message)
    this.name = 'PageChangedError'
  }
}

export class UnknownOutcomeError extends Error {
  constructor(message = 'The action may have been submitted, but no reliable receipt was observed.') {
    super(message)
    this.name = 'UnknownOutcomeError'
  }
}

export class DailyQuotaReachedError extends Error {
  constructor(message = 'The platform daily action quota has been reached.') {
    super(message)
    this.name = 'DailyQuotaReachedError'
  }
}

export interface AdapterContext {
  scenario: Scenario
  signal?: AbortSignal
  onProgress?: (update:Record<string,unknown>)=>Promise<void>|void
}

export interface JobPlatformAdapter {
  readonly platform: Platform
  status(): Promise<PlatformState>
  connect(): Promise<PlatformState>
  disconnect(): Promise<PlatformState>
  discover(spec: SearchSpec, context: AdapterContext): Promise<NormalizedJob[]>
  discoveryStats?(): Record<string,unknown>
  verifyHumanAction?(kind:HumanActionRequiredError['kind'],expectedAccountFingerprint?:string):Promise<'resolved'|'required'|'unknown'>
  prepare(plan: ApplicationPlan, context: AdapterContext): Promise<PreparedApplication>
  submit(plan: ApplicationPlan, context: AdapterContext): Promise<SubmitReceipt>
  reconcile(plan: ApplicationPlan, context: AdapterContext): Promise<SubmitReceipt | null>
}
