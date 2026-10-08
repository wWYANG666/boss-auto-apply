<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowRight,
  Banknote,
  Check,
  ChevronDown,
  CircleAlert,
  Funnel,
  GraduationCap,
  MapPin,
  Radar,
  Search,
  Save,
  Sparkles,
  Target,
  Workflow,
  X,
} from '@lucide/vue'
import PlatformBadge from '@/components/platform/PlatformBadge.vue'
import ConfirmDialog from '@/components/feedback/ConfirmDialog.vue'
import { useAutomationStore } from '@/stores/automation'
import { useWorkspaceStore } from '@/stores/workspace'
import { careerLensApi } from '@/api/careerlens'
import type { DiscoveredJob, JobGrade, JobPlatform, SearchSpec } from '@/types/automation'

const router = useRouter()
const automation = useAutomationStore()
const workspace = useWorkspaceStore()
interface SearchPreset {id:string;name:string;spec:SearchSpec;isDefault:boolean}
const presetStorageKey=()=>`careerlens:search-presets:v1:${automation.activePlatformIdentity?.id??'default'}`
function readPresets():SearchPreset[]{
 try{const value=JSON.parse(localStorage.getItem(presetStorageKey())||'[]');return Array.isArray(value)?value.filter((item):item is SearchPreset=>item&&typeof item.id==='string'&&typeof item.name==='string'&&item.spec&&typeof item.spec==='object'):[]}
 catch{return []}
}
const presets=ref<SearchPreset[]>([])
const presetsReady=computed(()=>automation.initialized)
const presetName=ref('')
const selectedPresetId=ref('')
const presetError=ref('')
watch([()=>automation.initialized,()=>automation.activePlatformIdentity?.id],()=>{
 if(!automation.initialized)return
 presets.value=readPresets();selectedPresetId.value=presets.value.find(item=>item.isDefault)?.id??''
},{immediate:true})
function persistPresets(){
 try{localStorage.setItem(presetStorageKey(),JSON.stringify(presets.value));presetError.value=''}
 catch{presetError.value='浏览器存储不可用，预设未保存。'}
}
function savePreset(){
 if(!presetsReady.value)return
 const name=presetName.value.trim()
 if(!name){presetError.value='请先输入预设名称';return}
 if(presets.value.some(item=>item.name===name)){presetError.value='名称已存在，请换一个名称或复制现有预设';return}
 const preset={id:crypto.randomUUID(),name,spec:JSON.parse(JSON.stringify(automation.searchSpec)) as SearchSpec,isDefault:presets.value.length===0}
 presets.value.push(preset);selectedPresetId.value=preset.id;persistPresets();presetName.value=''
}
function applyPreset(){
 const preset=presets.value.find(item=>item.id===selectedPresetId.value)
 if(!preset)return
 automation.applySearchSpec(preset.spec)
 workspace.addToast('已切换筛选预设',`${preset.name} · 运行前请再次核对城市和条件。`,'info')
}
function copyPreset(){
 const source=presets.value.find(item=>item.id===selectedPresetId.value)
 if(!source)return
 const copy={...source,id:crypto.randomUUID(),name:`${source.name} 副本`,spec:JSON.parse(JSON.stringify(source.spec)) as SearchSpec,isDefault:false}
 presets.value.push(copy);selectedPresetId.value=copy.id;persistPresets()
}
function setDefaultPreset(){
 presets.value.forEach(item=>{item.isDefault=item.id===selectedPresetId.value});persistPresets()
}
function deletePreset(){
 presets.value=presets.value.filter(item=>item.id!==selectedPresetId.value)
 selectedPresetId.value='';persistPresets()
}
const conditionSummary=computed(()=>{
 const spec=automation.searchSpec
 const headline=[spec.keywordTokens.join(' + ')||'未设置岗位关键词',spec.cities.length?spec.cities.slice(0,3).map(city=>cityLabel(city)).join(' / ')+(spec.cities.length>3?` 等${spec.cities.length}地`:''):'未选择城市',spec.salary||'薪资不限'].join(' · ')
 const exclusions=[...spec.excludeKeywords,...spec.excludeCompanies]
 return {headline,detail:`最低 ${spec.minimumScore} 分${exclusions.length?` · 排除 ${exclusions.slice(0,3).join('、')}${exclusions.length>3?' 等':''}`:''}${spec.sameCompanyDays?` · 公司冷却 ${spec.sameCompanyDays} 天`:''}`}
})
const platformFilters = ref<JobPlatform[]>(['boss'])
const connectedPlatforms = computed(() => automation.platformAccounts
  .filter(account => account.platform === 'boss' && account.status === 'connected')
  .map(account => account.platform))
watch(connectedPlatforms, (connected) => {
  const retained = platformFilters.value.filter(platform => connected.includes(platform))
  platformFilters.value = retained.length ? retained : [...connected]
}, { immediate: true })
const gradeFilter = ref<'all' | JobGrade>('all')
const searchText = ref('')
const jobPage=ref(1)
const pageSize=10
const cityDraft = ref('')
const regionDraft=ref('')
const regionCityDraft=ref('')
const cityDirectory=ref<Array<{code:string;name:string;cities:Array<{code:string;name:string}>}>>([])
const regionCities=computed(()=>cityDirectory.value.find(region=>region.code===regionDraft.value)?.cities??[])
const cityLabel=(value:string)=>value.split('|')[0]
const citySearchStats=computed(()=>{
 const cities=new Map<string,{city:string;pages:number;returned:number;added:number;observed:string[]}>()
 for(const page of automation.discoveryFilterStats.pages){
   const entry=cities.get(page.city)??{city:cityLabel(page.city),pages:0,returned:0,added:0,observed:[]}
   entry.pages++;entry.returned+=page.returned;entry.added+=page.added
   for(const observed of page.observedCities)if(observed&&!entry.observed.includes(observed))entry.observed.push(observed)
   cities.set(page.city,entry)
 }
 return [...cities.values()]
})
function addCity(){ const city=cityDraft.value.trim(); if(city && !automation.searchSpec.cities.includes(city)) automation.searchSpec.cities.push(city); cityDraft.value='' }
function addRegion(){
 const region=cityDirectory.value.find(item=>item.code===regionDraft.value);if(!region)return
 const additions=region.cities.map(city=>`${city.name}|${city.code}`)
 const existing=automation.searchSpec.cities.filter(value=>value!==`${region.name}|${region.code}`)
 const merged=[...new Set([...existing,...additions])]
 if(merged.length>40){workspace.addToast('地区数量超过上限',`当前配置已有${existing.length}个地区，无法完整加入${region.name}全部${additions.length}个地区。请先清空其他地区。`,'warning');return}
 automation.searchSpec.cities=merged
 workspace.addToast(`已选择${region.name}全部地区`,`${additions.length}个BOSS城市/地区已加入。`,'success')
}
function selectRegion(){regionCityDraft.value='';addRegion()}
function clearCities(){automation.searchSpec.cities=[];regionCityDraft.value=''}
function addRegionCity(){
 const city=regionCities.value.find(item=>item.code===regionCityDraft.value);if(!city)return
 const value=`${city.name}|${city.code}`
 if(!automation.searchSpec.cities.includes(value))automation.searchSpec.cities.push(value)
}
function addNationwide(){if(!automation.searchSpec.cities.includes('全国|100010000'))automation.searchSpec.cities.push('全国|100010000')}
onMounted(async()=>{cityDirectory.value=await careerLensApi.bossCities().catch(()=>[])})
const jobKeywordDraft=ref('')
const jobKeywordHint=computed(()=>automation.searchSpec.keywordTokens.some(item=>['实习','实习生','校招','应届'].includes(item.toLowerCase()))
  ?'求职类型会自动扩展为实习、实习生、校招、应届；技术关键词仍必须命中'
  :'每个关键词都必须出现在职位名称或描述中，避免误投无关岗位')
function commitJobKeywords(){
  const parts=jobKeywordDraft.value.split(/[,，]/).flatMap(item=>item.trim().match(/[A-Za-z0-9+#.-]+|[\u4e00-\u9fff]{2,}/g)??[]).filter(Boolean)
  for(const value of parts)if(!automation.searchSpec.keywordTokens.includes(value))automation.searchSpec.keywordTokens.push(value)
  automation.searchSpec.keyword=automation.searchSpec.keywordTokens.join(' ')
  jobKeywordDraft.value=''
}
function handleJobKeywordKey(event:KeyboardEvent){
  if(event.key===','||event.key==='，'){event.preventDefault();commitJobKeywords()}
}
function removeJobKeyword(value:string){
  automation.searchSpec.keywordTokens=automation.searchSpec.keywordTokens.filter(item=>item!==value)
  automation.searchSpec.keyword=automation.searchSpec.keywordTokens.join(' ')
}
function addKeywordGroup(){automation.searchSpec.keywordGroups.push({name:`词组${automation.searchSpec.keywordGroups.length+1}`,terms:[],minMatch:1})}
function updateKeywordGroupTerms(index:number,value:string){
  const group=automation.searchSpec.keywordGroups[index];if(!group)return
  group.terms=[...new Set(value.split(/[,，]/).map(item=>item.trim()).filter(Boolean))]
  group.minMatch=Math.max(1,Math.min(group.minMatch,Math.max(1,group.terms.length)))
}
function removeKeywordGroup(index:number){automation.searchSpec.keywordGroups.splice(index,1)}
const includeKeywordDraft=ref('')
const excludeKeywordDraft=ref('')
const excludeCompanyDraft=ref('')
type TokenField='include'|'exclude'|'company'
function tokenValues(field:TokenField){
  return field==='include'?automation.searchSpec.includeKeywords:field==='exclude'?automation.searchSpec.excludeKeywords:automation.searchSpec.excludeCompanies
}
function tokenDraft(field:TokenField){
  return field==='include'?includeKeywordDraft:field==='exclude'?excludeKeywordDraft:excludeCompanyDraft
}
function commitTokens(field:TokenField){
  const draft=tokenDraft(field)
  const values=tokenValues(field)
  const parts=draft.value.split(/[,，]/).map(item=>item.trim()).filter(Boolean)
  for(const value of parts)if(!values.includes(value))values.push(value)
  draft.value=''
  if(field==='include'&&values.length&&automation.searchSpec.minKeywordMatch===0)automation.searchSpec.minKeywordMatch=1
  if(field==='include')automation.searchSpec.minKeywordMatch=Math.min(automation.searchSpec.minKeywordMatch,values.length)
}
function removeToken(field:TokenField,value:string){
  const values=tokenValues(field)
  if(field==='include')automation.searchSpec.includeKeywords=values.filter(item=>item!==value)
  else if(field==='exclude')automation.searchSpec.excludeKeywords=values.filter(item=>item!==value)
  else automation.searchSpec.excludeCompanies=values.filter(item=>item!==value)
  if(field==='include')automation.searchSpec.minKeywordMatch=Math.min(automation.searchSpec.minKeywordMatch,automation.searchSpec.includeKeywords.length)
}
function handleTokenKey(event:KeyboardEvent,field:TokenField){
  if(event.key===','||event.key==='，'){event.preventDefault();commitTokens(field)}
}
const sweepMode=ref<'off'|'select'|'deselect'>('off')
let sweepActive=false
let sweepChanged=0
let sweepSkipped=0
const sweptIds=new Set<string>()
let longPressTimer:number|undefined

const visibleJobs = computed(() => automation.discoveredJobs.filter((job) => {
  const platformMatch = platformFilters.value.includes(job.platform)
  const gradeMatch = gradeFilter.value === 'all' || job.grade === gradeFilter.value
  const keyword = searchText.value.trim().toLowerCase()
  const textMatch = !keyword || `${job.company} ${job.role} ${job.location}`.toLowerCase().includes(keyword)
  return platformMatch && gradeMatch && textMatch
}))
const selectableVisibleJobs=computed(()=>visibleJobs.value.filter(job=>canSelect(job)))
const allVisibleSelected=computed(()=>selectableVisibleJobs.value.length>0&&selectableVisibleJobs.value.every(job=>job.selected))
const jobPageCount=computed(()=>Math.max(1,Math.ceil(visibleJobs.value.length/pageSize)))
const pagedJobs=computed(()=>visibleJobs.value.slice((jobPage.value-1)*pageSize,jobPage.value*pageSize))
watch([searchText,gradeFilter,platformFilters],()=>{jobPage.value=1})
watch(jobPageCount,count=>{if(jobPage.value>count)jobPage.value=count})

function togglePlatform(platform: JobPlatform) {
  if (!connectedPlatforms.value.includes(platform)) return
  if (platformFilters.value.includes(platform)) {
    if (platformFilters.value.length === 1) return
    platformFilters.value = platformFilters.value.filter((item) => item !== platform)
  } else {
    platformFilters.value.push(platform)
  }
}

function runDiscovery(){
  return automation.runDiscovery(platformFilters.value.filter(platform => connectedPlatforms.value.includes(platform)))
}
function handleDiscoveryAction(){
  if(automation.runner.globalPaused){router.push('/automation');return}
  return runDiscovery()
}
async function startOneStop(){
  if(!automation.autoOneStop){oneStopConfirmOpen.value=true;return}
  await automation.runOneStop(platformFilters.value.filter(platform => connectedPlatforms.value.includes(platform)))
}
const oneStopConfirmOpen=ref(false)
const startingOneStop=ref(false)
async function confirmOneStop(){startingOneStop.value=true;try{await automation.runOneStop(platformFilters.value.filter(platform => connectedPlatforms.value.includes(platform)));oneStopConfirmOpen.value=false}finally{startingOneStop.value=false}}

function gradeCopy(grade: JobGrade) {
  return grade === 'A' ? '优先' : grade === 'B' ? '可考虑' : '不推荐'
}

function canSelect(job: DiscoveredJob) {
  return !job.alreadyTracked && job.hardConflicts.length === 0
}

function applySweep(job:DiscoveredJob){
  if(sweptIds.has(job.id))return
  sweptIds.add(job.id)
  const changed=automation.setJobSelection(job.id,sweepMode.value==='select',true)
  if(changed)sweepChanged++
  else if(sweepMode.value==='select'&&!job.selected)sweepSkipped++
}
function beginSweep(event:PointerEvent,job:DiscoveredJob){
  if(event.button!==0)return
  window.clearTimeout(longPressTimer)
  const startX=event.clientX,startY=event.clientY
  const begin=()=>{
    sweepMode.value=job.selected?'deselect':'select'
    sweepActive=true;sweepChanged=0;sweepSkipped=0;sweptIds.clear()
    applySweep(job)
    window.addEventListener('pointermove',continueSweep,{passive:false})
    window.addEventListener('pointerup',endSweep,{once:true})
    window.addEventListener('pointercancel',endSweep,{once:true})
  }
  longPressTimer=window.setTimeout(begin,350)
  const cancelBeforeLongPress=(move:PointerEvent)=>{
    if(!sweepActive&&Math.hypot(move.clientX-startX,move.clientY-startY)>8){
      window.clearTimeout(longPressTimer);window.removeEventListener('pointermove',cancelBeforeLongPress)
    }
  }
  const releaseBeforeLongPress=()=>{
    if(!sweepActive)window.clearTimeout(longPressTimer)
    window.removeEventListener('pointermove',cancelBeforeLongPress)
    window.removeEventListener('pointerup',releaseBeforeLongPress)
    window.removeEventListener('pointercancel',releaseBeforeLongPress)
  }
  window.addEventListener('pointermove',cancelBeforeLongPress,{passive:true})
  window.addEventListener('pointerup',releaseBeforeLongPress,{once:true})
  window.addEventListener('pointercancel',releaseBeforeLongPress,{once:true})
}
function continueSweep(event:PointerEvent){
  if(!sweepActive)return
  event.preventDefault()
  const element=document.elementFromPoint(event.clientX,event.clientY)?.closest<HTMLElement>('[data-job-id]')
  if(!element)return
  const job=visibleJobs.value.find(item=>item.id===element.dataset.jobId)
  if(job)applySweep(job)
}
function endSweep(){
  window.clearTimeout(longPressTimer)
  if(!sweepActive)return
  sweepActive=false
  window.removeEventListener('pointermove',continueSweep)
  window.removeEventListener('pointerup',endSweep)
  window.removeEventListener('pointercancel',endSweep)
  const action=sweepMode.value==='select'?'选中':'取消'
  sweepMode.value='off'
  workspace.addToast(`滑动${action}完成`,`${sweepChanged} 个岗位已${action}${sweepSkipped?`，${sweepSkipped} 个因限制跳过`:''}。`,'info')
}
onBeforeUnmount(()=>{window.clearTimeout(longPressTimer);endSweep()})

async function addToReview() {
  const count = await automation.queueSelectedForReview()
  if (count) router.push('/review-queue')
  else workspace.addToast('还没有选择岗位', '勾选至少一个岗位后再加入审核。', 'warning')
}
</script>

<template>
  <div class="page discovery-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">跨平台机会雷达</p>
        <h1>职位发现</h1>
        <p class="page-subtitle">从已连接的平台发现岗位，并用主简历 v{{ workspace.resumeVersion }} 自动生成可解释匹配。</p>
      </div>
      <div class="page-header__actions">
        <button class="button button--secondary" type="button" @click="automation.selectRecommendedJobs"><Target :size="16" /> 选择推荐岗位</button>
        <label class="auto-run-toggle"><input type="checkbox" :checked="automation.autoOneStop" @change="automation.setAutoOneStop(($event.target as HTMLInputElement).checked)" /><span>免确认启动</span></label>
        <button class="button button--primary" type="button" :disabled="automation.busy || automation.oneStopRunning || !connectedPlatforms.length" @click="startOneStop"><Sparkles :size="16" /> {{ automation.oneStopRunning ? `第${automation.oneStopRun.discoveryRound}轮 · 第${automation.oneStopRun.groupNumber}组` : automation.oneStopRun.status==='paused' ? '继续一条龙' : '一条龙执行' }}</button><button v-if="automation.oneStopRun.status==='running'" class="button button--secondary" type="button" @click="automation.pauseOneStop">暂停并保留</button><button v-if="['running','paused'].includes(automation.oneStopRun.status)" class="button button--warning" type="button" @click="automation.stopOneStop">彻底终止</button>
        <button class="button button--primary" type="button" :disabled="automation.searchStatus === 'searching' || automation.busy || !connectedPlatforms.length" @click="handleDiscoveryAction">
          <Radar :size="17" /> {{ automation.runner.globalPaused ? '前往恢复BOSS访问' : automation.searchStatus === 'searching' ? '正在发现…' : '开始发现职位' }}
        </button>
      </div>
    </header>

    <section class="discovery-condition-summary panel" aria-label="当前筛选条件">
      <span class="discovery-condition-summary__icon"><Funnel :size="18" /></span>
      <div><small>当前筛选条件</small><strong>{{ conditionSummary.headline }}</strong><p>{{ conditionSummary.detail }}</p></div>
      <span class="discovery-condition-summary__hint">运行前请核对关键词与城市</span>
    </section>

    <section v-if="automation.oneStopRun.status!=='idle'" class="discovery-progress panel">
      <span class="discovery-progress__radar"><Workflow :size="24" /></span>
      <div><strong>一条龙{{automation.oneStopRun.status==='running'?'运行中':automation.oneStopRun.status==='paused'?'已暂停':automation.oneStopRun.status==='completed'?'已完成':'已停止'}}</strong><p>第 {{automation.oneStopRun.discoveryRound}} 轮 · 第 {{automation.oneStopRun.groupNumber}} 组 · 已处理 {{automation.oneStopRun.processedCount}} / {{automation.oneStopRun.targetCount||automation.searchSpec.maxJobs}} · 今日额度 {{automation.oneStopRun.dailyUsed}} / {{automation.oneStopRun.dailyLimit}}，剩余 {{automation.oneStopRun.dailyRemaining}} · 成功 {{automation.oneStopRun.succeededCount}} · 失败 {{automation.oneStopRun.failedCount}} · 取消/预检 {{automation.oneStopRun.cancelledCount}}<span v-if="automation.oneStopRun.attentionCount"> · 待处理 {{automation.oneStopRun.attentionCount}}</span><span v-if="automation.oneStopRun.lastError"> · {{automation.oneStopRun.lastError}}</span></p></div>
    </section>

    <section class="discovery-layout">
      <aside class="discovery-filters panel">
        <div class="panel__header panel__header--compact">
          <div><span class="eyebrow">搜索条件</span><h2>你的目标</h2></div>
          <Funnel :size="18" class="muted-icon" />
        </div>
        <div class="discovery-config-actions"><button class="button button--secondary button--small" type="button" @click="automation.saveSearchConfig"><Save :size="14" /> 保存配置</button><button class="button button--quiet button--small" type="button" @click="automation.resetSearchConfig">恢复默认</button></div>
        <div class="discovery-presets">
          <label class="field"><span>我的筛选预设</span><select v-model="selectedPresetId" aria-label="选择筛选预设" :disabled="!presetsReady"><option value="">{{presetsReady?'选择预设':'正在载入账号配置…'}}</option><option v-for="preset in presets" :key="preset.id" :value="preset.id">{{preset.name}}{{preset.isDefault?' · 默认':''}}</option></select></label>
          <div class="discovery-presets__actions"><button class="button button--secondary button--small" type="button" :disabled="!selectedPresetId" @click="applyPreset">应用</button><button class="button button--quiet button--small" type="button" :disabled="!selectedPresetId" @click="copyPreset">复制</button><button class="button button--quiet button--small" type="button" :disabled="!selectedPresetId" @click="setDefaultPreset">设为默认</button><button class="button button--quiet button--small" type="button" :disabled="!selectedPresetId" @click="deletePreset">删除</button></div>
          <form class="discovery-presets__save" @submit.prevent="savePreset"><input v-model="presetName" maxlength="30" aria-label="新预设名称" :disabled="!presetsReady" placeholder="例如：Java 实习 · 南京" /><button class="button button--secondary button--small" type="submit" :disabled="!presetsReady">保存为预设</button></form>
          <small>默认预设会预选，点击“应用”才会替换当前条件。仅保存在此浏览器，并按当前 BOSS 账号配置隔离。</small><p v-if="presetError" class="field-error" role="alert">{{presetError}}</p>
        </div>

        <div class="platform-picker">
          <span class="filter-label">搜索平台</span>
          <button
            v-for="account in automation.platformAccounts.filter(item => item.platform === 'boss')"
            :key="account.id"
            class="platform-pick-card"
            :class="{ 'platform-pick-card--active': platformFilters.includes(account.platform) }"
            type="button"
            @click="togglePlatform(account.platform)"
          >
            <PlatformBadge :platform="account.platform" />
            <span><strong>{{ account.status === 'connected' ? '已连接' : '未连接' }}</strong><small>{{ account.connectionType === 'MCP' ? 'MCP 接口' : '本地浏览器' }}</small></span>
            <span class="platform-pick-card__check"><Check v-if="platformFilters.includes(account.platform)" :size="13" /></span>
          </button>
        </div>

        <div class="discovery-filter-fields">
          <label class="field"><span>岗位关键词</span><div class="filter-token-input"><span v-for="item in automation.searchSpec.keywordTokens" :key="item">{{ item }}<button type="button" :aria-label="`删除${item}`" @click="removeJobKeyword(item)"><X :size="11" /></button></span><input v-model="jobKeywordDraft" aria-label="添加岗位关键词" placeholder="输入后按 Enter 添加" @keydown.enter.prevent="commitJobKeywords" @keydown="handleJobKeywordKey" @blur="commitJobKeywords" /></div><small>{{ jobKeywordHint }}</small></label>
          <label class="field"><span>省份（选择后自动加入全部地区）</span><span class="select-shell"><select v-model="regionDraft" @change="selectRegion" ><option value="">选择省份</option><option v-for="region in cityDirectory" :key="region.code" :value="region.code">{{region.name}}</option></select><ChevronDown :size="15" /></span></label>
          <div class="region-actions"><button class="button button--secondary button--small" type="button" @click="addNationwide">全国</button><button class="button button--quiet button--small" type="button" :disabled="!automation.searchSpec.cities.length" @click="clearCities">清空地区</button></div>
          <label v-if="regionDraft" class="field"><span>省内城市</span><span class="select-shell"><select v-model="regionCityDraft" @change="addRegionCity"><option value="">选择城市</option><option v-for="city in regionCities" :key="city.code" :value="city.code">{{city.name}}</option></select><ChevronDown :size="15" /></span></label>
          <label class="field"><span>已选地区</span><div class="filter-token-input"><span v-for="city in automation.searchSpec.cities" :key="city">{{ cityLabel(city) }}<button type="button" @click="automation.searchSpec.cities = automation.searchSpec.cities.filter((item) => item !== city)"><X :size="11" /></button></span><input v-model="cityDraft" placeholder="也可手动输入城市后按 Enter" @keydown.enter.prevent="addCity" @blur="addCity" /></div></label>
          <label class="field"><span>薪资范围</span><span class="input-with-icon"><Banknote :size="15" /><input v-model="automation.searchSpec.salary" /></span></label>
          <label class="field"><span>经验要求</span><span class="select-shell"><select v-model="automation.searchSpec.experience"><option>应届 / 经验不限</option><option>经验不限</option><option>1年以内</option><option>1–3年</option></select><ChevronDown :size="15" /></span></label>
          <label class="field"><span>学历要求</span><span class="select-shell"><select v-model="automation.searchSpec.degree"><option>本科及以上</option><option>学历不限</option><option>硕士及以上</option></select><ChevronDown :size="15" /></span></label>
          <label class="range-field"><span><strong>最低匹配分</strong><b>{{ automation.searchSpec.minimumScore }}</b></span><input v-model.number="automation.searchSpec.minimumScore" type="range" min="0" max="100" step="1" /></label>
          <details class="filter-advanced discovery-filter-group">
            <summary><span>内容筛选</span><small>必须包含、排除词和关键词组</small><ChevronDown :size="15" /></summary>
            <div class="filter-advanced__body">
          <div class="keyword-groups-editor"><div class="filter-label"><span>高级关键词组（可选）</span><button class="button button--quiet button--small" type="button" @click="addKeywordGroup">添加词组</button></div><p class="field-help">组内满足指定数量，组与组之间必须同时满足；配置后替代岗位关键词的严格本地校验。</p><article v-for="(group,index) in automation.searchSpec.keywordGroups" :key="index" class="keyword-group-row"><input v-model="group.name" aria-label="关键词组名称" placeholder="例如：技术组"/><input :value="group.terms.join('，')" aria-label="关键词组词条" placeholder="Java，Spring，Spring Boot" @change="updateKeywordGroupTerms(index,($event.target as HTMLInputElement).value)"/><label><span>至少命中</span><input v-model.number="group.minMatch" type="number" min="1" :max="Math.max(1,group.terms.length)"/></label><button class="icon-button icon-button--quiet" type="button" aria-label="删除关键词组" @click="removeKeywordGroup(index)"><X :size="14"/></button></article></div>
          <label class="field"><span>必须包含关键词</span><div class="filter-token-input"><span v-for="item in automation.searchSpec.includeKeywords" :key="item">{{ item }}<button type="button" :aria-label="`删除${item}`" @click="removeToken('include',item)"><X :size="11" /></button></span><input v-model="includeKeywordDraft" placeholder="输入后按 Enter 添加" @keydown.enter.prevent="commitTokens('include')" @keydown="handleTokenKey($event,'include')" @blur="commitTokens('include')" /></div></label>
          <label v-if="automation.searchSpec.includeKeywords.length" class="field"><span>至少命中数量</span><input v-model.number="automation.searchSpec.minKeywordMatch" type="number" min="0" :max="automation.searchSpec.includeKeywords.length" /></label>
          <label class="field"><span>排除关键词</span><div class="filter-token-input"><span v-for="item in automation.searchSpec.excludeKeywords" :key="item">{{ item }}<button type="button" :aria-label="`删除${item}`" @click="removeToken('exclude',item)"><X :size="11" /></button></span><input v-model="excludeKeywordDraft" placeholder="输入后按 Enter 添加" @keydown.enter.prevent="commitTokens('exclude')" @keydown="handleTokenKey($event,'exclude')" @blur="commitTokens('exclude')" /></div></label>
          <label class="field"><span>排除公司</span><div class="filter-token-input"><span v-for="item in automation.searchSpec.excludeCompanies" :key="item">{{ item }}<button type="button" :aria-label="`删除${item}`" @click="removeToken('company',item)"><X :size="11" /></button></span><input v-model="excludeCompanyDraft" placeholder="输入公司后按 Enter 添加" @keydown.enter.prevent="commitTokens('company')" @keydown="handleTokenKey($event,'company')" @blur="commitTokens('company')" /></div></label>
            </div>
          </details>
          <details class="filter-advanced">
            <summary><span>执行约束</span><small>活跃度、冷却、通勤和页数</small><ChevronDown :size="15" /></summary>
            <div class="filter-advanced__body">
          <label class="field"><span>招聘者活跃度</span><span class="select-shell"><select v-model="automation.searchSpec.recruiterActivity"><option value="any">不限</option><option value="recent">本周内活跃</option><option value="online">当前在线</option></select><ChevronDown :size="15" /></span></label>
          <label class="field"><span>最多返回岗位</span><input v-model.number="automation.searchSpec.maxJobs" type="number" min="1" max="150" /><small>最多保留150个；设置经验或学历条件后，会在搜索页数内扩大候选检测范围。</small></label>
          <label class="field"><span>每城市最多页数</span><input v-model.number="automation.searchSpec.maxPages" type="number" min="1" max="10" /><small>每个地区最多读取 10 页</small></label>
          <label class="field"><span>全部城市总页数</span><input v-model.number="automation.searchSpec.maxTotalPages" type="number" min="1" max="100" /><small>所有地区共享，建议 20～30 页</small></label>
          <label class="field"><span>同公司冷却天数</span><input v-model.number="automation.searchSpec.sameCompanyDays" type="number" min="0" max="365" /></label>
          <label class="field"><span>同招聘者冷却天数</span><input v-model.number="automation.searchSpec.sameRecruiterDays" type="number" min="0" max="365" /><small>优先按BOSS招聘者ID识别，无ID时按公司+姓名识别</small></label>
          <label class="field"><span>通勤起点</span><input v-model="automation.searchSpec.homeAddress" placeholder="例如：南京市雨花台区软件大道" /></label>
          <label class="field"><span>通勤方式</span><span class="select-shell"><select v-model="automation.searchSpec.commuteMode"><option value="none">不计算</option><option value="straight">直线距离</option><option value="driving">高德驾车</option><option value="walking">高德步行</option></select><ChevronDown :size="15" /></span></label>
          <label v-if="automation.searchSpec.commuteMode!=='none'" class="field"><span>最大距离（公里，0不限）</span><input v-model.number="automation.searchSpec.maxCommuteDistanceKm" type="number" min="0" max="500" /></label>
          <label v-if="['driving','walking'].includes(automation.searchSpec.commuteMode)" class="field"><span>最长时间（分钟，0不限）</span><input v-model.number="automation.searchSpec.maxCommuteMinutes" type="number" min="0" max="600" /></label>
          <label class="check-setting"><input v-model="automation.searchSpec.excludeHeadhunters" type="checkbox" /><i><Check :size="12" /></i><span><strong>排除猎头岗位</strong><small>根据BOSS岗位返回的招聘者标记过滤</small></span></label>
          <label class="check-setting"><input v-model="automation.searchSpec.excludeContacted" type="checkbox" /><i><Check :size="12" /></i><span><strong>排除已沟通岗位</strong><small>避免向已有BOSS会话重复发送招呼语</small></span></label>
          <label class="check-setting"><input v-model="automation.searchSpec.excludeHardConflicts" type="checkbox" /><i><Check :size="12" /></i><span><strong>排除硬条件冲突</strong><small>经验、学历等明确门槛默认不加入审核</small></span></label>
            </div>
          </details>
        </div>

        <div class="runner-mini-status">
          <span class="status-dot status-dot--pulse"></span>
          <p><strong>{{ automation.runner.name }}</strong><small>{{ automation.runner.online ? '执行器在线' : '执行器未连接' }} · {{ automation.runner.lastHeartbeat }}</small></p>
        </div>
      </aside>

      <main class="discovery-results">
        <section v-if="automation.searchStatus === 'searching'" class="discovery-progress panel">
          <span class="discovery-progress__radar"><Radar :size="24" /></span>
          <div><strong>正在发现BOSS职位</strong><p>{{ automation.searchProgress < 35 ? '检查登录状态与Runner…' : automation.searchProgress < 70 ? '读取岗位并执行筛选…' : '保存结果并计算历史约束…' }} <span class="progress-stage">{{ automation.searchProgress }}% · 请保持页面打开</span></p><div class="progress-track"><span :style="{ width: `${automation.searchProgress}%` }"></span></div></div>
          <strong>{{ automation.searchProgress }}%</strong>
        </section>

        <section class="discovery-summary panel">
          <div><span class="eyebrow">本次结果</span><h2>{{ automation.searchStatus==='partial'?'搜索暂停，已保留部分结果':automation.discoveredJobs.length ? '找到值得判断的机会' : automation.discoveryFilterStats.platformReturned===0?'平台没有返回岗位':'本次没有新增可执行岗位' }}</h2><p>平台返回 {{automation.discoveryFilterStats.platformReturned}} 条 · Runner 返回 {{automation.discoveryFilterStats.runnerReturned}} 条 · 历史重复 {{automation.discoveryFilterStats.historicalDuplicates}} 条 · 最终可执行 {{automation.discoveryFilterStats.eligible}} 条<span v-if="automation.discoveryFilterStats.eligibleTarget"> · 目标 {{automation.discoveryFilterStats.eligibleTarget}} 条</span></p></div>
          <div class="discovery-summary__stats">
            <span><strong>{{ automation.discoveryFilterStats.platformReturned }}</strong><small>平台返回</small></span>
            <span><strong>{{ automation.discoveredJobs.filter((job) => job.matchScore >= automation.searchSpec.minimumScore).length }}</strong><small>高相关</small></span>
            <span><strong>{{ automation.discoveryFilterStats.historicalDuplicates }}</strong><small>历史重复</small></span>
            <span><strong>{{ automation.selectedJobCount }}</strong><small>已选择</small></span>
          </div>
        </section>
        <div v-if="automation.discoveryFilterStats.platformReturned||automation.discoveryFilterStats.runnerReturned" class="discovery-filter-breakdown panel">
          <span><small>平台返回记录</small><strong>{{automation.discoveryFilterStats.platformReturned}}</strong></span>
          <span><small>Runner候选</small><strong>{{automation.discoveryFilterStats.runnerCandidates}}</strong></span>
          <span><small>Runner过滤</small><strong>{{automation.discoveryFilterStats.runnerFiltered}}</strong></span>
          <span><small>Runner返回</small><strong>{{automation.discoveryFilterStats.runnerReturned}}</strong></span>
          <span><small>本轮重复</small><strong>{{automation.discoveryFilterStats.inRunDuplicates}}</strong></span>
          <span><small>历史重复</small><strong>{{automation.discoveryFilterStats.historicalDuplicates}}</strong></span>
          <span><small>已投递</small><strong>{{automation.discoveryFilterStats.alreadyTracked}}</strong></span>
          <span><small>已沟通</small><strong>{{automation.discoveryFilterStats.contacted}}</strong></span>
          <span><small>公司冷却</small><strong>{{automation.discoveryFilterStats.companyCooldown}}</strong></span>
          <span><small>招聘者冷却</small><strong>{{automation.discoveryFilterStats.recruiterCooldown}}</strong></span>
          <span><small>关键词不符</small><strong>{{automation.discoveryFilterStats.keywordMismatch+automation.discoveryFilterStats.requiredKeywordMismatch}}</strong></span>
          <span><small>排除词/公司</small><strong>{{automation.discoveryFilterStats.excludedKeyword+automation.discoveryFilterStats.excludedCompany}}</strong></span>
          <span><small>薪资/活跃度</small><strong>{{automation.discoveryFilterStats.salaryMismatch+automation.discoveryFilterStats.recruiterActivity}}</strong></span>
          <span><small>经验/学历不符</small><strong>{{automation.discoveryFilterStats.experienceMismatch+automation.discoveryFilterStats.degreeMismatch}}</strong></span>
          <span><small>分数/冲突</small><strong>{{automation.discoveryFilterStats.scoreBelowMinimum+automation.discoveryFilterStats.hardConflict}}</strong></span>
          <span class="discovery-filter-breakdown__eligible"><small>最终可执行</small><strong>{{automation.discoveryFilterStats.eligible}}</strong></span>
        </div>
        <details v-if="citySearchStats.length" class="filter-advanced discovery-filter-group">
          <summary>各城市搜索记录 <small>{{automation.discoveryFilterStats.cacheHit?'使用了最近的搜索结果':'本次向平台搜索'}}{{automation.discoveryFilterStats.partial?' · 页面验证中断，保留部分结果':''}}</small><ChevronDown :size="15" /></summary>
          <div class="filter-advanced__body">
            <p v-for="city in citySearchStats" :key="city.city">{{city.city}}：请求 {{city.pages}} 页 · 返回 {{city.returned}} 条 · 本轮新增 {{city.added}} 条<span v-if="city.observed.length&&city.observed.some(name=>name!==city.city)"> · 实际地区 {{city.observed.join('、')}}</span></p>
            <p v-for="(page,index) in automation.discoveryFilterStats.pages" :key="`${page.city}-${page.query}-${page.pageNumber}-${index}`">{{cityLabel(page.city)}} · {{page.query}} · 第 {{page.pageNumber}} 页：返回 {{page.returned}} 条，新增 {{page.added}} 条<span v-if="page.stopReason"> · {{page.stopReason==='empty'?'空页停止':page.stopReason==='repeated'?'连续重复页停止':'在此页中断'}}</span><span v-if="page.errorMessage"> · {{page.errorMessage}}</span></p>
          </div>
        </details>

        <div class="results-toolbar">
          <label class="compact-search"><Search :size="16" /><input v-model="searchText" placeholder="搜索公司、岗位或地点" /></label>
          <div class="segmented-control segmented-control--small">
            <button class="segmented-control__item" :class="{ 'segmented-control__item--active': gradeFilter === 'all' }" type="button" @click="gradeFilter = 'all'">全部</button>
            <button class="segmented-control__item" :class="{ 'segmented-control__item--active': gradeFilter === 'A' }" type="button" @click="gradeFilter = 'A'">A 优先</button>
            <button class="segmented-control__item" :class="{ 'segmented-control__item--active': gradeFilter === 'B' }" type="button" @click="gradeFilter = 'B'">B 可考虑</button>
          </div>
          <div class="results-bulk-actions"><button class="button button--secondary button--small" type="button" :disabled="!selectableVisibleJobs.length" @click="automation.selectJobs(selectableVisibleJobs.map(job=>job.id),true)"><Check :size="14" /> {{allVisibleSelected?'已全选':'全部选择'}}</button><button class="button button--quiet button--small" type="button" :disabled="!visibleJobs.some(job=>job.selected)" @click="automation.selectJobs(visibleJobs.filter(job=>job.selected).map(job=>job.id),false)">全部取消</button></div>
          <span class="results-sort">按匹配分排序 <ChevronDown :size="13" /></span>
        </div>

        <p class="sweep-hint"><span>操作提示</span>长按岗位卡片约350毫秒后滑过其他卡片，可批量选择或取消；普通点击复选框仍可单条操作。</p>

        <div v-if="!visibleJobs.length && automation.searchStatus!=='searching'" class="empty-state panel"><h2>暂无岗位结果</h2><p>填写搜索条件并连接平台。</p><RouterLink to="/settings">管理连接</RouterLink></div>
<section class="discovered-job-list" :class="{'discovered-job-list--sweep':sweepActive}">
          <article
            v-for="job in pagedJobs"
            :key="job.id"
            :data-job-id="job.id"
            class="discovered-job-card panel"
            :class="{ 'discovered-job-card--selected': job.selected, 'discovered-job-card--blocked': !canSelect(job) }"
            @pointerdown="beginSweep($event,job)"
          >
            <button class="job-select-check" :class="{ 'job-select-check--active': job.selected }" type="button" :disabled="!canSelect(job)" :aria-label="job.selected ? '取消选择岗位' : '选择岗位'" @click.stop="sweepMode==='off'&&automation.toggleJobSelection(job.id)"><Check v-if="job.selected" :size="14" /></button>
            <span class="company-logo" :style="{ background: job.companyTone }">{{ job.company.slice(0, 1) }}</span>
            <div class="discovered-job-card__main">
              <div class="discovered-job-card__title"><PlatformBadge :platform="job.platform" /><strong>{{ job.role }}</strong><span>{{ job.company }}</span></div>
              <div class="job-facts"><span><MapPin :size="13" /> {{ job.location }}</span><span><Banknote :size="13" /> {{ job.salary }}</span><span><GraduationCap :size="13" /> {{ job.experience }} · {{ job.degree }}</span></div>
              <div class="job-skill-row"><span v-for="skill in job.matchedSkills.slice(0,4)" :key="skill" class="skill-hit"><Check :size="11" /> {{ skill }}</span><span v-if="job.matchedSkills.length>4">另 {{job.matchedSkills.length-4}} 项命中</span></div>
              <div v-if="job.hardConflicts.length" class="hard-conflict"><CircleAlert :size="14" /> {{ job.hardConflicts.join('、') }}</div>
              <div v-if="job.riskReasons.length" class="hard-conflict"><CircleAlert :size="14" /> 风险提示：{{ job.riskReasons[0] }}</div>
              <details class="job-detail-disclosure"><summary>查看岗位详情与历史 <ChevronDown :size="14" /></summary><div><p>{{ job.summary }}</p><p>发布于 {{job.publishedAt}} · 招聘者 {{ job.recruiter }}（{{ job.recruiterOnline ? '在线' : job.recruiterActive }}） · {{job.companySize}}</p><p v-if="job.riskReasons.length>1">其他风险：{{job.riskReasons.slice(1).join('；')}}</p><p v-if="job.missingSkills.length">未命中：{{job.missingSkills.join('、')}}</p><p v-if="job.companyTaskCount || job.recruiterTaskCount">历史任务：同公司 {{ job.companyTaskCount }} 次 · 同招聘者 {{ job.recruiterTaskCount }} 次</p><p v-if="job.commuteDistanceKm != null">通勤约 {{ job.commuteDistanceKm }} 公里<span v-if="job.commuteDurationMinutes != null"> · {{ job.commuteDurationMinutes }} 分钟</span></p><p v-if="job.alreadyTracked">已存在于沟通管理</p></div></details>
            </div>
            <aside class="job-match-score"><span :class="`grade-pill grade-pill--${job.grade.toLowerCase()}`">{{ job.grade }} · {{ gradeCopy(job.grade) }}</span><strong>{{ job.matchScore }}</strong><small>匹配分</small><span class="job-action-copy">{{ job.platform === 'boss' ? '发送招呼语' : '投递平台简历' }}</span></aside>
          </article>
        </section>
        <div v-if="jobPageCount>1" class="pagination-controls"><button class="button button--quiet button--small" :disabled="jobPage===1" @click="jobPage--">上一页</button><span>第 {{jobPage}} / {{jobPageCount}} 页</span><button class="button button--quiet button--small" :disabled="jobPage===jobPageCount" @click="jobPage++">下一页</button></div>
        <button v-if="automation.hasMoreDiscoveredJobs" class="button button--secondary load-more-button" type="button" :disabled="automation.busy" @click="automation.loadMoreDiscoveredJobs">加载更多岗位</button>
      </main>
    </section>

    <div v-if="automation.selectedJobCount" class="selection-action-bar">
      <div><span>{{ automation.selectedJobCount }}</span><p><strong>个岗位已选择</strong><small>下一步检查简历版本与发送内容</small></p></div>
      <button class="button button--primary" type="button" @click="addToReview">加入招呼审核 <ArrowRight :size="16" /></button>
    </div>
    <ConfirmDialog :open="oneStopConfirmOpen" :busy="startingOneStop" title="启动一条龙？" description="系统将按当前城市、关键词、页数和岗位数量持续发现；单个岗位需要处理时会保留并补位，登录、验证码或账号级限制会暂停流程。" confirm-label="确认启动" @close="oneStopConfirmOpen=false" @confirm="confirmOneStop" />
  </div>
</template>
