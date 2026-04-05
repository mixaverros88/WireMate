import { ref, watch, effectScope } from 'vue'

// Singleton ref — shared across all consumers of useTheme().
const isDark = ref(false)

// Idempotent flag: initialization and the DOM-sync watcher should run once,
// and only when the composable is first invoked in a browser environment.
let initialized = false

function initialize() {
  if (initialized) return
  initialized = true

  // Read saved preference / system preference.
  if (typeof window !== 'undefined') {
    const saved = window.localStorage?.getItem('wiremate-theme')
    if (saved) {
      isDark.value = saved === 'dark'
    } else if (typeof window.matchMedia === 'function') {
      isDark.value = window.matchMedia('(prefers-color-scheme: dark)').matches
    }
  }

  // Use a detached effect scope so the watcher lives for the app's lifetime
  // and is NOT torn down when the first component that called useTheme() unmounts.
  if (typeof document !== 'undefined') {
    const scope = effectScope(true)
    scope.run(() => {
      watch(
        isDark,
        (value) => {
          document.documentElement.classList.toggle('dark', value)
          window.localStorage?.setItem('wiremate-theme', value ? 'dark' : 'light')
        },
        { immediate: true },
      )
    })
  }
}

export function useTheme() {
  initialize()

  function toggleTheme() {
    isDark.value = !isDark.value
  }

  return {
    isDark,
    toggleTheme,
  }
}
