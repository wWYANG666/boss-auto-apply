<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import {
  ArrowRight,
  Check,
  CheckCheck,
  ChevronRight,
  CircleAlert,
  FileText,
  LockKeyhole,
  MapPin,
  Send,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
  Copy,
  RotateCcw,
} from '@lucide/vue'
import PlatformBadge from '@/components/platform/PlatformBadge.vue'
import ConfirmDialog from '@/components/feedback/ConfirmDialog.vue'
import { useDialogFocus } from '@/composables/useDialogFocus'
import { useAutomationStore } from '@/stores/automation'

import { careerLensApi } from '@/api/careerlens'
const router = useRouter()
const automation = useAutomationStore()
const selectedReviewId = ref(automation.reviews[0]?.id ?? '')
const confirmOpen = ref(false)
const approvalDialog=ref<HTMLElement|null>(null)
useDialogFocus(confirmOpen,approvalDialog,()=>{if(!approving.value)confirmOpen.value=false})
const acknowledged = ref(false)
const approving = ref(false)
const confirmAction = ref<'delete' | 'clear' | 'material' | null>(null)
const confirmBusy = ref(false)
const materialPreview = ref<{ resumeId: string; summary: string; snapshotHash: string } | null>(null)
const reviewFilter = ref<'all' | 'low' | 'risk'>('all')
const reviewSort = ref<'match' | 'quality'>('quality')
const generatingId=ref('')
const reviewPage=ref(1)
const pageSize=10
const filteredReviews=computed(()=>{
  const filtered=automation.reviews.filter((review)=>reviewFilter.value==='low' ? greetingQuality(review)<80 : reviewFilter.value==='risk' ? Boolean(review.riskReasons.length||review.hardConflicts.length) : true)
  return [...filtered].sort((a,b)=>reviewSort.value==='quality' ? greetingQuality(a)-greetingQuality(b) : b.matchScore-a.matchScore)
})
const reviewPageCount=computed(()=>Math.max(1,Math.ceil(filteredReviews.value.length/pageSize)))
const pagedReviews=computed(()=>filteredReviews.value.slice((reviewPage.value-1)*pageSize,reviewPage.value*pageSize))
watch(reviewPageCount,count=>{if(reviewPage.value>count)reviewPage.value=count})
watch(reviewPage,()=>{selectedReviewId.value=pagedReviews.value[0]?.id??''})

const selectedReview = computed(() =>
  automation.reviews.find((review) => review.id === selectedReviewId.value) ?? automation.reviews[0],
)

const actionLabel = computed(() => selectedReview.value?.platform === 'boss' ? '发起沟通并发送招呼语' : '投递已核对的平台简历')
const unsavedReviews=computed(()=>automation.reviews.filter(review=>review.included&&['saving','error'].includes(automation.greetingSaveState[review.id]??'')))
const selectedSaveState=computed(()=>selectedReview.value?automation.greetingSaveState[selectedReview.value.id]:'idle')
const recommendedCandidate=computed(()=>selectedReview.value?.greetingCandidates?.filter(candidate=>candidate.valid).reduce((best,candidate)=>!best||candidate.qualityScore>best.qualityScore?candidate:best,undefined as typeof selectedReview.value.greetingCandidates[number]|undefined))

function greetingQuality(review: (typeof automation.reviews)[number]) {
  return review.greetingCandidates?.find((candidate) => candidate.text === review.greeting)?.qualityScore ?? review.greetingCandidates?.[0]?.qualityScore ?? 0
}

function togglePage(include: boolean) {
  pagedReviews.value.filter((review) => review.included !== include).forEach((review) => void automation.toggleReviewIncluded(review.id))
}

function handleCheckKey(event: KeyboardEvent, id: string) {
  if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); void automation.toggleReviewIncluded(id) }
}

async function deleteSelectedReview(){
  if(!selectedReview.value)return
  confirmAction.value='delete'
}
async function clearReviews(){
  if(automation.reviews.length)confirmAction.value='clear'
}

function updateGreeting(event: Event) {
  const input = event.target as HTMLTextAreaElement
  if (selectedReview.value) automation.updateGreeting(selectedReview.value.id, input.value)
}
async function generateCandidates(){
 if(!selectedReview.value||generatingId.value)return
 const id=selectedReview.value.id;generatingId.value=id
 try{await automation.generateGreetingCandidates(id)}finally{generatingId.value=''}
}
async function restoreRecommended(){
 if(selectedReview.value&&recommendedCandidate.value)await automation.selectGreetingCandidate(selectedReview.value.id,recommendedCandidate.value.text,recommendedCandidate.value.style)
}

async function reviewMaterial(){
 if(!selectedReview.value)return
 const material=await careerLensApi.platformResume()
 if(!material.resumeId)throw new Error('平台简历尚未取得，请检查配对与猎聘登录')
 materialPreview.value=material;confirmAction.value='material'
}
async function runConfirmedAction(){
  confirmBusy.value=true
  try{
    if(confirmAction.value==='delete'&&selectedReview.value){const id=selectedReview.value.id;await automation.deleteReview(id);selectedReviewId.value=automation.reviews[0]?.id??''}
    else if(confirmAction.value==='clear'){await automation.clearReviews();selectedReviewId.value=''}
    else if(confirmAction.value==='material'&&selectedReview.value&&materialPreview.value){await careerLensApi.updateApplicationPlan(selectedReview.value.id,{platformResumeId:materialPreview.value.resumeId,platformResumeHash:materialPreview.value.snapshotHash})}
    confirmAction.value=null
  }finally{confirmBusy.value=false}
}
async function copyGreeting(){if(selectedReview.value)await navigator.clipboard.writeText(selectedReview.value.greeting)}
async function approveAndStart() {
  if (!acknowledged.value||approving.value||unsavedReviews.value.length) return
  approving.value = true
  const approved = await automation.approveIncludedReviews().finally(()=>{approving.value=false})
  if (!approved) return
  confirmOpen.value = false
  await router.push('/automation')
}
</script>

<template>
  <div class="page review-page">
    <header class="page-header">
      <div><p class="eyebrow">第 2 步 · 发送前最后检查</p><h1>招呼审核</h1><p class="page-subtitle">确认岗位与首条招呼语。BOSS 流程不会自动发送简历，后续沟通由手机接管。</p></div>
      <div class="page-header__actions"><button class="button button--secondary" type="button" :disabled="!automation.reviews.length" @click="clearReviews"><Trash2 :size="16" /> 清空审核</button><button class="button button--primary" type="button" :disabled="automation.includedReviewCount === 0 || !!unsavedReviews.length" @click="acknowledged = false; confirmOpen = true"><CheckCheck :size="17" /> 批准所选 {{ automation.includedReviewCount }} 项</button></div>
    </header>
    <p v-if="unsavedReviews.length" class="review-save-notice" :class="{'review-save-notice--error':unsavedReviews.some(review=>automation.greetingSaveState[review.id]==='error')}" role="status">{{unsavedReviews.some(review=>automation.greetingSaveState[review.id]==='error')?'有招呼语保存失败，请重试后再批准。':'正在保存招呼语，完成后可批准。'}}</p>

    <section v-if="automation.reviews.length" class="review-layout">
      <aside class="review-queue panel">
        <div class="panel__header panel__header--compact"><div><span class="eyebrow">待审核队列</span><h2>{{ filteredReviews.length }} 个岗位</h2></div><span class="version-badge">当前审核版本</span></div>
        <div class="review-queue__summary"><span><strong>{{ automation.includedReviewCount }}</strong> 将执行</span><span><strong>{{ automation.reviews.filter((item) => !item.included).length }}</strong> 已跳过</span></div>
        <div class="review-queue__tools">
          <label><span class="sr-only">队列筛选</span><select v-model="reviewFilter"><option value="all">全部岗位</option><option value="low">只看低分文案</option><option value="risk">只看风险岗位</option></select></label>
          <label><span class="sr-only">队列排序</span><select v-model="reviewSort"><option value="quality">质量分从低到高</option><option value="match">匹配分从高到低</option></select></label>
          <div><button class="button button--quiet button--small" type="button" @click="togglePage(true)">全选本页</button><button class="button button--quiet button--small" type="button" @click="togglePage(false)">跳过本页</button></div>
        </div>
        <div class="review-queue__list">
          <button v-for="review in pagedReviews" :key="review.id" class="review-queue-item" :class="{ 'review-queue-item--active': review.id === selectedReviewId, 'review-queue-item--skipped': !review.included }" type="button" @click="selectedReviewId = review.id">
            <span class="review-include-check" :class="{ 'review-include-check--active': review.included }" role="checkbox" :aria-checked="review.included" tabindex="0" @keydown="handleCheckKey($event, review.id)" @click.stop="automation.toggleReviewIncluded(review.id)"><Check v-if="review.included" :size="13" /></span>
            <span class="review-queue-item__copy"><span><PlatformBadge :platform="review.platform" compact /><strong>{{ review.company }}</strong><b :class="`grade-pill grade-pill--${review.grade.toLowerCase()}`">{{ review.matchScore }}</b></span><small>{{ review.role }}</small><em><MapPin :size="11" /> {{ review.location }} · 文案 {{ greetingQuality(review) || '—' }} 分 <CircleAlert v-if="review.riskReasons.length || review.hardConflicts.length" :size="11" /></em></span>
            <ChevronRight :size="16" />
          </button>
        </div>
        <div v-if="reviewPageCount>1" class="pagination-controls"><button class="button button--quiet button--small" :disabled="reviewPage===1" @click="reviewPage--">上一页</button><span>第 {{reviewPage}} / {{reviewPageCount}} 页</span><button class="button button--quiet button--small" :disabled="reviewPage===reviewPageCount" @click="reviewPage++">下一页</button></div>
        <button v-if="automation.hasMoreReviews" class="button button--quiet load-more-button" type="button" :disabled="automation.busy" @click="automation.loadMoreReviews">加载更早计划</button>
      </aside>

      <main v-if="selectedReview" class="review-preview">
        <section class="review-preview__hero panel">
          <div class="review-preview__identity"><span class="company-logo">{{ selectedReview.company.slice(0, 1) }}</span><div><span><PlatformBadge :platform="selectedReview.platform" /><b>{{ selectedReview.grade }} 级推荐</b></span><h2>{{ selectedReview.company }} · {{ selectedReview.role }}</h2><p>{{ selectedReview.location }} · {{ selectedReview.salary }}</p></div></div><button class="button button--quiet button--small" type="button" @click="deleteSelectedReview"><Trash2 :size="15" /> 删除计划</button>
          <div class="review-preview__score"><strong>{{ selectedReview.matchScore }}</strong><span>匹配分</span></div>
        </section>

        <div v-if="selectedReview.hardConflicts.length" class="review-alert"><CircleAlert :size="18" /><p><strong>存在硬条件冲突</strong><span>{{ selectedReview.hardConflicts.join('、') }}。建议跳过或确认实际情况后再处理。</span></p></div>
        <div v-if="selectedReview.riskReasons.length" class="review-alert"><CircleAlert :size="18" /><p><strong>岗位风险提示 {{ selectedReview.riskScore }}</strong><span>{{ selectedReview.riskReasons.join('；') }}。这是基于岗位文本的本地提示，不是工商征信结论。</span></p></div>

        <section class="review-preview__grid">
          <article class="review-section panel">
            <header><span class="lens-icon"><FileText :size="18" /></span><div><span class="eyebrow">材料版本</span><h3>简历 v{{ selectedReview.resumeVersion }}</h3></div><RouterLink to="/resumes" class="button button--quiet button--small">管理版本</RouterLink></header>
            <div class="review-version-details"><span><Check :size="13" /> 内容哈希已锁定</span><span>{{selectedReview.platform==='boss'?'仅用于岗位匹配和生成招呼语，不会自动发送':'投递平台侧简历，需核对版本'}}</span></div>
            <button v-if="selectedReview.platform==='liepin'" class="button button--secondary" @click="reviewMaterial">读取并核对平台简历</button><div class="review-skill-evidence"><span v-for="skill in selectedReview.matchedSkills" :key="skill" class="skill-hit"><Check :size="11" /> {{ skill }}</span><span v-for="skill in selectedReview.missingSkills" :key="skill" class="skill-gap">缺 {{ skill }}</span></div>
          </article>

          <article class="review-section panel review-section--action">
            <header><span class="lens-icon lens-icon--evidence"><Send :size="18" /></span><div><span class="eyebrow">实际动作</span><h3>{{ actionLabel }}</h3></div></header>
            <p>{{ selectedReview.platform === 'boss' ? '将在职位页面发起沟通并发送已审核的招呼语；消息送达后记录成功，不会点击“发简历”或发送简历卡片。' : '调用猎聘投递能力发送已核对的平台简历，本地PDF仅用于预览与匹配，不会自动作为附件上传。' }}</p>
            <div class="execution-contract"><span><LockKeyhole :size="14" /> 职位ID</span><strong>{{ selectedReview.externalJobId }}</strong></div>
          </article>
        </section>

        <section class="greeting-editor panel">
          <header><div><span class="eyebrow">个性化招呼语</span><h2>发送内容</h2></div><span class="fact-guard"><ShieldCheck :size="14" /> 事实保护已开启</span></header>
          <div v-if="selectedReview.greetingCandidates?.length" class="greeting-candidate-tabs" role="tablist" aria-label="招呼语候选">
            <button v-for="candidate in selectedReview.greetingCandidates" :key="candidate.style" role="tab" :aria-selected="selectedReview.greeting===candidate.text" :class="{'active':selectedReview.greeting===candidate.text}" type="button" :disabled="!candidate.valid || selectedSaveState==='saving'" @click="automation.selectGreetingCandidate(selectedReview.id,candidate.text,candidate.style)">{{candidate.style==='PROJECT_EVIDENCE'?'项目证明型':candidate.style==='SKILL_MATCH'?'技术匹配型':'简短提问型'}} <strong>{{candidate.qualityScore}}分</strong><small v-if="candidate===recommendedCandidate">推荐</small></button>
          </div>
          <div class="greeting-textarea"><Sparkles :size="17" /><textarea :value="selectedReview.greeting" maxlength="100" aria-label="最终发送的招呼语" @input="updateGreeting"></textarea><button type="button" aria-label="编辑招呼语"><Pencil :size="14" /></button></div>
          <div class="greeting-editor__actions"><button class="button button--quiet button--small" type="button" @click="copyGreeting"><Copy :size="14" /> 复制</button><button v-if="recommendedCandidate && selectedReview.greeting!==recommendedCandidate.text" class="button button--quiet button--small" type="button" :disabled="selectedSaveState==='saving'" @click="restoreRecommended">恢复推荐</button><button class="button button--quiet button--small" type="button" :disabled="generatingId===selectedReview.id || selectedSaveState==='saving'" @click="generateCandidates"><RotateCcw :size="14" /> {{generatingId===selectedReview.id?'正在生成…':'重新生成'}}</button><span class="inline-save-state" :class="{'inline-save-state--error':selectedSaveState==='error'}" aria-live="polite">{{ selectedSaveState==='saving'?'正在保存…':selectedSaveState==='saved'?'已保存':selectedSaveState==='error'?'保存失败':'' }}</span><button v-if="selectedSaveState==='error'" class="button button--secondary button--small" type="button" @click="automation.saveGreeting(selectedReview.id)">重试保存</button></div>
          <p v-if="selectedSaveState==='error'" class="review-save-error" role="alert">{{automation.greetingSaveErrors[selectedReview.id]}} · 修改或重试后才能批准</p>
          <details v-if="selectedReview.greetingCandidates?.length" class="greeting-score-details"><summary>评分依据与候选提示</summary><p>质量分由服务端生成；具体加减分明细尚未提供，不以推测分数代替。请选择与岗位和简历事实最吻合的文案。</p><p v-for="candidate in selectedReview.greetingCandidates.filter(item=>item.warnings.length)" :key="candidate.style">{{candidate.style==='PROJECT_EVIDENCE'?'项目证明型':candidate.style==='SKILL_MATCH'?'技术匹配型':'简短提问型'}}：{{candidate.warnings.join('；')}}</p></details>
          <div v-if="selectedReview.greetingEvidence" class="greeting-evidence"><strong>引用证据</strong><span v-if="selectedReview.greetingEvidence.project">项目：{{selectedReview.greetingEvidence.project}}</span><span v-if="selectedReview.greetingEvidence.skills">技能：{{(selectedReview.greetingEvidence.skills as string[]).join('、')}}</span><span v-if="selectedReview.greetingEvidence.fact">事实：{{selectedReview.greetingEvidence.fact}}</span></div>
          <footer><span>仅使用简历中的已确认事实</span><strong>{{ selectedReview.greeting.length }} / 100</strong></footer>
        </section>

        <section class="approval-summary panel">
          <div><span class="eyebrow">将要发送的数据</span><h2>审批摘要</h2></div>
          <div class="approval-summary__items"><span><small>平台动作</small><strong>{{ selectedReview.platform === 'boss' ? '仅发送招呼语' : '简历投递' }}</strong></span><span><small>简历版本</small><strong>v{{ selectedReview.resumeVersion }}</strong></span><span><small>招聘者</small><strong>{{ selectedReview.recruiter }} · {{ selectedReview.recruiterOnline ? '在线' : selectedReview.recruiterActive }}</strong></span><span><small>公司规模</small><strong>{{ selectedReview.companySize }}</strong></span><span><small>历史接触</small><strong>公司 {{ selectedReview.companyTaskCount }} · 招聘者 {{ selectedReview.recruiterTaskCount }}</strong></span><span v-if="selectedReview.commuteDistanceKm != null"><small>通勤估算</small><strong>{{ selectedReview.commuteDistanceKm }}公里<span v-if="selectedReview.commuteDurationMinutes != null"> · {{ selectedReview.commuteDurationMinutes }}分钟</span></strong></span><span><small>招呼语</small><strong>请逐项核对</strong></span></div>
          <div class="approval-summary__note"><LockKeyhole :size="15" /> 批准后若修改简历或招呼语，当前批准会自动失效。</div>
        </section>
      </main>
    </section>

    <section v-else class="empty-state panel"><span><CheckCheck :size="24" /></span><h2>审核队列还是空的</h2><p>前往职位发现，选择高匹配岗位后加入这里。</p><RouterLink to="/discovery" class="button button--primary">发现职位 <ArrowRight :size="15" /></RouterLink></section>

    <Teleport to="body">
      <Transition name="fade">
        <div v-if="confirmOpen" class="modal-backdrop" @mousedown.self="confirmOpen = false">
          <section ref="approvalDialog" class="dialog-card approval-dialog" role="dialog" aria-modal="true" aria-label="最终确认投递计划">
            <header class="dialog-card__header"><div><span class="eyebrow">最终确认</span><h2>批准 {{ automation.includedReviewCount }} 个执行计划</h2></div><button class="icon-button" type="button" aria-label="关闭" @click="confirmOpen = false"><X :size="18" /></button></header>
            <div class="dialog-card__body"><div class="approval-dialog__icon"><LockKeyhole :size="23" /></div><p>将批准 {{ automation.includedReviewCount }} 个岗位。BOSS 只发送首条招呼语，不会发送简历；沟通建立后自动化退出，后续由手机端接管。遇到登录或验证码时会暂停并通知你。</p><div class="approval-platform-summary"><span><PlatformBadge platform="boss" /><strong>{{ automation.reviews.filter((item) => item.included && item.platform === 'boss').length }}</strong><small>仅发起沟通并发送已审核招呼语</small></span></div><label class="approval-check"><input type="checkbox" v-model="acknowledged" /><i><Check :size="12" /></i><span>我已检查岗位、资料版本和发送内容</span></label></div>
            <footer class="dialog-card__footer"><button class="button button--quiet" type="button" :disabled="approving" @click="confirmOpen = false">返回检查</button><button class="button button--primary" type="button" :disabled="approving || !acknowledged || !!unsavedReviews.length" @click="approveAndStart"><CheckCheck :size="16" /> {{ approving ? '正在创建执行任务…' : '批准并进入执行中心' }}</button></footer>
          </section>
        </div>
      </Transition>
    </Teleport>
    <ConfirmDialog
      :open="confirmAction !== null"
      :busy="confirmBusy"
      :danger="confirmAction !== 'material'"
      :title="confirmAction === 'delete' ? '删除这条审核计划？' : confirmAction === 'clear' ? '清空全部招呼审核？' : '确认平台简历版本？'"
      :description="confirmAction === 'delete' && selectedReview ? `将删除 ${selectedReview.company} · ${selectedReview.role} 的审核计划。` : confirmAction === 'clear' ? '未进入执行的审核计划会被清空；已有任务仍需在自动执行页处理。' : materialPreview ? `平台简历 ID：${materialPreview.resumeId}。${materialPreview.summary} 请确认已在平台页面核对完整材料。` : ''"
      :confirm-label="confirmAction === 'material' ? '确认材料' : '确认删除'"
      @close="confirmAction = null"
      @confirm="runConfirmedAction"
    />
  </div>
</template>
function handleCheckKey(event: KeyboardEvent, id: string) {
  if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); void automation.toggleReviewIncluded(id) }
}
  Pencil,
