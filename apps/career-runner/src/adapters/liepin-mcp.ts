import { verifyLiepinReceipt } from './receipt-validation.js'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'
import type {
  ApplicationPlan,
  NormalizedJob,
  PlatformState,
  PreparedApplication,
  SearchSpec,
  SubmitReceipt,
} from '../domain.js'
import { planHash, sha256 } from '../core/crypto.js'
import { HumanActionRequiredError, type AdapterContext, type JobPlatformAdapter } from './contract.js'

interface McpTextContent {
  type: string
  text?: string
}

export class LiepinMcpAdapter implements JobPlatformAdapter {
  readonly platform = 'liepin' as const
  private client?: Client
  private connected = false

  constructor(
    private readonly token: string | undefined,
    private readonly endpoint = 'https://open-agent.liepin.com/mcp/user',
  ) {}

  private requireToken(): string {
    if (!this.token) throw new HumanActionRequiredError('LOGIN', 'Open the Liepin MCP authorization page and save the token locally.')
    return this.token
  }

  async status(): Promise<PlatformState> {
    return {
      platform: this.platform,
      connected: this.connected,
      mode: 'mcp',
      capabilities: ['DISCOVER', 'READ_JOB', 'READ_RESUME', 'APPLICATION_SUBMITTED'],
      lastCheckedAt: new Date().toISOString(),
      message: this.token ? 'MCP token configured locally' : 'MCP authorization required',
    }
  }

  async connect(): Promise<PlatformState> {
    if (process.env.RUNNER_REAL_PLATFORM !== 'true') throw new Error('Real platform execution is disabled.')
    const token = this.requireToken()
    const client = new Client({ name: 'careerlens-runner', version: '0.1.0' })
    const transport = new StreamableHTTPClientTransport(new URL(this.endpoint), {
      requestInit: { headers: { 'x-user-token': token } },
    })
    await client.connect(transport)
    await client.listTools()
    this.client = client
    this.connected = true
    return this.status()
  }

  async disconnect(): Promise<PlatformState> {
    await this.client?.close()
    this.client = undefined
    this.connected = false
    return this.status()
  }

  private async readyClient(): Promise<Client> {
    if (!this.client) await this.connect()
    if (!this.client) throw new Error('Liepin MCP client did not initialize.')
    return this.client
  }

  private parseToolJson(result: { content?: McpTextContent[] }): unknown {
    const text = result.content?.filter((item) => item.type === 'text').map((item) => item.text ?? '').join('\n') ?? ''
    const trimmed = text.trim()
    if (!trimmed) return null
    try {
      return JSON.parse(trimmed)
    } catch {
      const match = trimmed.match(/[\[{][\s\S]*[\]}]/)
      if (!match) return { text: trimmed }
      return JSON.parse(match[0])
    }
  }

  private normalizeJobs(value: unknown): NormalizedJob[] {
    const record = value as Record<string, unknown> | null
    const candidates = Array.isArray(value)
      ? value
      : Array.isArray(record?.data)
        ? record.data
        : Array.isArray(record?.jobs)
          ? record.jobs
          : []
    return candidates.filter(candidate => { const item=candidate as Record<string,unknown>;return item.jobId!==undefined||item.id!==undefined||item.job_id!==undefined }).map((candidate) => {
      const item = candidate as Record<string, unknown>
      const externalJobId = String(item.jobId ?? item.id ?? item.job_id)
      return {
        platform: 'liepin' as const,
        externalJobId,
        jobKind: typeof item.jobKind === 'string' || typeof item.jobKind === 'number' ? item.jobKind : undefined,
        canonicalUrl: String(item.url ?? item.jobUrl ?? `https://www.liepin.com/job/${externalJobId}`),
        company: String(item.companyName ?? item.company ?? '猎聘企业'),
        role: String(item.jobTitle ?? item.title ?? item.jobName ?? '职位'),
        location: String(item.location ?? item.address ?? ''),
        salary: String(item.salary ?? item.salaryText ?? ''),
        description: String(item.description ?? item.jobDescription ?? ''),
        recruiter: item.recruiter ? String(item.recruiter) : undefined,
      }
    })
  }

  async discover(spec: SearchSpec): Promise<NormalizedJob[]> {
    const client=await this.readyClient()
    const jobs=new Map<string,NormalizedJob>()
    for(const city of spec.cities){
      for(let page=0;page<spec.maxPages;page++){
        const result=await client.callTool({
          name:process.env.LIEPIN_SEARCH_TOOL??'user-search-job',
          arguments:{jobName:spec.keyword,address:city,salary:spec.salary,experience:spec.experience,degree:spec.degree,page},
        })
        if(result.isError===true)throw new Error('Liepin search returned a tool error')
        const current=this.normalizeJobs(this.parseToolJson(result as {content?:McpTextContent[]}))
        let added=0
        for(const job of current)if(!jobs.has(job.externalJobId)){jobs.set(job.externalJobId,job);added++}
        if(!current.length||!added)break
      }
    }
    return [...jobs.values()]
  }

  async resumeSnapshot():Promise<{resumeId:string;snapshotHash:string;summary:string}>{
    const client=await this.readyClient()
    const result=await client.callTool({name:process.env.LIEPIN_RESUME_TOOL??'my-resume',arguments:{}})
    const payload=this.parseToolJson(result as {content?:McpTextContent[]})
    const envelope=payload as Record<string,unknown>|null
    const data=(envelope?.data??envelope) as Record<string,unknown>|null
    const resumeId=data?.resumeId??data?.id
    if(result.isError===true||typeof resumeId!=='string')throw new Error('Platform resume identity is not available')
    return {resumeId,snapshotHash:sha256(data),summary:String(data?.summary??data?.selfAssessment??'请在猎聘页面核对完整简历')}
  }
  private async verifyMaterial(plan:ApplicationPlan){
    const actual=await this.resumeSnapshot()
    if(plan.answers.platformResumeId!==actual.resumeId||plan.answers.platformResumeHash!==actual.snapshotHash)
      throw new HumanActionRequiredError('QUESTION','Platform resume changed or has not been reviewed. Refresh the material and reapprove.')
  }
  async prepare(plan: ApplicationPlan): Promise<PreparedApplication> {
    await this.verifyMaterial(plan)
    return {
      planHash: planHash(plan),
      pageFingerprint: sha256({ endpoint: this.endpoint, job: plan.externalJobId, action: plan.actionType }),
      actionSummary: 'Submit the platform resume and retain the returned receipt.',
      fields: [
        { key: 'jobId', label: '猎聘职位ID', value: plan.externalJobId, source: 'JOB' },
        { key: 'resumeVersion', label: '简历版本', value: plan.resumeVersionId, source: 'RESUME' },
      ],
      warnings: plan.answers.jobKind ? [] : ['jobKind must be copied from the search result before a real submission.'],
    }
  }

  async submit(plan: ApplicationPlan): Promise<SubmitReceipt> {
    await this.verifyMaterial(plan)
    const client = await this.readyClient()
    const jobKind = plan.answers.jobKind
    if (jobKind === undefined || jobKind === '') {
      throw new HumanActionRequiredError('QUESTION', 'Confirm the jobKind returned by Liepin search before submitting.')
    }
    const result = await client.callTool({
      name: process.env.LIEPIN_APPLY_TOOL ?? 'user-apply-job',
      arguments: { jobId: plan.externalJobId, jobKind },
    })
    const payload = this.parseToolJson(result as { content?: McpTextContent[] })
    return verifyLiepinReceipt(result, payload, plan)
  }

  async reconcile(): Promise<SubmitReceipt | null> {
    return null
  }
}
