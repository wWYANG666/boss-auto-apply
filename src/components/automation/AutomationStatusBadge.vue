<script setup lang="ts">
import type { AutomationStatus } from '@/types/automation'

const props = defineProps<{ status: AutomationStatus }>()

const labels: Record<AutomationStatus, string> = {
  queued: '等待执行',
  preparing: '正在准备',
  submitting: '正在执行',
  verifying: '核对结果',
  succeeded: '已成功',
  awaiting_login: '需要登录',
  awaiting_captcha: '需要验证',
  awaiting_question: '补充问题',
  page_changed: '页面变化',
  unknown_outcome: '结果待核对',
  failed: '执行失败',
  cancelled: '已取消',
  dry_run: '预检通过',
}

const tone = () => {
  if (props.status === 'succeeded' || props.status === 'dry_run') return 'success'
  if (props.status.startsWith('awaiting_') || props.status === 'unknown_outcome') return 'warning'
  if (props.status === 'failed' || props.status === 'page_changed') return 'danger'
  if (['preparing', 'submitting', 'verifying'].includes(props.status)) return 'running'
  return 'neutral'
}
</script>

<template>
  <span class="automation-status" :class="`automation-status--${tone()}`">
    <i></i>{{ labels[status] }}
  </span>
</template>
