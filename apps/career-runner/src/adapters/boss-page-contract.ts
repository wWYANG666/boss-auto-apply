export type BossBlocker = {
  kind: 'LOGIN' | 'CAPTCHA' | 'QUESTION'
  code: 'LOGIN_REQUIRED' | 'CAPTCHA_REQUIRED' | 'DAILY_LIMIT' | 'JOB_UNAVAILABLE'
  message: string
}

export const BOSS_SELECTORS = {
  account: ['[ka="header-personal"]', '.nav-figure', '.user-nav'],
  cards: ['.job-card-wrapper', '.job-card-box', 'li.job-card-wrapper'],
  role: ['h1[title]', '.job-title', '[ka="job-detail-title"]', '.job-banner .job-name', '.job-primary .info-primary .name', '.job-detail-header .job-name', '.job-name', 'h1'],
  company: ['a[ka="job-detail-company_custompage"]', '[ka="job-detail-company"]', '.company-info a[title]', '.job-primary .company-info .name', '.job-sider .company-info a', '.company-info .name', '.sider-company .company-info a', '.company-name'],
  salary: ['.salary', '.job-banner .salary'],
  description: ['.job-sec-text', '.job-detail-section .text', '.job-detail-body'],
  communicate: ['a.btn-startchat', '.btn-startchat-wrap a', '[ka="job-detail-chat"]', '[ka="job_detail_chat"]', 'a.op-btn.op-btn-chat', '.op-btn-chat', '.btn-startchat'],
  continueCommunicate: ['button:has-text("继续沟通")', 'a:has-text("继续沟通")', '.dialog-container .btn-sure-v2'],
  messageBox: ['#chat-input', 'textarea', '[contenteditable="true"]'],
  sendButton: ['.btn-send', 'button:has-text("发送")'],
  sentMessage: ['.item-myself .message-content .text', '.item-myself .text'],
  conversationJob: ['[ka="geek_chat_job_detail"]', '.chat-job-info a'],
  conversationCompany: ['.chat-info .company', '.chat-job-info .company'],
  conversationRole: ['.chat-info .job-name', '.chat-job-info .job-name'],
} as const

export const COMMON_BOSS_CITY_CODES: Readonly<Record<string, string>> = {
  全国: '100010000', 北京: '101010100', 上海: '101020100', 广州: '101280100',
  深圳: '101280600', 杭州: '101210100', 南京: '101190100', 苏州: '101190400',
  成都: '101270100', 武汉: '101200100', 西安: '101110100', 天津: '101030100',
  重庆: '101040100', 合肥: '101220100', 长沙: '101250100', 郑州: '101180100',
  济南: '101120100', 青岛: '101120200', 宁波: '101210400', 无锡: '101190200',
}

export type BossCityRegion={code:string;name:string;cities:Array<{code:string;name:string}>}

export function expandBossCitySelections(selections:string[],regions:BossCityRegion[]):string[]{
  const result:string[]=[]
  for(const selection of selections){
    const label=selection.split('|')[0]?.trim()??''
    const code=selection.match(/\|([0-9]{6,12})$/)?.[1]??(/^[0-9]{6,12}$/.test(selection)?selection:'')
    const region=regions.find(item=>item.code===code||item.name===label)
    const values=region?.cities.map(city=>`${city.name}|${city.code}`)??[selection]
    for(const value of values)if(value&&!result.includes(value))result.push(value)
  }
  return result
}

export function resolveBossCityCode(input: string, configured: Record<string, string>): string | null {
  const encoded=input.match(/\|([0-9]{6,12})$/)?.[1]
  if(encoded)return encoded
  if (/^[0-9]{6,12}$/.test(input)) return input
  return configured[input] ?? COMMON_BOSS_CITY_CODES[input] ?? null
}

export function detectBossBlocker(url: string, body: string): BossBlocker | null {
  let pathname = url
  try { pathname = new URL(url).pathname } catch { /* Keep the raw value for malformed test fixtures. */ }
  if (/\/403\.html|[?&]code=32\b/i.test(url) || /账户存在异常行为|暂时被禁止使用/.test(body)) {
    return { kind: 'QUESTION', code: 'CAPTCHA_REQUIRED', message: 'BOSS已临时限制当前账号，请停止自动请求并在官方页面确认账号状态。' }
  }
  if (/captcha|verify|safe\.zhipin/i.test(url) || /滑块|安全验证|完成验证|人机验证/.test(body)) {
    return { kind: 'CAPTCHA', code: 'CAPTCHA_REQUIRED', message: '请在可见BOSS浏览器中完成安全验证。' }
  }
  if (/login|passport/i.test(pathname) || /扫码登录|手机号登录|密码登录|登录\s*\/\s*注册/.test(body)) {
    return { kind: 'LOGIN', code: 'LOGIN_REQUIRED', message: '请在可见BOSS浏览器中完成登录。' }
  }
  if (/今日.*(?:沟通|招呼).*(?:上限|达到)|已达到.*(?:沟通|招呼)|温馨提示.*(?:上限|限制)/s.test(body)) {
    return { kind: 'QUESTION', code: 'DAILY_LIMIT', message: 'BOSS提示今日沟通额度已达到限制，请人工核对。' }
  }
  if (/职位已下线|职位不存在|停止招聘|该职位已关闭/.test(body)) {
    return { kind: 'QUESTION', code: 'JOB_UNAVAILABLE', message: '该职位已下线或停止招聘。' }
  }
  return null
}

export function selectorCandidates(value: string | undefined, defaults: readonly string[]): string[] {
  return [...new Set([...(value ? value.split(',').map(item => item.trim()).filter(Boolean) : []), ...defaults])]
}

export function boundedInteger(value: string | undefined, fallback: number, min: number, max: number): number {
  const parsed = Number.parseInt(value ?? '', 10)
  return Number.isFinite(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback
}

export function isRecoverableBrowserPageError(error:unknown):boolean{
  const message=error instanceof Error?error.message:String(error??'')
  return /Target\.createTarget|Failed to open a new tab|browserContext\.newPage|Target page, context or browser has been closed/i.test(message)
}

export function rollingWindowWait(now:number,timestamps:number[],windowMs:number,maxActions:number):number{
  const active=timestamps.filter(timestamp=>timestamp>now-windowMs).sort((left,right)=>left-right)
  if(active.length<maxActions)return 0
  return Math.max(0,active[active.length-maxActions]!+windowMs-now)
}

export function throttledSubmissionWindow(configuredMs:number,currentMs:number):number{
  const next=currentMs?currentMs*2:configuredMs*2
  return Math.min(600_000,Math.max(120_000,next))
}

export function recoveredSubmissionWindow(configuredMs:number,currentMs:number):number{
  return Math.max(configuredMs,currentMs-30_000)
}

export function isBossDailyContactLimit(message:string):boolean{
  return /(?:已与|只与).*位BOSS沟通|每天.*位BOSS沟通|达到.*上限/.test(message)
}

export function bossJobIdFromUrl(value: string): string | null {
  try {
    const url = new URL(value)
    if (!['www.zhipin.com', 'zhipin.com'].includes(url.hostname)) return null
    return url.pathname.match(/\/job_detail\/([^/.]+)\.html/i)?.[1] ?? null
  } catch {
    return null
  }
}

export function isBossHomeUrl(value:string):boolean {
  try {
    const url=new URL(value)
    if(!['www.zhipin.com','zhipin.com'].includes(url.hostname))return false
    return url.pathname==='/' || /^\/[a-z]+\/?$/i.test(url.pathname)
  } catch {
    return false
  }
}

export function normalizeBossIdentity(value: string, company = false): string {
  let normalized = value.normalize('NFKC').toLowerCase().replace(/[\s·•・,，.。()（）【】[\]{}<>《》_-]/g, '')
  if (company) normalized = normalized.replace(/有限责任公司$|股份有限公司$|有限公司$|公司$/g, '')
  return normalized
}

export function matchesBossIdentity(expected: string, actual: string, company = false): boolean {
  const left = normalizeBossIdentity(expected, company)
  const right = normalizeBossIdentity(actual, company)
  if (!left || !right) return false
  return left === right || Math.min(left.length, right.length) >= 4 && (left.includes(right) || right.includes(left))
}
