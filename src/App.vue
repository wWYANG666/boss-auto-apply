<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { RouterView } from 'vue-router'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from '@/components/layout/AppSidebar.vue'
import TopBar from '@/components/layout/TopBar.vue'
import ProcessStepper from '@/components/layout/ProcessStepper.vue'
import ToastStack from '@/components/feedback/ToastStack.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useAutomationStore } from '@/stores/automation'
import { useSessionStore } from '@/stores/session'

const workspace = useWorkspaceStore()
const automation = useAutomationStore()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const publicPage = computed(() => Boolean(route.meta.public))

onMounted(async () => {
  workspace.initializeTheme()
  const authenticated = await session.initialize()
  if (session.remoteMode && !authenticated && !route.meta.public) {
    await router.replace({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (authenticated) await Promise.all([workspace.initialize(), automation.initialize()])
})

watch(() => session.authenticated, async (authenticated) => {
  if (authenticated) {
    await Promise.all([workspace.initialize(true), automation.initialize(true)])
  } else if (!authenticated && session.remoteMode && !route.meta.public) {
    workspace.reset()
    automation.stopTaskPolling()
    automation.tasks=[]; automation.reviews=[]; automation.discoveredJobs=[]; automation.platformAccounts=[]; automation.initialized=false
    await router.replace({ path: '/login', query: { redirect: route.fullPath } })
  }
})
</script>

<template>
  <RouterView v-if="publicPage" />
  <div v-else class="app-shell">
    <AppSidebar />
    <div class="app-stage">
      <TopBar />
      <ProcessStepper />
      <main class="app-content">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
    <ToastStack />
  </div>
</template>
