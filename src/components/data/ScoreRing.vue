<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  score: number
  label?: string
  size?: number
  thickness?: number
}>(), {
  label: '匹配分',
  size: 118,
  thickness: 9,
})

const ringStyle = computed(() => ({
  '--score': `${Math.max(0, Math.min(100, props.score)) * 3.6}deg`,
  '--ring-size': `${props.size}px`,
  '--ring-thickness': `${props.thickness}px`,
}))
</script>

<template>
  <div class="score-ring" :style="ringStyle" role="img" :aria-label="`${label} ${score} 分`">
    <div class="score-ring__inner">
      <strong>{{ score }}</strong>
      <small>{{ label }}</small>
    </div>
  </div>
</template>
