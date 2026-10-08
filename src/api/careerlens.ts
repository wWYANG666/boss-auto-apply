import { http } from '@/api/http'
import type {
  AutomationEvent,
  AutomationStatus,
  JobGrade,
  JobPlatform,
  PlatformActionType,
  PlatformCapability,
  PlatformConnectionStatus,
  SearchSpec,
} from '@/types/automation'

export interface PageResponse<T>{items:T[];total:number;nextCursor?:string;hasMore:boolean}
export interface GreetingPolicyResponse {activeResumeVersionId?:string;defaultStyle:string;includeQuestion:boolean;maxLength:number;bannedWords:string[];preferredWords:string[];aiEnabled:boolean;resumes:Array<{versionId:string;title:string;version:number;usable:boolean}>}

export interface HealthStatus {
  status: string
  service: string
  dependencies?: Record<string, { online?: boolean; status?: string; service?: string; mode?: string }>
}

export interface SessionUserResponse {
  id: string
  email: string
  displayName: string
  role?: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: SessionUserResponse
}

export interface ResumeSummaryResponse {
  id: string
  title: string
  kind: string
  headline: string
  currentVersionId: string
  currentVersion: number
  completionRate: number
  templateId: string
  createdAt: string
  updatedAt: string
}

export interface ResumeDraftResponse {
  resumeId: string
  revision: number
  content: Record<string, unknown>
  savedAt: string
  completionRate: number
  pageUsageRate: number
  pageCount: number
  textLayerReadable: boolean
}

export interface ResumeDetailResponse {
  resume: ResumeSummaryResponse
  draft: ResumeDraftResponse
}

export interface ResumeVersionResponse {
  id: string
  resumeId: string
  version: number
  title: string
  description: string
  source: string
  sourceVersionId?: string
  current: boolean
  createdAt: string
  content: Record<string, unknown>
}

export interface ApplicationResponse {
  id: string
  company: string
  role: string
  location: string
  stage: 'wishlist' | 'applied' | 'test' | 'interview' | 'offer' | 'contacted' | 'rejected' | 'withdrawn' | 'closed' | 'hired'
  matchScore: number
  updatedAt: string
  nextAction?: string
  logoText: string
  logoTone: string
  tags: string[]
  platform?: JobPlatform
  externalJobId?: string
  resumeVersionId?: string
  resumeVersion?: number
  actionType?: PlatformActionType | 'MANUAL'
  automationStatus?: AutomationStatus
  receipt?: string
  platformIdentityId?: string
}

export interface ApplicationStatsResponse {
  total: number
  byStage: Record<string, number>
  automated: number
  needsHumanAction: number
}

export interface ApplicationDailyStatsResponse {
  days: number
  today: number
  total: number
  average: number
  items: Array<{ date:string; contacted:number; submitted:number; total:number }>
}

export interface RunnerStatusResponse {
  online: boolean
  name: string
  version: string
  lastHeartbeatAt?: string
  latencyMs?: number
  globalPaused: boolean
}

export interface PlatformResponse {
  id: string
  platform: JobPlatform
  displayName: string
  connectionType: 'LOCAL_BROWSER' | 'MCP'
  status: PlatformConnectionStatus
  identity?: string
  capabilities: PlatformCapability[]
  adapterVersion?: string
  lastCheckedAt?: string
}
export interface PlatformIdentityResponse {id:string;platform:JobPlatform;profileName:string;displayName:string;maskedIdentity?:string;status:string;active:boolean;lastCheckedAt?:string}

export interface DiscoveryRunResponse {
  id: string
  status: string
  progress: number
  rawCount: number
  deduplicatedCount: number
  duplicateCount: number
  errorCode?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

export interface DiscoveredJobResponse {
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
  alreadyTracked: boolean
  jobContentHash: string
}

export interface ApplicationPlanResponse {
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
  resumeVersionId: string
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
  planHash: string
  jobContentHash: string
  approvalExpiresAt?: string
  createdAt: string
}

export interface AutomationTaskResponse {
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
  attempt: number
  stateVersion: number
  receiptSource?: string
  platformReceiptId?: string
  runnerTaskId?: string
  runnerCommandId?: string
  runnerPhase?: string
  lastSyncedAt?: string
  createdAt: string
  updatedAt: string
  retryOfTaskId?: string
  retryRootTaskId?: string
  failureCategory?: string
  retryable: boolean
}

export interface AutomationAuditResponse {
  id: string
  type: string
  detail: string
  occurredAt: string
}

export interface TaskDetailResponse {
  task: AutomationTaskResponse
  events: AutomationAuditResponse[]
}

export interface ApprovalResponse {
  automationRunId: string
  status: string
  approvedCount: number
  approvalExpiresAt: string
  tasks: AutomationTaskResponse[]
}

export interface OneStopRunResponse {
  id?: string
  status: 'idle' | 'running' | 'paused' | 'completed' | 'stopped' | 'failed'
  phase: string
  discoveryRound: number
  groupNumber: number
  batchJobCount: number
  currentTaskCount: number
  processedCount: number
  targetCount: number
  succeededCount: number
  failedCount: number
  cancelledCount: number
  attentionCount: number
  lastError?: string
  createdAt?: string
  updatedAt?: string
  emptyDiscoveryCount?: number
  nextRunAt?: string
  discoveryDetectedCount: number
  discoveryCandidateLimit: number
  discoveryPagesCompleted: number
  discoveryPageLimit: number
  discoveryProgress: number
  discoveryStartedAt?: string
  dailyUsed: number
  dailyLimit: number
  dailyRemaining: number
  platformDailyLimit: number
}

export interface DiscoveryFilterStatsResponse {
  platformReturned:number
  runnerCandidates:number
  runnerFiltered:number
  runnerReturned:number
  inRunDuplicates:number
  historicalDuplicates:number
  linkedJobs:number
  alreadyTracked:number
  contacted:number
  hardConflict:number
  scoreBelowMinimum:number
  companyCooldown:number
  recruiterCooldown:number
  eligible:number
  keywordMismatch:number
  requiredKeywordMismatch:number
  excludedKeyword:number
  excludedCompany:number
  headhunter:number
  contactedByPlatform:number
  recruiterActivity:number
  salaryMismatch:number
  experienceMismatch:number
  degreeMismatch:number
  eligibleTarget:number
  searchStopReason:string
  cacheHit:boolean
  partial:boolean
  pages:Array<{city:string;query:string;pageNumber:number;returned:number;added:number;observedCities:string[];stopReason:string;errorCode?:string;errorMessage?:string}>
}

export const careerLensApi = {
  backupWorkspace: () => http.get<Record<string,unknown>>('/workspace/backup'),
  validateWorkspaceBackup: () => http.get<{valid:boolean;totalRecords:number;tableCounts:Record<string,number>;missingReferences:number;artifactFilesExcluded:boolean}>('/workspace/backup/validate'),
  restoreWorkspace: (body:Record<string,unknown>) => http.post('/workspace/restore',{...body,acknowledged:true}),
  archiveOldData: () => http.post<{tasks:number;plans:number;discoveries:number}>('/maintenance/archive'),
  maintenanceStats: () => http.get<{applications:number;discoveries:number;plans:number;tasks:number;archivedTasks:number;artifactBytes:number;artifacts:number}>('/maintenance/stats'),
  localBackupStatus: () => http.get<{count:number;bytes:number;directory?:string}>('/maintenance/local-backup'),
  createLocalBackup: () => http.post<{path:string;bytes:number;kept:number}>('/maintenance/local-backup'),
  cleanupRunnerLegacyStorage: () => http.post<{deletedBytes:number}>('/runner/storage/cleanup-legacy'),
  greetingPolicy: () => http.get<GreetingPolicyResponse>('/greeting-policy'),
  updateGreetingPolicy: (input:Omit<GreetingPolicyResponse,'resumes'>) => http.put<GreetingPolicyResponse>('/greeting-policy',input),
  executionPolicy: () => http.get<{paused:boolean;dailyLimit:number;dryRun:boolean;used:number;remaining:number;platformLimit:number}>('/execution-policy'),
  updateExecutionPolicy: (paused:boolean,dailyLimit:number,dryRun=false) => http.patch<{paused:boolean;dailyLimit:number;dryRun:boolean;used:number;remaining:number;platformLimit:number}>('/execution-policy',{paused,dailyLimit,dryRun}),
  currentOneStopRun: () => http.get<OneStopRunResponse>('/one-stop-runs/current'),
  startOneStopRun: (resumeVersionId:string,platforms:JobPlatform[],spec:SearchSpec) => http.post<OneStopRunResponse>('/one-stop-runs',{resumeVersionId,platforms,spec}),
  pauseOneStopRun: (id:string) => http.post<OneStopRunResponse>(`/one-stop-runs/${id}/pause`),
  stopOneStopRun: (id:string) => http.post<OneStopRunResponse>(`/one-stop-runs/${id}/stop`),
  resumeOneStopRun: (id:string) => http.post<OneStopRunResponse>(`/one-stop-runs/${id}/resume`),
  health: () => http.get<HealthStatus>('/health'),
  listResumes: () => http.get<ResumeSummaryResponse[]>('/resumes'),
  listResumesPage: (limit=20,offset=0) => http.get<PageResponse<ResumeSummaryResponse>>(`/resumes/page?limit=${limit}&offset=${offset}`),
  importResumeFile: (file: File) => http.upload<ResumeDetailResponse>('/resumes/import',file),
  renameResume: (id:string,title:string) => http.patch<ResumeSummaryResponse>('/resumes/'+id,{title}),
  createArtifact: (versionId:string) => http.post<{id:string;sha256:string;pageCount:number}>('/resume-versions/'+versionId+'/artifacts'),
  createResume: (input: { title: string; headline?: string; content: Record<string, unknown>; source?: string }) =>
    http.post<ResumeDetailResponse>('/resumes', input),
  getResume: (id: string) => http.get<ResumeDetailResponse>(`/resumes/${id}`),
  saveResumeDraft: (id: string, baseRevision: number, content: Record<string, unknown>) =>
    http.put<ResumeDraftResponse>(`/resumes/${id}/draft`, { baseRevision, content }),
  reparseResume: (id: string, baseRevision: number) =>
    http.post<ResumeDraftResponse>(`/resumes/${id}:reparse`, { baseRevision }),
  publishResume: (id: string, content?: Record<string, unknown>, baseRevision?: number) =>
    http.post<ResumeVersionResponse>(`/resumes/${id}/versions`, { source: 'MANUAL_PUBLISH', content, baseRevision }),
  listResumeVersions: (id: string) => http.get<ResumeVersionResponse[]>(`/resumes/${id}/versions`),
  duplicateResume: (id: string) => http.post<ResumeDetailResponse>(`/resumes/${id}:duplicate`),
  deleteResume: (id: string) => http.delete<void>(`/resumes/${id}`),
  restoreResumeVersion: (resumeId: string, versionId: string) =>
    http.post<ResumeVersionResponse>(`/resumes/${resumeId}/versions/${versionId}:restore`),
  applicationDetail: (id:string) => http.get<{application:ApplicationResponse;events:Array<{id:string;detail:string;occurredAt:string}>}>('/applications/'+id),
  applicationNote: (id:string, detail:string) => http.post('/applications/'+id+'/events',{type:'NOTE',detail}),
  listFollowups: () => http.get<Array<{id:string;applicationId:string;title:string;dueAt:string;completed:boolean}>>('/followups'),
  listFollowupsPage: (limit=20,offset=0) => http.get<PageResponse<{id:string;applicationId:string;title:string;dueAt:string;completed:boolean}>>(`/followups/page?limit=${limit}&offset=${offset}`),
  createFollowup: (id:string,title:string,dueAt:string) => http.post('/applications/'+id+'/followups',{title,dueAt}),
  completeFollowup: (id:string,completed:boolean) => http.patch<void>('/followups/'+id,{completed}),
  listApplications: (stage?: string, query?: string,allAccounts=false) => {
    const params = new URLSearchParams()
    if (stage) params.set('stage', stage)
    if (query) params.set('query', query)
    if(allAccounts)params.set('allAccounts','true')
    return http.get<ApplicationResponse[]>(`/applications${params.size ? `?${params}` : ''}`)
  },
  listApplicationsPage: (stage?:string,query?:string,allAccounts=false,limit=20,offset=0) => {
    const params=new URLSearchParams({allAccounts:String(allAccounts),limit:String(limit),offset:String(offset)});if(stage)params.set('stage',stage);if(query)params.set('query',query)
    return http.get<PageResponse<ApplicationResponse>>(`/applications/page?${params}`)
  },
  applicationStats: () => http.get<ApplicationStatsResponse>('/applications/stats'),
  applicationDailyStats: (days=7) => http.get<ApplicationDailyStatsResponse>(`/applications/daily-stats?days=${days}`),
  createApplication: (input: Partial<ApplicationResponse> & { company: string; role: string }) =>
    http.post<ApplicationResponse>('/applications', input),
  updateApplication: (id: string, input: Record<string, unknown>) =>
    http.patch<ApplicationResponse>(`/applications/${id}`, input),
  deleteApplication: (id:string) => http.delete<void>(`/applications/${id}`),
  platformResume: () => http.get<{resumeId:string;snapshotHash:string;summary:string}>('/runner/platform-resume'),
  rotateRunner: () => http.post('/runner/rotate'),
  revokeRunner: () => http.post('/runner/revoke'),
  pairRunner: (code: string) => http.post<{paired:boolean}>('/runner/pair',{code}),
  runnerStatus: () => http.get<RunnerStatusResponse>('/runner/status'),
  runnerStorage: () => http.get<{databaseBytes:number;legacyBytes:number;totalTasks:number;activeTasks:number;retentionDays:number;runnerMemoryBytes:number}>('/runner/storage'),
  bossDiagnostics: () => http.get<{ready:boolean;browserFound:boolean;browserStarted:boolean;manualLoginActive:boolean;browserMode:string;signedIn:boolean;cityCodeCount:number;pageUrl:string;pageTitle:string;authCookies:{wt2:boolean;stoken:boolean;zpAt:boolean;bst:boolean};issues:string[]}>('/runner/boss-diagnostics'),
  bossProfiles: () => http.get<{active:string;profiles:Array<{name:string;active:boolean}>}>('/runner/boss-profiles'),
  bossCities: () => http.get<Array<{code:string;name:string;cities:Array<{code:string;name:string}>}>>('/runner/boss-cities'),
  activateBossProfile: (name:string) => http.post(`/runner/boss-profiles/${encodeURIComponent(name)}/activate`),
  listPlatformIdentities: () => http.get<PlatformIdentityResponse[]>('/platform-identities'),
  createPlatformIdentity: (profileName:string,displayName:string) => http.post<PlatformIdentityResponse>('/platform-identities',{profileName,displayName}),
  activatePlatformIdentity: (id:string) => http.post<PlatformIdentityResponse>(`/platform-identities/${id}/activate`),
  listPlatforms: () => http.get<PlatformResponse[]>('/platforms'),
  connectPlatform: (platform: JobPlatform) => http.post<PlatformResponse>(`/platforms/${platform}/connect`),
  disconnectPlatform: (platform: JobPlatform) => http.delete<PlatformResponse>(`/platforms/${platform}/connect`),
  startDiscovery: (resumeVersionId: string, platforms: JobPlatform[], spec: SearchSpec) =>
    http.post<DiscoveryRunResponse>('/discovery-runs', { resumeVersionId, platforms, spec }),
  getDiscovery: (runId: string) => http.get<DiscoveryRunResponse>(`/discovery-runs/${runId}`),
  currentDiscovery: () => http.get<DiscoveryRunResponse>('/discovery-runs/current'),
  getDiscoveryJobs: (runId: string,limit=100,offset=0) => http.get<DiscoveredJobResponse[]>(`/discovery-runs/${runId}/jobs?limit=${limit}&offset=${offset}`),
  getDiscoveryJobsPage: (runId:string,limit=20,offset=0) => http.get<PageResponse<DiscoveredJobResponse>>(`/discovery-runs/${runId}/jobs/page?limit=${limit}&offset=${offset}`),
  getDiscoveryFilterStats: (runId:string) => http.get<DiscoveryFilterStatsResponse>(`/discovery-runs/${runId}/filter-stats`),
  createApplicationPlans: (input: { discoveryRunId: string; jobIds: string[]; resumeVersionId: string }) =>
    http.post<ApplicationPlanResponse[]>('/application-plans', input),
  listApplicationPlans: (status?: string,limit=100,before?:string) => {
    const params=new URLSearchParams({limit:String(limit)});if(status)params.set('status',status);if(before)params.set('before',before)
    return http.get<ApplicationPlanResponse[]>(`/application-plans?${params}`)
  },
  listApplicationPlansPage: (status?:string,limit=20,before?:string) => {
    const params=new URLSearchParams({limit:String(limit)});if(status)params.set('status',status);if(before)params.set('before',before)
    return http.get<PageResponse<ApplicationPlanResponse>>(`/application-plans/page?${params}`)
  },
  updateApplicationPlan: (planId: string, input: { included?: boolean; greeting?: string; resumeVersionId?: string;platformResumeId?:string;platformResumeHash?:string }) =>
    http.patch<ApplicationPlanResponse>(`/application-plans/${planId}`, input),
  regenerateGreeting: (planId: string) =>
    http.post<ApplicationPlanResponse>(`/application-plans/${planId}:regenerate-greeting`),
  generateGreetingCandidates: (planId:string) => http.post<{text:string;style:string;evidence:Record<string,unknown>;candidates:Array<{style:string;text:string;evidence:Record<string,unknown>;warnings:string[];valid:boolean;qualityScore:number}>}>(`/application-plans/${planId}:generate-greetings`),
  deleteApplicationPlan: (planId:string) => http.delete<void>(`/application-plans/${planId}`),
  clearApplicationPlans: () => http.delete<{deleted:number}>('/application-plans'),
  approveApplicationPlans: (planIds: string[], batchMode = false) =>
    http.post<ApprovalResponse>('/application-plans/approve', { planIds, acknowledged: true, batchMode }),
  listAutomationTasks: (limit=100,before?:string) => http.get<AutomationTaskResponse[]>(`/automation-tasks?limit=${limit}${before?`&before=${encodeURIComponent(before)}`:''}`),
  listAutomationTasksPage: (limit=20,before?:string) => http.get<PageResponse<AutomationTaskResponse>>(`/automation-tasks/page?limit=${limit}${before?`&before=${encodeURIComponent(before)}`:''}`),
  getAutomationTask: (taskId: string) => http.get<TaskDetailResponse>(`/automation-tasks/${taskId}`),
  getAutomationAudit: (taskId: string) => http.get<AutomationAuditResponse[]>(`/automation-tasks/${taskId}/audit`),
  getAutomationAuditPage: (taskId:string,limit=20,offset=0) => http.get<PageResponse<AutomationAuditResponse>>(`/automation-tasks/${taskId}/audit/page?limit=${limit}&offset=${offset}`),
  startAutomationTask: (taskId: string, scenario = 'happy_path') =>
    http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/start`, { scenario }),
  syncAutomationTask: (taskId: string) =>
    http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/sync`),
  reconcileAutomationTask: (taskId: string) =>
    http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/reconcile`),
  reconcileAutomationTasks: (taskIds:string[]) => http.post<{completed:number;skipped:number}>('/automation-tasks/batch-reconcile',{taskIds}),
  retryAutomationTasks: (taskIds:string[]) => http.post<{completed:number;skipped:number}>('/automation-tasks/batch-retry',{taskIds}),
  automationFailureSummary: () => http.get<Array<{reason:string;count:number}>>('/automation-tasks/failure-summary'),
  resolveHumanAction: (taskId: string) =>
    http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/human-action/resolved`),
  retryAutomationTask: (taskId: string) => http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/retry`),
  cancelAutomationTask: (taskId: string) => http.post<AutomationTaskResponse>(`/automation-tasks/${taskId}/cancel`),
  deleteAutomationTask: (taskId:string) => http.delete<void>(`/automation-tasks/${taskId}`),
  clearAutomationTasks: () => http.delete<{deleted:number}>('/automation-tasks'),
}

export const authApi = {
  sessions: () => http.get<Array<{id:string;createdAt:string;expiresAt:string;current:boolean}>>('/auth/sessions'),
  revokeSession: (id:string) => http.delete<void>('/auth/sessions/'+id),
  register: (input: { email: string; password: string; displayName: string }) =>
    http.post<LoginResponse>('/auth/register', input),
  login: (input: { email: string; password: string; rememberMe?: boolean }) => http.post<LoginResponse>('/auth/login', input),
  refresh: () => http.post<LoginResponse>('/auth/refresh'),
  me: () => http.get<SessionUserResponse>('/auth/me'),
  logout: () => http.post<void>('/auth/logout'),
}

export function auditToAutomationEvent(audit: AutomationAuditResponse): AutomationEvent {
  const normalized = audit.type.toUpperCase()
  const tone: AutomationEvent['tone'] = normalized.includes('FAILED') || normalized.includes('CANCELLED')
    ? 'danger'
    : normalized.includes('SUCCEEDED') || normalized.includes('RESOLVED')
      ? 'success'
      : normalized.includes('AWAITING') || normalized.includes('UNKNOWN')
        ? 'warning'
        : 'neutral'
  return {
    id: audit.id,
    time: new Date(audit.occurredAt).toLocaleTimeString('zh-CN', { hour12: false }),
    title: normalized.startsWith('TASK_') ? normalized.slice(5).replaceAll('_', ' ') : normalized.replaceAll('_', ' '),
    detail: audit.detail,
    tone,
  }
}
