<script setup lang="ts">
import { Monitor, TriangleAlert } from '@lucide/vue'
import type { AutomationTask } from '@/types/automation'
import PlatformBadge from '@/components/platform/PlatformBadge.vue'

defineProps<{ task: AutomationTask }>()
defineEmits<{ resolve: [id: string] }>()
</script>

<template>
  <article class="human-action-banner">
    <span class="human-action-banner__icon"><TriangleAlert :size="20" /></span>
    <div class="human-action-banner__copy">
      <div><PlatformBadge :platform="task.platform" compact /><strong>{{ task.company }} · {{ task.role }}</strong></div>
      <p>{{ task.humanAction }}</p>
    </div>
    <button class="button button--warning" type="button" @click="$emit('resolve', task.id)">
      <Monitor :size="16" /> 我已完成验证
    </button>
  </article>
</template>
