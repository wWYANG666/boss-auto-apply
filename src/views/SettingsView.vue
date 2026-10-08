<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { authApi, careerLensApi, type HealthStatus } from '@/api/careerlens'
import { useWorkspaceStore } from '@/stores/workspace'
import { useAutomationStore } from '@/stores/automation'
import type { JobPlatform } from '@/types/automation'
import { ApiError } from '@/api/http'
import ConfirmDialog from '@/components/feedback/ConfirmDialog.vue'
const w=useWorkspaceStore(), a=useAutomationStore()
const route=useRoute(),router=useRouter()
type SettingsSection='boss'|'greeting'|'execution'|'data'|'appearance'
const sections:Array<{id:SettingsSection;label:string;hint:string}>=[
 {id:'boss',label:'BOSS账号',hint:'连接与就绪'},
 {id:'greeting',label:'招呼语策略',hint:'首条消息'},
 {id:'execution',label:'执行策略',hint:'限额与预检'},
 {id:'data',label:'数据维护',hint:'备份与清理'},
 {id:'appearance',label:'外观与账号',hint:'主题与会话'},
]
const currentSection=computed<SettingsSection>(()=>sections.some(item=>item.id===route.query.section)?route.query.section as SettingsSection:'boss')
function selectSection(section:SettingsSection){void router.replace({query:{...route.query,section}})}
const status=ref<HealthStatus|null>(null), busy=ref(false)
const bossDiagnostics=ref<Awaited<ReturnType<typeof careerLensApi.bossDiagnostics>>|null>(null)
const bossIssues=computed(()=>Array.isArray(bossDiagnostics.value?.issues)?bossDiagnostics.value.issues:[])
const bossAuthCookies=computed(()=>bossDiagnostics.value?.authCookies??{wt2:false,stoken:false,zpAt:false,bst:false})
const bossProfiles=ref<{active:string;profiles:Array<{name:string;active:boolean}>}>({active:'default',profiles:[]})
const newBossProfile=ref('')
const policy=ref({paused:false,dailyLimit:20,dryRun:false,used:0,remaining:20,platformLimit:150})
const policyLoaded=ref(false),policySaving=ref(false)
const runnerStorage=ref<Awaited<ReturnType<typeof careerLensApi.runnerStorage>>|null>(null)
const maintenanceStats=ref<Awaited<ReturnType<typeof careerLensApi.maintenanceStats>>|null>(null)
const localBackup=ref<Awaited<ReturnType<typeof careerLensApi.localBackupStatus>>|null>(null)
const greetingPolicy=ref<Awaited<ReturnType<typeof careerLensApi.greetingPolicy>>|null>(null)
const greetingSaving=ref(false)
const readiness=computed(()=>[
 {label:'BOSS登录',ready:a.platformAccounts.some(item=>item.platform==='boss'&&item.status==='connected'),next:'连接 / 检查 BOSS 账号',section:'boss' as SettingsSection},
 {label:'本地执行器',ready:a.runner.online,next:'检查本地执行器',section:'boss' as SettingsSection},
 {label:'主简历',ready:!!w.activeResumeVersionId,next:'发布求职资料',section:'greeting' as SettingsSection},
 {label:'招呼语策略',ready:!!greetingPolicy.value?.activeResumeVersionId,next:'选择发布简历',section:'greeting' as SettingsSection},
 {label:'执行模式',ready:policyLoaded.value,next:'读取执行策略',section:'execution' as SettingsSection},
])
const bannedWordsText=computed({get:()=>greetingPolicy.value?.bannedWords.join('，')??'',set:value=>{if(greetingPolicy.value)greetingPolicy.value.bannedWords=value.split(/[,，]/).map(item=>item.trim()).filter(Boolean)}})
const preferredWordsText=computed({get:()=>greetingPolicy.value?.preferredWords.join('，')??'',set:value=>{if(greetingPolicy.value)greetingPolicy.value.preferredWords=value.split(/[,，]/).map(item=>item.trim()).filter(Boolean)}})
async function loadGreetingPolicy(){greetingPolicy.value=await careerLensApi.greetingPolicy().catch(()=>null)}
async function saveGreetingPolicy(){if(!greetingPolicy.value)return;greetingSaving.value=true;try{greetingPolicy.value=await careerLensApi.updateGreetingPolicy(greetingPolicy.value);await a.initialize(true);w.addToast('招呼语策略已保存','后续新审核计划将绑定所选简历和证据。','success')}finally{greetingSaving.value=false}}
const sizeLabel=(bytes:number)=>bytes>=1024*1024?`${(bytes/1024/1024).toFixed(1)} MB`:`${Math.ceil(bytes/1024)} KB`
async function loadPolicy(){policy.value=await careerLensApi.executionPolicy();policyLoaded.value=true}
async function savePolicy(){
 const limit=Number(policy.value.dailyLimit)
 if(!Number.isInteger(limit)||limit<1||limit>150){w.addToast('每日批准上限无效','请输入1到150之间的整数。','warning');return}
 policySaving.value=true
 try{policy.value=await careerLensApi.updateExecutionPolicy(policy.value.paused,limit,policy.value.dryRun);a.runner.globalPaused=policy.value.paused;w.addToast('执行限制已保存',`每日批准上限：${policy.value.dailyLimit}`)}
 catch(cause){w.addToast('执行限制保存失败',cause instanceof Error?cause.message:'请稍后重试','warning')}
 finally{policySaving.value=false}
}
const sessions=ref<Awaited<ReturnType<typeof authApi.sessions>>>([])
async function loadSessions(){sessions.value=await authApi.sessions()}
async function revokeSession(id:string){await authApi.revokeSession(id);await loadSessions()}
const pairingCode=ref('')
const confirmAction=ref<'restore'|'cleanup'|'revoke'|null>(null)
const restoreFile=ref<File|null>(null)
const bossLoginWatching=ref(false)
let bossLoginTimer:number|undefined
async function pair(){busy.value=true;try{await careerLensApi.pairRunner(pairingCode.value.trim());pairingCode.value='';w.addToast('设备已配对')}finally{busy.value=false}}
async function restore(event:Event){
 const file=(event.target as HTMLInputElement).files?.[0];if(!file)return
 restoreFile.value=file;confirmAction.value='restore';(event.target as HTMLInputElement).value=''
}
async function rotate(){await careerLensApi.rotateRunner();w.addToast('设备凭据已轮换')}
async function archiveOldData(){const result=await careerLensApi.archiveOldData();w.addToast('历史数据归档完成',`任务 ${result.tasks} · 审核计划 ${result.plans} · 发现记录 ${result.discoveries}`,'success');await a.initialize(true)}
async function cleanupLegacyRunnerData(){
 confirmAction.value='cleanup'
}
async function validateBackup(){const result=await careerLensApi.validateWorkspaceBackup();w.addToast(result.valid?'备份完整性检查通过':'备份完整性检查失败',`业务记录 ${result.totalRecords} · 缺失引用 ${result.missingReferences} · PDF文件需单独备份`,result.valid?'success':'warning')}
async function createLocalBackup(){const result=await careerLensApi.createLocalBackup();localBackup.value=await careerLensApi.localBackupStatus();w.addToast('本机数据库备份完成',`${sizeLabel(result.bytes)} · 保留最近${result.kept}份`,'success')}
function revoke(){confirmAction.value='revoke'}
async function runConfirmedAction(){
 busy.value=true
 try{
  if(confirmAction.value==='restore'&&restoreFile.value){await careerLensApi.restoreWorkspace(JSON.parse(await restoreFile.value.text()));await w.initialize(true);w.addToast('备份恢复完成')}
  else if(confirmAction.value==='cleanup'){const result=await careerLensApi.cleanupRunnerLegacyStorage();runnerStorage.value=await careerLensApi.runnerStorage();w.addToast('旧Runner日志已清理',`释放 ${sizeLabel(result.deletedBytes)}`,'success')}
  else if(confirmAction.value==='revoke'){await careerLensApi.revokeRunner();w.addToast('设备配对已撤销')}
  confirmAction.value=null;restoreFile.value=null
 }finally{busy.value=false}
}
async function check(){busy.value=true;try{[status.value,bossDiagnostics.value,runnerStorage.value,maintenanceStats.value,localBackup.value]=await Promise.all([careerLensApi.health(),careerLensApi.bossDiagnostics(),careerLensApi.runnerStorage().catch(()=>null),careerLensApi.maintenanceStats().catch(()=>null),careerLensApi.localBackupStatus().catch(()=>null)]);await a.initialize(true)}finally{busy.value=false}}
async function loadBossProfiles(){bossProfiles.value=await careerLensApi.bossProfiles()}
async function activateBossProfile(name:string){busy.value=true;try{await careerLensApi.activateBossProfile(name);newBossProfile.value='';await loadBossProfiles();bossDiagnostics.value=await careerLensApi.bossDiagnostics();w.addToast('BOSS账号配置已切换',`当前配置：${name}。未登录时请在弹出的浏览器中完成登录。`,'info')}finally{busy.value=false}}
function stopBossLoginWatcher(){window.clearTimeout(bossLoginTimer);bossLoginTimer=undefined;bossLoginWatching.value=false}
function watchManualLogin(){
 stopBossLoginWatcher();bossLoginWatching.value=true
 const poll=async()=>{
  const diagnostics=await careerLensApi.bossDiagnostics().catch(()=>null)
  if(diagnostics)bossDiagnostics.value=diagnostics
  if(diagnostics&&!diagnostics.manualLoginActive){
   stopBossLoginWatcher();busy.value=true
   try{await careerLensApi.connectPlatform('boss');await a.initialize(true);bossDiagnostics.value=await careerLensApi.bossDiagnostics();w.addToast('BOSS直聘已连接','登录状态已同步到CareerLens。')}
   catch{bossLoginWatching.value=true;bossLoginTimer=window.setTimeout(()=>void poll(),2500)}
   finally{busy.value=false}
   return
  }
  bossLoginTimer=window.setTimeout(()=>void poll(),2500)
 }
 bossLoginTimer=window.setTimeout(()=>void poll(),2500)
}
async function connect(platform:JobPlatform){
 busy.value=true
 try{await careerLensApi.connectPlatform(platform);bossLoginWatching.value=false;await a.initialize(true);if(platform==='boss')bossDiagnostics.value=await careerLensApi.bossDiagnostics()}
 catch(cause){
  if(platform==='boss'&&cause instanceof ApiError&&cause.code==='PLATFORM_LOGIN_REQUIRED'){
   bossLoginWatching.value=true
   bossDiagnostics.value=await careerLensApi.bossDiagnostics().catch(()=>null)
   w.addToast('BOSS普通登录窗口已打开','请完成登录并关闭该窗口，CareerLens会自动同步连接状态。','info')
   watchManualLogin()
  }else throw cause
 }finally{busy.value=false}
}
onBeforeUnmount(stopBossLoginWatcher)
onMounted(()=>{void loadPolicy();void loadGreetingPolicy()})
</script>
<template><div class="page settings-page">
<header class="page-header"><div><p class="eyebrow">运行状态与偏好</p><h1>设置</h1><p class="page-subtitle">按用途管理账号、招呼语、执行方式和本机数据。</p></div><button class="button button--primary" :disabled="busy" @click="check">{{busy?'正在检查…':'检查服务连接'}}</button></header>
<section class="settings-readiness panel" aria-label="运行就绪检查"><div><span class="eyebrow">运行就绪检查</span><h2>{{readiness.filter(item=>item.ready).length}} / {{readiness.length}} 项已就绪</h2><p>这些状态来自当前账号和服务；缺失项可直接跳到对应配置。</p></div><div class="settings-readiness__items"><button v-for="item in readiness" :key="item.label" type="button" :class="item.ready?'ready':'needs-attention'" :title="item.ready?'已就绪':item.next" @click="selectSection(item.section)"><span>{{item.ready?'✓':'·'}}</span>{{item.label}}<small>{{item.ready?'已就绪':item.next}}</small></button></div></section>
<div class="settings-layout" :class="`settings-layout--${currentSection}`"><nav class="settings-nav panel" aria-label="设置分类"><button v-for="section in sections" :key="section.id" type="button" class="settings-nav__item" :class="{'settings-nav__item--active':currentSection===section.id}" :aria-current="currentSection===section.id?'page':undefined" @click="selectSection(section.id)"><strong>{{section.label}}</strong><small>{{section.hint}}</small></button></nav>
<section class="settings-content"><article class="settings-section panel"><h2>登录会话</h2><button class="button" @click="loadSessions">查看我的会话</button><p v-for="session in sessions" :key="session.id">{{session.current?'当前会话':'其他会话'}} · 到期 {{new Date(session.expiresAt).toLocaleString()}} <button class="button button--quiet" @click="revokeSession(session.id)">撤销</button></p></article>
<article class="settings-section panel"><h2>本机设备配对</h2><p>先配置Core的RUNNER_ENCRYPTION_KEY，再输入本地Runner数据目录 pairing.json 中的短时配对码。配对码有效期两分钟，已配对设备拒绝重复配对。</p><label class="field"><span>配对码</span><input type="password" v-model="pairingCode" autocomplete="off" /></label><button class="button button--primary" :disabled="busy || !pairingCode.trim()" @click="pair">配对当前账号</button><button class="button button--secondary" @click="rotate">轮换凭据</button><button class="button button--quiet" @click="revoke">撤销配对</button></article>
<article class="settings-section panel"><div class="settings-section__header"><div><h2>服务健康</h2><p>下方只显示实际健康检查返回值。</p></div></div><p v-if="!status">点击“检查服务连接”获取当前状态。</p><template v-else><p>核心服务：{{status.status}}</p><p v-for="(dependency,name) in status.dependencies" :key="name">{{name}}：{{dependency.online?'在线':'离线'}} {{dependency.mode||''}}</p></template></article>
<article class="settings-section panel"><div class="settings-section__header"><div><h2>BOSS直聘</h2><p>登录阶段使用不带自动化连接的普通浏览器。完成登录并关闭窗口后，再检查连接。</p></div></div><div class="platform-connection-list"><article class="platform-connection-row"><strong>BOSS直聘</strong><span>{{bossLoginWatching?'等待你完成登录并关闭窗口':a.platformAccounts.find(p=>p.platform==='boss')?.status||'未连接'}}</span><button class="button button--secondary" :disabled="busy" @click="connect('boss')">{{bossLoginWatching?'登录完成，检查连接':'连接 / 检查'}}</button><button v-if="a.platformAccounts.find(p=>p.platform==='boss')?.status==='connected'" class="button button--quiet" @click="a.togglePlatformConnection(a.platformAccounts.find(p=>p.platform==='boss')!.id)">断开</button></article></div><div class="data-export-actions"><button class="button button--secondary" :disabled="busy" @click="loadBossProfiles">管理BOSS账号配置</button><template v-for="profile in bossProfiles.profiles" :key="profile.name"><button class="button" :class="profile.active?'button--primary':'button--quiet'" :disabled="busy||profile.active" @click="activateBossProfile(profile.name)">{{profile.name}}{{profile.active?'（当前）':''}}</button></template><label class="field"><span>新增配置名称</span><input v-model="newBossProfile" maxlength="32" placeholder="例如：campus" /></label><button class="button button--secondary" :disabled="busy||!/^[a-zA-Z0-9_-]{1,32}$/.test(newBossProfile)" @click="activateBossProfile(newBossProfile)">创建并切换</button></div><div v-if="bossDiagnostics" class="runner-mini-status"><span class="status-dot" :class="bossDiagnostics.ready?'status-dot--pulse':'status-dot--warning'"></span><p><strong>BOSS运行检查：{{bossDiagnostics.ready?'已就绪':'尚未就绪'}}</strong><small>{{bossIssues.length?bossIssues.join('；'):'浏览器、登录与城市配置均已通过'}}</small><small>运行方式：{{bossDiagnostics.browserMode==='manual-login'?'普通登录窗口':bossDiagnostics.browserMode==='background'?'后台浏览器':bossDiagnostics.browserMode==='headless'?'实验性无头模式':bossDiagnostics.browserMode==='visible'?'可见登录模式':bossDiagnostics.browserMode}}</small><small v-if="bossDiagnostics.pageTitle">当前页面：{{bossDiagnostics.pageTitle}}</small><small>登录信号：头像/页面 {{bossDiagnostics.signedIn?'通过':'未通过'}} · wt2 {{bossAuthCookies.wt2?'存在':'缺失'}}</small></p></div></article>
<article class="settings-section panel"><h2>执行约束</h2><button class="button" :disabled="policySaving" @click="loadPolicy">重新读取</button><p v-if="policyLoaded">系统记录今日已占用 {{policy.used}} / {{policy.dailyLimit}}，剩余 {{policy.remaining}}；BOSS平台硬上限 {{policy.platformLimit}}。官方客户端的手动沟通可能不在系统统计内。</p><label class="field"><span>每日批准上限（北京时间，1-150）</span><input type="number" min="1" max="150" step="1" :disabled="!policyLoaded||policySaving" v-model.number="policy.dailyLimit"/><small>失败、页面变化、取消和仅预检任务不占用本地额度。</small></label><div class="policy-presets"><button v-for="limit in [20,50,100,150]" :key="limit" class="button button--quiet button--small" type="button" @click="policy.dailyLimit=limit">{{limit}}</button></div><label><input type="checkbox" v-model="policy.paused"/>暂停后续提交</label><label><input type="checkbox" v-model="policy.dryRun"/>仅预检（Dry-run，不发送消息或简历）</label><button class="button button--primary" :disabled="!policyLoaded||policySaving" @click="savePolicy">{{policySaving?'正在保存…':'保存执行限制'}}</button><p>成功、执行中和结果待核对任务会占用额度；明确失败的任务释放额度。遇到登录、验证码和未知结果时暂停，未知结果必须先核对。</p><p>建议首次使用先启用“仅预检”，确认岗位识别和筛选结果后再关闭。</p></article>
<article v-if="greetingPolicy" class="settings-section panel"><h2>首条招呼语策略</h2><p>程序只发送经过审核的第一条招呼语；后续聊天和简历由你在手机端处理。</p><label class="field"><span>用于招呼语的发布简历</span><select v-model="greetingPolicy.activeResumeVersionId"><option v-for="resume in greetingPolicy.resumes" :key="resume.versionId" :value="resume.versionId" :disabled="!resume.usable">{{resume.title}} · v{{resume.version}}{{resume.usable?'':'（内容不足）'}}</option></select></label><label class="field"><span>默认风格</span><select v-model="greetingPolicy.defaultStyle"><option value="PROJECT_EVIDENCE">项目证明型</option><option value="SKILL_MATCH">技术匹配型</option><option value="SHORT_QUESTION">简短提问型</option></select></label><label class="toggle-row"><span>结尾包含一个简短问题</span><input v-model="greetingPolicy.includeQuestion" type="checkbox"/><i></i></label><label class="field"><span>最大字数（50-100）</span><input v-model.number="greetingPolicy.maxLength" type="number" min="50" max="100"/></label><label class="field"><span>禁用词</span><input v-model="bannedWordsText" placeholder="精通，专家，大师，顶尖"/></label><label class="field"><span>偏好词</span><input v-model="preferredWordsText" placeholder="做过，实现过，独立完成"/></label><button class="button button--primary" :disabled="greetingSaving||!greetingPolicy.activeResumeVersionId" @click="saveGreetingPolicy">{{greetingSaving?'正在保存…':'保存招呼语策略'}}</button></article>
<article class="settings-section panel"><h2>外观</h2><label class="toggle-row"><span>深色模式</span><input type="checkbox" :checked="w.theme==='dark'" @change="w.toggleTheme"/><i></i></label></article>
<article class="settings-section panel"><h2>数据</h2><p>导出当前账号的求职资料、岗位和投递记录；归档只隐藏过期历史，不会物理删除。</p><div v-if="runnerStorage" class="runner-mini-status"><p><strong>Runner本地数据</strong><small>SQLite {{sizeLabel(runnerStorage.databaseBytes)}} · 旧JSONL {{sizeLabel(runnerStorage.legacyBytes)}} · 内存 {{sizeLabel(runnerStorage.runnerMemoryBytes)}}</small><small>任务 {{runnerStorage.totalTasks}} · 活动 {{runnerStorage.activeTasks}} · 终态保留 {{runnerStorage.retentionDays}} 天</small></p></div><p v-if="maintenanceStats">投递 {{maintenanceStats.applications}} · 发现批次 {{maintenanceStats.discoveries}} · 审核计划 {{maintenanceStats.plans}} · 自动任务 {{maintenanceStats.tasks}} · 已归档 {{maintenanceStats.archivedTasks}} · PDF {{maintenanceStats.artifacts}}份 / {{sizeLabel(maintenanceStats.artifactBytes)}}</p><p v-if="localBackup">本机自动备份 {{localBackup.count}}份 · {{sizeLabel(localBackup.bytes)}} · 每天04:10执行并保留最近7份</p><div class="data-export-actions"><button class="button button--secondary" @click="w.exportWorkspace">导出工作区 JSON</button><button class="button button--secondary" @click="createLocalBackup">立即备份本机数据库</button><button class="button button--secondary" @click="validateBackup">检查备份完整性</button><label class="button button--secondary">恢复备份到空账号<input type="file" accept=".json" hidden @change="restore"/></label><button class="button button--secondary" @click="archiveOldData">立即归档旧记录</button><button v-if="runnerStorage?.legacyBytes" class="button button--quiet" :disabled="runnerStorage.activeTasks>0" @click="cleanupLegacyRunnerData">清理旧Runner日志</button><RouterLink to="/resumes" class="button button--secondary">导入求职资料</RouterLink></div></article>
</section></div><ConfirmDialog :open="confirmAction!==null" :busy="busy" danger :title="confirmAction==='restore'?'恢复工作区备份？':confirmAction==='cleanup'?'清理旧 Runner 日志？':'撤销设备配对？'" :description="confirmAction==='restore'?'只允许恢复到空工作区；不会恢复会话，也不会自动启动外部任务。':confirmAction==='cleanup'?'只删除已成功导入 SQLite 的旧 JSONL 日志，不会删除任务状态。':'撤销后，本地执行器需要重新配对才能访问当前账号。'" :confirm-label="confirmAction==='restore'?'确认恢复':confirmAction==='cleanup'?'确认清理':'确认撤销'" @close="confirmAction=null;restoreFile=null" @confirm="runConfirmedAction" /></div></template>
