<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  Activity,
  Check,
  CheckCheck,
  ChevronDown,
  CircleAlert,
  CircleCheck,
  CirclePause,
  Clock3,
  ExternalLink,
  Laptop,
  RefreshCw,
  ShieldCheck,
  Workflow,
  Trash2,
} from '@lucide/vue'
import PlatformBadge from '@/components/platform/PlatformBadge.vue'
import AutomationStatusBadge from '@/components/automation/AutomationStatusBadge.vue'
import HumanActionBanner from '@/components/automation/HumanActionBanner.vue'
import ConfirmDialog from '@/components/feedback/ConfirmDialog.vue'
import { useAutomationStore } from '@/stores/automation'
import type { AutomationStatus } from '@/types/automation'

import { useWorkspaceStore } from '@/stores/workspace'
const workspace = useWorkspaceStore()
const router=useRouter()
import { careerLensApi } from '@/api/careerlens'
const policy=ref({paused:false,dailyLimit:20,dryRun:false,used:0,remaining:20,platformLimit:150})
onMounted(async()=>{policy.value=await careerLensApi.executionPolicy()})
const nowTick=ref(Date.now())
const countdownTimer=window.setInterval(()=>{nowTick.value=Date.now()},1000)
onBeforeUnmount(()=>window.clearInterval(countdownTimer))
const oneStopPhaseLabels:Record<string,string>={DISCOVER:'准备发现岗位',WAIT_DISCOVERY:'等待岗位发现',APPROVE_GROUP:'补充执行任务',WAIT_GROUP:'等待任务完成',STOPPED:'已停止',IDLE:'未启动'}
const oneStopWaitText=computed(()=>{
  const next=automation.oneStopRun.nextRunAt
  if(!next||automation.oneStopRun.status!=='running')return ''
  const seconds=Math.max(0,Math.ceil((new Date(next).getTime()-nowTick.value)/1000))
  if(seconds<=0)return '即将检查'
  return `下次检查 ${Math.floor(seconds/60)}分${seconds%60}秒后`
})
async function retryTask(id:string){await careerLensApi.retryAutomationTask(id);await automation.refreshTasks()}
function rediscoverTask(){workspace.addToast('请重新发现岗位','岗位页面与原审核快照不一致，请在职位发现中获取最新岗位后重新审核。','info');router.push('/discovery')}
const failureLabels:Record<string,string>={NOT_SUBMITTED_CONFIRMED:'平台确认未发送，可重新执行',CONTACT_ROUTE_STALE:'沟通地址已失效，可重新执行',APPROVAL_EXPIRED:'审批已过期，可重新批准',REQUIRES_REDISCOVERY:'岗位快照变化，需要重新发现',JOB_UNAVAILABLE:'岗位已关闭',PLATFORM_LIMITED:'平台访问受限',EXECUTION_FAILED:'执行失败，需要人工检查'}
function failureLabel(reason: string): string { return failureLabels[reason] || reason.replaceAll('_', ' ').toLowerCase() }
async function cancelTask(id:string){await careerLensApi.cancelAutomationTask(id);await automation.refreshTasks()}
async function togglePause(){policy.value=await careerLensApi.updateExecutionPolicy(!policy.value.paused,policy.value.dailyLimit,policy.value.dryRun);automation.runner.globalPaused=policy.value.paused}
async function startQueued(){await automation.startRun();policy.value=await careerLensApi.executionPolicy()}
const confirmAction=ref<'delete'|'clear'|'stop'|null>(null)
const confirmBusy=ref(false)
function deleteSelectedTask(){if(selectedTask.value)confirmAction.value='delete'}
function clearTasks(){if(automation.tasks.length)confirmAction.value='clear'}
async function runConfirmedAction(){confirmBusy.value=true;try{if(confirmAction.value==='delete'&&selectedTask.value){const id=selectedTask.value.id;await automation.deleteTask(id);selectedTaskId.value=automation.tasks[0]?.id??''}else if(confirmAction.value==='clear'){await automation.clearTasks();selectedTaskId.value=''}else if(confirmAction.value==='stop'){await automation.stopOneStop()}confirmAction.value=null}finally{confirmBusy.value=false}}
const automation = useAutomationStore()
const selectedTaskId = ref(automation.tasks.find((task) => task.status !== 'succeeded')?.id ?? automation.tasks[0]?.id ?? '')
const selectedTaskIds=ref(new Set<string>())
const taskPage=ref(1)
const pageSize=10
function toggleTaskSelection(id:string){const next=new Set(selectedTaskIds.value);next.has(id)?next.delete(id):next.add(id);selectedTaskIds.value=next}
function selectedIds(){return [...selectedTaskIds.value]}

const taskPageCount=computed(()=>Math.max(1,Math.ceil(automation.tasks.length/pageSize)))
const visibleTasks=computed(()=>automation.tasks.slice((taskPage.value-1)*pageSize,taskPage.value*pageSize))
watch(taskPageCount,count=>{if(taskPage.value>count)taskPage.value=count})
watch(taskPage,()=>{selectedTaskId.value=visibleTasks.value[0]?.id??''})

const selectedTask = computed(() =>
  automation.tasks.find((task) => task.id === selectedTaskId.value) ?? visibleTasks.value[0],
)
watch(()=>selectedTask.value?.id,id=>{if(id)void automation.refreshTaskDetail(id)},{immediate:true})

const humanTasks = computed(() => automation.tasks.filter((task) => task.status.startsWith('awaiting_')))
const activeTasks=computed(()=>automation.tasks.filter(task=>['queued','preparing','submitting','verifying','unknown_outcome'].includes(task.status)||task.status.startsWith('awaiting_')))
const currentTask=computed(()=>activeTasks.value.find(task=>['preparing','submitting','verifying'].includes(task.status))??activeTasks.value[0])
const discovering=computed(()=>automation.oneStopRun.status==='running'&&['DISCOVER','WAIT_DISCOVERY'].includes(automation.oneStopRun.phase))
const discoveryPercent=computed(()=>{
 const run=automation.oneStopRun
 return run.discoveryPageLimit>0?Math.min(100,Math.round(run.discoveryPagesCompleted/run.discoveryPageLimit*100)):run.discoveryProgress
})
function durationLabel(seconds:number){
 if(seconds<60)return`约 ${Math.max(1,Math.ceil(seconds))} 秒`
 const minutes=Math.ceil(seconds/60)
 return minutes<60?`约 ${minutes} 分钟`:`约 ${Math.floor(minutes/60)} 小时 ${minutes%60} 分钟`
}
const discoveryEta=computed(()=>{
 const run=automation.oneStopRun
 if(!discovering.value||!run.discoveryStartedAt||run.discoveryPagesCompleted<3||run.discoveryPageLimit<=run.discoveryPagesCompleted)return''
 const elapsed=Math.max(1,(nowTick.value-new Date(run.discoveryStartedAt).getTime())/1000)
 return durationLabel(elapsed/run.discoveryPagesCompleted*(run.discoveryPageLimit-run.discoveryPagesCompleted))
})
const runHeadline=computed(()=>{
 if(humanTasks.value.length)return'需要你处理后才能继续'
 if(currentTask.value)return currentTask.value.currentStep
 if(policy.value.paused&&automation.oneStopRun.status!=='running')return'后续提交已暂停'
 if(discovering.value)return'一条龙正在检索岗位'
 if(automation.oneStopRun.status==='running')return'一条龙正在准备下一轮岗位'
 if(automation.oneStopRun.status==='paused')return'一条龙已暂停'
 if(automation.oneStopRun.status==='completed')return'本次一条龙已完成'
 if(automation.oneStopRun.status==='failed')return'本次一条龙运行失败'
 if(automation.oneStopRun.status==='stopped')return'本次一条龙已停止'
 return'当前没有活动任务'
})
const runDetail=computed(()=>{
 if(currentTask.value)return`${currentTask.value.company} · ${currentTask.value.role} · ${currentTask.value.progress}%`
 const run=automation.oneStopRun
 if(policy.value.paused&&run.status!=='running')return'队列不会自动推进。恢复后续提交或启动待执行任务即可继续。'
 if(discovering.value)return`已检测 ${run.discoveryDetectedCount} / ${run.discoveryCandidateLimit} 个候选 · 已检查 ${run.discoveryPagesCompleted} / ${run.discoveryPageLimit} 页 · 今日额度 ${run.dailyUsed} / ${run.dailyLimit}，剩余 ${run.dailyRemaining}${discoveryEta.value?` · 预计剩余 ${discoveryEta.value}`:run.discoveryPagesCompleted<3?' · 完成3页后估算时间':' · 正在估算剩余时间'}`
 if(run.status!=='idle')return`本次：第${run.discoveryRound}轮 · 第${run.groupNumber}组 · 处理${run.processedCount} · 成功${run.succeededCount} · 失败${run.failedCount} · 今日额度 ${run.dailyUsed}/${run.dailyLimit}（剩余${run.dailyRemaining}） · ${oneStopPhaseLabels[run.phase]||run.phase}${oneStopWaitText.value?` · ${oneStopWaitText.value}`:''}`
 return`全部任务：成功 ${automation.succeededCount} · 等待 ${automation.queuedCount}`
})
function taskRecommendation(task:typeof selectedTask.value){
 if(!task)return''
 if(task.status==='unknown_outcome')return'先核对平台结果，不要直接重新发送。'
 if(task.failureCategory==='REQUIRES_REDISCOVERY'||task.status==='page_changed')return'岗位信息已经变化，建议重新发现并审核。'
 if(task.retryable)return'平台确认未发送，可以创建新的执行尝试。'
 if(task.status.startsWith('awaiting_'))return task.humanAction||'完成平台要求的人工操作后继续。'
 return task.currentStep
}
async function copyTaskError(){
 if(!selectedTask.value)return
 await navigator.clipboard.writeText([selectedTask.value.company,selectedTask.value.role,failureLabel(selectedTask.value.failureCategory||selectedTask.value.status),selectedTask.value.currentStep,taskRecommendation(selectedTask.value)].join('\n'))
 workspace.addToast('错误信息已复制','可用于排查或反馈。','success')
}

const companySummary = computed(() => {
  const counts = new Map<string, number>()
  for (const task of automation.tasks) counts.set(task.company, (counts.get(task.company) ?? 0) + 1)
  return [...counts.entries()].sort((a, b) => b[1] - a[1])
})

function csvCell(value: unknown): string {
  const text = value == null ? '' : String(value)
  return /[",\n\r]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

function exportTaskHistory() {
  const columns = ['时间', '平台', '公司', '职位', '状态', '进度', '当前步骤', '尝试次数', '回执', 'Runner任务ID']
  const rows = automation.tasks.map(task => [
    new Date(task.events[0]?.time ?? Date.now()).toLocaleString('zh-CN'),
    task.platform === 'boss' ? 'BOSS直聘' : '猎聘', task.company, task.role,
    task.status, `${task.progress}%`, task.currentStep, task.attempt ?? 0,
    task.receipt ?? '', task.runnerTaskId ?? '',
  ])
  const csv = '\uFEFF' + [columns, ...rows].map(row => row.map(csvCell).join(',')).join('\r\n')
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `careerlens-automation-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  workspace.addToast('投递记录已导出', `${rows.length} 条任务记录已保存为 CSV。`, 'success')
}

const statusSequence: Array<{ id: AutomationStatus; label: string }> = [
  { id: 'preparing', label: '预检' },
  { id: 'submitting', label: '执行动作' },
  { id: 'verifying', label: '核对回执' },
  { id: 'succeeded', label: '完成' },
]

function stepState(step: AutomationStatus, current: AutomationStatus) {
  const order = statusSequence.map((item) => item.id)
  if (current === 'succeeded') return 'done'
  if (current.startsWith('awaiting_')) {
    if (step === 'preparing') return 'done'
    if (step === 'submitting') return 'current'
    return 'pending'
  }
  const currentIndex = order.indexOf(current)
  const stepIndex = order.indexOf(step)
  if (stepIndex < currentIndex) return 'done'
  if (stepIndex === currentIndex) return 'current'
  return 'pending'
}
</script>

<template>
  <div class="page automation-page">
    <header class="page-header">
      <div><p class="eyebrow">可见、可停、可追溯</p><h1>自动执行</h1><p class="page-subtitle">执行器按批准计划串行工作；单岗位待处理会自动让位，账号登录、验证码和平台限制才会暂停整条队列。</p></div>
      <div class="page-header__actions automation-header-actions">
        <details class="automation-more-actions">
          <summary class="button button--secondary">更多操作 <ChevronDown :size="14" /></summary>
          <div class="automation-more-actions__menu">
            <button class="button button--secondary" :disabled="automation.busy" @click="automation.approvePendingReviews"><CheckCheck :size="16" /> 一键批准并核对</button>
            <button class="button button--secondary" :disabled="automation.busy || !automation.tasks.some(task=>task.status==='unknown_outcome'&&(!selectedTaskIds.size||selectedTaskIds.has(task.id)))" @click="automation.batchReconcileUnknown(selectedIds())"><RefreshCw :size="16" /> {{selectedTaskIds.size?'核对所选':'批量核对'}}</button>
            <button class="button button--secondary" :disabled="automation.busy || !automation.tasks.some(task=>task.retryable&&['failed','page_changed'].includes(task.status)&&(!selectedTaskIds.size||selectedTaskIds.has(task.id)))" @click="automation.batchRetryFailed(selectedIds())"><RefreshCw :size="16" /> {{selectedTaskIds.size?'重新执行所选':'批量重新执行'}}</button>
            <button class="button button--secondary" :disabled="automation.busy" @click="automation.refreshTasks"><RefreshCw :size="16" /> 刷新任务</button>
            <button class="button button--secondary" :disabled="!automation.tasks.length" @click="exportTaskHistory">导出记录</button>
            <button class="button button--secondary" :disabled="!automation.tasks.length" @click="clearTasks"><Trash2 :size="16" /> 清理已结束任务</button>
          </div>
        </details>
      </div>
    </header>

    <section class="automation-current panel" :class="{'automation-current--attention':humanTasks.length||policy.paused||automation.oneStopRun.status==='paused'||automation.oneStopRun.status==='failed'}" aria-live="polite">
      <div class="automation-current__icon"><CircleAlert v-if="humanTasks.length" :size="23"/><Workflow v-else :size="23"/></div>
      <div class="automation-current__copy"><span class="eyebrow">当前运行</span><h2>{{runHeadline}}</h2><p>{{runDetail}}</p><div v-if="discovering" class="automation-current__discovery-progress" role="progressbar" :aria-valuenow="discoveryPercent" aria-valuemin="0" aria-valuemax="100"><i :style="{width:`${discoveryPercent}%`}"></i></div><small v-if="automation.oneStopRun.lastError" class="automation-current__error">{{automation.oneStopRun.lastError}}</small></div>
      <div class="automation-current__metrics"><span><small>活动任务</small><strong>{{activeTasks.length}}</strong></span><span><small>需要处理</small><strong>{{automation.humanActionCount}}</strong></span><span><small>全部成功</small><strong>{{automation.succeededCount}}</strong></span></div>
      <div class="automation-current__actions"><button v-if="humanTasks.length" class="button button--warning" type="button" @click="selectedTaskId=humanTasks[0]?.id??''">处理人工验证</button><template v-else-if="automation.oneStopRun.status==='paused'"><button class="button button--primary" type="button" :disabled="automation.busy" @click="automation.runOneStop()">继续一条龙</button><button class="button button--quiet" type="button" @click="confirmAction='stop'">彻底终止</button></template><template v-else-if="automation.oneStopRun.status==='running'"><button class="button button--secondary" type="button" @click="automation.pauseOneStop">暂停并保留</button><button class="button button--quiet" type="button" @click="confirmAction='stop'">彻底终止</button></template><button v-else-if="policy.paused" class="button button--primary" type="button" @click="togglePause">恢复后续提交</button><button v-else-if="automation.queuedCount" class="button button--primary" type="button" :disabled="automation.busy" @click="startQueued">启动待执行任务</button><RouterLink v-else class="button button--secondary" to="/discovery">发现新岗位</RouterLink></div>
    </section>

    <section v-if="humanTasks.length" class="human-action-stack"><HumanActionBanner v-for="task in humanTasks" :key="task.id" :task="task" @resolve="automation.resolveHumanAction" /></section>

    <details class="automation-secondary-details panel">
      <summary><span><strong>运行环境与执行策略</strong><small>执行器、平台开关、队列进度和暂停策略</small></span><ChevronDown :size="17"/></summary>
      <section class="automation-stats">
      <article class="automation-stat panel"><span class="automation-stat__icon automation-stat__icon--online"><Laptop :size="19" /></span><div><span>执行器</span><strong>{{ automation.runner.online ? '在线' : '离线' }}</strong><small>{{ automation.runner.name }}</small></div><i class="status-dot" :class="automation.runner.online ? 'status-dot--pulse' : 'status-dot--warning'"></i></article>
      <article class="automation-stat panel"><span class="automation-stat__icon"><Workflow :size="19" /></span><div><span>总体进度</span><strong>{{ automation.overallProgress }}%</strong><small>{{ automation.runStatus === 'running' ? '正在执行' : automation.runStatus === 'completed' ? '本轮已完成' : '已保存检查点' }}</small></div><div class="mini-progress"><i :style="{ width: `${automation.overallProgress}%` }"></i></div></article>
      <article class="automation-stat panel"><span class="automation-stat__icon"><Clock3 :size="19" /></span><div><span>队列等待</span><strong>{{ automation.queuedCount }}</strong><small>所有平台串行处理</small></div></article>
      <article class="automation-stat panel automation-stat--warning"><span class="automation-stat__icon"><CircleAlert :size="19" /></span><div><span>需要人工处理</span><strong>{{ automation.humanActionCount }}</strong><small>岗位级任务不阻塞一条龙；处理后可从检查点继续</small></div></article>
      </section>

      <section class="automation-controls">
      <div class="automation-run-state"><span class="run-indicator" :class="`run-indicator--${automation.runStatus}`"><i></i>{{ automation.runStatus === 'running' ? '执行中' : automation.runStatus === 'completed' ? '已完成' : automation.runStatus === 'ready' ? '等待开始' : '已暂停' }}</span><p>同一时间只允许一个平台任务产生外部动作</p></div>
      <div class="platform-kill-switches"><span>平台能力</span><button v-for="account in automation.platformAccounts.filter(item=>item.platform==='boss')" :key="account.id" class="platform-switch" :class="{ 'platform-switch--off': account.status !== 'connected' }" type="button" @click="automation.togglePlatformConnection(account.id)"><PlatformBadge :platform="account.platform" compact /><strong>{{ account.status === 'connected' ? '开启' : '关闭' }}</strong><i></i></button></div>
      <button @click="workspace.addToast('执行策略', '人工批准、短时确认、未知结果先核对', 'info')" class="button button--quiet button--small" type="button"><ShieldCheck :size="15" /> 执行策略 <ChevronDown :size="13" /></button>
      <button class="button button--warning button--small" type="button" @click="togglePause">{{policy.paused?'恢复后续提交':'暂停后续提交'}}</button>
      </section>
    </details>

    <details v-if="companySummary.length||automation.failureSummary.length" class="automation-secondary-details panel">
      <summary><span><strong>历史统计与失败汇总</strong><small>按需查看公司任务分布和可执行的失败原因</small></span><ChevronDown :size="17"/></summary>
      <section v-if="companySummary.length" class="automation-controls"><div class="automation-run-state"><span class="eyebrow">历史聚合</span><p>按公司查看本账号近期任务，冷却过滤会在发现阶段生效</p></div><div class="platform-kill-switches"><span v-for="([company, count]) in companySummary.slice(0, 5)" :key="company"><strong>{{ company }}</strong><small>{{ count }} 条</small></span></div></section>
      <section v-if="automation.failureSummary.length" class="automation-controls"><div class="automation-run-state"><span class="eyebrow">失败原因汇总</span><p>根据最近任务的可执行原因分类，可使用顶部“批量重试”处理仍有效的计划</p></div><div class="platform-kill-switches"><span v-for="item in automation.failureSummary" :key="item.reason"><strong>{{failureLabel(item.reason)}}</strong><small>{{item.count}} 条</small></span></div></section>
    </details>

    <section class="automation-workspace">
      <article class="task-queue panel">
        <div class="panel__header"><div><span class="eyebrow">执行队列</span><h2>{{ automation.tasks.length }} 个任务</h2></div><button class="icon-button icon-button--quiet" type="button" aria-label="刷新队列" @click="automation.refreshTasks"><RefreshCw :size="17" /></button></div>
        <div class="task-list">
          <button v-for="task in visibleTasks" :key="task.id" class="task-item" :class="{ 'task-item--active': task.id === selectedTaskId }" type="button" @click="selectedTaskId = task.id">
            <span class="review-include-check" :class="{'review-include-check--active':selectedTaskIds.has(task.id)}" role="checkbox" :aria-checked="selectedTaskIds.has(task.id)" tabindex="0" @click.stop="toggleTaskSelection(task.id)" @keydown.space.prevent.stop="toggleTaskSelection(task.id)"><Check v-if="selectedTaskIds.has(task.id)" :size="13" /></span>
            <PlatformBadge :platform="task.platform" compact />
            <span class="task-item__copy"><strong>{{ task.company }} · {{ task.role }}</strong><small>{{ policy.paused && task.status==='queued' ? '后续提交已暂停，等待恢复' : task.currentStep }}</small><span><i :style="{ width: `${task.progress}%` }"></i></span></span>
            <AutomationStatusBadge :status="task.status" />
          </button>
        </div>
        <div v-if="taskPageCount>1" class="pagination-controls"><button class="button button--quiet button--small" :disabled="taskPage===1" @click="taskPage--">上一页</button><span>第 {{taskPage}} / {{taskPageCount}} 页</span><button class="button button--quiet button--small" :disabled="taskPage===taskPageCount" @click="taskPage++">下一页</button></div>
        <button v-if="automation.hasMoreTasks" class="button button--quiet load-more-button" type="button" :disabled="automation.busy" @click="automation.loadMoreTasks">加载更早任务</button>
      </article>

      <article v-if="selectedTask" class="task-detail panel">
        <header class="task-detail__header"><div><PlatformBadge :platform="selectedTask.platform" /><span><small>{{ selectedTask.externalJobId }}</small><h2>{{ selectedTask.company }} · {{ selectedTask.role }}</h2></span></div><AutomationStatusBadge :status="selectedTask.status" /></header>
        <div class="task-progress-steps">
          <div v-for="step in statusSequence" :key="step.id" :class="`task-step task-step--${stepState(step.id, selectedTask.status)}`"><span><Check v-if="stepState(step.id, selectedTask.status) === 'done'" :size="12" /><i v-else></i></span><strong>{{ step.label }}</strong></div>
        </div>
        <div class="task-current-state"><span class="lens-icon"><Activity :size="18" /></span><div><small>当前步骤 · {{ selectedTask.progress }}%</small><strong>{{ policy.paused && selectedTask.status==='queued' ? '后续提交已暂停，点击顶部按钮恢复' : selectedTask.currentStep }}</strong></div></div>
        <div v-if="selectedTask.failureCategory||selectedTask.status==='unknown_outcome'" class="task-error-guidance"><CircleAlert :size="18"/><div><span class="eyebrow">发生了什么</span><strong>{{failureLabels[selectedTask.failureCategory||'']||failureLabel(selectedTask.status)}}</strong><p>{{taskRecommendation(selectedTask)}}</p><small>{{selectedTask.status==='unknown_outcome'?'可能已经发送，核对前不会再次执行。':'系统未将当前状态当作成功。'}}</small></div><button class="button button--quiet button--small" type="button" @click="copyTaskError">复制错误</button></div>
        <div class="task-contract-grid"><span><small>平台动作</small><strong>{{ selectedTask.platform === 'boss' || selectedTask.actionType === 'CHAT' ? '发起沟通' : '投递简历' }}</strong></span><span><small>简历版本</small><strong>主简历 v{{ selectedTask.resumeVersion }}</strong></span><span><small>执行模式</small><strong>本地串行</strong></span><span><small>{{ selectedTask.receiptSource === 'platform' ? '平台回执' : '本地核验记录' }}</small><strong>{{ selectedTask.receipt || '等待生成' }}</strong></span></div>
        <div class="task-timeline"><div class="task-timeline__title"><span>执行时间线</span><small>仅保存脱敏审计数据</small></div><article v-for="event in selectedTask.events" :key="event.id" :class="`timeline-event timeline-event--${event.tone}`"><span class="timeline-event__dot"></span><div><strong>{{ event.title }}</strong><p>{{ event.detail }}</p></div><time>{{ event.time }}</time></article></div>
        <footer class="task-detail__footer"><button v-if="selectedTask.retryable&&['failed','page_changed'].includes(selectedTask.status)" class="button button--secondary" @click="retryTask(selectedTask.id)">创建新的执行尝试</button><button v-else-if="selectedTask.failureCategory==='REQUIRES_REDISCOVERY'" class="button button--secondary" @click="rediscoverTask">前往重新发现岗位</button><button v-if="['queued','preparing','awaiting_login','awaiting_captcha','awaiting_question'].includes(selectedTask.status)" class="button button--quiet" @click="cancelTask(selectedTask.id)">取消未提交任务</button><button v-if="['failed','cancelled','succeeded'].includes(selectedTask.status)" class="button button--quiet" @click="deleteSelectedTask"><Trash2 :size="14" /> 删除任务</button><span><ShieldCheck :size="14" /> 职位、简历和消息均绑定批准摘要</span><button v-if="selectedTask.status === 'unknown_outcome' && automation.remoteMode" class="button button--warning button--small" type="button" :disabled="automation.busy" @click="automation.reconcileUnknownOutcome(selectedTask.id)"><RefreshCw :size="14" /> 核对平台结果</button><button @click="workspace.addToast(selectedTask.receiptSource === 'platform' ? '平台回执' : '本地核验记录', selectedTask.receipt, 'info')" v-else-if="selectedTask.receipt" class="button button--secondary button--small" type="button"><ExternalLink :size="14" /> 查看回执</button></footer>
      </article>
    </section>

    <section class="automation-principles panel"><span class="automation-principles__icon"><CheckCheck :size="18" /></span><div><strong>每次外部动作都有明确语义和回执</strong><p>BOSS记录“沟通发起/消息发送”，猎聘记录“平台简历投递”；网络中断后的未知结果会先核对再处理。</p></div><span><CirclePause :size="15" /> 可暂停后续提交</span><span><CircleCheck :size="15" /> 幂等执行</span></section>
    <ConfirmDialog :open="confirmAction!==null" :busy="confirmBusy" danger :title="confirmAction==='stop'?'彻底终止一条龙？':confirmAction==='delete'?'删除执行任务？':'清理已结束任务？'" :description="confirmAction==='stop'?'停止后不再继续发现和补充任务；已提交的任务会保留结果，可在任务队列核对。':confirmAction==='delete'&&selectedTask?`将删除 ${selectedTask.company} · ${selectedTask.role} 的本地执行记录。`:'只会清理已结束的任务；进行中、待处理或结果未知的任务会保留。'" :confirm-label="confirmAction==='stop'?'确认终止':'确认删除'" @close="confirmAction=null" @confirm="runConfirmedAction" />
  </div>
</template>
