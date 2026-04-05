import { ref, watch, onScopeDispose, type Ref } from 'vue'

/**
 * Creates a debounced ref that updates after a delay.
 * Useful for search inputs where you want instant UI feedback
 * but debounced side-effects (URL sync, API calls, etc.).
 *
 * Must be called within a component setup or active effect scope — the
 * pending timer is cleared automatically when the scope is disposed.
 */
export function useDebouncedRef<T>(source: Ref<T>, delay = 300): Ref<T> {
  const debounced = ref(source.value) as Ref<T>
  let timeout: ReturnType<typeof setTimeout> | null = null

  watch(source, (val) => {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(() => {
      debounced.value = val
      timeout = null
    }, delay)
  })

  // Clear any pending timer if the consuming scope is torn down before it fires.
  onScopeDispose(() => {
    if (timeout) {
      clearTimeout(timeout)
      timeout = null
    }
  })

  return debounced
}
