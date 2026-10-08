<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Check } from '@lucide/vue'

const route = useRoute()
const steps = [
  { label: '职位发现', shortLabel: '发现', to: '/discovery', paths: ['/discovery'] },
  { label: '招呼审核', shortLabel: '审核', to: '/review-queue', paths: ['/review-queue'] },
  { label: '自动执行', shortLabel: '执行', to: '/automation', paths: ['/automation'] },
  { label: '手机跟进', shortLabel: '跟进', to: '/applications', paths: ['/applications'] },
]
const activeIndex = computed(() => steps.findIndex((step) => step.paths.some((path) => route.path.startsWith(path))))
</script>

<template>
  <nav v-if="activeIndex >= 0" class="process-stepper" aria-label="求职主流程">
    <RouterLink
      v-for="(step, index) in steps"
      :key="step.to"
      :to="step.to"
      class="process-step"
      :class="{ 'process-step--active': index === activeIndex, 'process-step--done': index < activeIndex }"
      :aria-current="index === activeIndex ? 'step' : undefined"
    >
      <span class="process-step__index"><Check v-if="index < activeIndex" :size="13" /><template v-else>{{ index + 1 }}</template></span>
      <span class="process-step__label"><span>{{ step.label }}</span><small>{{ step.shortLabel }}</small></span>
    </RouterLink>
  </nav>
</template>
