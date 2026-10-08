<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowRight, CircleAlert, Clock3, Smartphone, Sparkles, TrendingUp } from '@lucide/vue'
import { careerLensApi, type ApplicationDailyStatsResponse } from '@/api/careerlens'
import { useWorkspaceStore } from '@/stores/workspace'
import { useSessionStore } from '@/stores/session'
import { useAutomationStore } from '@/stores/automation'

const workspace = useWorkspaceStore()
const session = useSessionStore()
const automation = useAutomationStore()
const dailyDays = ref<7 | 14 | 30>(7)
const dailyLoading = ref(false)
const dailyStats = ref<ApplicationDailyStatsResponse>({ days: 7, today: 0, total: 0, average: 0, items: [] })
const dailyMax = computed(() => Math.max(1, ...dailyStats.value.items.map((item) => item.total)))
const dateLabel = (date: string) => date === new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date()) ? '今天' : date.slice(5).replace('-', '/')
const todayLabel = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' }).format(new Date())

const failedCount = computed(() => automation.tasks.filter((task) => ['failed', 'page_changed', 'unknown_outcome'].includes(task.status)).length)
const phoneFollowupCount = computed(() => workspace.applications.filter((item) => ['contacted', 'applied', 'test', 'interview', 'offer'].includes(item.stage)).length)
const phaseLabel = computed(() => {
  if (!workspace.resumes.length) return '先发布一份求职资料，再用它筛选岗位和生成招呼语'
  if (automation.humanActionCount) return '平台正在等待登录、验证或其他人工操作'
  if (automation.includedReviewCount) return '检查岗位与首条招呼语后，才能进入执行'
  if (failedCount.value) return '先核对失败或未知结果，避免重复发送'
  const phase = automation.oneStopRun.phase.toUpperCase()
  if (phase.includes('DISCOVER')) return '正在发现下一批岗位'
  if (phase.includes('REVIEW')) return '正在等待招呼语审核'
  if (phase.includes('EXECUT')) return '正在建立沟通并核对结果'
  if (phase.includes('WAIT')) return '正在等待下一轮运行'
  return automation.oneStopRunning ? '正在处理当前批次' : '可以开始下一轮职位发现'
})
const nextAction = computed(() => {
  if (!workspace.resumes.length) return { label: '填写求职资料', to: '/resumes', reason: '尚未准备可用于匹配的求职资料' }
  if (automation.humanActionCount) return { label: '处理人工验证', to: '/automation', reason: `${automation.humanActionCount} 项任务正在等待登录或验证` }
  if (automation.includedReviewCount) return { label: `审核 ${automation.includedReviewCount} 条招呼语`, to: '/review-queue', reason: '审核通过后才能进入自动执行' }
  if (failedCount.value) return { label: '查看失败任务', to: '/automation', reason: `${failedCount.value} 项结果需要确认或重试` }
  if (automation.oneStopRunning || automation.oneStopRun.status === 'paused') return { label: '查看一条龙进度', to: '/automation', reason: '系统正在后台推进当前批次' }
  return { label: '开始发现岗位', to: '/discovery', reason: '设置条件后发现并筛选下一批岗位' }
})
const focusHeadline=computed(()=>{
 if(!workspace.resumes.length)return'先准备一份可用的求职资料'
 if(automation.humanActionCount)return`${automation.humanActionCount} 项任务需要人工处理`
 if(automation.includedReviewCount)return`${automation.includedReviewCount} 条招呼语等待审核`
 if(failedCount.value)return`${failedCount.value} 项执行结果需要核对`
 if(automation.oneStopRunning)return'一条龙正在持续运行'
 if(automation.oneStopRun.status==='paused')return'一条龙已暂停'
 return'可以开始发现下一批岗位'
})
const dashboardStats = computed(() => [
  { label: '今日已招呼', value: dailyLoading.value ? '—' : dailyStats.value.today, hint: '查看沟通记录', icon: Sparkles, tone: 'success',to:'/applications' },
  { label: '待审核', value: automation.includedReviewCount, hint: '检查招呼语与岗位', icon: Clock3, tone: 'info',to:'/review-queue' },
  { label: '需要处理', value: automation.humanActionCount + failedCount.value, hint: '处理验证或异常', icon: CircleAlert, tone: 'warning',to:'/automation' },
  { label: '手机待跟进', value: phoneFollowupCount.value, hint: '推进后续沟通', icon: Smartphone, tone: 'neutral',to:'/applications' },
])
const workflowSteps = [
  { label: '发现岗位', hint: '设置筛选条件', to: '/discovery' },
  { label: '审核招呼', hint: '确认首条消息', to: '/review-queue' },
  { label: '自动执行', hint: '建立首次沟通', to: '/automation' },
  { label: '手机跟进', hint: '推进后续流程', to: '/applications' },
]

async function loadDailyStats() {
  dailyLoading.value = true
  try { dailyStats.value = await careerLensApi.applicationDailyStats(dailyDays.value) }
  finally { dailyLoading.value = false }
}
watch(dailyDays, loadDailyStats)
watch(() => automation.activePlatformIdentity?.id, loadDailyStats)
onMounted(loadDailyStats)
</script>

<template>
  <div class="page dashboard-page">
    <header class="dashboard-welcome">
      <div><p class="dashboard-welcome__date">{{ todayLabel }}</p><h1>你好，{{ session.user?.displayName || '新用户' }}</h1><p>今天从最重要的一步开始，其他信息可以稍后处理。</p></div>
      <RouterLink to="/resumes" class="dashboard-profile-link" aria-label="打开求职资料"><span>管理求职资料</span><ArrowRight :size="15" /></RouterLink>
    </header>

    <section class="dashboard-focus" aria-live="polite">
      <div class="dashboard-focus__main">
        <span class="dashboard-focus__label">当前最重要的事</span>
        <h2>{{ focusHeadline }}</h2>
        <p>{{ phaseLabel }}</p>
        <div v-if="automation.oneStopRun.processedCount" class="dashboard-focus__progress">
          <span><small>已处理</small><strong>{{ automation.oneStopRun.processedCount }}</strong></span>
          <span><small>成功</small><strong>{{ automation.oneStopRun.succeededCount }}</strong></span>
          <span><small>失败</small><strong>{{ automation.oneStopRun.failedCount }}</strong></span>
        </div>
        <div v-else class="dashboard-focus__readiness">筛选条件与求职资料会在开始前再次核对</div>
      </div>
      <div class="dashboard-focus__action">
        <div><small>下一步</small><strong>{{ nextAction.label }}</strong><p>{{ nextAction.reason }}</p></div>
        <RouterLink :to="nextAction.to" class="dashboard-focus__button"><span>{{ nextAction.label }}</span><ArrowRight :size="17" /></RouterLink>
      </div>
    </section>

    <section class="dashboard-core-stats" aria-label="今日关键数据">
      <RouterLink v-for="stat in dashboardStats" :key="stat.label" :to="stat.to" :aria-label="`${stat.label} ${stat.value}，${stat.hint}`" :class="`dashboard-stat dashboard-stat--${stat.tone}`">
        <strong class="dashboard-stat__value">{{ stat.value }}</strong>
        <span class="dashboard-stat__copy"><small>{{ stat.label }}</small><em>{{ stat.hint }}</em></span>
        <ArrowRight class="dashboard-stat__arrow" :size="14"/>
      </RouterLink>
    </section>

    <section v-if="!workspace.resumes.length" class="empty-state panel"><h2>先填写求职资料</h2><p>保存求职方向、技能和项目亮点后，系统才能解释岗位匹配并生成招呼语。</p><RouterLink to="/resumes" class="button button--primary">填写求职资料</RouterLink></section>
    <section class="daily-delivery-panel" aria-labelledby="daily-delivery-title">
      <header class="daily-delivery-panel__header">
        <div><span class="dashboard-section-kicker"><TrendingUp :size="14" /> 沟通趋势</span><h2 id="daily-delivery-title">每日投递数量</h2><p>统计已确认成功的 BOSS 招呼与其他平台简历投递。</p></div>
        <div class="daily-delivery-panel__range" aria-label="统计范围"><span>统计范围</span><div><button v-for="days in [7,14,30]" :key="days" type="button" :class="{'active':dailyDays===days}" :aria-pressed="dailyDays===days" @click="dailyDays=days as 7|14|30">{{days}} 天</button></div></div>
      </header>
      <div class="daily-delivery-panel__body">
        <div class="daily-delivery-overview">
          <span>范围合计</span><strong>{{ dailyLoading ? '—' : dailyStats.total }}</strong><small>近 {{ dailyDays }} 天已完成动作</small>
          <div><span><small>今天</small><b>{{ dailyLoading ? '—' : dailyStats.today }}</b></span><span><small>日均</small><b>{{ dailyLoading ? '—' : dailyStats.average }}</b></span></div>
        </div>
        <div v-if="dailyLoading" class="daily-delivery-panel__loading" role="status">正在统计每日投递…</div>
        <div v-else-if="dailyStats.items.length" class="daily-delivery__chart">
          <div v-for="item in dailyStats.items" :key="item.date" class="daily-delivery__row" :title="`BOSS 招呼 ${item.contacted} · 简历投递 ${item.submitted}`">
            <span>{{ dateLabel(item.date) }}</span><div><i :style="{ width: `${item.total / dailyMax * 100}%` }"></i></div><strong>{{ item.total }}</strong>
          </div>
        </div>
        <div v-else class="daily-delivery-panel__empty">当前范围内还没有成功记录。</div>
      </div>
    </section>
    <section class="dashboard-guidance">
      <header><div><span class="dashboard-section-kicker">完整流程</span><h2>从发现岗位到手机跟进</h2></div><p>系统只发送已批准的首条招呼语，不会在 BOSS 自动发送简历。</p></header>
      <div class="dashboard-workflow">
        <RouterLink v-for="(step, index) in workflowSteps" :key="step.to" :to="step.to" class="dashboard-workflow__step"><span>{{String(index+1).padStart(2,'0')}}</span><div><strong>{{ step.label }}</strong><em>{{ step.hint }}</em></div></RouterLink>
      </div>
    </section>
  </div>
</template>
