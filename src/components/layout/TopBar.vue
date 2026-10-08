<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  Command,
  FileText,
  LayoutDashboard,
  Moon,
  Search,
  Sun,
  Radar,
  ListChecks,
  LogOut,
  Workflow,
  X,
} from '@lucide/vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useAutomationStore } from '@/stores/automation'
import { useSessionStore } from '@/stores/session'
import { useDialogFocus } from '@/composables/useDialogFocus'

const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const automation = useAutomationStore()
const session = useSessionStore()
const commandOpen = ref(false)
const query = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const activeCommandIndex = ref(0)
const commandDialog=ref<HTMLElement|null>(null)
useDialogFocus(commandOpen,commandDialog,closeCommand,()=>searchInput.value)

const title = computed(() => String(route.meta.title ?? '工作台'))
const commands = computed(() => [
  { label: '返回工作台', hint: '总览进度与下一步', to: '/', icon: LayoutDashboard },
  { label: '编辑求职资料', hint: '填写和保存投递所需资料', to: '/resumes/editor', icon: FileText },
  { label: '发现匹配职位', hint: '从 BOSS 与猎聘同步岗位', to: '/discovery', icon: Radar },
  { label: '审核招呼语', hint: `${automation.includedReviewCount} 个等待确认`, to: '/review-queue', icon: ListChecks },
  { label: '查看自动执行', hint: `${automation.humanActionCount} 项需要人工处理`, to: '/automation', icon: Workflow },
])

const filteredCommands = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return commands.value
  return commands.value.filter((command) =>
    `${command.label} ${command.hint}`.toLowerCase().includes(keyword),
  )
})

function openCommand() {
  commandOpen.value = true
  query.value = ''
  activeCommandIndex.value = 0
  nextTick(() => searchInput.value?.focus())
}

function closeCommand() {
  commandOpen.value = false
}

function navigate(to: string) {
  closeCommand()
  router.push(to)
}

function handleKeyboard(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    openCommand()
  }
  if (!commandOpen.value) return
  if (event.key === 'ArrowDown') { event.preventDefault(); activeCommandIndex.value = Math.min(activeCommandIndex.value + 1, filteredCommands.value.length - 1) }
  if (event.key === 'ArrowUp') { event.preventDefault(); activeCommandIndex.value = Math.max(activeCommandIndex.value - 1, 0) }
  if (event.key === 'Enter' && document.activeElement === searchInput.value) { event.preventDefault(); const command = filteredCommands.value[activeCommandIndex.value]; if (command) navigate(command.to) }
}

watch(filteredCommands, () => { activeCommandIndex.value = 0 })

async function logout() {
  await session.logout()
  await router.replace('/login')
}

onMounted(() => window.addEventListener('keydown', handleKeyboard))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeyboard))
</script>

<template>
  <header class="topbar">
    <div class="topbar__context">
      <span class="topbar__workspace">求职工作区</span>
      <span class="topbar__divider">/</span>
      <strong>{{ title }}</strong>
    </div>

    <div class="topbar__actions">
      <label v-if="automation.platformIdentities.length" class="topbar-account-select"><span>BOSS账号</span><select :value="automation.activePlatformIdentity?.id" :disabled="automation.oneStopRunning" @change="automation.activatePlatformIdentity(($event.target as HTMLSelectElement).value)"><option v-for="identity in automation.platformIdentities" :key="identity.id" :value="identity.id">{{identity.displayName}}</option></select></label>
      <button class="task-chip" :class="{ 'task-chip--warning': automation.humanActionCount }" type="button" @click="router.push('/automation')">
        <span class="status-dot" :class="automation.humanActionCount ? 'status-dot--warning' : 'status-dot--pulse'"></span>
        <span>{{ automation.humanActionCount ? `${automation.humanActionCount} 项需要处理` : automation.runner.online ? '本地执行器在线' : '本地执行器离线' }}</span>
      </button>

      <button class="search-trigger" type="button" aria-label="打开命令搜索" @click="openCommand">
        <Search :size="17" />
        <span>搜索或执行命令</span>
        <kbd><Command :size="12" /> K</kbd>
      </button>

      <button class="icon-button" type="button" :aria-label="workspace.theme === 'light' ? '切换深色模式' : '切换浅色模式'" @click="workspace.toggleTheme">
        <Moon v-if="workspace.theme === 'light'" :size="18" />
        <Sun v-else :size="18" />
      </button>

      <button class="icon-button notification-button" type="button" aria-label="查看通知" @click="router.push(automation.humanActionCount ? '/automation' : '/applications')">
        <Bell :size="18" />
        <span v-if="automation.humanActionCount" class="notification-dot"></span>
      </button>

      <button v-if="session.remoteMode" class="icon-button" type="button" aria-label="退出登录" title="退出登录" @click="logout">
        <LogOut :size="17" />
      </button>
    </div>
  </header>

  <Teleport to="body">
    <Transition name="fade">
      <div v-if="commandOpen" class="modal-backdrop command-backdrop" @mousedown.self="closeCommand">
        <section ref="commandDialog" class="command-dialog" role="dialog" aria-modal="true" aria-label="命令菜单">
          <div class="command-dialog__search">
            <Search :size="20" />
            <input ref="searchInput" v-model="query" placeholder="搜索页面或操作…" />
            <button class="icon-button icon-button--quiet" type="button" aria-label="关闭" @click="closeCommand">
              <X :size="18" />
            </button>
          </div>
          <div class="command-dialog__body">
            <span class="command-dialog__eyebrow">快速前往</span>
            <button
              v-for="command in filteredCommands"
              :key="command.to"
              class="command-item"
              :class="{ 'command-item--active': activeCommandIndex === filteredCommands.indexOf(command) }"
              type="button"
              @click="navigate(command.to)"
            >
              <span class="command-item__icon"><component :is="command.icon" :size="18" /></span>
              <span><strong>{{ command.label }}</strong><small>{{ command.hint }}</small></span>
              <span class="command-item__arrow">↗</span>
            </button>
            <div v-if="!filteredCommands.length" class="command-empty">没有匹配的操作</div>
          </div>
          <footer class="command-dialog__footer">
            <span><kbd>↑</kbd><kbd>↓</kbd> 浏览</span>
            <span><kbd>Enter</kbd> 打开</span>
            <span><kbd>Esc</kbd> 关闭</span>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
