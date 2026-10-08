<script setup lang="ts">
import { computed, ref } from 'vue'
import { AlertTriangle, X } from '@lucide/vue'
import { useDialogFocus } from '@/composables/useDialogFocus'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  description: string
  confirmLabel?: string
  busy?: boolean
  danger?: boolean
}>(), { confirmLabel: '确认', busy: false, danger: false })
const emit = defineEmits<{ close: []; confirm: [] }>()
const dialog = ref<HTMLElement | null>(null)
useDialogFocus(computed(() => props.open), dialog, close)

function close() {
  if (!props.busy) emit('close')
}

</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="open" class="modal-backdrop" @mousedown.self="close">
        <section ref="dialog" class="dialog-card confirm-dialog" role="alertdialog" aria-modal="true" :aria-labelledby="`${title}-title`" :aria-describedby="`${title}-description`">
          <header class="dialog-card__header">
            <div><span class="eyebrow">请确认操作</span><h2 :id="`${title}-title`">{{ title }}</h2></div>
            <button class="icon-button" type="button" aria-label="关闭" :disabled="busy" @click="close"><X :size="18" /></button>
          </header>
          <div class="dialog-card__body confirm-dialog__body">
            <span class="confirm-dialog__icon" :class="{ 'confirm-dialog__icon--danger': danger }"><AlertTriangle :size="22" /></span>
            <p :id="`${title}-description`">{{ description }}</p>
          </div>
          <footer class="dialog-card__footer">
            <button class="button button--quiet" type="button" :disabled="busy" @click="close">取消</button>
            <button class="button" :class="danger ? 'button--danger' : 'button--primary'" type="button" :disabled="busy" @click="emit('confirm')">{{ busy ? '正在处理…' : confirmLabel }}</button>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
