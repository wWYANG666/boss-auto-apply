import { z } from 'zod'

export const PlatformSchema = z.enum(['boss', 'liepin', 'fake'])
export type Platform = z.infer<typeof PlatformSchema>

export const ScenarioSchema = z.enum([
  'happy_path',
  'login_required',
  'captcha_required',
  'page_changed',
  'unknown_after_submit',
])
export type Scenario = z.infer<typeof ScenarioSchema>

export const CommandEnvelopeSchema = z.object({
  commandId: z.string().min(8).max(128),
  taskId: z.string().min(3).max(128).optional(),
  expectedStateVersion: z.number().int().nonnegative().optional(),
  scenario: ScenarioSchema.default('happy_path'),
})

export const SearchSpecSchema = z.object({
  keyword: z.string().min(1).max(120),
  keywordTokens: z.array(z.string().min(1).max(60)).max(20).optional(),
  keywordGroups: z.array(z.object({name:z.string().min(1).max(40),terms:z.array(z.string().min(1).max(60)).min(1).max(20),minMatch:z.number().int().min(1).max(20).default(1)})).max(10).optional(),
  cities: z.array(z.string().min(1).max(40)).min(1).max(40),
  salary: z.string().max(60).optional(),
  experience: z.string().max(60).optional(),
  degree: z.string().max(60).optional(),
  maxPages: z.number().int().min(1).max(10).default(2),
  maxTotalPages: z.number().int().min(1).max(100).optional(),
  bypassCache: z.boolean().optional(),
  eligibleTarget: z.number().int().min(20).max(170).optional(),
  resumeScopes: z.array(z.object({city:z.string().min(1).max(40),query:z.string().min(1).max(120),pageNumber:z.number().int().min(1).max(10)})).max(100).optional(),
  resumePagesCompleted: z.number().int().min(0).max(100).optional(),
  resumeDetectedCount: z.number().int().min(0).max(1000).optional(),
  resumePageLimit: z.number().int().min(1).max(100).optional(),
  resumeCandidateLimit: z.number().int().min(1).max(1000).optional(),
  includeKeywords: z.array(z.string().max(60)).max(30).default([]),
  minKeywordMatch: z.number().int().min(0).max(30).default(0),
  excludeKeywords: z.array(z.string().max(60)).max(30).default([]),
  excludeCompanies: z.array(z.string().max(80)).max(30).default([]),
  excludeHeadhunters: z.boolean().default(false),
  excludeContacted: z.boolean().default(true),
  recruiterActivity: z.enum(['any','online','recent']).default('any'),
  maxJobs: z.number().int().min(1).max(150).default(50),
  sameCompanyDays: z.number().int().min(0).max(365).default(0),
  sameRecruiterDays: z.number().int().min(0).max(365).default(0),
  homeAddress: z.string().max(200).default(''),
  commuteMode: z.enum(['none','straight','driving','walking']).default('none'),
  maxCommuteDistanceKm: z.number().min(0).max(500).default(0),
  maxCommuteMinutes: z.number().int().min(0).max(600).default(0),
})
export type SearchSpec = z.infer<typeof SearchSpecSchema>

export const DiscoverCommandSchema = CommandEnvelopeSchema.extend({
  platform: PlatformSchema,
  platformIdentityId: z.string().uuid().optional(),
  profileName: z.string().regex(/^[a-zA-Z0-9_-]{1,32}$/).optional(),
  accountFingerprint: z.string().regex(/^[a-f0-9]{64}$/i).optional(),
  spec: SearchSpecSchema,
})

export const ApplicationPlanSchema = z.object({
  planId: z.string().min(3).max(128),
  platform: PlatformSchema,
  platformIdentityId: z.string().uuid().optional(),
  profileName: z.string().regex(/^[a-zA-Z0-9_-]{1,32}$/).optional(),
  accountFingerprint: z.string().regex(/^[a-f0-9]{64}$/i).optional(),
  externalJobId: z.string().min(1).max(256),
  canonicalUrl: z.string().url().optional(),
  company: z.string().min(1).max(160),
  role: z.string().min(1).max(160),
  location: z.string().max(160).optional(),
  actionType: z.enum(['CHAT', 'RESUME_SUBMIT']),
  resumeVersionId: z.string().min(1).max(128),
  resumeFileSha256: z.string().regex(/^[a-f0-9]{64}$/i).optional(),
  resumeContentSha256: z.string().regex(/^[a-f0-9]{64}$/i).optional(),
  materialSource: z.enum(['CHAT_ONLY','PLATFORM_RESUME']).optional(),
  message: z.string().max(500).default(''),
  answers: z.record(z.string(), z.union([z.string(), z.boolean(), z.number()])).default({}),
  jobContentHash: z.string().min(8).max(128),
  pageFingerprint: z.string().min(3).max(256).optional(),
})
export type ApplicationPlan = z.infer<typeof ApplicationPlanSchema>

export const ApprovalProofSchema = z.object({
  approvalId: z.string().min(8).max(128),
  planHash: z.string().regex(/^[a-f0-9]{64}$/i),
  expiresAt: z.string().datetime(),
  nonce: z.string().min(12).max(256),
  signature: z.string().regex(/^[a-f0-9]{64}$/i),
})
export type ApprovalProof = z.infer<typeof ApprovalProofSchema>

export const PrepareCommandSchema = CommandEnvelopeSchema.extend({
  plan: ApplicationPlanSchema,
})

export const SubmitCommandSchema = CommandEnvelopeSchema.extend({
  plan: ApplicationPlanSchema,
  approval: ApprovalProofSchema,
})

export type TaskStatus =
  | 'queued'
  | 'running'
  | 'waiting_human'
  | 'succeeded'
  | 'partial'
  | 'failed'
  | 'unknown_outcome'
  | 'cancelled'

export type TaskPhase =
  | 'connecting'
  | 'searching'
  | 'normalizing'
  | 'opening_page'
  | 'preflight'
  | 'filling'
  | 'submitting'
  | 'verifying'
  | 'reconciling'

export interface RunnerEvent {
  id: string
  taskId: string
  at: string
  type: 'task.updated' | 'human-action.created' | 'human-action.resolved' | 'platform.updated'
  title: string
  detail: string
}

export interface HumanAction {
  id: string
  kind: 'LOGIN' | 'CAPTCHA' | 'QUESTION' | 'EXTERNAL_HANDOFF'
  title: string
  description: string
  status: 'pending' | 'resolved'
}

export interface RunnerTask<T = unknown> {
  id: string
  commandId: string
  platform: Platform
  type: 'discover' | 'prepare' | 'submit' | 'reconcile'
  status: TaskStatus
  phase: TaskPhase
  progress: number
  stateVersion: number
  createdAt: string
  updatedAt: string
  input?: unknown
  inputHash: string
  result?: T
  metadata?: Record<string,unknown>
  errorCode?: string
  errorMessage?: string
  humanAction?: HumanAction
  events: RunnerEvent[]
}

export interface NormalizedJob {
  jobKind?: string | number
  securityId?: string
  lid?: string
  platform: Platform
  externalJobId: string
  canonicalUrl: string
  company: string
  role: string
  location: string
  salary: string
  experience?: string
  experienceRisk?: 'exact'|'unrestricted'|'lower'|'adjacent'
  degree?: string
  description: string
  recruiter?: string
  recruiterId?: string
  recruiterActive?: string
  recruiterOnline?: boolean
  companySize?: string
  headhunter?: boolean
  contacted?: boolean
  longitude?: number
  latitude?: number
  commuteDistanceKm?: number
  commuteDurationMinutes?: number
  publishedAt?: string
}

export interface PreparedApplication {
  planHash: string
  pageFingerprint: string
  actionSummary: string
  fields: Array<{ key: string; label: string; value: string; source: string }>
  warnings: string[]
}

export interface SubmitReceipt {
  receiptSource?: 'platform' | 'observation' | 'fixture'
  platformReceiptId?: string
  outcome: 'APPLICATION_SUBMITTED' | 'CHAT_INITIATED' | 'MESSAGE_SENT' | 'ALREADY_APPLIED'
  receiptId: string
  verifiedAt: string
  evidence: string
}

export interface PlatformState {
  platform: Platform
  connected: boolean
  mode: 'fake' | 'browser' | 'mcp'
  capabilities: string[]
  lastCheckedAt: string
  message: string
  profileName?: string
  accountFingerprint?: string
  maskedIdentity?: string
}
