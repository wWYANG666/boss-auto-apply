import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bossJobIdFromUrl, boundedInteger, detectBossBlocker, expandBossCitySelections, isBossDailyContactLimit, isBossHomeUrl, isRecoverableBrowserPageError, matchesBossIdentity, recoveredSubmissionWindow, resolveBossCityCode, rollingWindowWait, selectorCandidates, throttledSubmissionWindow } from '../src/adapters/boss-page-contract.js'
import { classifyBossAuthEvidence } from '../src/adapters/boss-browser.js'

test('BOSS page blockers distinguish login, captcha, quota and closed jobs', () => {
  assert.equal(detectBossBlocker('https://www.zhipin.com/web/user/?ka=login', '扫码登录')?.code, 'LOGIN_REQUIRED')
  assert.equal(detectBossBlocker('https://www.zhipin.com/xuzhou/', '我要招聘 我要找工作 登录/注册')?.code, 'LOGIN_REQUIRED')
  assert.equal(detectBossBlocker('https://www.zhipin.com/web/common/safe-check', '完成安全验证')?.code, 'CAPTCHA_REQUIRED')
  assert.equal(detectBossBlocker('https://www.zhipin.com/web/geek/chat', '温馨提示：今日沟通已达到上限')?.code, 'DAILY_LIMIT')
  assert.equal(detectBossBlocker('https://www.zhipin.com/job_detail/test', '该职位已停止招聘')?.code, 'JOB_UNAVAILABLE')
  assert.equal(detectBossBlocker('https://www.zhipin.com/job_detail/test', 'Java后端开发'), null)
})

test('BOSS auth evidence distinguishes logout from temporary uncertainty',()=>{
  assert.equal(classifyBossAuthEvidence({blockerCode:'LOGIN_REQUIRED'}),'signed_out')
  assert.equal(classifyBossAuthEvidence({blockerCode:'CAPTCHA_REQUIRED',cookieSignedIn:true}),'unknown')
  assert.equal(classifyBossAuthEvidence({cookieSignedIn:true,serverDefinitive:false}),'authenticated')
  assert.equal(classifyBossAuthEvidence({serverDefinitive:false}),'unknown')
  assert.equal(classifyBossAuthEvidence({serverDefinitive:true,serverSignedIn:false}),'signed_out')
})

test('BOSS tracking query parameters do not turn a signed-in page into a login page', () => {
  assert.equal(detectBossBlocker('https://www.zhipin.com/?ka=header-login', '首页 职位 推荐'), null)
})

test('BOSS selector and numeric configuration has safe fallbacks', () => {
  assert.deepEqual(selectorCandidates('.custom,.second', ['.second', '.fallback']), ['.custom', '.second', '.fallback'])
  assert.equal(boundedInteger('50', 20, 1, 30), 30)
  assert.equal(boundedInteger('bad', 20, 1, 30), 20)
  assert.equal(resolveBossCityCode('南京', {}), '101190100')
  assert.equal(resolveBossCityCode('江苏|101190000', {}), '101190000')
  assert.equal(resolveBossCityCode('自定义城市', { 自定义城市: '123456789' }), '123456789')
  assert.equal(resolveBossCityCode('未知城市', {}), null)
})

test('BOSS snapshot and rendered identity tolerate formatting but keep the approved job id exact', () => {
  assert.equal(bossJobIdFromUrl('https://www.zhipin.com/job_detail/abc_123.html?securityId=x'), 'abc_123')
  assert.equal(bossJobIdFromUrl('https://example.com/job_detail/abc_123.html'), null)
  assert.equal(isBossHomeUrl('https://www.zhipin.com/xuzhou/?seoRefer=index'), true)
  assert.equal(isBossHomeUrl('https://www.zhipin.com/web/geek/jobs'), false)
  assert.equal(matchesBossIdentity('后端开发（python）', '后端开发 Python'), true)
  assert.equal(matchesBossIdentity('南京飞谷网络科技公司', '南京飞谷网络科技有限公司', true), true)
  assert.equal(matchesBossIdentity('Java开发', '产品经理'), false)
})

test('province selections expand to BOSS city codes before discovery',()=>{
  const regions=[{code:'101190000',name:'江苏',cities:[{code:'101190100',name:'南京'},{code:'101190400',name:'苏州'}]}]
  assert.deepEqual(expandBossCitySelections(['江苏|101190000'],regions),['南京|101190100','苏州|101190400'])
  assert.deepEqual(expandBossCitySelections(['全国|100010000','南京|101190100'],regions),['全国|100010000','南京|101190100'])
})

test('rolling submission window replaces fixed batch pause',()=>{
  const now=1_000_000
  assert.equal(rollingWindowWait(now,[now-100_000,now-80_000,now-60_000,now-40_000],180_000,5),0)
  assert.equal(rollingWindowWait(now,[now-100_000,now-80_000,now-60_000,now-40_000,now-20_000],180_000,5),80_000)
})

test('submission throttling backs off quickly and recovers gradually',()=>{
  assert.equal(throttledSubmissionWindow(60_000,0),120_000)
  assert.equal(throttledSubmissionWindow(60_000,120_000),240_000)
  assert.equal(throttledSubmissionWindow(60_000,480_000),600_000)
  assert.equal(recoveredSubmissionWindow(60_000,120_000),90_000)
  assert.equal(recoveredSubmissionWindow(60_000,60_000),60_000)
})

test('BOSS daily contact quota messages are terminal limits',()=>{
  assert.equal(isBossDailyContactLimit('每天只与150位BOSS沟通，休息一下，明天再来吧~'),true)
  assert.equal(isBossDailyContactLimit('今日已与150位BOSS沟通'),true)
  assert.equal(isBossDailyContactLimit('操作过于频繁，请稍后再试'),false)
})

test('new tab protocol failures are recoverable before any platform action',()=>{
  assert.equal(isRecoverableBrowserPageError(new Error('browserContext.newPage: Protocol error (Target.createTarget): Failed to open a new tab')),true)
  assert.equal(isRecoverableBrowserPageError(new Error('BOSS_JOB_API_37')),false)
})
