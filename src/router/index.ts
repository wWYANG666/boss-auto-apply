import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import ResumesView from '@/views/ResumesView.vue'
import ResumeEditorView from '@/views/ResumeEditorView.vue'
import ApplicationsView from '@/views/ApplicationsView.vue'
import SettingsView from '@/views/SettingsView.vue'
import JobDiscoveryView from '@/views/JobDiscoveryView.vue'
import ReviewQueueView from '@/views/ReviewQueueView.vue'
import AutomationView from '@/views/AutomationView.vue'
import LoginView from '@/views/LoginView.vue'
import { hasStoredAccessToken, shouldUseRemoteApi } from '@/api/http'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView, meta: { title: '登录', public: true } },
    { path: '/', name: 'dashboard', component: DashboardView, meta: { title: '工作台' } },
    { path: '/resumes', name: 'resumes', component: ResumesView, meta: { title: '求职资料' } },
    { path: '/resumes/editor', name: 'resume-editor', component: ResumeEditorView, meta: { title: '编辑求职资料', wide: true } },
    { path: '/jobs', redirect: '/discovery' },
    { path: '/matches/:pathMatch(.*)*', redirect: '/discovery' },
    { path: '/suggestions', redirect: '/discovery' },
    { path: '/discovery', name: 'discovery', component: JobDiscoveryView, meta: { title: '职位发现', wide: true } },
    { path: '/review-queue', name: 'review-queue', component: ReviewQueueView, meta: { title: '招呼审核', wide: true } },
    { path: '/automation', name: 'automation', component: AutomationView, meta: { title: '自动执行', wide: true } },
    { path: '/applications', name: 'applications', component: ApplicationsView, meta: { title: '沟通管理', wide: true } },
    { path: '/settings', name: 'settings', component: SettingsView, meta: { title: '设置' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (!shouldUseRemoteApi()) return true
  if (to.meta.public) {
    return hasStoredAccessToken() ? { path: '/' } : true
  }
  if (!hasStoredAccessToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})
