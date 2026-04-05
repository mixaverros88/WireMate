import { ref, onScopeDispose } from 'vue'
import { BaseToastEnum } from 'mgv-backoffice'

/**
 * Per-component toast controller.
 *
 * Replaces the hand-rolled `showToast / toastMessage / toastType / setTimeout`
 * boilerplate scattered across views. Each component gets its own state refs
 * (not a global singleton) so multiple mounted views don't fight over the
 * same toast slot. The pending auto-hide timer is cleared on unmount so it
 * cannot fire after the component is gone.
 *
 * Usage:
 *   const { showToast, toastMessage, toastType, showToastMessage } = useToast()
 *   showToastMessage('Saved', BaseToastEnum.SUCCESS)
 */
export function useToast(defaultDurationMs = 3000) {
  const showToast = ref(false)
  const toastMessage = ref('')
  const toastType = ref<BaseToastEnum>(BaseToastEnum.SUCCESS)

  let timer: ReturnType<typeof setTimeout> | null = null

  function clearTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  function showToastMessage(
    message: string,
    type: BaseToastEnum = BaseToastEnum.SUCCESS,
    durationMs: number = defaultDurationMs,
  ) {
    toastMessage.value = message
    toastType.value = type
    showToast.value = true

    clearTimer()
    timer = setTimeout(() => {
      showToast.value = false
      timer = null
    }, durationMs)
  }

  function hideToast() {
    clearTimer()
    showToast.value = false
  }

  onScopeDispose(() => {
    clearTimer()
  })

  return {
    showToast,
    toastMessage,
    toastType,
    showToastMessage,
    hideToast,
  }
}
