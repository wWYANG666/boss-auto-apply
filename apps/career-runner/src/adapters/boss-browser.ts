import { access, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { spawn, type ChildProcess } from 'node:child_process'
import { basename, dirname, join, resolve } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { chromium, type Browser, type BrowserContext, type Locator, type Page } from 'playwright-core'
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
  DailyQuotaReachedError,
  HumanActionRequiredError,
  PageChangedError,
  UnknownOutcomeError,
  type AdapterContext,
  type JobPlatformAdapter,
} from './contract.js'
import { BOSS_SELECTORS, COMMON_BOSS_CITY_CODES, bossJobIdFromUrl, boundedInteger, detectBossBlocker, expandBossCitySelections, isBossDailyContactLimit, isBossHomeUrl, isRecoverableBrowserPageError, matchesBossIdentity, recoveredSubmissionWindow, resolveBossCityCode, rollingWindowWait, selectorCandidates, throttledSubmissionWindow } from './boss-page-contract.js'
import { bossCandidateLimit, bossDiscoveryProgress, bossExperienceRisk, bossJobFilterReason, bossPageStopReason, bossSearchQueries, buildBossQuerySchedule, hasCareerStageIntent, needsBossJobDetail, selectBalancedBossJobs, summarizeBossFilters } from './boss-filter.js'
import { enrichCommute } from './amap-commute.js'
import { createBossConversationWithGreeting, inspectBossConversation, sendBossReviewedText } from './boss-chat.js'

const ALLOWED_HOSTS = new Set(['www.zhipin.com', 'zhipin.com'])
type BossBrowserMode = 'auto' | 'visible' | 'headless' | 'cdp'
type ActiveBossBrowserMode = Exclude<BossBrowserMode, 'auto'> | 'background'
type BossAuthState = 'authenticated' | 'signed_out' | 'unknown'
const pageWait=(milliseconds:number)=>new Promise<void>(resolve=>setTimeout(resolve,milliseconds))
export function classifyBossAuthEvidence(input:{blockerCode?:string;serverDefinitive?:boolean;serverSignedIn?:boolean;accountVisible?:boolean;cookieSignedIn?:boolean;passive?:boolean}):BossAuthState{
  if(input.blockerCode==='LOGIN_REQUIRED')return'signed_out'
  if(input.blockerCode==='CAPTCHA_REQUIRED')return'unknown'
  if(input.passive)return input.accountVisible||input.cookieSignedIn?'authenticated':'unknown'
  if(input.serverDefinitive)return input.serverSignedIn?'authenticated':'signed_out'
  return input.accountVisible||input.cookieSignedIn?'authenticated':'unknown'
}

export class BossBrowserAdapter implements JobPlatformAdapter {
  readonly platform = 'boss' as const
  private browser?: Browser
  private context?: BrowserContext
  private discoveryPage?:Page
  private activeBrowserMode?: ActiveBossBrowserMode
  private ownedBrowserProcess?:ChildProcess
  private manualLoginProcess?:ChildProcess
  private awaitingManualLogin=false
  private profileSessionReady=false
  private profileDirectory: string
  private readonly baseProfileDirectory: string
  private activeProfile='default'
  private profileSelectionLoaded=false
  private readonly discoveryCache = new Map<string, { expiresAt: number; cityJobs: NormalizedJob[][]; pages: Array<Record<string,unknown>> }>()
  private readonly discoveryCityRotation = new Map<string,number>()
  private cityDirectoryCache?:{expiresAt:number;regions:Array<{code:string;name:string;cities:Array<{code:string;name:string}>}>;codes:Record<string,string>}
  private lastSubmissionAt=0
  private submissionTimestamps:number[]=[]
  private submissionQueue:Promise<void>=Promise.resolve()
  private adaptiveSubmissionWindowMs=0
  private successfulSubmissionsSinceThrottle=0
  private submissionsSinceRecycle=0
  private lastDiscoveryFilterStats:Record<string,unknown>={}

  constructor(
    profileDirectory = process.env.BOSS_USER_DATA_DIR ?? '.runner-data/sessions/boss',
    private readonly executablePath = process.env.BROWSER_EXECUTABLE_PATH,
  ) {
    this.profileDirectory = resolve(profileDirectory)
    this.baseProfileDirectory=this.profileDirectory
  }

  private profilePath(name:string):string{return name==='default'?this.baseProfileDirectory:`${this.baseProfileDirectory}-${name}`}
  private profileSelectionPath():string{return `${this.baseProfileDirectory}.active-profile`}

  private async loadProfileSelection():Promise<void>{
    if(this.profileSelectionLoaded)return
    this.profileSelectionLoaded=true
    const saved=(await readFile(this.profileSelectionPath(),'utf8').catch(()=>'' )).trim()
    if(/^[a-zA-Z0-9_-]{1,32}$/.test(saved)){
      this.activeProfile=saved
      this.profileDirectory=this.profilePath(saved)
    }
  }

  async profiles():Promise<{active:string;profiles:Array<{name:string;active:boolean}>}>{
    await this.loadProfileSelection()
    const parent=dirname(this.baseProfileDirectory),prefix=basename(this.baseProfileDirectory)
    await mkdir(parent,{recursive:true})
    const names=(await readdir(parent,{withFileTypes:true})).filter(entry=>entry.isDirectory()&&(entry.name===prefix||entry.name.startsWith(prefix+'-')))
      .map(entry=>entry.name===prefix?'default':entry.name.slice(prefix.length+1)).filter(name=>/^[a-zA-Z0-9_-]{1,32}$/.test(name))
    if(!names.includes('default'))names.unshift('default')
    return {active:this.activeProfile,profiles:[...new Set(names)].map(name=>({name,active:name===this.activeProfile}))}
  }

  async switchProfile(name:string):Promise<PlatformState>{
    const normalized=name.trim()
    if(!/^[a-zA-Z0-9_-]{1,32}$/.test(normalized))throw new Error('BOSS_PROFILE_INVALID: 配置名称只允许字母、数字、下划线和短横线。')
    if(normalized===this.activeProfile&&await this.contextIsUsable())return this.status()
    await this.closeBrowserSession()
    this.activeProfile=normalized
    this.profileDirectory=this.profilePath(normalized)
    this.profileSessionReady=false
    this.awaitingManualLogin=false
    this.manualLoginProcess=undefined
    await writeFile(this.profileSelectionPath(),normalized,{encoding:'utf8',mode:0o600})
    return this.launchManualLoginBrowser()
  }

  private assertAllowedUrl(value: string): URL {
    const url = new URL(value)
    if (url.protocol !== 'https:' || !ALLOWED_HOSTS.has(url.hostname)) {
      throw new Error(`Blocked non-BOSS URL: ${url.hostname}`)
    }
    return url
  }

  private async findExecutable(): Promise<string | undefined> {
    const candidates = [
      this.executablePath,
      chromium.executablePath(),
      'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
      'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
      '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
      '/usr/bin/google-chrome',
    ].filter((candidate): candidate is string => Boolean(candidate))

    for (const candidate of candidates) {
      try {
        await access(candidate)
        return candidate
      } catch {
        // Continue to the next well-known local browser path.
      }
    }
    return undefined
  }

  private async page(): Promise<Page> {
    if(!await this.contextIsUsable())await this.connect()
    const existing=this.platformPage()
    if(existing&&!existing.isClosed())return existing
    return this.newPageWithRecovery()
  }

  private async newPageWithRecovery():Promise<Page>{
    let lastError:unknown
    for(let attempt=0;attempt<2;attempt++){
      if(!await this.contextIsUsable())await this.connect()
      if(!this.context)continue
      try{return await this.context.newPage()}
      catch(error){
        lastError=error
        if(attempt===1||!isRecoverableBrowserPageError(error))throw error
        await this.closeBrowserSession()
      }
    }
    throw lastError instanceof Error?lastError:new Error('BOSS browser context did not initialize.')
  }

  private isAllowedPageUrl(value:string):boolean{
    try{
      const url=new URL(value)
      return url.protocol==='https:'&&ALLOWED_HOSTS.has(url.hostname)
    }catch{return false}
  }

  private isDiscoveryPageUrl(value:string):boolean{
    try{return this.isAllowedPageUrl(value)&&new URL(value).pathname.startsWith('/web/geek/jobs')}
    catch{return false}
  }

  private async waitForStableBossPage(page:Page):Promise<void>{
    let readyChecks=0,lastUrl='about:blank',lastReadyState='unknown'
    for(let attempt=0;attempt<24;attempt++){
      if(page.isClosed())break
      const current=page.url()
      lastUrl=current
      if(this.isAllowedPageUrl(current)){
        const state=await page.evaluate(()=>({readyState:document.readyState,origin:location.origin,visible:document.visibilityState})).catch(()=>null)
        lastReadyState=state?.readyState??'unavailable'
        if(state&&state.readyState!=='loading'&&state.origin.startsWith('https://')){
          readyChecks++
          // BOSS is a SPA and may rewrite tracking parameters while the document is already usable.
          // Requiring the complete URL to remain identical creates false navigation failures.
          if(readyChecks>=2)return
        }else readyChecks=0
      }else readyChecks=0
      await page.waitForTimeout(250)
    }
    throw new Error(`BOSS_DISCOVERY_PAGE_NOT_STABLE: url=${lastUrl}; readyState=${lastReadyState}`)
  }

  private async ensureBossPage(targetUrl='https://www.zhipin.com/web/geek/jobs'):Promise<Page>{
    if(!await this.contextIsUsable())await this.connect()
    if(!this.context)throw new Error('BOSS browser context did not initialize.')
    let page=this.discoveryPage
    if(!page||page.isClosed()){
      page=this.context.pages().find(candidate=>!candidate.isClosed()&&this.isDiscoveryPageUrl(candidate.url()))
        ??await this.newPageWithRecovery()
      this.discoveryPage=page
    }
    if(!this.isDiscoveryPageUrl(page.url()))await this.navigate(page,targetUrl)
    await this.waitForStableBossPage(page)
    return page
  }

  private async rebuildDiscoveryPage(targetUrl='https://www.zhipin.com/web/geek/jobs'):Promise<Page>{
    if(!await this.contextIsUsable())await this.connect()
    if(!this.context)throw new Error('BOSS browser context did not initialize.')
    const previous=this.discoveryPage
    this.discoveryPage=undefined
    if(previous&&!previous.isClosed())await previous.close({runBeforeUnload:false}).catch(()=>undefined)
    const page=await this.newPageWithRecovery()
    this.discoveryPage=page
    await this.navigate(page,targetUrl)
    await this.waitForStableBossPage(page)
    return page
  }

  private async navigate(page:Page,url:string,timeout=30_000):Promise<void>{
    let lastError:unknown
    for(let attempt=0;attempt<3;attempt++){
      try{await page.goto(url,{waitUntil:'domcontentloaded',timeout});return}
      catch(error){
        lastError=error
        if(page.url()===url||page.url().startsWith(url+'?'))return
        await page.waitForTimeout(700*(attempt+1))
      }
    }
    throw lastError instanceof Error?lastError:new Error(`BOSS页面导航失败: ${url}`)
  }

  private configuredBrowserMode(): BossBrowserMode {
    const value=(process.env.BOSS_BROWSER_MODE??'auto').trim().toLowerCase()
    return ['auto','visible','headless','cdp'].includes(value)?value as BossBrowserMode:'auto'
  }

  private async closeBrowserSession(): Promise<void> {
    const context=this.context
    const browser=this.browser
    this.context=undefined
    this.browser=undefined
    this.discoveryPage=undefined
    this.activeBrowserMode=undefined
    if(context)await context.close().catch(()=>undefined)
    if(browser)await browser.close().catch(()=>undefined)
    if(this.ownedBrowserProcess){this.ownedBrowserProcess.kill();this.ownedBrowserProcess=undefined}
  }

  private manualLoginState(message:string):PlatformState{
    return{platform:this.platform,connected:false,mode:'browser',
      capabilities:['DISCOVER','READ_JOB','CHAT_INITIATED','MESSAGE_SENT'],
      lastCheckedAt:new Date().toISOString(),message,profileName:this.activeProfile}
  }

  private async accountIdentity():Promise<{state:BossAuthState;accountFingerprint?:string;maskedIdentity?:string}>{
    if(!this.context)return{state:'unknown'}
    const response=await this.context.request.get('https://www.zhipin.com/wapi/zpuser/wap/getUserInfo.json',{timeout:5_000}).catch(()=>null)
    if(!response)return{state:'unknown'}
    if([401,403].includes(response.status()))return{state:'signed_out'}
    if(!response.ok())return{state:'unknown'}
    const payload=await response.json().catch(()=>null)
    if(!payload)return{state:'unknown'}
    const userId=String(payload?.zpData?.userId??'')
    if(!userId)return Number(payload?.code)===0?{state:'signed_out'}:{state:'unknown'}
    const label=String(payload?.zpData?.name??payload?.zpData?.userName??payload?.zpData?.nickName??'BOSS账号')
    return{state:'authenticated',accountFingerprint:sha256(`boss:${userId}`),maskedIdentity:label.slice(0,40)}
  }

  private async launchManualLoginBrowser():Promise<PlatformState>{
    await this.closeBrowserSession()
    const executable=await this.findExecutable()
    if(!executable)throw new Error('BOSS_BROWSER_NOT_FOUND: 未找到Chrome或Edge浏览器。')
    await mkdir(this.profileDirectory,{recursive:true,mode:0o700})
    this.manualLoginProcess=spawn(executable,[`--user-data-dir=${this.profileDirectory}`,'--new-window',
      '--no-first-run','--no-default-browser-check','--disable-extensions','https://www.zhipin.com/web/user/?ka=header-login'],
    {stdio:'ignore',windowsHide:false})
    this.awaitingManualLogin=true
    this.profileSessionReady=false
    return this.manualLoginState('BOSS普通登录窗口已打开。完成登录后请关闭该窗口，再点击“登录完成，检查连接”。')
  }

  private profileAuthCookieNames():Set<string>{
    const names=new Set<string>()
    let database:DatabaseSync|undefined
    try{
      database=new DatabaseSync(join(this.profileDirectory,'Default','Network','Cookies'),{readOnly:true})
      const rows=database.prepare("SELECT name FROM cookies WHERE host_key LIKE '%zhipin.com'").all() as Array<{name:string}>
      rows.forEach(row=>names.add(String(row.name)))
    }catch{
      // Chrome may still hold the cookie database; verification runs after the login window closes.
    }finally{database?.close()}
    return names
  }

  private persistedProfileSignedIn():boolean{
    const names=this.profileAuthCookieNames()
    return names.has('wt2')&&(names.has('zp_at')||names.has('bst')||names.has('__zp_stoken__'))
  }

  private async launchOwnedBrowser(mode: 'visible'|'background'|'headless', targetUrl='about:blank'): Promise<void> {
    const executable=await this.findExecutable()
    if(!executable)throw new Error('BOSS_BROWSER_NOT_FOUND: 未找到Chrome或Edge浏览器。')
    await mkdir(this.profileDirectory,{recursive:true,mode:0o700})
    if(mode!=='headless'){
      const port=boundedInteger(process.env.BOSS_CDP_PORT,43121,1024,65535)
      const endpoint=`http://127.0.0.1:${port}`
      const ready=async()=>fetch(`${endpoint}/json/version`,{signal:AbortSignal.timeout(1_000)}).then(response=>response.ok).catch(()=>false)
      if(!await ready()){
        this.ownedBrowserProcess=spawn(executable,[`--remote-debugging-port=${port}`,`--user-data-dir=${this.profileDirectory}`,
          '--new-window','--no-first-run','--no-default-browser-check','--disable-session-crashed-bubble','--disable-extensions',
          ...(mode==='background'?['--start-minimized']:[]),targetUrl],{stdio:'ignore',windowsHide:false})
        for(let attempt=0;attempt<30&&!await ready();attempt++)await new Promise(resolve=>setTimeout(resolve,250))
      }
      if(!await ready())throw new Error(`BOSS_CDP_NOT_READY: Edge调试端口${port}未就绪。`)
      await new Promise(resolve=>setTimeout(resolve,750))
      let connectionError:unknown
      for(let attempt=0;attempt<2;attempt++){
        try{this.browser=await chromium.connectOverCDP(endpoint,{timeout:20_000});connectionError=undefined;break}
        catch(error){connectionError=error;await new Promise(resolve=>setTimeout(resolve,1_000))}
      }
      if(!this.browser)throw connectionError instanceof Error?connectionError:new Error('BOSS_CDP_CONNECT_FAILED')
      this.context=this.browser.contexts()[0]
      if(!this.context)throw new Error('BOSS专用浏览器没有可用上下文。')
      this.activeBrowserMode=mode
      const page=this.platformPage()??await this.context.newPage()
      if(mode==='visible'){await page.bringToFront();await this.showBrowserWindow(page)}
      else await this.hideBrowserWindow(page)
      return
    }
    this.context=await chromium.launchPersistentContext(this.profileDirectory,{
      executablePath:executable,
      headless:mode==='headless',
      viewport:mode==='headless'?{width:1440,height:1000}:null,
      args:['--no-first-run','--no-default-browser-check','--disable-session-crashed-bubble','--disable-extensions'],
    })
    this.browser=this.context.browser()??undefined
    this.activeBrowserMode=mode
    let page=this.platformPage()
    if(!page)page=await this.context.newPage()
    if(page.url()==='about:blank'||!ALLOWED_HOSTS.has(new URL(page.url()).hostname)){
      await this.navigate(page,targetUrl)
    }
  }

  private async connectCdp(): Promise<void> {
    const cdpEndpoint=process.env.BOSS_CDP_ENDPOINT??'http://127.0.0.1:43121'
    let lastError:unknown
    for(let attempt=0;attempt<3;attempt++){
      try{this.browser=await chromium.connectOverCDP(cdpEndpoint,{timeout:5_000});lastError=undefined;break}
      catch(error){lastError=error;if(attempt<2)await pageWait(500*(attempt+1))}
    }
    if(!this.browser)throw lastError instanceof Error?lastError:new Error('BOSS_CDP_CONNECT_FAILED')
    this.context=this.browser.contexts()[0]
    this.activeBrowserMode='cdp'
    if(!this.context)throw new Error('BOSS专用浏览器没有可用上下文。')
  }

  private async switchOwnedBrowser(mode:'visible'|'background'|'headless',targetUrl?:string):Promise<void>{
    if(this.activeBrowserMode===mode&&await this.contextIsUsable())return
    await this.closeBrowserSession()
    await this.launchOwnedBrowser(mode,targetUrl)
  }

  private async ensureExecutionBrowser():Promise<void>{
    if(!await this.contextIsUsable())await this.connect()
    if(!await this.contextIsUsable()&&this.profileSessionReady){
      if(this.configuredBrowserMode()==='cdp')await this.connectCdp()
      else await this.launchOwnedBrowser('background')
      const auth=await this.probeAuthStateWithRetry(true)
      if(auth==='signed_out')await this.launchManualLoginBrowser()
      if(auth==='unknown')throw new HumanActionRequiredError('QUESTION','BOSS_SESSION_UNCERTAIN: 浏览器连接已恢复，但登录状态暂时无法确认。')
    }
    if(!await this.contextIsUsable())throw new HumanActionRequiredError('LOGIN','BOSS_BROWSER_LOGIN_REQUIRED: 请完成BOSS登录并关闭登录窗口。')
    if(this.configuredBrowserMode()==='auto'&&this.activeBrowserMode==='visible'&&await this.isSignedIn()){
      const page=await this.page()
      await this.hideBrowserWindow(page)
      this.activeBrowserMode='background'
    }
  }

  private async contextIsUsable(): Promise<boolean> {
    if (!this.context) return false
    try {
      await this.context.cookies()
      return true
    } catch {
      this.context = undefined
      return false
    }
  }

  private platformPage(): Page | undefined {
    if(this.discoveryPage&&!this.discoveryPage.isClosed()&&this.isAllowedPageUrl(this.discoveryPage.url()))return this.discoveryPage
    const pages = this.context?.pages() ?? []
    return pages.find(page => {
      return !page.isClosed()&&this.isAllowedPageUrl(page.url())
    })
  }

  private async showBrowserWindow(page: Page): Promise<void> {
    if (!this.context) return
    try {
      const session = await this.context.newCDPSession(page)
      const { windowId } = await session.send('Browser.getWindowForTarget')
      await session.send('Browser.setWindowBounds', { windowId, bounds: { windowState: 'normal' } })
      await session.send('Browser.setWindowBounds', {
        windowId,
        bounds: { left: 60, top: 40, width: 1280, height: 860 },
      })
      await session.detach()
    } catch {
      // bringToFront above remains the portable fallback when window control is unavailable.
    }
  }

  private async hideBrowserWindow(page:Page):Promise<void>{
    if(!this.context)return
    try{
      const session=await this.context.newCDPSession(page)
      const {windowId}=await session.send('Browser.getWindowForTarget')
      await session.send('Browser.setWindowBounds',{windowId,bounds:{windowState:'minimized'}})
      await session.detach()
    }catch{
      // Background mode still remains a normal headed browser if window minimization is unavailable.
    }
  }

  private async firstVisible(page: Page, selectors: string[]): Promise<Locator | null> {
    for (const selector of selectors) {
      const locator = page.locator(selector).first()
      if (await locator.isVisible().catch(() => false)) return locator
    }
    return null
  }

  private async firstText(page: Page, selectors: string[]): Promise<string> {
    const locator = await this.firstVisible(page, selectors)
    return locator ? (await locator.innerText().catch(() => '')).trim() : ''
  }

  private selectors(key: keyof typeof BOSS_SELECTORS, configured?: string): string[] {
    return selectorCandidates(configured, BOSS_SELECTORS[key])
  }

  async status(): Promise<PlatformState> {
    const auth=await this.probeAuthState()
    const signedIn=auth==='authenticated'||auth==='unknown'&&this.profileSessionReady
    const identity=signedIn?await this.accountIdentity():{state:auth}
    const {state:_,...identityFields}=identity
    return {
      platform: this.platform,
      connected: signedIn,
      mode: 'browser',
      capabilities: ['DISCOVER', 'READ_JOB', 'CHAT_INITIATED', 'MESSAGE_SENT'],
      lastCheckedAt: new Date().toISOString(),
      profileName:this.activeProfile,
      ...identityFields,
      message: this.context
        ? signedIn ? `BOSS本地浏览器已登录（${this.activeBrowserMode??'browser'}）` : 'BOSS浏览器已打开，等待重新登录'
        : 'BOSS本地浏览器尚未启动',
    }
  }

  async diagnostics() {
    const executable = await this.findExecutable()
    const cityCodes = (() => {
      try { return JSON.parse(process.env.BOSS_CITY_CODES ?? '{}') as Record<string, string> }
      catch { return {} }
    })()
    const issues: string[] = []
    const page = this.platformPage()
    const cookieNames = new Set<string>()
    if (await this.contextIsUsable()) {
      for (const cookie of await this.context!.cookies('https://www.zhipin.com')) cookieNames.add(cookie.name)
    }else this.profileAuthCookieNames().forEach(name=>cookieNames.add(name))
    if (process.env.RUNNER_REAL_PLATFORM !== 'true') issues.push('RUNNER_REAL_PLATFORM未启用')
    if (!executable) issues.push('未找到Chrome或Edge浏览器')
    const manualLoginActive=Boolean(this.manualLoginProcess&&this.manualLoginProcess.exitCode===null)
    const authState=await this.probeAuthState(true)
    const signedIn=authState==='authenticated'||authState==='unknown'&&(this.profileSessionReady||(!manualLoginActive&&this.persistedProfileSignedIn()))
    if (this.context && !signedIn) issues.push('BOSS浏览器尚未完成登录')
    return {
      ready: issues.length === 0,
      browserFound: Boolean(executable),
      browserExecutable: executable ?? '',
      browserStarted: Boolean(this.context)||Boolean(this.manualLoginProcess),
      manualLoginActive,
      browserMode: this.awaitingManualLogin?'manual-login':this.activeBrowserMode??this.configuredBrowserMode(),
      activeProfile: this.activeProfile,
      signedIn,
      authState,
      cityCodeCount: new Set([...Object.keys(COMMON_BOSS_CITY_CODES), ...Object.keys(cityCodes)]).size,
      pageUrl: page?.url() ?? '',
      pageTitle: page ? await page.title().catch(() => '') : '',
      pages: (this.context?.pages() ?? []).map(item => ({
        url: item.url(),
        title: '',
      })),
      authCookies: {
        wt2: cookieNames.has('wt2'),
        stoken: cookieNames.has('__zp_stoken__') || cookieNames.has('stoken'),
        zpAt: cookieNames.has('zp_at'),
        bst: cookieNames.has('bst'),
      },
      issues,
    }
  }

  async cityDirectory():Promise<Array<{code:string;name:string;cities:Array<{code:string;name:string}>}>>{
    if(this.cityDirectoryCache&&this.cityDirectoryCache.expiresAt>Date.now())return this.cityDirectoryCache.regions
    type CityNode={code?:string|number;name?:string;subLevelModelList?:CityNode[]}
    type CityPayload={code?:number|string;zpData?:{cityList?:CityNode[]}}
    const configured=(()=>{try{return JSON.parse(process.env.BOSS_CITY_CODES??'{}') as Record<string,string>}catch{return {}}})()
    const fallbackCodes={...COMMON_BOSS_CITY_CODES,...configured}
    const fallback=()=>Object.entries(fallbackCodes).map(([name,code])=>({code,name,cities:[{code,name}]}))
    let regions:Array<{code:string;name:string;cities:Array<{code:string;name:string}>}> = []
    for(let attempt=0;attempt<2&&regions.length===0;attempt++){
      try {
        const response=await fetch('https://www.zhipin.com/wapi/zpCommon/data/city.json',{
          signal:AbortSignal.timeout(10_000),
          headers:{accept:'application/json',referer:'https://www.zhipin.com/', 'user-agent':'Mozilla/5.0'}
        })
        if(!response.ok)throw new Error(`BOSS_CITY_DIRECTORY_HTTP_${response.status}`)
        const payload=await response.json() as CityPayload
        if(Number(payload.code)!==0)throw new Error('BOSS_CITY_DIRECTORY_UNAVAILABLE')
        regions=(payload.zpData?.cityList??[]).map(region=>({code:String(region.code??''),name:String(region.name??''),
          cities:(region.subLevelModelList??[]).map(city=>({code:String(city.code??''),name:String(city.name??'')})).filter(city=>city.code&&city.name)}))
          .filter(region=>region.code&&region.name&&region.cities.length)
      } catch {
        if(attempt===0)await pageWait(250)
      }
    }
    if(!regions.length)regions=fallback()
    const codes:Record<string,string>={全国:'100010000'}
    for(const region of regions){codes[region.name]=region.code;for(const city of region.cities)codes[city.name]=city.code}
    this.cityDirectoryCache={expiresAt:Date.now()+24*60*60_000,regions,codes}
    return regions
  }

  async connect(): Promise<PlatformState> {
    await this.loadProfileSelection()
    if (process.env.RUNNER_REAL_PLATFORM !== 'true') {
      throw new Error('Real platform adapters are disabled. Set RUNNER_REAL_PLATFORM=true on the local machine after review.')
    }
    if(this.awaitingManualLogin){
      if(this.manualLoginProcess?.exitCode===null)
        return this.manualLoginState('请先在普通BOSS窗口完成登录并关闭该窗口，然后再次检查连接。')
      this.manualLoginProcess=undefined;this.awaitingManualLogin=false
      if(this.persistedProfileSignedIn()){
        this.profileSessionReady=true
        await this.launchOwnedBrowser('background')
        return this.status()
      }
      return this.launchManualLoginBrowser()
    }
    if(!await this.contextIsUsable()&&this.persistedProfileSignedIn()){
      this.profileSessionReady=true
      await this.launchOwnedBrowser('background')
      return this.status()
    }
    if (await this.contextIsUsable()) {
      const auth=await this.probeAuthStateWithRetry()
      if(auth==='authenticated'){
        if(this.configuredBrowserMode()==='auto'&&this.activeBrowserMode==='visible'){
          const page=await this.page();await this.hideBrowserWindow(page);this.activeBrowserMode='background'
        }
      }else if(auth==='signed_out'){
        return this.launchManualLoginBrowser()
      }else{
        throw new HumanActionRequiredError('QUESTION','BOSS_SESSION_UNCERTAIN: 暂时无法确认BOSS登录状态，未打开登录窗口，请稍后重试。')
      }
      return this.status()
    }
    await mkdir(this.profileDirectory, { recursive: true, mode: 0o700 })
    try {
      const mode=this.configuredBrowserMode()
      if(mode==='cdp')await this.connectCdp()
      else await this.launchOwnedBrowser(mode==='headless'?'headless':mode==='visible'?'visible':'background')
    } catch (error) {
      throw new HumanActionRequiredError(
        'QUESTION',
        `BOSS_BROWSER_START_FAILED: 无法启动BOSS专用浏览器。${error instanceof Error ? ` ${error.message}` : ''}`,
      )
    }
    const auth=await this.probeAuthStateWithRetry()
    if(auth==='signed_out')return this.launchManualLoginBrowser()
    if(auth==='unknown')throw new HumanActionRequiredError('QUESTION','BOSS_SESSION_UNCERTAIN: 浏览器已连接，但暂时无法确认登录状态。')
    return this.status()
  }

  async disconnect(): Promise<PlatformState> {
    await this.closeBrowserSession()
    if(this.manualLoginProcess?.exitCode===null)this.manualLoginProcess.kill()
    this.manualLoginProcess=undefined;this.awaitingManualLogin=false
    this.profileSessionReady=false
    return this.status()
  }

  async verifyHumanAction(kind:HumanActionRequiredError['kind'],expectedAccountFingerprint?:string):Promise<'resolved'|'required'|'unknown'>{
    if(kind!=='LOGIN')return'required'
    await this.loadProfileSelection()
    try{
      if(!await this.contextIsUsable()){
        if(this.configuredBrowserMode()==='cdp')await this.connectCdp()
        else if(this.persistedProfileSignedIn()){this.profileSessionReady=true;await this.launchOwnedBrowser('background')}
        else return'required'
      }
      const auth=await this.probeAuthStateWithRetry()
      if(auth==='signed_out')return'required'
      if(auth==='unknown')return'unknown'
      if(expectedAccountFingerprint){
        const identity=await this.accountIdentity()
        if(identity.state==='unknown')return'unknown'
        if(identity.state==='signed_out'||identity.accountFingerprint!==expectedAccountFingerprint)return'required'
      }
      return'resolved'
    }catch{return'unknown'}
  }

  private async detectHumanAction(page: Page): Promise<void> {
    const url = page.url()
    const body = (await page.locator('body').innerText({ timeout: 4_000 }).catch(() => '')).slice(0, 30_000)
    const blocker = detectBossBlocker(url, body)
    if(blocker){
      if(blocker.kind==='LOGIN'&&this.configuredBrowserMode()==='auto'){
        await this.launchManualLoginBrowser()
      }
      if(blocker.kind==='CAPTCHA'&&this.configuredBrowserMode()==='auto'&&this.activeBrowserMode==='background'){
        await this.switchOwnedBrowser('visible',url)
      }
      throw new HumanActionRequiredError(blocker.kind, `${blocker.code}: ${blocker.message}`)
    }
  }

  async discover(spec: SearchSpec,context:AdapterContext): Promise<NormalizedJob[]> {
    await this.ensureExecutionBrowser()
    const regions=await this.cityDirectory()
    const effectiveSpec={...spec,cities:expandBossCitySelections(spec.cities,regions)}
    const maxTotalPages=Math.max(effectiveSpec.cities.length,Math.min(100,effectiveSpec.maxTotalPages??30))
    const searchQueries=bossSearchQueries(effectiveSpec)
    const cacheKey=JSON.stringify({keyword:effectiveSpec.keyword.trim().toLowerCase(),keywordTokens:effectiveSpec.keywordTokens,searchQueries,cities:effectiveSpec.cities,maxPages:effectiveSpec.maxPages,maxTotalPages,resumeScopes:effectiveSpec.resumeScopes,
      salary:spec.salary??'',experience:spec.experience??'',degree:spec.degree??''})
    const cached=this.discoveryCache.get(cacheKey)
    if(!spec.eligibleTarget&&!spec.bypassCache&&cached&&cached.expiresAt>Date.now())return this.selectDiscovery(cacheKey,cached.cityJobs,effectiveSpec,{cacheHit:true,pages:cached.pages})
    const jobs=new Map<string,NormalizedJob>()
    const cityJobs=effectiveSpec.cities.map(()=>[] as NormalizedJob[])
    const cityIndex=new Map(effectiveSpec.cities.map((city,index)=>[city,index]))
    const exhaustedSearches=new Set<string>()
    const previousPageIds=new Map<string,string[]>()
    const repeatedPages=new Map<string,number>()
    const pages:Array<Record<string,unknown>>=[]
    let platformReturned=0,inRunDuplicates=0
    const schedule=effectiveSpec.resumeScopes?.length?effectiveSpec.resumeScopes:buildBossQuerySchedule(effectiveSpec.cities,searchQueries,effectiveSpec.maxPages,maxTotalPages)
    const completedBaseline=effectiveSpec.resumePagesCompleted??0
    const detectedBaseline=effectiveSpec.resumeDetectedCount??0
    const pageLimit=effectiveSpec.resumePageLimit??maxTotalPages
    const candidateTarget=bossCandidateLimit(effectiveSpec,pageLimit,boundedInteger(process.env.BOSS_MAX_JOBS_PER_PAGE,15,1,30))
    for(let requestIndex=0;requestIndex<schedule.length&&detectedBaseline+jobs.size<candidateTarget;requestIndex++){
      if(context.signal?.aborted)throw new Error('DISCOVERY_CANCELLED')
      const {city,query,pageNumber}=schedule[requestIndex]!
      const searchKey=`${city}\u0000${query}`
      if(exhaustedSearches.has(searchKey))continue
      const currentCityJobs=cityJobs[cityIndex.get(city)!]!
        let current: NormalizedJob[]
        try {
          current=await this.discoverPage({...effectiveSpec,keyword:query,cities:[city]},pageNumber)
        } catch (error) {
          // BOSS may request verification after one or more successful pages. Keep the verified
          // results already read instead of discarding the complete discovery run.
          if(error instanceof HumanActionRequiredError && jobs.size) {
            pages.push({city,query,pageNumber,returned:0,added:0,observedCities:[],stopReason:'interrupted',errorCode:error.kind,errorMessage:error.message})
            await context.onProgress?.({...bossDiscoveryProgress(completedBaseline+pages.length-1,pageLimit,detectedBaseline+jobs.size,candidateTarget),platformReturned,inRunDuplicates,interrupted:true})
            return this.enrichCacheAndSelect(cacheKey,jobs,cityJobs,effectiveSpec,pages,false,{platformReturned,inRunDuplicates,
              pagesCompleted:completedBaseline+pages.length-1,pageLimit,detectedCount:detectedBaseline+jobs.size,candidateLimit:candidateTarget,
              interruption:{kind:error.kind,code:error.message.split(':')[0],message:error.message,city,query,pageNumber},
              completedScopes:pages.filter(page=>page.stopReason!=='interrupted').map(page=>({city:page.city,query:page.query,pageNumber:page.pageNumber})),
              pendingScopes:schedule.slice(requestIndex).map(scope=>({...scope}))})
          }
          throw error
        }
        platformReturned+=current.length
        let added=0
        for(const job of current){if(!jobs.has(job.externalJobId)){jobs.set(job.externalJobId,job);currentCityJobs.push(job);added++}else inRunDuplicates++}
        const ids=current.map(job=>job.externalJobId)
        const previous=previousPageIds.get(searchKey)
        const repeated=previous?.length===ids.length&&previous.every((id,index)=>id===ids[index])
          ?(repeatedPages.get(searchKey)??0)+1:0
        repeatedPages.set(searchKey,repeated)
        const stopReason=bossPageStopReason(ids,previous,repeated)
        pages.push({city,query,pageNumber,returned:current.length,added,observedCities:[...new Set(current.map(job=>job.location.split('·')[0]))],stopReason:stopReason??''})
        await context.onProgress?.({...bossDiscoveryProgress(completedBaseline+pages.length,pageLimit,detectedBaseline+jobs.size,candidateTarget),platformReturned,inRunDuplicates})
        previousPageIds.set(searchKey,ids)
        if(stopReason)exhaustedSearches.add(searchKey)
        if(spec.eligibleTarget){
          const pendingScopes=schedule.slice(requestIndex+1).filter(scope=>!exhaustedSearches.has(`${scope.city}\u0000${scope.query}`))
          return this.enrichCacheAndSelect(cacheKey,jobs,cityJobs,effectiveSpec,pages,false,{
            partial:false,platformReturned,inRunDuplicates,pagesCompleted:completedBaseline+pages.length,pageLimit,
            detectedCount:detectedBaseline+jobs.size,candidateLimit:candidateTarget,pendingScopes,eligibleTarget:spec.eligibleTarget,
          })
        }
        if(requestIndex<schedule.length-1&&detectedBaseline+jobs.size<candidateTarget){
          if(context.signal?.aborted)throw new Error('DISCOVERY_CANCELLED')
          // Do not call page() just to sleep: if the current tab is between SPA
          // navigations it can create and retain an about:blank tab, which the
          // next page request would then mistakenly use.
          await pageWait(3_000+Math.floor(Math.random()*3_001))
        }
    }
    return this.enrichCacheAndSelect(cacheKey,jobs,cityJobs,effectiveSpec,pages,true,{platformReturned,inRunDuplicates,
      pagesCompleted:completedBaseline+pages.length,pageLimit,detectedCount:detectedBaseline+jobs.size,candidateLimit:candidateTarget})
  }

  private async enrichCacheAndSelect(cacheKey:string,jobs:Map<string,NormalizedJob>,cityJobs:NormalizedJob[][],spec:SearchSpec,pages:Array<Record<string,unknown>>,cacheable:boolean,extra:Record<string,unknown>={}){
    const enriched=await this.enrichJobs([...jobs.values()],spec)
    const byId=new Map(enriched.map(job=>[job.externalJobId,job]))
    const groups=cityJobs.map(group=>group.map(job=>byId.get(job.externalJobId)).filter((job):job is NormalizedJob=>Boolean(job)))
    if(cacheable)this.discoveryCache.set(cacheKey,{expiresAt:Date.now()+15*60_000,cityJobs:groups,pages})
    return this.selectDiscovery(cacheKey,groups,spec,{cacheHit:false,partial:!cacheable,pages,...extra})
  }

  private async selectDiscovery(cacheKey:string,cityJobs:NormalizedJob[][],spec:SearchSpec,diagnostics:Record<string,unknown>){
    const commuted=await enrichCommute(cityJobs.flat().map(job=>({...job})),spec)
    const filterSummary=summarizeBossFilters(commuted,spec)
    const byId=new Map(commuted.map(job=>[job.externalJobId,job]))
    const groups=cityJobs.map(group=>group.map(job=>byId.get(job.externalJobId)).filter((job):job is NormalizedJob=>Boolean(job)))
    const rotation=this.discoveryCityRotation.get(cacheKey)??0
    this.discoveryCityRotation.set(cacheKey,rotation+1)
    const selected=selectBalancedBossJobs(groups,spec,rotation)
    this.lastDiscoveryFilterStats={...filterSummary,runnerFiltered:Number(filterSummary.runnerCandidates??0)-Number(filterSummary.runnerEligible??0),runnerReturned:selected.length,...diagnostics,
      decisions:commuted.map(job=>({externalJobId:job.externalJobId,company:job.company,role:job.role,reason:bossJobFilterReason(job,spec)??'eligible',experienceRisk:bossExperienceRisk(job.experience??'',spec.experience??'')}))}
    return selected
  }
  discoveryStats(){return{...this.lastDiscoveryFilterStats}}
  private async discoverPage(spec: SearchSpec,pageNumber:number): Promise<NormalizedJob[]> {
    await this.cityDirectory()
    const codes={...(this.cityDirectoryCache?.codes??{}),...JSON.parse(process.env.BOSS_CITY_CODES??'{}') as Record<string,string>}
    const input=spec.cities[0]??''
    const code = resolveBossCityCode(input, codes)
    if(!code)throw new Error(`BOSS_CITY_CODE_REQUIRED: 未识别城市“${input}”，请在BOSS_CITY_CODES中配置平台城市编码。`)
    const pageSize = boundedInteger(process.env.BOSS_MAX_JOBS_PER_PAGE, 15, 1, 30)
    type JobListResult = {
      httpStatus: number
      code: number
      message: string
      jobs: Array<Record<string, unknown>>
    }
    const requestJobList = async (activeContext:BrowserContext):Promise<JobListResult> => {
      const params = new URLSearchParams({
        scene:'1',query:spec.keyword,city:code,experience:'',degree:'',industry:'',scale:'',
        stage:'',position:'',jobType:'',salary:'',multiBusinessDistrict:'',multiSubway:'',
        page:String(pageNumber),pageSize:String(pageSize),
      })
      const endpoint=`https://www.zhipin.com/wapi/zpgeek/search/joblist.json?${params}`
      const response=await activeContext.request.get(endpoint,{timeout:15_000,headers:{Accept:'application/json, text/plain, */*',Referer:'https://www.zhipin.com/web/geek/jobs'}})
      const payload=await response.json().catch(()=>null)
      return {
        httpStatus:response.status(),
        code: Number(payload?.code ?? -1),
        message: String(payload?.message ?? payload?.msg ?? ''),
        jobs: Array.isArray(payload?.zpData?.jobList) ? payload.zpData.jobList : [],
      }
    }
    let result: JobListResult | undefined
    let lastError: unknown
    let page:Page|undefined
    for (let attempt = 0; attempt < 3 && !result; attempt++) {
      try {
        const activePage=attempt===0?await this.ensureBossPage():await this.rebuildDiscoveryPage()
        page=activePage
        await activePage.waitForLoadState('domcontentloaded', { timeout: 10_000 }).catch(() => undefined)
        await activePage.waitForTimeout(500+attempt*500)
        await this.detectHumanAction(activePage)
        if(!this.context)throw new Error('BOSS_BROWSER_CONTEXT_MISSING')
        result = await requestJobList(this.context)
      } catch (error) {
        lastError = error
        const navigationRace = error instanceof Error && /Execution context was destroyed|Target page|navigation|BOSS_SEARCH_ORIGIN_MISSING|BOSS_DISCOVERY_PAGE_NOT_STABLE|BOSS_BROWSER_CONTEXT_MISSING|Timeout|ECONN|socket|fetch failed/i.test(error.message)
        if (!navigationRace) throw error
         if(attempt<2)await (page?.waitForTimeout(1_500*(attempt+1))??new Promise(resolve=>setTimeout(resolve,1_500*(attempt+1)))).catch(()=>undefined)
         if(attempt===2)throw new HumanActionRequiredError('QUESTION',
            `BOSS_PAGE_NAVIGATION_UNSTABLE: BOSS页面连续跳转，已停止本次发现。${lastError instanceof Error?` 最后错误：${lastError.message}`:''}`)
      }
    }
    if (!result) throw lastError instanceof Error ? lastError : new Error('BOSS岗位接口没有返回结果。')
    if (result.httpStatus !== 200 || result.code !== 0) {
      if(result.code===36){
        throw new HumanActionRequiredError('QUESTION','BOSS_JOB_API_36: BOSS检测到异常执行环境，已暂停。请切换回后台浏览器模式并稍后重试。')
      }
      if (result.code===37) {
        throw new HumanActionRequiredError('QUESTION', 'BOSS_JOB_API_37: BOSS返回“您的环境存在异常”，已停止本批次且不会自动重试；请在官方页面确认恢复后再继续。')
      }
      if ([7, 35].includes(result.code)) {
        throw new HumanActionRequiredError('CAPTCHA', `BOSS_JOB_API_${result.code}: ${result.message || '请在浏览器中完成安全验证。'}`)
      }
      throw new PageChangedError(`BOSS_JOB_API_${result.code}: ${result.message || '岗位接口返回异常'}`)
    }
    const jobs: NormalizedJob[] = result.jobs
      .filter(item => typeof item.encryptJobId === 'string' && item.encryptJobId)
      .map(item => {
        const jobId = String(item.encryptJobId)
        const detailUrl = new URL(`https://www.zhipin.com/job_detail/${jobId}.html`)
        if (typeof item.securityId === 'string' && item.securityId) detailUrl.searchParams.set('securityId', item.securityId)
        if (typeof item.lid === 'string' && item.lid) detailUrl.searchParams.set('lid', item.lid)
        const words = (value: unknown) => Array.isArray(value) ? value.map(String) : []
        return {
          platform: 'boss' as const,
          externalJobId: jobId,
          securityId: typeof item.securityId === 'string' ? item.securityId : undefined,
          lid: typeof item.lid === 'string' ? item.lid : undefined,
          canonicalUrl: detailUrl.toString(),
          role: String(item.jobName ?? '职位'),
          salary: String(item.salaryDesc ?? ''),
          experience: String(item.jobExperience ?? ''),
          degree: String(item.jobDegree ?? ''),
          company: String(item.brandName ?? '招聘企业'),
          location: [item.cityName, item.areaDistrict, item.businessDistrict].filter(Boolean).map(String).join('·'),
          description: [...words(item.skills), ...words(item.jobLabels), ...words(item.welfareList)].join('、'),
          recruiter: item.bossName ? String(item.bossName) : undefined,
          recruiterId: item.encryptBossId ? String(item.encryptBossId) : undefined,
          recruiterActive: item.bossActiveTimeDesc ? String(item.bossActiveTimeDesc) : undefined,
          recruiterOnline: Boolean(item.bossOnline),
          companySize: item.brandScaleName ? String(item.brandScaleName) : undefined,
          headhunter: Number(item.goldHunter??0)===1,
          contacted: Boolean(item.contact),
          longitude: typeof (item.gps as Record<string,unknown>|undefined)?.longitude==='number' ? Number((item.gps as Record<string,unknown>).longitude) : undefined,
          latitude: typeof (item.gps as Record<string,unknown>|undefined)?.latitude==='number' ? Number((item.gps as Record<string,unknown>).latitude) : undefined,
          publishedAt: undefined,
        }
      })
    return jobs
  }

  private async enrichJobs(jobs: NormalizedJob[],spec:SearchSpec): Promise<NormalizedJob[]> {
    if (!jobs.length) return jobs
    const detailLimit = boundedInteger(process.env.BOSS_DETAIL_JOB_LIMIT, hasCareerStageIntent(spec)?12:0, 0, 30)
    if (!detailLimit) return jobs
    const page = await this.page()
    const delayMin = boundedInteger(process.env.BOSS_DETAIL_DELAY_MIN_MS, 700, 200, 10_000)
    const delayMax = boundedInteger(process.env.BOSS_DETAIL_DELAY_MAX_MS, 1_300, delayMin, 20_000)
    const enriched: NormalizedJob[] = []
    let detailCount=0
    for (const job of jobs) {
      if (!needsBossJobDetail(job,spec)||detailCount>=detailLimit) { enriched.push(job); continue }
      detailCount++
      try {
        await page.waitForTimeout(delayMin + Math.floor(Math.random() * (delayMax - delayMin + 1)))
        if (!job.securityId) { enriched.push(job); continue }
        const detail = await page.evaluate(async ({ securityId, lid }) => {
          const params = new URLSearchParams({ securityId, ...(lid ? { lid } : {}) })
          for (const path of ['/wapi/zpgeek/job/card.json', '/wapi/zpgeek/job/detail.json']) {
            const response = await fetch(`${path}?${params}`)
            const payload = await response.json().catch(() => null)
            if (response.ok && payload?.code === 0) {
              const value = payload?.zpData?.jobCard ?? payload?.zpData?.jobInfo ?? payload?.zpData ?? {}
              return { code: 0, value }
            }
          }
          return { code: -1, value: {} }
        }, { securityId: job.securityId, lid: job.lid }) as { code: number; value: Record<string, unknown> }
        const value = detail.value
        enriched.push({
          ...job,
          role: String(value.jobName ?? job.role),
          company: String(value.brandName ?? job.company),
          salary: String(value.salaryDesc ?? job.salary),
          experience: String(value.jobExperience ?? job.experience ?? ''),
          degree: String(value.jobDegree ?? job.degree ?? ''),
          location: String(value.address ?? value.locationName ?? job.location),
          description: String(value.postDescription ?? job.description),
        })
      } catch {
        enriched.push(job)
      }
    }
    return enriched
  }

  async prepare(plan: ApplicationPlan): Promise<PreparedApplication> {
    await this.loadProfileSelection()
    if(plan.profileName&&plan.profileName!==this.activeProfile)throw new HumanActionRequiredError('QUESTION',`BOSS_ACCOUNT_PROFILE_MISMATCH: 任务绑定${plan.profileName}，当前为${this.activeProfile}。`)
    if (!plan.canonicalUrl) throw new Error('A canonical BOSS job URL is required for preparation.')
    if (!plan.message.trim() || plan.message.length > 100) {
      throw new HumanActionRequiredError('QUESTION', 'BOSS招呼语必须为1至100个字符，请返回审核页修改。')
    }
    const url=this.approvedSnapshotUrl(plan)
    return {
      planHash: planHash(plan),
      pageFingerprint: sha256({ url: url.toString(), company:plan.company, role:plan.role, job: plan.externalJobId, content:plan.jobContentHash }),
      actionSummary: 'Open the reviewed job and send only the reviewed greeting. No resume will be sent automatically.',
      fields: [
        { key: 'company', label: '公司', value: plan.company, source: 'DISCOVERY_SNAPSHOT' },
        { key: 'role', label: '岗位', value: plan.role, source: 'DISCOVERY_SNAPSHOT' },
        { key: 'message', label: '招呼语', value: plan.message, source: 'USER_REVIEWED' },
      ],
      warnings: ['准备阶段不重复请求BOSS搜索或详情接口；发送前会在真实岗位页面再次核验岗位ID、公司和职位。'],
    }
  }

  private approvedSnapshotUrl(plan:ApplicationPlan):URL{
    const url=this.assertAllowedUrl(plan.canonicalUrl!)
    if(bossJobIdFromUrl(url.toString())!==plan.externalJobId)
      throw new PageChangedError('BOSS_SNAPSHOT_JOB_ID_MISMATCH: 审核快照中的岗位ID与链接不一致。')
    if(!url.searchParams.get('securityId'))
      throw new PageChangedError('BOSS_SNAPSHOT_SECURITY_ID_MISSING: 岗位快照缺少securityId，请重新发现岗位。')
    return url
  }

  private async openAndVerifyJobPage(page:Page,plan:ApplicationPlan):Promise<{url:string;company:string;role:string;contactUrl?:string}>{
    const approvedUrl=this.approvedSnapshotUrl(plan)
    const navigationUrl=new URL(approvedUrl.pathname,approvedUrl.origin)
    await this.openApprovedJobFromPage(page,navigationUrl.toString())
    let openedJobId=bossJobIdFromUrl(page.url())
    for(let attempt=0;attempt<10&&!openedJobId;attempt++){
      await page.waitForTimeout(50)
      openedJobId=bossJobIdFromUrl(page.url())
    }
    if(!openedJobId&&isBossHomeUrl(page.url()))
      throw new HumanActionRequiredError('QUESTION','BOSS_JOB_REDIRECTED_HOME: BOSS将岗位详情页重定向回主页，已暂停后续访问；请在专用浏览器中确认该岗位可以正常打开。')
    if(openedJobId!==plan.externalJobId)
      throw new PageChangedError(`BOSS_PAGE_JOB_ID_MISMATCH: 打开的岗位ID不是已审核岗位（${openedJobId??'无法识别'}）。`)
    let role='',company=''
    for(let attempt=0;attempt<20&&(!role||!company);attempt++){
      role=role||await this.firstText(page,this.selectors('role',process.env.BOSS_JOB_ROLE_SELECTOR))
      company=company||await this.firstText(page,this.selectors('company',process.env.BOSS_JOB_COMPANY_SELECTOR))
      if(!company){
        const title=await page.title().catch(()=>'')
        company=title.match(/」_(.+?)招聘-BOSS直聘$/)?.[1]?.trim()??''
      }
      if(role&&company)break
      await page.waitForTimeout(100)
    }
    await this.detectHumanAction(page)
    if(!role||!company)
      throw new PageChangedError('BOSS_PAGE_IDENTITY_MISSING: 岗位页面未完整呈现公司或职位，请勿发送。')
    if(!matchesBossIdentity(plan.role,role)||!matchesBossIdentity(plan.company,company,true))
      throw new PageChangedError(`BOSS_PAGE_IDENTITY_MISMATCH: 页面显示“${company} · ${role}”，与审核内容不一致。`)
    const communicate=await this.firstVisible(page,this.selectors('communicate',process.env.BOSS_COMMUNICATE_SELECTOR))
    const contactUrl=await communicate?.getAttribute('data-url')??undefined
    return{url:page.url(),company,role,contactUrl}
  }

  private async openApprovedJobFromPage(page:Page,url:string):Promise<void>{
    try{
      await page.goto(url,{waitUntil:'commit',timeout:15_000,referer:'https://www.zhipin.com/web/geek/jobs'})
    }catch(error){
      if(bossJobIdFromUrl(page.url())===bossJobIdFromUrl(url))return
      throw new PageChangedError(`BOSS_JOB_NAVIGATION_FAILED: 岗位详情页未打开，尚未执行沟通动作。${error instanceof Error?` ${error.message.split('\n')[0]}`:''}`)
    }
  }

  private async freshSubmissionPage():Promise<Page>{
    if(!this.context)throw new PageChangedError('BOSS_PRE_SUBMIT_BROWSER_MISSING: BOSS浏览器上下文不可用，尚未执行沟通动作。')
    let page:Page
    try{page=await this.newPageWithRecovery()}
    catch(error){throw new PageChangedError(`BOSS_PRE_SUBMIT_PAGE_FAILED: 无法创建岗位标签页，尚未执行沟通动作。${error instanceof Error?` ${error.message}`:''}`)}
    if(!this.discoveryPage||this.discoveryPage.isClosed()){
      this.discoveryPage=this.context.pages().find(existing=>!existing.isClosed()&&this.isDiscoveryPageUrl(existing.url()))
    }
    for(const existing of this.context.pages()){
      if(existing===page)continue
      if(existing===this.discoveryPage)continue
      try{
        const url=existing.url()
        if(url==='about:blank'||ALLOWED_HOSTS.has(new URL(url).hostname))await existing.close({runBeforeUnload:false})
      }catch{/* Ignore stale tabs. */}
    }
    return page
  }

  async submit(plan: ApplicationPlan, context: AdapterContext): Promise<SubmitReceipt> {
    const previous=this.submissionQueue
    let release!:()=>void
    this.submissionQueue=new Promise<void>(resolve=>{release=resolve})
    await previous
    try{return await this.submitInternal(plan,context)}finally{release()}
  }

  private async submitInternal(plan: ApplicationPlan, context: AdapterContext): Promise<SubmitReceipt> {
    await this.loadProfileSelection()
    if (context.scenario === 'unknown_after_submit') throw new UnknownOutcomeError()
    if (!['CHAT', 'RESUME_SUBMIT'].includes(plan.actionType)) throw new Error('BOSS adapter received an unsupported action plan.')
    if (!plan.message.trim() || plan.message.length > 100) {
      throw new HumanActionRequiredError('QUESTION', 'BOSS招呼语必须为1至100个字符，请返回审核页修改。')
    }
    if (!plan.canonicalUrl) throw new PageChangedError('BOSS_PRE_SUBMIT_URL_MISSING: 审核计划缺少岗位链接，尚未执行沟通动作。')
    if(plan.profileName&&plan.profileName!==this.activeProfile)throw new HumanActionRequiredError('QUESTION',`BOSS_ACCOUNT_PROFILE_MISMATCH: 任务绑定${plan.profileName}，当前为${this.activeProfile}。`)
    let page:Page,identity:{url:string;company:string;role:string;contactUrl?:string}
    try{
      await this.ensureExecutionBrowser()
      if(plan.accountFingerprint){
        let identity=await this.accountIdentity()
        for(let attempt=0;identity.state==='unknown'&&attempt<2;attempt++){await pageWait(500*(attempt+1));identity=await this.accountIdentity()}
        if(identity.state==='unknown')throw new HumanActionRequiredError('QUESTION','BOSS_ACCOUNT_IDENTITY_UNCERTAIN: 暂时无法确认当前BOSS账号，未执行发送。')
        if(identity.state==='signed_out')throw new HumanActionRequiredError('LOGIN','BOSS_BROWSER_LOGIN_REQUIRED: BOSS已明确退出登录。')
        if(identity.accountFingerprint!==plan.accountFingerprint)throw new HumanActionRequiredError('QUESTION','BOSS_ACCOUNT_IDENTITY_MISMATCH: 当前BOSS登录账号与任务绑定账号不一致。')
      }
       await this.waitForSubmissionSlot(await this.page())
      page=await this.freshSubmissionPage()
      identity=await this.openAndVerifyJobPage(page,plan)
    }catch(error){
      if(error instanceof HumanActionRequiredError||error instanceof PageChangedError)throw error
      throw new PageChangedError(`BOSS_PRE_SUBMIT_FAILED: 发送前检查失败，尚未执行沟通动作。${error instanceof Error?` ${error.message}`:''}`)
    }
    try{
      const receipt=await this.sendGreetingViaPage(page,plan,identity)
      this.recordSubmissionSuccess()
      return receipt
    }catch(error){
      if(error instanceof HumanActionRequiredError&&error.message.includes('BOSS_CONTACT_RATE_LIMITED'))this.recordSubmissionThrottle()
      throw error
    }
    finally{
      await page.close({runBeforeUnload:false}).catch(()=>undefined)
      this.submissionsSinceRecycle++
      const recycleAfter=boundedInteger(process.env.BOSS_BROWSER_RECYCLE_TASKS,40,10,200)
      if(this.submissionsSinceRecycle>=recycleAfter&&this.configuredBrowserMode()!=='cdp'){
        this.submissionsSinceRecycle=0
        await this.closeBrowserSession()
      }
    }
  }

  private async waitForSubmissionSlot(page:Page):Promise<void>{
    const min=boundedInteger(process.env.BOSS_SUBMIT_DELAY_MIN_MS,8_000,5_000,300_000)
    const max=boundedInteger(process.env.BOSS_SUBMIT_DELAY_MAX_MS,12_000,min,600_000)
    const configuredWindowMs=boundedInteger(process.env.BOSS_SUBMIT_WINDOW_MS,60_000,60_000,3_600_000)
    const windowMs=Math.max(configuredWindowMs,this.adaptiveSubmissionWindowMs)
    const windowMax=boundedInteger(process.env.BOSS_SUBMIT_WINDOW_MAX,5,1,50)
    const now=Date.now()
    let wait=0
    if(this.lastSubmissionAt)wait=Math.max(0,min+Math.floor(Math.random()*(max-min+1))-(now-this.lastSubmissionAt))
    wait=Math.max(wait,rollingWindowWait(now,this.submissionTimestamps,windowMs,windowMax))
    if(wait)await page.waitForTimeout(wait)
    this.lastSubmissionAt=Date.now()
    this.submissionTimestamps=this.submissionTimestamps.filter(timestamp=>timestamp>this.lastSubmissionAt-windowMs)
    this.submissionTimestamps.push(this.lastSubmissionAt)
  }

  private recordSubmissionThrottle():void{
    const configured=boundedInteger(process.env.BOSS_SUBMIT_WINDOW_MS,60_000,60_000,3_600_000)
    this.adaptiveSubmissionWindowMs=throttledSubmissionWindow(configured,this.adaptiveSubmissionWindowMs)
    this.successfulSubmissionsSinceThrottle=0
  }

  private recordSubmissionSuccess():void{
    if(!this.adaptiveSubmissionWindowMs)return
    this.successfulSubmissionsSinceThrottle++
    if(this.successfulSubmissionsSinceThrottle<20)return
    const configured=boundedInteger(process.env.BOSS_SUBMIT_WINDOW_MS,60_000,60_000,3_600_000)
    this.adaptiveSubmissionWindowMs=recoveredSubmissionWindow(configured,this.adaptiveSubmissionWindowMs)
    this.successfulSubmissionsSinceThrottle=0
  }

  private async sendGreetingViaPage(page:Page,plan:ApplicationPlan,identity:{url:string;company:string;role:string;contactUrl?:string}):Promise<SubmitReceipt>{
    if(!this.context)throw new PageChangedError('BOSS_CONTACT_CONTEXT_MISSING: 浏览器上下文不可用，尚未建立沟通。')
    const existing=await inspectBossConversation(page,plan.externalJobId,plan.message,this.context)
    if(existing){
      const greeting=existing.messageId?existing:await sendBossReviewedText(page,this.context,plan.externalJobId,plan.message)
      const evidence={jobId:plan.externalJobId,company:identity.company,role:identity.role,
        messageId:greeting.messageId,status:greeting.messageStatus,channel:'BOSS_CHAT_EXISTING'}
      return{outcome:'MESSAGE_SENT',receiptId:`BOSS-MSG-${greeting.messageId}`,
        platformReceiptId:greeting.messageId,receiptSource:'platform',verifiedAt:new Date().toISOString(),evidence:JSON.stringify(evidence)}
    }
    const dataUrl=identity.contactUrl
    if(!dataUrl||!dataUrl.startsWith('/wapi/zpgeek/friend/add.json?'))
      throw new PageChangedError('BOSS_CONTACT_URL_MISSING: 沟通按钮缺少实时请求地址，尚未建立沟通。')
    const token=(await this.context.cookies('https://www.zhipin.com')).find(cookie=>cookie.name==='bst')?.value??''
    if(!token)throw new HumanActionRequiredError('LOGIN','BOSS_CONTACT_TOKEN_MISSING: 登录信息不完整，请重新登录。')
    // BOSS automatically sends one greeting when a new conversation is
    // created. Save the reviewed text first so that this automatic message is
    // the only message sent; publishing MQTT here would create duplicates.
    const contact=await createBossConversationWithGreeting(this.context,token,plan.externalJobId,plan.message,dataUrl)
    if(contact.httpStatus!==200||contact.code!==0){
      if(contact.stage==='greeting')throw new PageChangedError(`BOSS_GREETING_SAVE_FAILED: ${contact.message}`)
      if(/操作过于频繁/.test(contact.message))throw new HumanActionRequiredError('QUESTION',`BOSS_CONTACT_RATE_LIMITED: ${contact.message}`)
      if(isBossDailyContactLimit(contact.message))throw new DailyQuotaReachedError(`BOSS_DAILY_CONTACT_LIMIT: ${contact.message}`)
      throw new PageChangedError(`BOSS_CONTACT_API_${contact.code}: ${contact.message||'建立沟通失败'}`)
    }
    const receiptId=`BOSS-CONTACT-${plan.externalJobId}`
    const evidence={jobId:plan.externalJobId,company:identity.company,role:identity.role,channel:'BOSS_CONTACT_API_ACK',status:contact.code}
    return{outcome:'MESSAGE_SENT',receiptId,platformReceiptId:plan.externalJobId,receiptSource:'platform',verifiedAt:new Date().toISOString(),evidence:JSON.stringify(evidence)}
  }

  private async probeAuthStateWithRetry(passive=false):Promise<BossAuthState>{
    let state:BossAuthState='unknown'
    for(let attempt=0;attempt<3;attempt++){
      state=await this.probeAuthState(passive)
      if(state!=='unknown')return state
      if(attempt<2)await pageWait(400*(attempt+1))
    }
    return state
  }

  private async probeAuthState(passive=false): Promise<BossAuthState> {
    try {
      if (!await this.contextIsUsable()) return 'unknown'
      const page = this.platformPage()
      const cookieNames = new Set(
        (await this.context!.cookies('https://www.zhipin.com')).map(cookie => cookie.name),
      )
      const cookieSignedIn=cookieNames.has('wt2')&&(cookieNames.has('zp_at')||cookieNames.has('bst')||cookieNames.has('__zp_stoken__'))
      if (!page || !ALLOWED_HOSTS.has(new URL(page.url()).hostname)) return cookieSignedIn?'authenticated':'unknown'
      const body = await page.locator('body').innerText({ timeout: 3_000 }).catch(() => '')
      const blocker = detectBossBlocker(page.url(), body)
      if (blocker?.code === 'LOGIN_REQUIRED'||blocker?.code === 'CAPTCHA_REQUIRED')return classifyBossAuthEvidence({blockerCode:blocker.code})
      const accountVisible = Boolean(await this.firstVisible(
        page,
        this.selectors('account', process.env.BOSS_ACCOUNT_SELECTOR),
      ))
      if(passive)return classifyBossAuthEvidence({passive:true,accountVisible,cookieSignedIn})
      const serverAuth=await page.evaluate(async()=>{
        try{
          const response=await fetch('/wapi/zpuser/wap/getUserInfo.json')
          const payload=await response.json()
          return{definitive:Number(payload?.code)===0,signedIn:Number(payload?.code)===0&&Boolean(payload?.zpData?.token)}
        }catch{return{definitive:false,signedIn:false}}
      }).catch(()=>({definitive:false,signedIn:false}))
      return classifyBossAuthEvidence({serverDefinitive:serverAuth.definitive,serverSignedIn:serverAuth.signedIn,accountVisible,cookieSignedIn})
    } catch {
      return 'unknown'
    }
  }

  private async isSignedIn(passive=false):Promise<boolean>{return await this.probeAuthState(passive)==='authenticated'}

  async reconcile(plan: ApplicationPlan): Promise<SubmitReceipt | null> {
    await this.ensureExecutionBrowser()
    const page = await this.page()
    const conversation=await inspectBossConversation(page,plan.externalJobId,plan.message)
    if(!conversation)throw new PageChangedError('VERIFIED_NOT_SUBMITTED: BOSS沟通列表中没有该岗位。')
    if(!conversation.messageId)throw new PageChangedError('VERIFIED_NOT_SUBMITTED: 审核招呼语未发送。')
    return{outcome:'MESSAGE_SENT',
      receiptId:`BOSS-RECONCILED-${conversation.messageId}`,platformReceiptId:conversation.messageId,
      receiptSource:'platform',verifiedAt:new Date().toISOString(),
      evidence:JSON.stringify({jobId:plan.externalJobId,messageId:conversation.messageId})}
  }
}
