export type JobPlatform = 'boss' | 'liepin'

export type PlatformConnectionStatus = 'connected' | 'disconnected' | 'needs_auth'

export type PlatformCapability =
  | 'DISCOVER'
  | 'READ_JOB'
  | 'READ_RESUME'
  | 'CHAT_INITIATED'
  | 'MESSAGE_SENT'
  | 'APPLICATION_SUBMITTED'

export interface PlatformAccount {
  id: string
  platform: JobPlatform
  displayName: string
  connectionType: 'LOCAL_BROWSER' | 'MCP'
  status: PlatformConnectionStatus
  identity: string
  lastCheckedAt: string
  capabilities: PlatformCapability[]
}

export interface SearchSpec {
  keyword: string
  keywordTokens: string[]
  keywordGroups: Array<{name:string;terms:string[];minMatch:number}>
  cities: string[]
  salary: string
  experience: string
  degree: string
  minimumScore: number
  excludeHardConflicts: boolean
  includeKeywords: string[]
  minKeywordMatch: number
  excludeKeywords: string[]
  excludeCompanies: string[]
  excludeHeadhunters: boolean
  excludeContacted: boolean
  recruiterActivity: 'any' | 'online' | 'recent'
  maxJobs: number
  maxPages: number
  maxTotalPages: number
  sameCompanyDays: number
  sameRecruiterDays: number
  homeAddress: string
  commuteMode: 'none' | 'straight' | 'driving' | 'walking'
  maxCommuteDistanceKm: number
  maxCommuteMinutes: number
}

export type JobGrade = 'A' | 'B' | 'C'

export interface DiscoveredJob {
  id: string
  platform: JobPlatform
  externalJobId: string
  company: string
  role: string
  location: string
  salary: string
  experience: string
  degree: string
  publishedAt: string
  recruiter: string
  recruiterActive: string
  recruiterOnline: boolean
  companySize: string
  headhunter: boolean
  contacted: boolean
  riskScore: number
  riskReasons: string[]
  companyTaskCount: number
  recruiterTaskCount: number
  commuteDistanceKm?: number
  commuteDurationMinutes?: number
  companyTone: string
  matchScore: number
  grade: JobGrade
  matchedSkills: string[]
  missingSkills: string[]
  hardConflicts: string[]
  summary: string
  selected: boolean
  alreadyTracked?: boolean
}

export type PlatformActionType = 'CHAT' | 'RESUME_SUBMIT'

export interface ApplicationReview {
  id: string
  jobId: string
  platform: JobPlatform
  externalJobId: string
  company: string
  role: string
  location: string
  salary: string
  matchScore: number
  grade: JobGrade
  actionType: PlatformActionType
  resumeVersionId?: string
  resumeVersion: number
  greeting: string
  greetingStyle?: string
  greetingEvidence?: Record<string,unknown>
  greetingCandidates?: Array<{style:string;text:string;evidence:Record<string,unknown>;warnings:string[];valid:boolean;qualityScore:number}>
  matchedSkills: string[]
  missingSkills: string[]
  hardConflicts: string[]
  recruiter: string
  recruiterActive: string
  recruiterOnline: boolean
  companySize: string
  riskScore: number
  riskReasons: string[]
  companyTaskCount: number
  recruiterTaskCount: number
  commuteDistanceKm?: number
  commuteDurationMinutes?: number
  included: boolean
  approvalStatus: 'draft' | 'approved' | 'expired' | 'failed_retryable' | 'requires_rediscovery' | 'failed_final' | 'skipped' | 'executed'
  createdAt?: string
}

export type AutomationStatus =
  | 'queued'
  | 'preparing'
  | 'submitting'
  | 'verifying'
  | 'succeeded'
  | 'awaiting_login'
  | 'awaiting_captcha'
  | 'awaiting_question'
  | 'page_changed'
  | 'unknown_outcome'
  | 'failed'
  | 'cancelled'
  | 'dry_run'

export interface AutomationEvent {
  id: string
  time: string
  title: string
  detail: string
  tone: 'neutral' | 'success' | 'warning' | 'danger'
}

export interface AutomationTask {
  id: string
  reviewId: string
  platform: JobPlatform
  externalJobId: string
  company: string
  role: string
  actionType: PlatformActionType
  resumeVersion: number
  status: AutomationStatus
  progress: number
  currentStep: string
  humanAction?: string
  receipt?: string
  challengeHandled?: boolean
  attempt?: number
  stateVersion?: number
  receiptSource?: string
  platformReceiptId?: string
  runnerTaskId?: string
  runnerCommandId?: string
  runnerPhase?: string
  lastSyncedAt?: string
  createdAt?: string
  retryOfTaskId?: string
  retryRootTaskId?: string
  failureCategory?: string
  retryable?: boolean
  events: AutomationEvent[]
}

export type AutomationRunStatus = 'ready' | 'running' | 'paused' | 'completed'
