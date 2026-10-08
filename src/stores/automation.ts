import { computed, reactive, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import {
  auditToAutomationEvent,
  careerLensApi,
  type ApplicationPlanResponse,
  type AutomationAuditResponse,
  type AutomationTaskResponse,
  type DiscoveredJobResponse,
  type DiscoveryRunResponse,
  type OneStopRunResponse,
  type PlatformResponse,
  type PlatformIdentityResponse,
} from '@/api/careerlens'
import type {
  ApplicationReview,
  AutomationRunStatus,
  AutomationTask,
  DiscoveredJob,
  JobPlatform,
  PlatformAccount,
  SearchSpec,
} from '@/types/automation'
import { useSessionStore } from '@/stores/session'
import { useWorkspaceStore } from '@/stores/workspace'

function formatRecency(value?: string): string {
  if (!value) return '尚未检查'
  const elapsed = Date.now() - new Date(value).getTime()
  if (!Number.isFinite(elapsed) || elapsed < 60_000) return '刚刚'
  if (elapsed < 3_600_000) return `${Math.max(1, Math.floor(elapsed / 60_000))} 分钟前`
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function taskMessage(value?:string):string|undefined{
  if(!value)return value
  const messages:Array<[RegExp,string]>=[
    [/Approval expired before execution\.?/i,'审批凭证已过期，任务未执行平台动作，可重新批准。'],
    [/BOSS_PAGE_JOB_ID_MISMATCH[^.]*/i,'BOSS打开的岗位与审核岗位不一致，未发送招呼语。'],
    [/VERIFIED_NOT_SUBMITTED[^.]*/i,'平台历史确认该招呼语尚未发送。'],
    [/BOSS_GREETING_DELIVERY_NOT_OBSERVED[^.]*/i,'沟通已建立，但暂未在平台历史中确认招呼语。'],
    [/BOSS_JOB_REDIRECTED_HOME[^.]*/i,'BOSS将岗位页跳回首页，需要在专用浏览器中检查。'],
  ]
  for(const [pattern,message] of messages)if(pattern.test(value))return message
  return value
}

function mapPlatform(account: PlatformResponse): PlatformAccount {
  return {
    id: account.id,
    platform: account.platform,
    displayName: account.displayName,
    connectionType: account.connectionType,
    status: account.status,
    identity: account.identity || '待本地授权',
    lastCheckedAt: formatRecency(account.lastCheckedAt),
    capabilities: account.capabilities,
  }
}

function mapJob(job: DiscoveredJobResponse, minimumScore: number): DiscoveredJob {
  return {
    id: job.id,
    platform: job.platform,
    externalJobId: job.externalJobId,
    company: job.company,
    role: job.role,
    location: job.location,
    salary: job.salary,
    experience: job.experience,
    degree: job.degree,
    publishedAt: job.publishedAt,
    recruiter: job.recruiter,
    recruiterActive: job.recruiterActive,
    recruiterOnline: Boolean(job.recruiterOnline),
    companySize: job.companySize || '未提供',
    headhunter: Boolean(job.headhunter),
    contacted: Boolean(job.contacted),
    riskScore: job.riskScore || 0,
    riskReasons: job.riskReasons || [],
    companyTaskCount: job.companyTaskCount || 0,
    recruiterTaskCount: job.recruiterTaskCount || 0,
    commuteDistanceKm: job.commuteDistanceKm,
    commuteDurationMinutes: job.commuteDurationMinutes,
    companyTone: job.companyTone || '#eef4f0',
    matchScore: job.matchScore,
    grade: job.grade,
    matchedSkills: job.matchedSkills,
    missingSkills: job.missingSkills,
    hardConflicts: job.hardConflicts,
    summary: job.summary,
    selected: job.matchScore >= minimumScore && !job.hardConflicts.length && !job.alreadyTracked && !job.contacted,
    alreadyTracked: job.alreadyTracked,
  }
}

function mapPlan(plan: ApplicationPlanResponse): ApplicationReview {
  return {
    id: plan.id,
    jobId: plan.jobId,
    platform: plan.platform,
    externalJobId: plan.externalJobId,
    company: plan.company,
    role: plan.role,
    location: plan.location,
    salary: plan.salary,
    matchScore: plan.matchScore,
    grade: plan.grade,
    actionType: plan.actionType,
    resumeVersionId: plan.resumeVersionId,
    resumeVersion: plan.resumeVersion,
    greeting: plan.greeting,
    greetingStyle: plan.greetingStyle,
    greetingEvidence: plan.greetingEvidence,
    greetingCandidates: plan.greetingCandidates,
    matchedSkills: plan.matchedSkills,
    missingSkills: plan.missingSkills,
    hardConflicts: plan.hardConflicts,
    recruiter: plan.recruiter || '未提供',
    recruiterActive: plan.recruiterActive || '未提供',
    recruiterOnline: Boolean(plan.recruiterOnline),
    companySize: plan.companySize || '未提供',
    riskScore: plan.riskScore || 0,
    riskReasons: plan.riskReasons || [],
    companyTaskCount: plan.companyTaskCount || 0,
    recruiterTaskCount: plan.recruiterTaskCount || 0,
    commuteDistanceKm: plan.commuteDistanceKm,
    commuteDurationMinutes: plan.commuteDurationMinutes,
    included: plan.included,
    approvalStatus: plan.approvalStatus,
    createdAt: plan.createdAt,
  }
}

function defaultTaskEvent(task: AutomationTaskResponse): AutomationTask['events'][number] {
  const tone = task.status === 'succeeded'
    ? 'success'
    : task.status.startsWith('awaiting_') || task.status === 'unknown_outcome'
      ? 'warning'
      : task.status === 'failed' || task.status === 'cancelled'
        ? 'danger'
        : 'neutral'
  return {
    id: `state-${task.id}-${task.stateVersion}`,
    time: new Date(task.updatedAt).toLocaleTimeString('zh-CN', { hour12: false }),
    title: task.status === 'queued' ? '已进入执行队列' : taskMessage(task.currentStep)!,
    detail: taskMessage(task.humanAction) || task.receipt || `状态版本 ${task.stateVersion}`,
    tone,
  }
}

function mapTask(task: AutomationTaskResponse, audits: AutomationAuditResponse[] = [], previous?: AutomationTask): AutomationTask {
  const events = audits.length
    ? audits.map(auditToAutomationEvent)
    : previous?.events.length
      ? previous.events
      : [defaultTaskEvent(task)]
  return {
    id: task.id,
    reviewId: task.reviewId,
    platform: task.platform,
    externalJobId: task.externalJobId,
    company: task.company,
    role: task.role,
    actionType: task.actionType,
    resumeVersion: task.resumeVersion,
    status: task.status,
    progress: task.progress,
    currentStep: taskMessage(task.currentStep)!,
    humanAction: taskMessage(task.humanAction),
    receipt: task.receipt,
    attempt: task.attempt,
    stateVersion: task.stateVersion,
    receiptSource: task.receiptSource,
    platformReceiptId: task.platformReceiptId,
    runnerTaskId: task.runnerTaskId,
    runnerCommandId: task.runnerCommandId,
    runnerPhase: task.runnerPhase,
    lastSyncedAt: task.lastSyncedAt,
    createdAt: task.createdAt,
    retryOfTaskId: task.retryOfTaskId,
    retryRootTaskId: task.retryRootTaskId,
    failureCategory: task.failureCategory,
    retryable: task.retryable,
    events,
  }
}

export const useAutomationStore = defineStore('automation', () => {
  const workspace = useWorkspaceStore()
  const session = useSessionStore()
  const remoteMode = computed(() => session.remoteMode)
  const gatewayStatus = ref<'connecting' | 'online' | 'error'>('connecting')
  const gatewayError = ref('')
  const initialized = ref(false)
  const busy = ref(false)
  const oneStopRunning = ref(false)
  const oneStopStopRequested = ref(false)
  const oneStopRun=ref<OneStopRunResponse>({status:'idle',phase:'IDLE',discoveryRound:0,groupNumber:0,batchJobCount:0,currentTaskCount:0,processedCount:0,targetCount:0,succeededCount:0,failedCount:0,cancelledCount:0,attentionCount:0,discoveryDetectedCount:0,discoveryCandidateLimit:0,discoveryPagesCompleted:0,discoveryPageLimit:0,discoveryProgress:0,dailyUsed:0,dailyLimit:20,dailyRemaining:20,platformDailyLimit:150})
  const oneStopStorageKey = 'careerlens:one-stop:last-run:v1'
  const autoOneStop = ref(typeof window !== 'undefined' && localStorage.getItem('careerlens:auto-one-stop') === 'true')
  const activeResumeVersionId = ref('')
  const currentDiscoveryRunId = ref('')
  let initializePromise: Promise<void> | undefined

  function persistOneStopSnapshot() {
    if (typeof window === 'undefined') return
    try { localStorage.setItem(oneStopStorageKey, JSON.stringify(oneStopRun.value)) } catch { /* Ignore unavailable storage. */ }
  }

  function restoreOneStopSnapshot() {
    if (typeof window === 'undefined') return
    try {
      const saved = JSON.parse(localStorage.getItem(oneStopStorageKey) || 'null') as Partial<OneStopRunResponse> | null
      if (saved && typeof saved === 'object' && saved.status) oneStopRun.value = { ...oneStopRun.value, ...saved }
    } catch { /* Ignore malformed stale snapshots. */ }
  }

  watch(oneStopRun, persistOneStopSnapshot, { deep: true })

  const runner = reactive({
    online: false,
    name: '本地执行器',
    version: 'runner 0.1.0',
    lastHeartbeat: '尚未检查',
    globalPaused: false,
  })

  const platformAccounts = ref<PlatformAccount[]>([])
  const platformIdentities=ref<PlatformIdentityResponse[]>([])
  const activePlatformIdentity=computed(()=>platformIdentities.value.find(identity=>identity.active))

  const defaultSearchSpec:SearchSpec = {
    keyword: '',
    keywordTokens: [],
    keywordGroups: [],
    cities: [],
    salary: '',
    experience: '应届 / 经验不限',
    degree: '本科及以上',
    minimumScore: 70,
    excludeHardConflicts: true,
    includeKeywords: [],
    minKeywordMatch: 0,
    excludeKeywords: [],
    excludeCompanies: [],
    excludeHeadhunters: false,
    excludeContacted: true,
    recruiterActivity: 'any',
    maxJobs: 50,
    maxPages: 4,
    maxTotalPages: 30,
    sameCompanyDays: 30,
    sameRecruiterDays: 14,
    homeAddress: '',
    commuteMode: 'none',
    maxCommuteDistanceKm: 0,
    maxCommuteMinutes: 0,
  }
  const searchConfigStorageKey=()=>`careerlens:job-search-config:v2:${activePlatformIdentity.value?.id??'default'}`
  function loadSearchSpec():SearchSpec{
    if(typeof window==='undefined')return {...defaultSearchSpec,cities:[]}
    try{
      const stored=localStorage.getItem(searchConfigStorageKey())
        ?? ((!activePlatformIdentity.value||activePlatformIdentity.value.profileName==='default')?localStorage.getItem('careerlens:job-search-config:v1'):null)
      const saved=JSON.parse(stored||'null') as Partial<SearchSpec>|null
      if(!saved||typeof saved!=='object')return {...defaultSearchSpec,cities:[]}
      return {...defaultSearchSpec,...saved,
        minimumScore:Math.max(0,Math.min(100,Number(saved.minimumScore??defaultSearchSpec.minimumScore))),
        maxJobs:Math.max(1,Math.min(150,Number(saved.maxJobs??defaultSearchSpec.maxJobs))),
        maxPages:Math.max(1,Math.min(10,Number(saved.maxPages??defaultSearchSpec.maxPages))),
        maxTotalPages:Math.max(1,Math.min(100,Number(saved.maxTotalPages??defaultSearchSpec.maxTotalPages))),
        cities:Array.isArray(saved.cities)?saved.cities.filter(item=>typeof item==='string'):[],
        keywordTokens:Array.isArray(saved.keywordTokens)?saved.keywordTokens.filter(item=>typeof item==='string'&&item.trim()):typeof saved.keyword==='string'?(saved.keyword.match(/[A-Za-z0-9+#.-]+|[\u4e00-\u9fff]{2,}/g)??[saved.keyword]).filter(Boolean):[],
        keywordGroups:Array.isArray(saved.keywordGroups)?saved.keywordGroups.filter(group=>group&&Array.isArray(group.terms)&&group.terms.length).map(group=>({name:String(group.name||'关键词组'),terms:group.terms.map(String),minMatch:Math.max(1,Number(group.minMatch)||1)})):[]}
    }catch{return {...defaultSearchSpec,cities:[]}}
  }
  const searchSpec = reactive<SearchSpec>(loadSearchSpec())
  function applySearchSpec(spec:SearchSpec){
    Object.assign(searchSpec,JSON.parse(JSON.stringify(spec)) as SearchSpec)
  }
  function saveSearchConfig(){
    searchSpec.minimumScore=Math.max(0,Math.min(100,Number(searchSpec.minimumScore)||0))
    searchSpec.maxJobs=Math.max(1,Math.min(150,Number(searchSpec.maxJobs)||defaultSearchSpec.maxJobs))
    searchSpec.maxPages=Math.max(1,Math.min(10,Number(searchSpec.maxPages)||defaultSearchSpec.maxPages))
    searchSpec.maxTotalPages=Math.max(1,Math.min(100,Number(searchSpec.maxTotalPages)||defaultSearchSpec.maxTotalPages))
    searchSpec.keyword=searchSpec.keywordTokens.join(' ')
    localStorage.setItem(searchConfigStorageKey(),JSON.stringify({...searchSpec,cities:[...searchSpec.cities],keywordTokens:[...searchSpec.keywordTokens],includeKeywords:[...searchSpec.includeKeywords],excludeKeywords:[...searchSpec.excludeKeywords],excludeCompanies:[...searchSpec.excludeCompanies]}))
    workspace.addToast('职位筛选配置已保存','下次进入职位发现会自动恢复。')
  }
  function setAutoOneStop(enabled:boolean){
    autoOneStop.value=enabled
    localStorage.setItem('careerlens:auto-one-stop',String(enabled))
    workspace.addToast(enabled?'已开启免确认启动':'已恢复启动确认',enabled?'点击一条龙后将直接启动。':'点击一条龙时会再次询问确认。','info')
  }
  function resetSearchConfig(){
    Object.assign(searchSpec,{...defaultSearchSpec,cities:[] ,includeKeywords:[],excludeKeywords:[],excludeCompanies:[]})
    localStorage.removeItem(searchConfigStorageKey())
    workspace.addToast('已恢复默认配置')
  }

  const discoveryStats = reactive({ rawCount: 0, deduplicatedCount: 0, duplicateCount: 0 })
  const discoveryFilterStats=reactive({platformReturned:0,runnerCandidates:0,runnerFiltered:0,runnerReturned:0,inRunDuplicates:0, historicalDuplicates:0,linkedJobs:0,alreadyTracked:0,contacted:0,hardConflict:0,scoreBelowMinimum:0,companyCooldown:0,recruiterCooldown:0,eligible:0,keywordMismatch:0,requiredKeywordMismatch:0,excludedKeyword:0,excludedCompany:0,headhunter:0,contactedByPlatform:0,recruiterActivity:0,salaryMismatch:0,experienceMismatch:0,degreeMismatch:0,eligibleTarget:0,searchStopReason:'',cacheHit:false,partial:false,pages:[] as Array<{city:string;query:string;pageNumber:number;returned:number;added:number;observedCities:string[];stopReason:string;errorCode?:string;errorMessage?:string}>})
  const discoveredJobs = ref<DiscoveredJob[]>([])
  const hasMoreDiscoveredJobs=ref(false)
  const reviews = ref<ApplicationReview[]>([])
  const hasMoreReviews=ref(false)
  const tasks = ref<AutomationTask[]>([])
  const hasMoreTasks=ref(false)
  const failureSummary=ref<Array<{reason:string;count:number}>>([])
  const runStatus = ref<AutomationRunStatus>('ready')
  const searchStatus = ref<'idle' | 'searching' | 'partial' | 'completed'>('idle')
  const searchProgress = ref(0)
  let searchTimer: number | undefined
  const discoveryStorageKey='careerlens:discovery:active:v1'
  let pollingTimer: number | undefined
  let pollingInFlight = false
  let oneStopMonitorTimer:number|undefined
  const planUpdateTimers = new Map<string, number>()

  const selectedJobCount = computed(() => discoveredJobs.value.filter((job) => job.selected).length)
  const includedReviewCount = computed(() => reviews.value.filter((review) => review.included).length)
  const humanActionCount = computed(() => tasks.value.filter((task) => task.status.startsWith('awaiting_')).length)
  const succeededCount = computed(() => tasks.value.filter((task) => task.status === 'succeeded').length)
  const queuedCount = computed(() => tasks.value.filter((task) => ['queued', 'preparing', 'submitting', 'verifying'].includes(task.status)).length)
  const overallProgress = computed(() => {
    if (!tasks.value.length) return 0
    return Math.round(tasks.value.reduce((total, task) => total + task.progress, 0) / tasks.value.length)
  })

  function platformName(platform: JobPlatform) {
    return platform === 'boss' ? 'BOSS直聘' : '猎聘'
  }

  function reportError(title: string, cause: unknown) {
    const message = cause instanceof Error ? cause.message : '请稍后重试'
    gatewayError.value = message
    gatewayStatus.value = 'error'
    workspace.addToast(title, message, 'warning')
  }

  function deriveRunStatus() {
    if (!tasks.value.length) {
      runStatus.value = 'ready'
      return
    }
    if(oneStopRun.value.status==='running'){
      runStatus.value='running'
    } else if (tasks.value.some((task) => task.status.startsWith('awaiting_') || task.status === 'unknown_outcome')) {
      runStatus.value = 'paused'
    } else if (tasks.value.some((task) => ['preparing', 'submitting', 'verifying'].includes(task.status))) {
      runStatus.value = 'running'
    } else if (tasks.value.some((task) => task.status === 'queued')) {
      runStatus.value = 'ready'
    } else {
      runStatus.value = 'completed'
    }
  }

  function taskNeedsPolling(task:AutomationTask):boolean {
    return ['queued','preparing','submitting','verifying'].includes(task.status)
  }

  function taskSnapshotChanged(next:AutomationTask[],previous:AutomationTask[]):boolean {
    if(next.length!==previous.length)return true
    const versions=new Map(previous.map(task=>[task.id,`${task.stateVersion}|${task.status}|${task.progress}|${task.currentStep}|${task.receipt??''}`]))
    return next.some(task=>versions.get(task.id)!==`${task.stateVersion}|${task.status}|${task.progress}|${task.currentStep}|${task.receipt??''}`)
  }

  async function loadRemoteState() {
    gatewayStatus.value = 'connecting'
    gatewayError.value = ''
    const [resumes, accounts, identities, runnerStatus, policy, plans, remoteTasks, remoteOneStop, greetingPolicy] = await Promise.all([
      careerLensApi.listResumes(),
      careerLensApi.listPlatforms(),
      careerLensApi.listPlatformIdentities(),
      careerLensApi.runnerStatus(),
      careerLensApi.executionPolicy(),
      careerLensApi.listApplicationPlansPage('actionable',20),
      careerLensApi.listAutomationTasksPage(20),
      careerLensApi.currentOneStopRun(),
      careerLensApi.greetingPolicy().catch(()=>null),
    ])

    const activeResume = resumes.find((resume) => resume.currentVersionId===greetingPolicy?.activeResumeVersionId)
      ?? resumes.find((resume)=>resume.completionRate>0&&Boolean(resume.currentVersionId))
    if (activeResume) {
      activeResumeVersionId.value = activeResume.currentVersionId
      workspace.resumeVersion = activeResume.currentVersion
    }
    platformAccounts.value = accounts.map(mapPlatform)
    platformIdentities.value=identities
    const activeIdentity=identities.find(identity=>identity.active)
    const bossAccount=platformAccounts.value.find(account=>account.platform==='boss')
    if(bossAccount&&activeIdentity)bossAccount.status=activeIdentity.status==='connected'?'connected':activeIdentity.status==='needs_auth'?'needs_auth':'disconnected'
    Object.assign(searchSpec,loadSearchSpec())
    Object.assign(runner, {
      online: runnerStatus.online,
      name: runnerStatus.name || 'CareerLens 本地执行器',
      version: runnerStatus.version || 'runner',
      lastHeartbeat: formatRecency(runnerStatus.lastHeartbeatAt),
      globalPaused: runnerStatus.globalPaused || policy.paused,
    })
    hasMoreReviews.value=plans.hasMore
    reviews.value = plans.items.filter(plan => ['draft','expired'].includes(plan.approvalStatus)).map(mapPlan)
    hasMoreTasks.value=remoteTasks.hasMore
    tasks.value = remoteTasks.items.map(task=>mapTask(task))
    failureSummary.value=await careerLensApi.automationFailureSummary().catch(()=>[])
    oneStopRun.value=remoteOneStop
    persistOneStopSnapshot()
    oneStopRunning.value=remoteOneStop.status==='running'
    discoveredJobs.value = []
    discoveryStats.rawCount = 0
    discoveryStats.deduplicatedCount = 0
    discoveryStats.duplicateCount = 0
    searchStatus.value = 'idle'
    searchProgress.value = 0
    deriveRunStatus()
    gatewayStatus.value = 'online'
    initialized.value = true
    if (tasks.value.some(taskNeedsPolling)) startTaskPolling()
    if(oneStopRunning.value||remoteOneStop.status==='paused'&&tasks.value.some(task=>task.status==='awaiting_login'))startOneStopMonitor()
    await restoreDiscoveryRun()
  }

  async function initialize(force = false) {
    if (!remoteMode.value || !session.authenticated) return
    if (initialized.value && !force) return
    if (initializePromise) return initializePromise
    restoreOneStopSnapshot()
    initializePromise = loadRemoteState()
      .catch((cause) => reportError('真实数据加载失败', cause))
      .finally(() => { initializePromise = undefined })
    return initializePromise
  }

  function toggleJobSelection(id: string) {
    const job = discoveredJobs.value.find((item) => item.id === id)
    if (!job || job.alreadyTracked || job.hardConflicts.length > 0) return
    setJobSelection(id,!job.selected)
  }

  function jobCompanyKey(job:DiscoveredJob):string {
    return `${job.platform}:${job.company.trim().toLowerCase().replace(/[\\s·•・,，.。()（）【】[\\]{}<>《》_-]/g,'')}`
  }

  function setJobSelection(id:string,selected:boolean,silent=false):boolean {
    const job=discoveredJobs.value.find(item=>item.id===id)
    if(!job||job.alreadyTracked||job.hardConflicts.length>0||job.selected===selected)return false
    if(selected&&job.platform==='boss'){
      const selectedBoss=discoveredJobs.value.filter(item=>item.platform==='boss'&&item.selected)
      const key=jobCompanyKey(job)
      if(selectedBoss.some(item=>jobCompanyKey(item)===key)){
        if(!silent)workspace.addToast('同一家公司只能选择一个岗位',job.company,'warning')
        return false
      }
      if(new Set(selectedBoss.map(jobCompanyKey)).size>=5){
        if(!silent)workspace.addToast('本批最多选择5家公司','请先取消一家后再选择。','warning')
        return false
      }
    }
    job.selected=selected
    return true
  }

  function selectRecommendedJobs() {
    const companies=new Set<string>()
    discoveredJobs.value.forEach((job) => {
      const eligible=job.matchScore >= searchSpec.minimumScore && !job.hardConflicts.length && !job.alreadyTracked && !(searchSpec.excludeContacted&&job.contacted)
      const key=`${job.platform}:${job.company.trim().toLowerCase().replace(/[\\s·•・,，.。()（）【】[\\]{}<>《》_-]/g,'')}`
      const allowed=job.platform!=='boss'||companies.size<5&&!companies.has(key)
      job.selected=eligible&&allowed
      if(job.selected&&job.platform==='boss')companies.add(key)
    })
    workspace.addToast('已选择推荐岗位', `${selectedJobCount.value} 个岗位已按最多5家公司、每家公司1个岗位的规则选择。`)
  }

  function selectJobs(ids:string[],selected:boolean):{changed:number;skipped:number}{
    let changed=0,skipped=0
    for(const id of ids){
      const before=discoveredJobs.value.find(job=>job.id===id)?.selected
      if(setJobSelection(id,selected,true)){changed++}
      else if(selected&&!before)skipped++
    }
    if(selected&&skipped)workspace.addToast('已按投递约束选择',`选中 ${changed} 个岗位，跳过 ${skipped} 个（BOSS手动模式最多5家公司、每家公司1个岗位或存在硬冲突）。`,'info')
    else workspace.addToast(selected?'已全选当前可用岗位':'已取消当前筛选岗位',selected?`${changed} 个岗位已加入选择。`:`${changed} 个岗位已取消选择。`,'info')
    return{changed,skipped}
  }

  async function loadDiscoveryResult(run:DiscoveryRunResponse,notify=true){
    currentDiscoveryRunId.value=run.id
    localStorage.setItem(discoveryStorageKey,run.id)
    const jobs=await careerLensApi.getDiscoveryJobsPage(run.id,20,0)
    hasMoreDiscoveredJobs.value=jobs.hasMore
    discoveredJobs.value=jobs.items.map(job=>mapJob(job,searchSpec.minimumScore))
    Object.assign(discoveryStats,{rawCount:run.rawCount,deduplicatedCount:run.deduplicatedCount,duplicateCount:run.duplicateCount})
    Object.assign(discoveryFilterStats,await careerLensApi.getDiscoveryFilterStats(run.id))
    searchProgress.value=run.progress
    searchStatus.value=run.status==='partial'?'partial':run.status==='completed'||run.status==='failed'?'completed':'searching'
    if(!notify)return
    if(run.status==='partial')workspace.addToast('职位发现部分完成',run.errorMessage||'已保留当前结果，仍有城市或页码未完成。','warning')
    else if(run.status==='failed')workspace.addToast('职位发现未完成',run.errorMessage||(jobs.items.length?'已保留可用结果。':'请检查Runner和平台状态。'),'warning')
    else workspace.addToast('职位发现完成',`平台返回 ${discoveryFilterStats.platformReturned} 条，最终可执行 ${discoveryFilterStats.eligible} 个岗位。`)
  }

  async function restoreDiscoveryRun(){
    const saved=localStorage.getItem(discoveryStorageKey)
    let run=saved?await careerLensApi.getDiscovery(saved).catch(()=>null):null
    if(!run)run=await careerLensApi.currentDiscovery().catch(()=>null)
    if(!run)return
    await loadDiscoveryResult(run,false)
    if(['queued','running','searching'].includes(run.status))void monitorDiscovery(run.id,false)
  }

  async function monitorDiscovery(runId:string,notify=true){
    searchStatus.value='searching'
    let failures=0
    for(;;){
      await new Promise(resolve=>window.setTimeout(resolve,1000))
      let run:DiscoveryRunResponse
      try{
        run=await careerLensApi.getDiscovery(runId)
        failures=0;gatewayStatus.value='online';gatewayError.value=''
      }catch(cause){
        failures++
        if(failures>=3){gatewayStatus.value='error';gatewayError.value=cause instanceof Error?cause.message:'暂时无法读取职位发现状态'}
        await new Promise(resolve=>window.setTimeout(resolve,Math.min(10_000,failures*1000)))
        continue
      }
      searchProgress.value=Math.max(searchProgress.value,run.progress)
      if(['completed','partial','failed','cancelled'].includes(run.status)){await loadDiscoveryResult(run,notify);return}
    }
  }

  async function runDiscovery(platforms?: JobPlatform[]) {
    window.clearInterval(searchTimer)
    searchStatus.value = 'searching'
    searchProgress.value = 8
    searchTimer = window.setInterval(() => {
      searchProgress.value = Math.min(92, searchProgress.value + (remoteMode.value ? 4 : 12))
    }, remoteMode.value ? 350 : 140)

    if (remoteMode.value) {
      busy.value = true
      try {
        await initialize()
        activeResumeVersionId.value = workspace.activeResumeVersionId
        if (!searchSpec.keyword.trim() || !searchSpec.cities.length) throw new Error("请填写岗位关键词和至少一个城市")
        if (!activeResumeVersionId.value) throw new Error('当前账号还没有可用于匹配的已发布简历版本')
        const connected = new Set(platformAccounts.value
          .filter((account) => account.status === 'connected')
          .map((account) => account.platform))
        const selectedPlatforms = (platforms?.length ? platforms : [...connected])
          .filter((platform) => connected.has(platform))
        if (!selectedPlatforms.length) throw new Error('请先连接至少一个招聘平台')
        if(selectedPlatforms.includes('boss')){
          const policy=await careerLensApi.executionPolicy()
          runner.globalPaused=policy.paused
          if(policy.paused)throw new Error('BOSS访问已暂停。请先在官方页面确认账号恢复，再到自动执行页手动恢复策略。')
        }
        let run = await careerLensApi.startDiscovery(activeResumeVersionId.value, selectedPlatforms, { ...searchSpec })
        currentDiscoveryRunId.value = run.id
        localStorage.setItem(discoveryStorageKey,run.id)
        await monitorDiscovery(run.id)
        gatewayStatus.value = 'online'
      } catch (cause) {
        searchStatus.value = discoveredJobs.value.length ? (discoveryFilterStats.partial?'partial':'completed') : 'idle'
        searchProgress.value = discoveredJobs.value.length ? 100 : 0
        reportError('职位发现未完成', cause)
      } finally {
        window.clearInterval(searchTimer)
        busy.value = false
      }
      return
    }


  }

  async function runOneStop(platforms?: JobPlatform[]):Promise<boolean>{
    if(busy.value||oneStopRunning.value)return false
    if(!activeResumeVersionId.value){workspace.addToast('缺少可用求职资料','请先发布一个投递版本。','warning');return false}
    busy.value=true
    try{
      saveSearchConfig()
      const active=await careerLensApi.currentOneStopRun()
      oneStopRun.value=active.status==='paused'&&active.id
        ?await careerLensApi.resumeOneStopRun(active.id)
        :await careerLensApi.startOneStopRun(activeResumeVersionId.value,platforms??['boss'],{...searchSpec})
      oneStopRunning.value=true;oneStopStopRequested.value=false
      startOneStopMonitor()
      workspace.addToast('一条龙已启动','Core会持久化批次状态；刷新或关闭页面后仍会继续运行。','success')
      return true
    }catch(cause){reportError('一条龙执行未完成',cause);return false}
    finally{busy.value=false}
  }

  function startOneStopMonitor(){
    window.clearInterval(oneStopMonitorTimer)
    oneStopMonitorTimer=window.setInterval(async()=>{
      const previous=oneStopRun.value.status
      const current=await careerLensApi.currentOneStopRun().catch(()=>null)
      if(!current)return
      oneStopRun.value=current;persistOneStopSnapshot();oneStopRunning.value=current.status==='running'
      await refreshAutomationTasks(false)
      const autoLoginCheck=current.status==='paused'&&tasks.value.some(task=>task.status==='awaiting_login')
      if(previous==='paused'&&current.status==='running')workspace.addToast('登录状态已自动验证','一条龙已从原检查点继续。','success')
      if(current.status!=='running'&&!autoLoginCheck){
        window.clearInterval(oneStopMonitorTimer);oneStopMonitorTimer=undefined
        if(current.status==='paused'&&previous==='running')workspace.addToast('一条龙已暂停',current.lastError||'需要人工处理后继续。','warning')
        if(current.status==='completed'&&previous==='running')workspace.addToast('一条龙已完成',current.lastError||`已处理 ${current.processedCount} 个岗位。`,'success')
      }
    },3000)
  }

  async function stopOneStop(){
    if(!oneStopRun.value.id||!['running','paused'].includes(oneStopRun.value.status))return
    oneStopStopRequested.value=true
    oneStopRun.value=await careerLensApi.stopOneStopRun(oneStopRun.value.id)
    persistOneStopSnapshot()
    oneStopRunning.value=false
    window.clearInterval(oneStopMonitorTimer);oneStopMonitorTimer=undefined
    workspace.addToast('已请求停止一条龙','当前正在执行的外部动作会完成核验，后续分组不会再启动。','info')
  }

  async function pauseOneStop(){
    if(!oneStopRun.value.id||oneStopRun.value.status!=='running')return
    oneStopRun.value=await careerLensApi.pauseOneStopRun(oneStopRun.value.id)
    oneStopRunning.value=false
    window.clearInterval(oneStopMonitorTimer);oneStopMonitorTimer=undefined
    workspace.addToast('一条龙已暂停','当前检查点和已发现岗位已保留，可稍后继续。','info')
  }

  async function waitForOneStopBatch(ids:Set<string>):Promise<{stopReason?:string}> {
    const reconciliation=new Map<string,{attempts:number;startedAt:number;lastAttemptAt:number}>()
    for(let attempt=0;attempt<900;attempt++){
      await new Promise(resolve=>window.setTimeout(resolve,3000))
      await refreshAutomationTasks(false)
      let batch=tasks.value.filter(task=>ids.has(task.id))
      const unknown=batch.filter(task=>task.status==='unknown_outcome')
      for(const task of unknown){
        const state=reconciliation.get(task.id)??{attempts:0,startedAt:Date.now(),lastAttemptAt:0}
        const retryDelay=state.attempts===0?0:state.attempts===1?10_000:30_000
        if(state.attempts>=3||Date.now()-state.lastAttemptAt<retryDelay)continue
        state.attempts++;state.lastAttemptAt=Date.now();reconciliation.set(task.id,state)
        try{
          const updated=await careerLensApi.reconcileAutomationTask(task.id)
          const index=tasks.value.findIndex(item=>item.id===task.id)
          if(index>=0)tasks.value[index]=mapTask(updated,[],tasks.value[index])
          workspace.addToast('已自动核对平台结果',`${task.company} · ${task.role}`,'info')
        }catch(cause){
          reportError(`自动核对平台结果失败：${task.company} / ${task.role}`,cause)
        }
      }
      batch=tasks.value.filter(task=>ids.has(task.id))
      if(batch.some(task=>['awaiting_login','awaiting_captcha','awaiting_question'].includes(task.status)))
        return{stopReason:'执行遇到需要人工处理，已暂停剩余岗位。'}
      const unresolved=batch.find(task=>task.status==='unknown_outcome')
      if(unresolved){
        const state=reconciliation.get(unresolved.id)
        if(state&&(Date.now()-state.startedAt>120_000||state.attempts>=3&&Date.now()-state.lastAttemptAt>30_000))
          return{stopReason:'平台结果自动核对超时，已暂停剩余岗位，请检查该任务。'}
        continue
      }
      if(batch.length&&batch.every(task=>['succeeded','failed','page_changed','cancelled','dry_run'].includes(task.status)))return{}
    }
    return{stopReason:'批次执行等待超过45分钟，已暂停剩余岗位。'}
  }

  async function queueSelectedForReview() {
    const selectedJobs = discoveredJobs.value.filter((job) => job.selected)
    if (!selectedJobs.length) return 0

    if (remoteMode.value) {
      if (!currentDiscoveryRunId.value || !activeResumeVersionId.value) {
        workspace.addToast('请先完成一次职位发现', '真实投递计划需要绑定本次发现批次和简历版本。', 'warning')
        return 0
      }
      busy.value = true
      try {
        const plans = await careerLensApi.createApplicationPlans({
          discoveryRunId: currentDiscoveryRunId.value,
          jobIds: selectedJobs.map((job) => job.id),
          resumeVersionId: activeResumeVersionId.value,
        })
        reviews.value = plans.map(mapPlan)
        gatewayStatus.value = 'online'
        workspace.addToast('已加入投递审核', `${plans.length} 个岗位等待最终检查。`)
        return plans.length
      } catch (cause) {
        reportError('创建审核计划失败', cause)
        return 0
      } finally {
        busy.value = false
      }
    }

    return 0
  }

  const greetingSaveState=reactive<Record<string,'idle'|'saving'|'saved'|'error'>>({})
  const greetingSaveErrors=reactive<Record<string,string>>({})
  const greetingSaveRequests=new Map<string,Promise<boolean>>()
  async function persistReview(review: ApplicationReview, options:{skipGreetingWait?:boolean}={}) {
    window.clearTimeout(planUpdateTimers.get(review.id))
    planUpdateTimers.delete(review.id)
    if(!options.skipGreetingWait&&greetingSaveRequests.has(review.id)) await greetingSaveRequests.get(review.id)
    if(!options.skipGreetingWait&&greetingSaveState[review.id]==='error') throw new Error(`请先重试保存 ${review.company} 的招呼语`)
    const greeting=review.greeting
    const updated = await careerLensApi.updateApplicationPlan(review.id, {
      included: review.included,
      greeting: review.greeting,
      resumeVersionId: review.resumeVersionId,
    })
    const index = reviews.value.findIndex((item) => item.id === review.id)
    if (index >= 0 && reviews.value[index]?.greeting===greeting) reviews.value[index] = mapPlan(updated)
  }

  async function saveGreeting(id:string):Promise<boolean>{
    window.clearTimeout(planUpdateTimers.get(id));planUpdateTimers.delete(id)
    const active=greetingSaveRequests.get(id)
    if(active){await active;const current=reviews.value.find(item=>item.id===id);if(!current||greetingSaveState[id]==='saved')return greetingSaveState[id]==='saved'}
    const review=reviews.value.find(item=>item.id===id)
    if(!review)return false
    const greeting=review.greeting
    greetingSaveState[id]='saving';delete greetingSaveErrors[id]
    const request=careerLensApi.updateApplicationPlan(id,{greeting}).then(updated=>{
      const current=reviews.value.find(item=>item.id===id)
      if(current?.greeting===greeting){Object.assign(current,mapPlan(updated));greetingSaveState[id]='saved';return true}
      return false
    }).catch(cause=>{
      greetingSaveState[id]='error'
      greetingSaveErrors[id]=cause instanceof Error?cause.message:'保存失败，请检查网络后重试'
      return false
    }).finally(()=>greetingSaveRequests.delete(id))
    greetingSaveRequests.set(id,request)
    const saved=await request
    if(!saved&&!greetingSaveErrors[id]&&reviews.value.some(item=>item.id===id))return saveGreeting(id)
    return saved
  }

  async function flushIncludedGreetings():Promise<boolean>{
    const included=reviews.value.filter(review=>review.included)
    const results=await Promise.all(included.map(review=>greetingSaveState[review.id]==='saving'||greetingSaveState[review.id]==='error'||planUpdateTimers.has(review.id)?saveGreeting(review.id):Promise.resolve(true)))
    return results.every(Boolean)
  }

  async function toggleReviewIncluded(id: string) {
    const review = reviews.value.find((item) => item.id === id)
    if (!review) return
    const previous = review.included
    review.included = !review.included
    review.approvalStatus = review.included ? 'draft' : 'skipped'
    if (!remoteMode.value) return
    try {
      await persistReview(review)
    } catch (cause) {
      review.included = previous
      review.approvalStatus = previous ? 'draft' : 'skipped'
      reportError('审核项更新失败', cause)
    }
  }

  function updateGreeting(id: string, greeting: string) {
    const review = reviews.value.find((item) => item.id === id)
    if (!review) return
    review.greeting = greeting
    review.approvalStatus = 'draft'
    if (!remoteMode.value) {greetingSaveState[id]='saved';return}
    greetingSaveState[id]='saving'
    window.clearTimeout(planUpdateTimers.get(id))
    planUpdateTimers.set(id, window.setTimeout(() => {
      void saveGreeting(id)
    }, 450))
  }

  async function regenerateGreeting(id: string) {
    const updated = await careerLensApi.regenerateGreeting(id)
    const review = reviews.value.find((item) => item.id === id)
    if (review) review.greeting = updated.greeting
    workspace.addToast('招呼语已重新生成', '已根据岗位匹配证据生成，请发送前核对。')
  }

  async function generateGreetingCandidates(id:string){
    const generated=await careerLensApi.generateGreetingCandidates(id)
    const review=reviews.value.find(item=>item.id===id)
    if(review){review.greeting=generated.text;review.greetingStyle=generated.style;review.greetingEvidence=generated.evidence;review.greetingCandidates=generated.candidates}
    workspace.addToast('已生成三种招呼语','请选择最适合当前岗位的一条。','success')
  }
  async function selectGreetingCandidate(id:string,text:string,style:string){
    const review=reviews.value.find(item=>item.id===id);if(!review)return
    updateGreeting(id,text);review.greetingStyle=style;await saveGreeting(id)
  }

  async function deleteReview(id:string) {
    window.clearTimeout(planUpdateTimers.get(id));planUpdateTimers.delete(id)
    if(greetingSaveRequests.has(id))await greetingSaveRequests.get(id)
    await careerLensApi.deleteApplicationPlan(id)
    reviews.value=reviews.value.filter(item=>item.id!==id)
    delete greetingSaveState[id];delete greetingSaveErrors[id]
    workspace.addToast('审核计划已删除')
  }

  async function clearReviews() {
    planUpdateTimers.forEach(timer=>window.clearTimeout(timer));planUpdateTimers.clear()
    const result=await careerLensApi.clearApplicationPlans()
    reviews.value=[]
    workspace.addToast('投递审核已清空',`共删除 ${result.deleted} 条计划。`)
  }

  async function syncIncludedReviews() {
    const included = reviews.value.filter((review) => review.included)
    if(!await flushIncludedGreetings())throw new Error('招呼语尚未保存成功，请重试后再批准')
    await Promise.all(included.map(review=>persistReview(review,{skipGreetingWait:true})))
    return reviews.value.filter((review) => review.included)
  }

  async function approveIncludedReviews(batchMode = false) {
    if (remoteMode.value) {
      busy.value = true
      try {
        const included = await syncIncludedReviews()
        if (!included.length) throw new Error('至少保留一个待执行岗位')
        const approval = await careerLensApi.approveApplicationPlans(included.map((review) => review.id), batchMode)
        reviews.value.forEach((review) => {
          if (review.included) review.approvalStatus = 'approved'
        })
        reviews.value=reviews.value.filter(review=>['draft','expired'].includes(review.approvalStatus))
        tasks.value = approval.tasks.map(task=>mapTask(task))
        deriveRunStatus()
        runner.globalPaused = false
        gatewayStatus.value = 'online'
        startTaskPolling()
        workspace.addToast('执行计划已锁定', `${approval.approvedCount} 个岗位已进入本地执行队列。`)
        return true
      } catch (cause) {
        reportError('批准执行计划失败', cause)
        return false
      } finally {
        busy.value = false
      }
    }

    return false
  }

  async function approvePendingReviews():Promise<boolean>{
    if(!remoteMode.value||busy.value)return false
    // Handle both draft approvals and uncertain platform results from one action.
    const pending=reviews.value.filter(review=>review.included&&['draft','expired'].includes(review.approvalStatus))
    const pendingResults=tasks.value.filter(task=>task.status==='unknown_outcome')
    if(!pending.length&&!pendingResults.length){workspace.addToast('没有待处理任务','当前没有待批准计划或待核对的平台结果。','info');return false}
    busy.value=true
    try{
      let reconciled=0
      for(const task of pendingResults){
        try{
          const updated=await careerLensApi.reconcileAutomationTask(task.id)
          const index=tasks.value.findIndex(item=>item.id===task.id)
          if(index>=0)tasks.value[index]=mapTask(updated,[],tasks.value[index])
          reconciled++
        }catch(cause){
          reportError(`核对平台结果失败：${task.company} / ${task.role}`,cause)
        }
      }
      if(pendingResults.length){
        const result=await waitForOneStopBatch(new Set(pendingResults.map(task=>task.id)))
        if(result.stopReason)throw new Error(result.stopReason)
      }
      const startedTasks:AutomationTask[]=[]
      const policy=await careerLensApi.executionPolicy()
      if(policy.paused)await careerLensApi.updateExecutionPolicy(false,policy.dailyLimit,policy.dryRun)
      for(let offset=0;offset<pending.length;offset+=5){
        const group=pending.slice(offset,offset+5)
        await Promise.all(group.map(review=>persistReview(review)))
        const approval=await careerLensApi.approveApplicationPlans(group.map(review=>review.id),false)
        group.forEach(review=>{review.approvalStatus='approved'})
        reviews.value=reviews.value.filter(review=>['draft','expired'].includes(review.approvalStatus))
        const groupTasks=approval.tasks.map(task=>mapTask(task))
        startedTasks.push(...groupTasks)
        const ids=new Set(groupTasks.map(task=>task.id))
        const retained=tasks.value.filter(task=>!ids.has(task.id))
        tasks.value=[...groupTasks,...retained]
        startTaskPolling()
        const result=await waitForOneStopBatch(ids)
        if(result.stopReason)throw new Error(result.stopReason)
      }
      deriveRunStatus();startTaskPolling()
      workspace.addToast('待处理任务已一键处理',`${startedTasks.length} 个计划已批准，${reconciled} 个平台结果已核对。`,'success')
      return true
    }catch(cause){reportError('一键批准失败',cause);return false}
    finally{busy.value=false}
  }

  async function refreshAutomationTasks(includeAudits = false) {
    if (!remoteMode.value || !session.authenticated) return
    try {
      const requested=Math.max(20,tasks.value.length)
      const snapshot = await careerLensApi.listAutomationTasks(Math.min(200,requested+1))
      hasMoreTasks.value=snapshot.length>requested
      const visibleSnapshot=snapshot.slice(0,requested)
      const previousTasks=tasks.value
      const previous = new Map(previousTasks.map((task) => [task.id, task]))
      const nextTasks = visibleSnapshot.map(task=>mapTask(task,[],previous.get(task.id)))
      void includeAudits
      const completedNow=nextTasks.some(task=>task.status==='succeeded'&&previous.get(task.id)?.status!=='succeeded')
      if(taskSnapshotChanged(nextTasks,previousTasks))tasks.value=nextTasks
      const failedReviews=new Set(nextTasks.filter(task=>['failed','page_changed'].includes(task.status)).map(task=>task.reviewId))
      if(failedReviews.size)reviews.value=reviews.value.filter(review=>!failedReviews.has(review.id))
      deriveRunStatus()
      if(completedNow)await workspace.loadApplications()
      if(nextTasks.some(task=>['failed','page_changed'].includes(task.status)))void loadFailureSummary()
      if(!nextTasks.some(taskNeedsPolling))stopTaskPolling()
      gatewayStatus.value = 'online'
    } catch (cause) {
      reportError('执行状态同步失败', cause)
    }
  }

  async function refreshTaskDetail(id:string){
    if(!remoteMode.value||!session.authenticated)return
    const detail=await careerLensApi.getAutomationTask(id)
    const index=tasks.value.findIndex(task=>task.id===id)
    if(index>=0)tasks.value[index]=mapTask(detail.task,detail.events,tasks.value[index])
  }

  async function loadMoreTasks(){
    const before=tasks.value.at(-1)?.createdAt
    if(!before)return
    const page=await careerLensApi.listAutomationTasksPage(20,before)
    const existing=new Set(tasks.value.map(task=>task.id))
    tasks.value.push(...page.items.filter(task=>!existing.has(task.id)).map(task=>mapTask(task)))
    hasMoreTasks.value=page.hasMore
  }

  async function loadMoreReviews(){
    const before=reviews.value.at(-1)?.createdAt
    if(!before)return
    const page=await careerLensApi.listApplicationPlansPage('actionable',20,before)
    const existing=new Set(reviews.value.map(review=>review.id))
    reviews.value.push(...page.items.filter(plan=>['draft','expired'].includes(plan.approvalStatus)&&!existing.has(plan.id)).map(mapPlan))
    hasMoreReviews.value=page.hasMore
  }

  async function loadMoreDiscoveredJobs(){
    if(!currentDiscoveryRunId.value)return
    const page=await careerLensApi.getDiscoveryJobsPage(currentDiscoveryRunId.value,20,discoveredJobs.value.length)
    const existing=new Set(discoveredJobs.value.map(job=>job.id))
    discoveredJobs.value.push(...page.items.filter(job=>!existing.has(job.id)).map(job=>mapJob(job,searchSpec.minimumScore)))
    hasMoreDiscoveredJobs.value=page.hasMore
  }

  async function batchReconcileUnknown(selectedIds:string[]=[]){
    const selection=new Set(selectedIds)
    const ids=tasks.value.filter(task=>task.status==='unknown_outcome'&&(!selection.size||selection.has(task.id))).map(task=>task.id)
    if(!ids.length){workspace.addToast('没有待核对任务','','info');return}
    const result=await careerLensApi.reconcileAutomationTasks(ids)
    workspace.addToast('批量核对已启动',`${result.completed} 个任务开始核对，跳过 ${result.skipped} 个。`,'info')
    await refreshAutomationTasks(false)
  }

  async function batchRetryFailed(selectedIds:string[]=[]){
    const selection=new Set(selectedIds)
    const ids=tasks.value.filter(task=>task.retryable&&['failed','page_changed'].includes(task.status)&&(!selection.size||selection.has(task.id))).map(task=>task.id)
    if(!ids.length){workspace.addToast('没有可重试任务','','info');return}
    const result=await careerLensApi.retryAutomationTasks(ids)
    workspace.addToast('批量重试已处理',`${result.completed} 个任务重新入队，跳过 ${result.skipped} 个。`,'info')
    await refreshAutomationTasks(false);startTaskPolling()
  }

  async function loadFailureSummary(){failureSummary.value=await careerLensApi.automationFailureSummary()}

  function startTaskPolling() {
    if (!remoteMode.value || !tasks.value.some(taskNeedsPolling)) return
    window.clearInterval(pollingTimer)
    pollingTimer = window.setInterval(async() => {
      if(pollingInFlight)return
      pollingInFlight=true
      try{await refreshAutomationTasks(false)}finally{pollingInFlight=false}
    }, 2500)
  }

  function stopTaskPolling() {
    window.clearInterval(pollingTimer)
    pollingTimer = undefined
    pollingInFlight = false
  }

  async function startRun() {
    if (!tasks.value.length) {
      workspace.addToast('执行队列为空', '先在审核页批准至少一个岗位。', 'warning')
      return
    }
    if (remoteMode.value) {
      busy.value = true
      try {
        const policy=await careerLensApi.executionPolicy()
        if(policy.paused)await careerLensApi.updateExecutionPolicy(false,policy.dailyLimit,policy.dryRun)
        const queue = tasks.value.filter((task) => task.status === 'queued')
        for (const task of queue) await careerLensApi.startAutomationTask(task.id)
        await refreshAutomationTasks(true)
        startTaskPolling()
        runner.globalPaused = false
        workspace.addToast('本地执行器已开始处理', '队列状态会自动刷新，平台验证出现时会提醒你。', 'info')
      } catch (cause) {
        reportError('执行队列启动失败', cause)
      } finally {
        busy.value = false
      }
      return
    }

  }

  async function reconcileUnknownOutcome(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task || task.status !== 'unknown_outcome' || !remoteMode.value) return
    busy.value = true
    try {
      const updated = await careerLensApi.reconcileAutomationTask(id)
      const audits = await careerLensApi.getAutomationAudit(id).catch(() => [])
      const index = tasks.value.findIndex((item) => item.id === id)
      if (index >= 0) tasks.value[index] = mapTask(updated, audits, task)
      deriveRunStatus()
      startTaskPolling()
      workspace.addToast('平台结果已核对', updated.receipt || updated.currentStep, updated.status === 'succeeded' ? 'success' : 'info')
    } catch (cause) {
      reportError('平台结果核对失败', cause)
    } finally {
      busy.value = false
    }
  }

  async function resolveHumanAction(id: string) {
    const task = tasks.value.find((item) => item.id === id)
    if (!task) return
    if (remoteMode.value) {
      busy.value = true
      try {
        const updated = await careerLensApi.resolveHumanAction(id)
        const audits = await careerLensApi.getAutomationAudit(id).catch(() => [])
        const index = tasks.value.findIndex((item) => item.id === id)
        if (index >= 0) tasks.value[index] = mapTask(updated, audits, task)
        runner.globalPaused = false
        runStatus.value = 'ready'
        startTaskPolling()
        if(oneStopRun.value.status==='paused')startOneStopMonitor()
        workspace.addToast('验证状态已更新', '后台核验成功后会自动继续一条龙。')
      } catch (cause) {
        reportError('人工处理状态提交失败', cause)
      } finally {
        busy.value = false
      }
      return
    }

  }

  async function refreshTasks() { await refreshAutomationTasks(true) }

  async function deleteTask(id:string) {
    await careerLensApi.deleteAutomationTask(id)
    tasks.value=tasks.value.filter(item=>item.id!==id)
    deriveRunStatus()
    workspace.addToast('执行任务已删除')
  }

  async function clearTasks() {
    const result=await careerLensApi.clearAutomationTasks()
    tasks.value=[];deriveRunStatus()
    workspace.addToast('自动执行已清空',`共删除 ${result.deleted} 个任务。`)
  }

  async function togglePlatformConnection(id: string) {
    const account = platformAccounts.value.find((item) => item.id === id)
    if (!account) return
    if (remoteMode.value) {
      busy.value = true
      try {
        const updated = account.status === 'connected'
          ? await careerLensApi.disconnectPlatform(account.platform)
          : await careerLensApi.connectPlatform(account.platform)
        const index = platformAccounts.value.findIndex((item) => item.id === id)
        if (index >= 0) platformAccounts.value[index] = mapPlatform(updated)
        gatewayStatus.value = 'online'
        workspace.addToast(
          updated.status === 'connected' ? `${updated.displayName}已连接` : `${updated.displayName}已断开`,
          updated.status === 'connected' ? '平台能力检查已通过。' : '本地凭据仍由本机管理。',
          updated.status === 'connected' ? 'success' : 'info',
        )
      } catch (cause) {
        reportError('平台连接状态更新失败', cause)
      } finally {
        busy.value = false
      }
      return
    }

  }

  async function activatePlatformIdentity(id:string){
    if(oneStopRunning.value||tasks.value.some(task=>['queued','preparing','submitting','verifying','unknown_outcome'].includes(task.status))){workspace.addToast('暂时不能切换BOSS账号','请先停止一条龙并处理活动任务。','warning');return false}
    await careerLensApi.activatePlatformIdentity(id)
    localStorage.setItem('careerlens:active-platform-identity',id)
    initialized.value=false;await initialize(true);Object.assign(searchSpec,loadSearchSpec());workspace.reset();await workspace.initialize(true)
    workspace.addToast('BOSS账号已切换',activePlatformIdentity.value?.displayName||'')
    return true
  }

  return {
    remoteMode,
    gatewayStatus,
    gatewayError,
    initialized,
    busy,
    oneStopRunning,
    oneStopRun,
    oneStopStopRequested,
    autoOneStop,
    setAutoOneStop,
    runner,
    platformAccounts,
    platformIdentities,
    activePlatformIdentity,
    activatePlatformIdentity,
    searchSpec,
    applySearchSpec,
    saveSearchConfig,
    resetSearchConfig,
    discoveryStats,
    discoveryFilterStats,
    discoveredJobs,
    hasMoreDiscoveredJobs,
    reviews,
    hasMoreReviews,
    tasks,
    hasMoreTasks,
    failureSummary,
    runStatus,
    searchStatus,
    searchProgress,
    selectedJobCount,
    includedReviewCount,
    humanActionCount,
    succeededCount,
    queuedCount,
    overallProgress,
    initialize,
    platformName,
    toggleJobSelection,
    setJobSelection,
    selectRecommendedJobs,
    selectJobs,
    runDiscovery,
    runOneStop,
    pauseOneStop,
    stopOneStop,
    queueSelectedForReview,
    toggleReviewIncluded,
    updateGreeting,
    saveGreeting,
    greetingSaveState,
    greetingSaveErrors,
    regenerateGreeting,
    generateGreetingCandidates,
    selectGreetingCandidate,
    deleteReview,
    clearReviews,
    approveIncludedReviews,
    approvePendingReviews,
    refreshAutomationTasks,
    refreshTaskDetail,
    loadMoreTasks,
    loadMoreReviews,
    loadMoreDiscoveredJobs,
    batchReconcileUnknown,
    batchRetryFailed,
    loadFailureSummary,
    startTaskPolling,
    stopTaskPolling,
    startRun,
    reconcileUnknownOutcome,
    resolveHumanAction,
    refreshTasks,
    deleteTask,
    clearTasks,
    togglePlatformConnection,
  }
})
