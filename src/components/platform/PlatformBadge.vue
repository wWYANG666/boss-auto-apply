<script setup lang="ts">
import type { JobPlatform } from '@/types/automation'

withDefaults(defineProps<{
  platform: JobPlatform
  compact?: boolean
}>(), {
  compact: false,
})
</script>

<template>
  <span class="platform-badge" :class="[`platform-badge--${platform}`, { 'platform-badge--compact': compact }]">
    <i>{{ platform === 'boss' ? 'B' : '猎' }}</i>
    <span v-if="!compact">{{ platform === 'boss' ? 'BOSS直聘' : '猎聘' }}</span>
  </span>
</template>
