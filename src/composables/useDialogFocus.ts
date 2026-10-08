import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue'

const openDialogs: symbol[] = []
let previousOverflow = ''

export function useDialogFocus(
  open: Ref<boolean>,
  element: Ref<HTMLElement | null>,
  close: () => void,
  initialFocus?: () => HTMLElement | null,
) {
  const id = Symbol('dialog')
  let returnFocus: HTMLElement | null = null

  function focusableElements() {
    return [...(element.value?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ) ?? [])].filter(item => item.getClientRects().length > 0)
  }

  function onKeydown(event: KeyboardEvent) {
    if (openDialogs.at(-1) !== id) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      close()
      return
    }
    if (event.key !== 'Tab') return
    const items = focusableElements()
    if (!items.length) { event.preventDefault(); element.value?.focus(); return }
    const first = items[0]!, last = items.at(-1)!
    if (event.shiftKey && (document.activeElement === first || !element.value?.contains(document.activeElement))) {
      event.preventDefault(); last.focus()
    } else if (!event.shiftKey && (document.activeElement === last || !element.value?.contains(document.activeElement))) {
      event.preventDefault(); first.focus()
    }
  }

  function onFocus(event: FocusEvent) {
    if (openDialogs.at(-1) === id && !element.value?.contains(event.target as Node)) {
      ;(initialFocus?.() ?? focusableElements()[0] ?? element.value)?.focus()
    }
  }

  function release() {
    const index = openDialogs.indexOf(id)
    if (index < 0) return
    openDialogs.splice(index, 1)
    document.removeEventListener('keydown', onKeydown, true)
    document.removeEventListener('focusin', onFocus)
    if (!openDialogs.length) document.body.style.overflow = previousOverflow
    nextTick(() => returnFocus?.isConnected && returnFocus.focus())
  }

  watch(open, async value => {
    if (!value) { release(); return }
    returnFocus = document.activeElement as HTMLElement
    if (!openDialogs.length) { previousOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    openDialogs.push(id)
    document.addEventListener('keydown', onKeydown, true)
    document.addEventListener('focusin', onFocus)
    await nextTick()
    ;(initialFocus?.() ?? focusableElements()[0] ?? element.value)?.focus()
  }, { immediate: true })
  onBeforeUnmount(release)
}
