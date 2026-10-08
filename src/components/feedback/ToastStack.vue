<script setup lang="ts">
import { CheckCircle2, CircleAlert, Info, X } from '@lucide/vue'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = useWorkspaceStore()
</script>

<template>
  <div class="toast-stack" aria-live="polite" aria-atomic="true">
    <TransitionGroup name="toast">
      <article v-for="toast in workspace.toasts" :key="toast.id" class="toast" :class="`toast--${toast.tone}`">
        <span class="toast__icon">
          <CheckCircle2 v-if="toast.tone === 'success'" :size="19" />
          <CircleAlert v-else-if="toast.tone === 'warning'" :size="19" />
          <Info v-else :size="19" />
        </span>
        <span class="toast__copy">
          <strong>{{ toast.title }}</strong>
          <small v-if="toast.message">{{ toast.message }}</small>
        </span>
        <button class="icon-button icon-button--quiet" type="button" aria-label="关闭通知" @click="workspace.removeToast(toast.id)">
          <X :size="16" />
        </button>
      </article>
    </TransitionGroup>
  </div>
</template>
