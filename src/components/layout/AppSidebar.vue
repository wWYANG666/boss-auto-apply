<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  FileText,
  LayoutDashboard,
  Radar,
  ListChecks,
  Workflow,
  PanelLeftClose,
  PanelLeftOpen,
  Settings2,
  Target,
  MoreHorizontal,
} from '@lucide/vue'
import { useSessionStore } from '@/stores/session'
import { useWorkspaceStore } from '@/stores/workspace'
import { useAutomationStore } from '@/stores/automation'

interface NavigationItem {
  label: string
  to: string
  icon: Component
  match?: string
  badge?: string
  mobileVisible?: boolean
}

const route = useRoute()
const session = useSessionStore()
const workspace = useWorkspaceStore()
const automation = useAutomationStore()
const collapsed = ref(false)
const displayName = computed(() => session.user?.displayName || '用户')
const userContext = computed(() => workspace.activeResumeSummary?.headline || session.user?.email || '个人工作区')

const preparationNavigation = computed<NavigationItem[]>(() => [
  { label: '工作台', to: '/', icon: LayoutDashboard },
])

const executionNavigation = computed<NavigationItem[]>(() => [
  { label: '职位发现', to: '/discovery', icon: Radar, mobileVisible: true },
  { label: '招呼审核', to: '/review-queue', icon: ListChecks, badge: automation.includedReviewCount ? String(automation.includedReviewCount) : undefined, mobileVisible: true },
  { label: '自动执行', to: '/automation', icon: Workflow, mobileVisible: true },
  { label: '沟通管理', to: '/applications', icon: Target, mobileVisible: true },
])

const auxiliaryNavigation = computed<NavigationItem[]>(() => [
  { label: '求职资料', to: '/resumes', icon: FileText, match: '/resumes' },
  { label: '设置', to: '/settings', icon: Settings2 },
  { label: '更多', to: '/settings', icon: MoreHorizontal, mobileVisible: true },
])

function isActive(item: NavigationItem) {
  if (item.to === '/') return route.path === '/'
  return route.path === item.to || Boolean(item.match && route.path.startsWith(item.match))
}
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <div class="sidebar__brand">
      <RouterLink to="/" class="brand-lockup" aria-label="职镜首页">
        <span class="brand-mark" aria-hidden="true">
          <span class="brand-mark__lens"></span>
          <span class="brand-mark__point"></span>
        </span>
        <span class="brand-copy">
          <strong>职镜</strong>
          <small>CareerLens</small>
        </span>
      </RouterLink>
      <button
        class="icon-button sidebar__collapse"
        type="button"
        :aria-label="collapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="collapsed = !collapsed"
      >
        <PanelLeftOpen v-if="collapsed" :size="18" />
        <PanelLeftClose v-else :size="18" />
      </button>
    </div>

    <nav class="sidebar__nav" aria-label="主导航">
      <span class="sidebar__eyebrow">求职准备</span>
      <RouterLink
        v-for="item in preparationNavigation"
        :key="item.to"
        :to="item.to"
        class="nav-link"
        :class="{ 'nav-link--active': isActive(item), 'nav-link--mobile': item.mobileVisible }"
        :title="collapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="19" :stroke-width="1.8" />
        <span class="nav-link__label">{{ item.label }}</span>
        <span v-if="item.badge" class="nav-link__badge">{{ item.badge }}</span>
      </RouterLink>
      <span class="sidebar__eyebrow sidebar__eyebrow--execution">机会执行</span>
      <RouterLink
        v-for="item in executionNavigation"
        :key="item.to"
        :to="item.to"
        class="nav-link"
        :class="{ 'nav-link--active': isActive(item), 'nav-link--mobile': item.mobileVisible }"
        :title="collapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="19" :stroke-width="1.8" />
        <span class="nav-link__label">{{ item.label }}</span>
        <span v-if="item.badge" class="nav-link__badge">{{ item.badge }}</span>
      </RouterLink>
      <span class="sidebar__eyebrow sidebar__eyebrow--execution">辅助工具</span>
      <RouterLink
        v-for="item in auxiliaryNavigation"
        :key="`${item.to}-${item.label}`"
        :to="item.to"
        class="nav-link"
        :class="{ 'nav-link--active': isActive(item), 'nav-link--mobile': item.mobileVisible, 'nav-link--desktop-only': item.label !== '更多', 'nav-link--mobile-only': item.label === '更多' }"
        :title="collapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="19" :stroke-width="1.8" />
        <span class="nav-link__label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="sidebar__footer">
      <div class="user-card">
        <span class="avatar avatar--green">{{ displayName.slice(0, 1) }}</span>
        <span class="user-card__copy">
          <strong>{{ displayName }}</strong>
          <small>{{userContext}}</small>
        </span>
        <span class="status-dot" title="数据已同步"></span>
      </div>
    </div>
  </aside>
</template>
