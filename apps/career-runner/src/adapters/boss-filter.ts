import type { NormalizedJob, SearchSpec } from '../domain.js'

export function buildBossDiscoverySchedule(cities:string[],maxPages:number,maxTotalPages:number):Array<{city:string;pageNumber:number}>{
  const schedule:Array<{city:string;pageNumber:number}>=[]
  const pageLimit=Math.max(1,maxPages)
  const totalLimit=Math.max(1,maxTotalPages)
  for(let pageNumber=1;pageNumber<=pageLimit&&schedule.length<totalLimit;pageNumber++){
    for(const city of cities){
      if(schedule.length>=totalLimit)break
      schedule.push({city,pageNumber})
    }
  }
  return schedule
}

function normalized(values:string[]|undefined):string[]{
  return (values??[]).map(value=>value.trim().toLowerCase()).filter(Boolean)
}

function keywordTokens(spec:SearchSpec):string[]{
  const source=spec.keywordTokens?.length?spec.keywordTokens:[spec.keyword]
  return normalized(source.flatMap(value=>value.match(/[A-Za-z0-9+#.-]+|[\u4e00-\u9fff]{2,}/g)??[value]))
}

const CAREER_STAGE_ALIASES:Record<string,string[]>={
  '实习':['实习','实习生','校招','应届'],
  '实习生':['实习','实习生','校招','应届'],
  '校招':['校招','应届','实习','实习生'],
  '应届':['应届','校招','实习','实习生'],
}

export function bossKeywordGroups(spec:SearchSpec):string[][]{
  if(spec.keywordGroups?.length)return spec.keywordGroups.map(group=>normalized(group.terms))
  return keywordTokens(spec).map(term=>CAREER_STAGE_ALIASES[term]??[term])
}

export function hasCareerStageIntent(spec:SearchSpec):boolean{
  return keywordTokens(spec).some(term=>Boolean(CAREER_STAGE_ALIASES[term]))
}

export function needsBossJobDetail(job:NormalizedJob,spec:SearchSpec):boolean{
  const text=`${job.role} ${job.description}`.toLowerCase()
  return bossKeywordGroups(spec).some(group=>!group.some(term=>text.includes(term)))
}

export function bossSearchQueries(spec:SearchSpec):string[]{
  const tokens=keywordTokens(spec)
  const technical=tokens.filter(term=>!CAREER_STAGE_ALIASES[term])
  const stages=tokens.filter(term=>CAREER_STAGE_ALIASES[term])
  if(!stages.length)return [tokens.join(' ')||spec.keyword]
  const technicalText=technical.join(' ')
  const stageAliases=[...new Set(stages.flatMap(term=>CAREER_STAGE_ALIASES[term]!))]
  return [...new Set([
    tokens.join(' '),
    ...stageAliases.map(stage=>[technicalText,stage].filter(Boolean).join(' ')),
    technicalText,
  ].filter(Boolean))]
}

export function buildBossQuerySchedule(cities:string[],queries:string[],maxPages:number,maxTotalPages:number):Array<{city:string;query:string;pageNumber:number}>{
  const schedule:Array<{city:string;query:string;pageNumber:number}>=[]
  for(let pageNumber=1;pageNumber<=Math.max(1,maxPages)&&schedule.length<Math.max(1,maxTotalPages);pageNumber++){
    for(const query of queries)for(const city of cities){
      if(schedule.length>=maxTotalPages)break
      schedule.push({city,query,pageNumber})
    }
  }
  return schedule
}

export function bossPageStopReason(currentIds:string[],previousIds:string[]|undefined,repeatedPages:number):'empty'|'repeated'|null{
  if(currentIds.length===0)return'empty'
  if(repeatedPages>=2&&previousIds?.length===currentIds.length&&previousIds.every((id,index)=>id===currentIds[index]))return'repeated'
  return null
}

export function bossDiscoveryProgress(pagesCompleted:number,pageLimit:number,detectedCount:number,candidateLimit:number){
  return{pagesCompleted:Math.max(0,pagesCompleted),pageLimit:Math.max(1,pageLimit),detectedCount:Math.max(0,detectedCount),candidateLimit:Math.max(1,candidateLimit)}
}

export function bossCandidateLimit(spec:SearchSpec,pageLimit:number,pageSize:number):number{
  if(spec.resumeCandidateLimit!==undefined)return spec.resumeCandidateLimit
  const experience=spec.experience?.trim()??''
  const degree=spec.degree?.trim()??''
  const filtered=Boolean(experience&&!['不限','经验不限'].includes(experience))||Boolean(degree&&!['不限','学历不限'].includes(degree))
  // Restrictive filters need to inspect more than the first 150 unfiltered jobs.
  return filtered?Math.min(1000,Math.max(150,pageLimit*pageSize)):150
}

function salaryThousands(value:string):[number,number]|null{
  const match=value.replace(/\s/g,'').match(/(\d+(?:\.\d+)?)(?:[-~—至](\d+(?:\.\d+)?))?K/i)
  if(!match)return null
  const min=Number(match[1]),max=Number(match[2]??match[1])
  return Number.isFinite(min)&&Number.isFinite(max)?[min,max]:null
}

function experienceCategory(value:string):string{
  const text=value.replace(/\s/g,'').replace(/[–—~至]/g,'-')
  if(/经验不限|不限经验/.test(text))return'any'
  if(/应届|校招/.test(text))return'graduate'
  if(/1年以[内下]|不到1年/.test(text))return'under-one'
  const range=text.match(/^(\d+)-(\d+)年/)
  return range?`${range[1]}-${range[2]}`:text
}

function experienceBand(value:string):number|null{
  const category=experienceCategory(value)
  if(category==='graduate'||category==='under-one')return 0
  if(category==='any')return null
  const start=Number(category.match(/^(\d+)-/)?.[1])
  if(/10年以上/.test(category))return 4
  return Number.isFinite(start)?start<=1?1:start<=3?2:start<=5?3:4:null
}

export type BossExperienceRisk='exact'|'unrestricted'|'lower'|'adjacent'|'mismatch'

export function bossExperienceRisk(actual:string,expected:string):BossExperienceRisk{
  const normalizedExpected=expected.replace(/\s/g,'')
  if(!normalizedExpected||normalizedExpected==='不限'||normalizedExpected==='经验不限')return'exact'
  const category=experienceCategory(actual)
  if(category==='any')return'unrestricted'
  if(normalizedExpected==='应届/经验不限')return category==='graduate'?'exact':'mismatch'
  const actualBand=experienceBand(actual),expectedBand=experienceBand(expected)
  if(actualBand===null||expectedBand===null)return'mismatch'
  if(actualBand<expectedBand)return'lower'
  if(actualBand===expectedBand)return'exact'
  return actualBand===expectedBand+1?'adjacent':'mismatch'
}

function matchesExperience(actual:string,expected:string):boolean{
  return bossExperienceRisk(actual,expected)!=='mismatch'
}

export function filterBossJobs(jobs:NormalizedJob[],spec:SearchSpec):NormalizedJob[]{
  return jobs
    .filter(job=>bossJobFilterReason(job,spec)===null)
    .map(job=>{
      const experienceRisk=bossExperienceRisk(job.experience??'',spec.experience??'')
      return {...job,experienceRisk:experienceRisk==='mismatch'?undefined:experienceRisk}
    })
    .slice(0,Math.max(1,spec.maxJobs??50))
}

function degreeRank(value:string):number{
  if(/博士/.test(value))return 4
  if(/硕士|研究生/.test(value))return 3
  if(/本科|学士/.test(value))return 2
  if(/大专|专科/.test(value))return 1
  return 0
}

function matchesDegree(actual:string,expected:string):boolean{
  if(!expected.trim()||expected.trim()==='不限'||expected.trim()==='学历不限')return true
  const minimum=degreeRank(expected)
  return minimum>0?degreeRank(actual)>=minimum:actual.trim()===expected.trim()
}

export type BossFilterReason='keywordMismatch'|'requiredKeywordMismatch'|'excludedKeyword'|'excludedCompany'|'headhunter'|'contacted'|'recruiterActivity'|'salaryMismatch'|'experienceMismatch'|'degreeMismatch'

export function bossJobFilterReason(job:NormalizedJob,spec:SearchSpec):BossFilterReason|null{
  const required=normalized(spec.includeKeywords)
  const requiredJobGroups=bossKeywordGroups(spec)
  const excluded=normalized(spec.excludeKeywords)
  const excludedCompanies=normalized(spec.excludeCompanies)
  const requiredCount=Math.min(required.length,Math.max(0,spec.minKeywordMatch??0))
  const expectedSalary=salaryThousands(spec.salary??'')
  const text=`${job.role} ${job.description}`.toLowerCase()
  const company=job.company.toLowerCase()
  if(requiredJobGroups.some((group,index)=>group.filter(term=>text.includes(term)).length<Math.min(group.length,spec.keywordGroups?.[index]?.minMatch??1)))return'keywordMismatch'
  if(requiredCount&&required.filter(keyword=>text.includes(keyword)).length<requiredCount)return'requiredKeywordMismatch'
  if(excluded.some(keyword=>text.includes(keyword)))return'excludedKeyword'
  if(excludedCompanies.some(keyword=>company.includes(keyword)))return'excludedCompany'
  if(spec.excludeHeadhunters&&job.headhunter)return'headhunter'
  if(spec.excludeContacted&&job.contacted)return'contacted'
  if(!matchesExperience(job.experience??'',spec.experience??''))return'experienceMismatch'
  if(!matchesDegree(job.degree??'',spec.degree??''))return'degreeMismatch'
  if(spec.recruiterActivity==='online'&&!job.recruiterOnline)return'recruiterActivity'
  if(spec.recruiterActivity==='recent'&&!job.recruiterOnline&&!/(刚刚|今日|3日|本周)/.test(job.recruiterActive??''))return'recruiterActivity'
  if(expectedSalary){
    const actual=salaryThousands(job.salary)
    if(actual&&(actual[1]<expectedSalary[0]||actual[0]>expectedSalary[1]))return'salaryMismatch'
  }
  return null
}

export function summarizeBossFilters(jobs:NormalizedJob[],spec:SearchSpec):Record<string,number>{
  const unique=[...new Map(jobs.map(job=>[job.externalJobId,job])).values()]
  const result:Record<string,number>={runnerCandidates:unique.length,keywordMismatch:0,requiredKeywordMismatch:0,excludedKeyword:0,excludedCompany:0,headhunter:0,contactedByPlatform:0,recruiterActivity:0,salaryMismatch:0,experienceMismatch:0,degreeMismatch:0,runnerEligible:0}
  for(const job of unique){const reason=bossJobFilterReason(job,spec);if(reason===null)result.runnerEligible=(result.runnerEligible??0)+1;else if(reason==='contacted')result.contactedByPlatform=(result.contactedByPlatform??0)+1;else result[reason]=(result[reason]??0)+1}
  return result
}

export function selectBalancedBossJobs(cityJobs:NormalizedJob[][],spec:SearchSpec,startIndex=0):NormalizedJob[]{
  const maxJobs=Math.max(1,spec.maxJobs??50)
  const groups=cityJobs.map(jobs=>filterBossJobs(jobs,{...spec,maxJobs:Math.max(maxJobs,jobs.length)}))
  if(groups.length<=1)return (groups[0]??[]).slice(0,maxJobs)
  const offsets=groups.map(()=>0),result:NormalizedJob[]=[]
  const seen=new Set<string>()
  let remaining=groups.reduce((sum,group)=>sum+group.length,0)
  const first=((startIndex%groups.length)+groups.length)%groups.length
  while(result.length<maxJobs&&remaining>0){
    let added=false
    for(let step=0;step<groups.length&&result.length<maxJobs;step++){
      const groupIndex=(first+step)%groups.length
      const group=groups[groupIndex]!
      while(offsets[groupIndex]!<group.length){
        const job=group[offsets[groupIndex]!]!
        offsets[groupIndex]=(offsets[groupIndex]??0)+1
        remaining--
        if(seen.has(job.externalJobId))continue
        seen.add(job.externalJobId);result.push(job);added=true;break
      }
    }
    if(!added)break
  }
  return result
}
