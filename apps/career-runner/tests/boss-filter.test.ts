import assert from 'node:assert/strict'
import { test } from 'node:test'
import { bossCandidateLimit, bossDiscoveryProgress, bossExperienceRisk, bossPageStopReason, bossSearchQueries, buildBossDiscoverySchedule, buildBossQuerySchedule, filterBossJobs, needsBossJobDetail, selectBalancedBossJobs, summarizeBossFilters } from '../src/adapters/boss-filter.js'
import type { NormalizedJob, SearchSpec } from '../src/domain.js'
import { SearchSpecSchema } from '../src/domain.js'

const base:SearchSpec={keyword:'Java',cities:['南京'],maxPages:1,salary:'8-15K',experience:'',degree:'',
  includeKeywords:['spring','redis'],minKeywordMatch:2,excludeKeywords:['外包'],excludeCompanies:[],
  excludeHeadhunters:true,excludeContacted:true,recruiterActivity:'recent',maxJobs:10,sameCompanyDays:0,sameRecruiterDays:0,
  homeAddress:'',commuteMode:'none',maxCommuteDistanceKm:0,maxCommuteMinutes:0}
const job=(value:Partial<NormalizedJob>):NormalizedJob=>({platform:'boss',externalJobId:'1',canonicalUrl:'https://www.zhipin.com',
  company:'甲公司',role:'Java开发',location:'南京',salary:'10-14K',description:'Spring Redis',...value})

test('discovery accepts 150 returned jobs but rejects a larger target',()=>{
  assert.equal(SearchSpecSchema.parse({...base,maxJobs:150}).maxJobs,150)
  assert.throws(()=>SearchSpecSchema.parse({...base,maxJobs:151}))
})

test('BOSS filter composes keyword, salary, recruiter and company rules',()=>{
  const accepted=job({recruiterOnline:true})
  const result=filterBossJobs([
    accepted,
    job({externalJobId:'2',description:'Spring 外包 Redis',recruiterOnline:true}),
    job({externalJobId:'3',salary:'20-30K',recruiterOnline:true}),
    job({externalJobId:'4',headhunter:true,recruiterOnline:true}),
    job({externalJobId:'5',recruiterActive:'本月活跃'}),
    job({externalJobId:'6',contacted:true,recruiterOnline:true}),
  ],base)
  assert.deepEqual(result.map(item=>item.externalJobId),['1'])
})

test('BOSS discovery shares the result limit evenly across selected cities',()=>{
  const spec={...base,cities:['苏州','南京'],maxJobs:6,includeKeywords:[],minKeywordMatch:0,recruiterActivity:'any' as const}
  const suzhou=Array.from({length:8},(_,index)=>job({externalJobId:`s-${index}`,location:'苏州'}))
  const nanjing=Array.from({length:8},(_,index)=>job({externalJobId:`n-${index}`,location:'南京'}))
  const result=selectBalancedBossJobs([suzhou,nanjing],spec)
  assert.deepEqual(result.map(item=>item.externalJobId),['s-0','n-0','s-1','n-1','s-2','n-2'])
})

test('BOSS experience selections match graduate, unrestricted and year categories',()=>{
  const jobs=['应届生','经验不限','1年以内','1-3年','3-5年',''].map((experience,index)=>job({externalJobId:String(index),experience}))
  const spec={...base,includeKeywords:[],recruiterActivity:'any' as const}
  for(const [experience,ids] of [
    ['应届 / 经验不限',['0','1']],['1年以内',['0','1','2','3']],['1–3年',['0','1','2','3','4']],['经验不限',['0','1','2','3','4','5']],
  ] as const){
    assert.deepEqual(filterBossJobs(jobs,{...spec,experience}).map(item=>item.externalJobId),ids)
  }
  assert.equal(filterBossJobs([job({experience:'1至3年'})],{...spec,experience:'1–3年'}).length,1)
})

test('BOSS experience matching keeps one adjacent higher band with a risk marker',()=>{
  const spec={...base,experience:'1–3年',includeKeywords:[],recruiterActivity:'any' as const}
  const jobs=[
    job({externalJobId:'exact',experience:'1-3年'}),
    job({externalJobId:'unrestricted',experience:'经验不限'}),
    job({externalJobId:'lower',experience:'1年以内'}),
    job({externalJobId:'adjacent',experience:'3-5年'}),
    job({externalJobId:'too-high',experience:'5-10年'}),
  ]
  assert.deepEqual(filterBossJobs(jobs,spec).map(item=>item.externalJobId),['exact','unrestricted','lower','adjacent'])
  assert.equal(bossExperienceRisk('3-5年','1–3年'),'adjacent')
  assert.equal(bossExperienceRisk('5-10年','1–3年'),'mismatch')
  assert.equal(filterBossJobs(jobs,spec).find(item=>item.externalJobId==='adjacent')?.experienceRisk,'adjacent')
})

test('BOSS degree selections enforce minimum degrees and allow disabling the filter',()=>{
  const jobs=['高中','大专','本科','硕士','博士','学历不限',''].map((degree,index)=>job({externalJobId:String(index),degree}))
  const spec={...base,includeKeywords:[],recruiterActivity:'any' as const}
  for(const [degree,ids] of [
    ['本科及以上',['2','3','4']],['硕士及以上',['3','4']],['学历不限',['0','1','2','3','4','5','6']],
  ] as const){
    assert.deepEqual(filterBossJobs(jobs,{...spec,degree}).map(item=>item.externalJobId),ids)
  }
})

test('BOSS combines experience and degree filters before city allocation and counts rejections',()=>{
  const spec={...base,includeKeywords:[],recruiterActivity:'any' as const,experience:'应届 / 经验不限',degree:'本科及以上',maxJobs:2}
  const jobs=[
    job({externalJobId:'experienced',experience:'3-5年',degree:'本科'}),
    job({externalJobId:'college',experience:'应届生',degree:'大专'}),
    job({externalJobId:'graduate',experience:'应届生',degree:'本科'}),
    job({externalJobId:'unrestricted',experience:'经验不限',degree:'硕士'}),
  ]
  assert.deepEqual(selectBalancedBossJobs([jobs.slice(0,3),jobs.slice(3)],spec).map(item=>item.externalJobId),['graduate','unrestricted'])
  const stats=summarizeBossFilters(jobs,spec)
  assert.equal(stats.experienceMismatch,1)
  assert.equal(stats.degreeMismatch,1)
  assert.equal(stats.runnerEligible,2)
})

test('BOSS discovery fills another city quota when one selected city has too few jobs',()=>{
  const spec={...base,cities:['苏州','南京'],maxJobs:6,includeKeywords:[],minKeywordMatch:0,recruiterActivity:'any' as const}
  const suzhou=Array.from({length:8},(_,index)=>job({externalJobId:`s-${index}`,location:'苏州'}))
  const nanjing=[job({externalJobId:'n-0',location:'南京'})]
  const result=selectBalancedBossJobs([suzhou,nanjing],spec)
  assert.equal(result.filter(item=>item.location==='苏州').length,5)
  assert.equal(result.filter(item=>item.location==='南京').length,1)
})

test('BOSS discovery shares one total page budget across cities',()=>{
  const schedule=buildBossDiscoverySchedule(['济南','青岛','烟台'],10,5)
  assert.deepEqual(schedule,[
    {city:'济南',pageNumber:1},{city:'青岛',pageNumber:1},{city:'烟台',pageNumber:1},
    {city:'济南',pageNumber:2},{city:'青岛',pageNumber:2},
  ])
})

test('BOSS job keyword requires every term instead of accepting a partial match',()=>{
  const spec={...base,keyword:'初级java',includeKeywords:[],minKeywordMatch:0,recruiterActivity:'any' as const}
  const result=filterBossJobs([
    job({externalJobId:'java',role:'初级Java开发工程师',description:'Java Spring Boot'}),
    job({externalJobId:'bakery',role:'初级烘焙师',description:'门店制作'}),
  ],spec)
  assert.deepEqual(result.map(item=>item.externalJobId),['java'])
})

test('internship intent expands search queries but remains a required local group',()=>{
  const spec={...base,keyword:'java 实习',keywordTokens:['java','实习'],includeKeywords:[],minKeywordMatch:0,recruiterActivity:'any' as const}
  assert.deepEqual(bossSearchQueries(spec),['java 实习','java 实习生','java 校招','java 应届','java'])
  const result=filterBossJobs([
    job({externalJobId:'intern',role:'Java开发实习生',description:'Spring Boot'}),
    job({externalJobId:'campus',role:'Java校招工程师',description:'应届生可投'}),
    job({externalJobId:'normal',role:'Java开发工程师',description:'3年经验'}),
    job({externalJobId:'bakery',role:'烘焙实习生',description:'门店制作'}),
  ],spec)
  assert.deepEqual(result.map(item=>item.externalJobId),['intern','campus'])
})

test('query variants and cities share one global page budget',()=>{
  const schedule=buildBossQuerySchedule(['南京','苏州'],['java 实习','java 校招'],2,5)
  assert.equal(schedule.length,5)
  assert.deepEqual(schedule[0],{city:'南京',query:'java 实习',pageNumber:1})
  assert.deepEqual(schedule[4],{city:'南京',query:'java 实习',pageNumber:2})
})

test('a duplicate page does not hide later pages with new jobs',()=>{
  const same=['job-a','job-b']
  assert.equal(bossPageStopReason(same,same,1),null)
  assert.equal(bossPageStopReason(['job-c'],same,0),null)
  assert.equal(bossPageStopReason(same,same,2),'repeated')
  assert.equal(bossPageStopReason([],same,0),'empty')
})

test('discovery progress exposes detected candidates and both limits',()=>{
  assert.deepEqual(bossDiscoveryProgress(7,30,42,150),{pagesCompleted:7,pageLimit:30,detectedCount:42,candidateLimit:150})
})

test('restricted discovery can inspect later pages without exceeding the candidate safety limit',()=>{
  assert.equal(bossCandidateLimit(base,30,15),150)
  assert.equal(bossCandidateLimit({...base,experience:'经验不限',degree:'学历不限'},30,15),150)
  assert.equal(bossCandidateLimit({...base,experience:'1年以内'},30,15),450)
  assert.equal(bossCandidateLimit({...base,degree:'本科及以上'},30,15),450)
  assert.equal(bossCandidateLimit({...base,degree:'本科及以上'},100,30),1000)
  assert.equal(bossCandidateLimit({...base,degree:'本科及以上',resumeCandidateLimit:450},10,15),450)
})

test('only internship candidates missing list evidence require detail enrichment',()=>{
  const spec={...base,keyword:'java 实习',keywordTokens:['java','实习']}
  assert.equal(needsBossJobDetail(job({role:'Java实习生',description:'Spring'}),spec),false)
  assert.equal(needsBossJobDetail(job({role:'后端实习生',description:'Spring Boot'}),spec),true)
})

test('custom keyword groups use OR within groups and AND across groups',()=>{
  const spec={...base,keyword:'Java',includeKeywords:[],minKeywordMatch:0,recruiterActivity:'any' as const,keywordGroups:[
    {name:'技术组',terms:['Java','Spring'],minMatch:1},
    {name:'类型组',terms:['实习','校招','应届'],minMatch:1},
  ]}
  const result=filterBossJobs([
    job({externalJobId:'ok',role:'后端实习生',description:'Spring Boot'}),
    job({externalJobId:'formal',role:'Java开发',description:'3年经验'}),
    job({externalJobId:'wrong',role:'市场实习生',description:'销售'}),
  ],spec)
  assert.deepEqual(result.map(item=>item.externalJobId),['ok'])
})
