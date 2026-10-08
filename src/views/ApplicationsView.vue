<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  CalendarClock,
  AlertCircle,
  Check,
  GripVertical,
  KanbanSquare,
  List,
  MapPin,
  Plus,
  Search,
  X,
  Workflow,
  ShieldCheck,
  Smartphone,
  Trash2,
} from '@lucide/vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useAutomationStore } from '@/stores/automation'
import PlatformBadge from '@/components/platform/PlatformBadge.vue'
import AutomationStatusBadge from '@/components/automation/AutomationStatusBadge.vue'
import { useDialogFocus } from '@/composables/useDialogFocus'
import type { ApplicationStage } from '@/types'

import { careerLensApi } from '@/api/careerlens'
const workspace = useWorkspaceStore()
const automation = useAutomationStore()
const displayModeStorageKey='careerlens:applications-display-mode:v1'
function savedDisplayMode():'board'|'list'{
  try{const saved=localStorage.getItem(displayModeStorageKey);if(saved==='list'||saved==='board')return saved;return window.matchMedia('(max-width: 720px)').matches?'list':'board'}catch{return'board'}
}
const displayMode = ref<'board' | 'list'>(savedDisplayMode())
function setDisplayMode(mode:'board'|'list'){
  displayMode.value=mode
  try{localStorage.setItem(displayModeStorageKey,mode)}catch{/* Storage can be unavailable in private browser contexts. */}
}
const query = ref('')
const accountScope=ref<'current'|'all'>('current')
const applicationPage=ref(1)
const applicationPageSize=10
async function changeAccountScope(){await workspace.loadApplications(accountScope.value==='all')}
const modalOpen = ref(false)
const createDialogElement=ref<HTMLElement|null>(null)
const actionDialogElement=ref<HTMLElement|null>(null)
const timelineDialogElement=ref<HTMLElement|null>(null)
const draggingId = ref('')
const newApplication = reactive({ company: '', role: '', location: '' })
const newApplicationStage = ref<ApplicationStage>('wishlist')
const saving = ref(false)
const actionDialog = reactive({ open:false, type:'' as 'note'|'next'|'follow'|'delete'|'', applicationId:'', company:'', role:'', value:'', dueAt:'', error:'' })

const followups=ref<Awaited<ReturnType<typeof careerLensApi.listFollowups>>>([])
const timeline=ref<Awaited<ReturnType<typeof careerLensApi.applicationDetail>>|null>(null)
const activeFollowups=computed(()=>followups.value.filter(item=>!item.completed))
const followupGroups=computed(()=>{
 const start=new Date();start.setHours(0,0,0,0);const today=start.getTime(),tomorrow=today+24*60*60*1000
 return [
  {id:'overdue',label:'已经逾期',tone:'danger',items:activeFollowups.value.filter(item=>new Date(item.dueAt).getTime()<today)},
  {id:'today',label:'今天',tone:'warning',items:activeFollowups.value.filter(item=>{const time=new Date(item.dueAt).getTime();return time>=today&&time<tomorrow})},
  {id:'future',label:'接下来',tone:'neutral',items:activeFollowups.value.filter(item=>new Date(item.dueAt).getTime()>=tomorrow)},
 ].filter(group=>group.items.length)
})
function closeTimeline(){timeline.value=null}
onMounted(async()=>{await workspace.loadApplications();followups.value=await careerLensApi.listFollowups()})
useDialogFocus(modalOpen,createDialogElement,()=>{if(!saving.value)modalOpen.value=false},()=>createDialogElement.value?.querySelector('input')??null)
useDialogFocus(computed(()=>actionDialog.open),actionDialogElement,closeAction,()=>actionDialogElement.value?.querySelector('textarea')??actionDialogElement.value?.querySelector('button')??null)
useDialogFocus(computed(()=>Boolean(timeline.value)),timelineDialogElement,closeTimeline)
async function showTimeline(id:string){timeline.value=await careerLensApi.applicationDetail(id)}
function openAction(type:'note'|'next'|'follow'|'delete',application:{id:string;company:string;role:string}){
  const defaultDue=new Date(Date.now()+24*60*60*1000);defaultDue.setMinutes(0,0,0)
  Object.assign(actionDialog,{open:true,type,applicationId:application.id,company:application.company,role:application.role,value:'',dueAt:defaultDue.toISOString().slice(0,16),error:''})
}
function closeAction(){if(!saving.value)actionDialog.open=false}
async function submitAction(){
  actionDialog.error=''
  if(actionDialog.type!=='delete'&&!actionDialog.value.trim()){actionDialog.error=actionDialog.type==='follow'?'请填写跟进内容':'请填写内容';return}
  if(actionDialog.type==='follow'&&!Number.isFinite(new Date(actionDialog.dueAt).getTime())){actionDialog.error='请选择有效的提醒时间';return}
  saving.value=true
  try{
    if(actionDialog.type==='note'){await careerLensApi.applicationNote(actionDialog.applicationId,actionDialog.value.trim());timeline.value=await careerLensApi.applicationDetail(actionDialog.applicationId)}
    else if(actionDialog.type==='next'){await careerLensApi.updateApplication(actionDialog.applicationId,{nextAction:actionDialog.value.trim(),nextActionSet:true});await workspace.loadApplications()}
    else if(actionDialog.type==='follow'){await careerLensApi.createFollowup(actionDialog.applicationId,actionDialog.value.trim(),new Date(actionDialog.dueAt).toISOString());followups.value=await careerLensApi.listFollowups()}
    else if(actionDialog.type==='delete'){await workspace.deleteApplication(actionDialog.applicationId);if(timeline.value?.application.id===actionDialog.applicationId)timeline.value=null;followups.value=await careerLensApi.listFollowups()}
    workspace.addToast(actionDialog.type==='delete'?'记录已删除':'已保存',actionDialog.type==='follow'?'跟进提醒已创建。':'沟通记录已更新。','success')
    actionDialog.open=false
  }catch(cause){actionDialog.error=cause instanceof Error?cause.message:'操作失败，请稍后重试'}finally{saving.value=false}
}
async function complete(id:string){await careerLensApi.completeFollowup(id,true);followups.value=await careerLensApi.listFollowups()}
function canDeleteApplication(application:{stage:ApplicationStage;automationStatus?:string}){
  return application.stage==='wishlist' && application.automationStatus!=='succeeded'
}


const columns: Array<{ id: ApplicationStage; label: string; hint: string }> = [
  { id: 'wishlist', label: '待投递', hint: '准备材料' },
  {id:'contacted',label:'已招呼',hint:'手机接管跟进'},
  { id: 'applied', label: '已发简历', hint: '等待筛选' },
  { id: 'test', label: '笔试', hint: '及时准备' },
  { id: 'interview', label: '面试', hint: '重点跟进' },
  { id: 'offer', label: 'Offer', hint: '确认选择' },
  {id:'rejected',label:'已拒绝',hint:'记录原因'}, {id:'withdrawn',label:'已撤回',hint:'主动撤回'}, {id:'closed',label:'已关闭',hint:'岗位关闭'}, {id:'hired',label:'已入职',hint:'求职结束'},
]

const stageLabels: Record<ApplicationStage, string> = {
  contacted:'已招呼',rejected:'已结束',withdrawn:'已结束',closed:'已结束',hired:'已结束',
  wishlist: '待处理', applied: '已发简历', test: '笔试', interview: '面试', offer: 'Offer',
}

const filteredApplications = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return workspace.applications
  return workspace.applications.filter((item) =>
    `${item.company} ${item.role} ${item.location}`.toLowerCase().includes(keyword),
  )
})

const applicationPageCount = computed(() => Math.max(1, Math.ceil(filteredApplications.value.length / applicationPageSize)))
const visibleApplications = computed(() => filteredApplications.value.slice(
  (applicationPage.value - 1) * applicationPageSize,
  applicationPage.value * applicationPageSize,
))
watch([query, accountScope, displayMode], () => { applicationPage.value = 1 })
watch(applicationPageCount, (count) => { if (applicationPage.value > count) applicationPage.value = count })

const applicationStats = computed(() => ({
  total: workspace.applications.length,
  applied: workspace.applications.filter((item) => item.stage !== 'wishlist').length,
  active: workspace.applications.filter((item) => ['test', 'interview'].includes(item.stage)).length,
  offers: workspace.applications.filter((item) => item.stage === 'offer').length,
}))

function applicationsFor(stage: ApplicationStage) {
  return visibleApplications.value.filter((item) => item.stage === stage)
}

async function handleDrop(stage: ApplicationStage) {
  if(accountScope.value==='all'){workspace.addToast('全部账号视图为只读','切换到具体BOSS账号后再修改投递阶段。','info');return}
  if (draggingId.value) await workspace.moveApplication(draggingId.value, stage)
  draggingId.value = ''
}

function openCreate(stage: ApplicationStage = 'wishlist') {
  newApplicationStage.value = stage
  modalOpen.value = true
}

async function createApplication() {
  if (!newApplication.company.trim() || !newApplication.role.trim()) {
    workspace.addToast('请填写公司和岗位', '这两个字段用于识别投递记录。', 'warning')
    return
  }
  saving.value = true
  try {
    await workspace.addApplication({
      company: newApplication.company.trim(), role: newApplication.role.trim(),
      location: newApplication.location.trim(), stage: newApplicationStage.value,
    })
    Object.assign(newApplication, { company: '', role: '', location: '' })
    modalOpen.value = false
  } finally { saving.value = false }
}
</script>

<template>
  <div class="page applications-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">第 4 步 · 手机接管</p>
        <h1>沟通管理</h1>
        <p class="page-subtitle">自动化建立首轮沟通后退出；在这里记录手机端回复、简历、笔试和面试进展。</p>
      </div>
      <div class="page-header__actions"><RouterLink to="/discovery" class="button button--secondary"><Workflow :size="16" /> 自动发现职位</RouterLink><button class="button button--primary" type="button" @click="openCreate()"><Plus :size="17" /> 添加岗位</button></div>
    </header>

    <section class="application-stats-row">
      <div><span>本轮岗位</span><strong>{{ applicationStats.total }}</strong><small>手动与自动记录合计</small></div>
      <div><span>已投递</span><strong>{{ applicationStats.applied }}</strong><small>含沟通发起与简历投递</small></div>
      <div><span>推进中</span><strong>{{ applicationStats.active }}</strong><small>{{ automation.humanActionCount }} 项自动任务需处理</small></div>
      <div class="application-stats-row__highlight"><span>Offer</span><strong>{{ applicationStats.offers }}</strong><small><Check :size="12" /> 已记录的录用结果</small></div>
    </section>

    <section v-if="automation.humanActionCount" class="application-automation-banner">
      <span><Workflow :size="18" /></span><p><strong>{{ automation.humanActionCount }} 项执行任务已暂停</strong><small>平台需要你完成登录或安全验证，现有投递记录不受影响。</small></p><RouterLink to="/automation" class="button button--warning button--small">前往处理</RouterLink>
    </section>

    <section v-if="followupGroups.length" class="followup-agenda panel" aria-labelledby="followup-agenda-title">
      <header><div><span class="eyebrow">需要跟进</span><h2 id="followup-agenda-title">今天先处理这些事项</h2></div><strong>{{activeFollowups.length}}</strong></header>
      <div class="followup-agenda__groups"><section v-for="group in followupGroups" :key="group.id" :class="`followup-group followup-group--${group.tone}`"><h3><i></i>{{group.label}} <span>{{group.items.length}}</span></h3><article v-for="item in group.items" :key="item.id"><CalendarClock :size="15"/><span><strong>{{item.title}}</strong><small>{{new Date(item.dueAt).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}}</small></span><button class="button button--quiet button--small" type="button" @click="complete(item.id)"><Check :size="14"/> 完成</button></article></section></div>
    </section>
<div class="application-toolbar">
      <label class="compact-search"><Search :size="16" /><input v-model="query" placeholder="搜索公司、岗位或地点" /></label>
      <label class="topbar-account-select"><span>数据范围</span><select v-model="accountScope" @change="changeAccountScope"><option value="current">当前BOSS账号</option><option value="all">全部BOSS账号（只读）</option></select></label>

      <div class="view-toggle view-toggle--icons">
        <button :class="{ active: displayMode === 'board' }" type="button" aria-label="看板视图" :aria-pressed="displayMode==='board'" @click="setDisplayMode('board')"><KanbanSquare :size="16" /> 看板</button>
        <button :class="{ active: displayMode === 'list' }" type="button" aria-label="列表视图" :aria-pressed="displayMode==='list'" @click="setDisplayMode('list')"><List :size="16" /> 列表</button>
      </div>
    </div>

    <div v-if="workspace.applications.length === 0" class="empty-state panel">
      <span><KanbanSquare :size="24" /></span><h2>还没有投递记录</h2>
      <p>手动添加一个岗位，或从职位发现页创建经过审核的投递任务。</p>
      <button class="button button--primary" type="button" @click="openCreate()"><Plus :size="16" /> 添加第一个岗位</button>
    </div>

    <section v-else-if="displayMode === 'board'" class="kanban-board">
      <div
        v-for="column in columns"
        :key="column.id"
        class="kanban-column"
        @dragover.prevent
        @drop="handleDrop(column.id)"
      >
        <header class="kanban-column__header">
          <div><span class="kanban-column__dot" :class="`kanban-column__dot--${column.id}`"></span><strong>{{ column.label }}</strong><span>{{ applicationsFor(column.id).length }}</span></div>
          <small>{{ column.hint }}</small>
        </header>
        <div class="kanban-column__body">
          <article
            v-for="application in applicationsFor(column.id)"
            :key="application.id"
            class="application-card"
            :draggable="accountScope==='current'"
            @dragstart="draggingId = application.id"
            @dragend="draggingId = ''"
          >
            <div class="application-card__top">
              <span class="company-logo" :style="{ background: application.logoTone }">{{ application.logoText }}</span>
              <div class="application-card__badges"><PlatformBadge v-if="application.platform" :platform="application.platform" compact /><span class="application-score" :class="{ 'application-score--empty': application.matchScore === 0 }">{{ application.matchScore || '—' }}</span></div>
            </div>
            <h3>{{ application.company }}</h3><small v-if="accountScope==='all'&&application.platformIdentityId">{{automation.platformIdentities.find(identity=>identity.id===application.platformIdentityId)?.displayName||'BOSS账号'}}</small><details v-if="accountScope==='current'" class="application-action-menu"><summary class="button button--quiet">更多</summary><div><button type="button" @click="openAction('note',application)">添加备注</button><button type="button" @click="openAction('next',application)">设置下一步</button><button type="button" @click="openAction('follow',application)">创建提醒</button><button type="button" @click="showTimeline(application.id)">查看时间线</button><button v-if="canDeleteApplication(application)" type="button" class="danger" @click="openAction('delete',application)"><Trash2 :size="14" /> 删除记录</button><span v-else>已执行记录不可删除</span></div></details>
            <p>{{ application.role }}</p>
            <span class="application-location"><MapPin :size="13" /> {{ application.location }}</span>
            <div class="application-tags"><span v-for="tag in application.tags" :key="tag">{{ tag }}</span></div>
            <div v-if="application.nextAction" class="next-action" :class="{ 'next-action--urgent': ['test', 'interview', 'offer'].includes(application.stage) }">
              <CalendarClock :size="14" /> {{ application.nextAction }}
            </div>
            <div v-if="application.automationStatus" class="application-automation-meta"><AutomationStatusBadge :status="application.automationStatus" /><span>主简历 v{{ application.resumeVersion }}</span></div>
            <div v-if="application.stage==='contacted'" class="mobile-handoff"><Smartphone :size="14" /><span><strong>自动化已退出</strong>下一步：在手机 BOSS 中跟进</span></div>
            <div v-if="application.receipt" class="application-receipt"><ShieldCheck :size="12" /> {{ application.actionType === 'CHAT' ? '沟通回执' : '投递回执' }} · {{ application.receipt }}</div>
            <footer><span>{{ application.updatedAt }}更新</span><GripVertical :size="15" /></footer>
          </article>
          <button v-if="accountScope==='current'" class="column-add-button" type="button" @click="openCreate(column.id)"><Plus :size="15" /> 添加到{{ column.label }}</button>
        </div>
      </div>
    </section>

    <section v-else class="application-table panel">
      <div class="application-table__head"><span>公司与岗位</span><span>来源</span><span>阶段</span><span>执行状态</span><span>下一步</span><span></span></div>
      <article v-for="application in visibleApplications" :key="application.id" class="application-table__row">
        <div><span class="company-logo" :style="{ background: application.logoTone }">{{ application.logoText }}</span><span><strong>{{ application.company }}</strong><small>{{ application.role }} · {{ application.location }}</small></span></div>
        <span><PlatformBadge v-if="application.platform" :platform="application.platform" compact /><span v-else>手动</span></span>
        <span><span class="stage-pill" :class="`stage-pill--${application.stage}`">{{ stageLabels[application.stage] }}</span></span>
        <span><AutomationStatusBadge v-if="application.automationStatus" :status="application.automationStatus" /><span v-else>手动记录</span></span>
        <span>{{ application.nextAction || '暂时没有待办' }}</span>
<span><select aria-label="更新沟通阶段" :disabled="accountScope==='all'" :value="application.stage" @change="workspace.moveApplication(application.id, ($event.target as HTMLSelectElement).value as ApplicationStage)"><option v-for="column in columns" :key="column.id" :value="column.id">{{ column.label }}</option></select><button v-if="accountScope==='current'" class="button button--quiet button--small" type="button" @click="openAction('next',application)">更多</button></span>
      </article>
    </section>

    <div v-if="applicationPageCount > 1" class="pagination-controls application-pagination">
      <button class="button button--quiet button--small" type="button" :disabled="applicationPage === 1" @click="applicationPage--">上一页</button>
      <span>第 {{ applicationPage }} / {{ applicationPageCount }} 页 · 每页 {{ applicationPageSize }} 条</span>
      <button class="button button--quiet button--small" type="button" :disabled="applicationPage === applicationPageCount" @click="applicationPage++">下一页</button>
    </div>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="modalOpen" class="modal-backdrop" @mousedown.self="modalOpen = false">
          <form ref="createDialogElement" class="dialog-card" role="dialog" aria-modal="true" aria-label="添加岗位" @submit.prevent="createApplication">
            <header class="dialog-card__header">
              <div><span class="eyebrow">机会记录</span><h2>添加一个岗位</h2></div>
              <button class="icon-button" type="button" aria-label="关闭" @click="modalOpen = false"><X :size="18" /></button>
            </header>
            <div class="dialog-card__body">
              <label class="field"><span>公司名称</span><input v-model="newApplication.company" autofocus placeholder="公司名称" /></label>
              <label class="field"><span>岗位名称</span><input v-model="newApplication.role" placeholder="岗位名称" /></label>
              <label class="field"><span>工作地点</span><input v-model="newApplication.location" placeholder="工作地点（可选）" /></label>
              <label class="field"><span>初始阶段</span><select v-model="newApplicationStage"><option v-for="column in columns" :key="column.id" :value="column.id">{{ column.label }}</option></select></label>
              <div class="dialog-tip"><span class="lens-icon"><KanbanSquare :size="16" /></span><p><strong>手动记录岗位</strong><span>岗位将加入“待投递”，方便统一管理后续状态和备注。</span></p></div>
            </div>
            <footer class="dialog-card__footer">
              <button class="button button--quiet" type="button" @click="modalOpen = false">取消</button>
              <button class="button button--primary" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '添加岗位' }}</button>
            </footer>
          </form>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="fade">
        <div v-if="actionDialog.open" class="modal-backdrop" @mousedown.self="closeAction">
          <form ref="actionDialogElement" class="dialog-card followup-dialog" role="dialog" aria-modal="true" aria-labelledby="followup-dialog-title" @submit.prevent="submitAction">
            <header class="dialog-card__header"><div><span class="eyebrow">{{ actionDialog.company }} · {{ actionDialog.role }}</span><h2 id="followup-dialog-title">{{ actionDialog.type==='note'?'添加备注':actionDialog.type==='next'?'设置下一步':actionDialog.type==='follow'?'创建跟进提醒':'删除沟通记录' }}</h2></div><button class="icon-button" type="button" aria-label="关闭" :disabled="saving" @click="closeAction"><X :size="18" /></button></header>
            <div class="dialog-card__body">
              <template v-if="actionDialog.type!=='delete'">
                <label class="field"><span>{{ actionDialog.type==='note'?'备注内容':actionDialog.type==='next'?'下一步行动':'跟进内容' }}</span><textarea v-model="actionDialog.value" autofocus rows="4" :placeholder="actionDialog.type==='follow'?'例如：查看对方回复并准备发送简历':'写下明确、可执行的下一步'"></textarea></label>
                <label v-if="actionDialog.type==='follow'" class="field"><span>提醒时间</span><input v-model="actionDialog.dueAt" type="datetime-local" /></label>
              </template>
              <p v-else>将删除这条尚未执行的记录。此操作不会影响平台上的岗位或沟通。</p>
              <p v-if="actionDialog.error" class="field-error" role="alert">{{ actionDialog.error }} · 请检查后重试</p>
            </div>
            <footer class="dialog-card__footer"><button class="button button--quiet" type="button" :disabled="saving" @click="closeAction">取消</button><button class="button" :class="actionDialog.type==='delete'?'button--danger':'button--primary'" type="submit" :disabled="saving">{{ saving?'正在保存…':actionDialog.type==='delete'?'确认删除':'保存' }}</button></footer>
          </form>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="drawer">
        <div v-if="timeline" class="drawer-backdrop" @mousedown.self="closeTimeline">
          <aside ref="timelineDialogElement" class="drawer-panel application-timeline-drawer" role="dialog" aria-modal="true" aria-labelledby="application-timeline-title">
            <header class="drawer-panel__header"><div><span class="eyebrow">沟通记录</span><h2 id="application-timeline-title">{{timeline.application.company}} · {{timeline.application.role}}</h2><p>{{timeline.application.location}} · {{stageLabels[timeline.application.stage]}}</p></div><button class="icon-button" type="button" aria-label="关闭时间线" @click="closeTimeline"><X :size="18"/></button></header>
            <div class="application-timeline-drawer__summary"><span><small>当前阶段</small><strong>{{stageLabels[timeline.application.stage]}}</strong></span><span><small>下一步</small><strong>{{timeline.application.nextAction||'尚未设置'}}</strong></span></div>
            <div v-if="timeline.events.length" class="application-event-list"><article v-for="event in timeline.events" :key="event.id"><i></i><div><strong>{{event.detail}}</strong><time>{{new Date(event.occurredAt).toLocaleString('zh-CN')}}</time></div></article></div>
            <div v-else class="application-timeline-empty"><AlertCircle :size="22"/><strong>还没有沟通记录</strong><p>添加备注或更新阶段后，时间线会保留变化。</p></div>
            <footer><button class="button button--secondary" type="button" @click="openAction('note',timeline.application)">添加备注</button><button class="button button--primary" type="button" @click="openAction('next',timeline.application)">设置下一步</button></footer>
          </aside>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>
