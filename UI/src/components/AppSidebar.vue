<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import {
  FolderIcon,
  BookOpenIcon,
  CogIcon,
  PlusIcon,
  ServerStackIcon,
  ExclamationCircleIcon,
  BellIcon,
} from '@heroicons/vue/24/outline'
import { BaseSidebar, useTheme, type NavSection } from 'mgv-backoffice'
import WireMateLogo from './WireMateLogo.vue'
import { fetchHealth } from '../services/settingsService'

// Thin wrapper around mgv-backoffice's BaseSidebar that pins in the
// WireMate-specific pieces: nav sections, the WireMock health indicator,
// the build-time app version, and the GitHub link in the footer.

const { isDark } = useTheme()

// Injected by Vite `define` at build time (see vite.config.ts).
const appVersion = __APP_VERSION__

const navSections: NavSection[] = [
  {
    title: 'Main',
    items: [
      { name: 'projects', label: 'Projects', icon: FolderIcon },
      { name: 'create-project', label: 'New Project', icon: PlusIcon },
    ],
  },
  {
    title: 'WireMock',
    items: [
      { name: 'logs', label: 'Logs', icon: BookOpenIcon },
      { name: 'stubs', label: 'WireMock Stubs', icon: ServerStackIcon },
      { name: 'near-misses', label: 'Near Misses', icon: ExclamationCircleIcon },
      { name: 'notifications', label: 'Notifications', icon: BellIcon },
      { name: 'settings', label: 'Settings', icon: CogIcon },
    ],
  },
]

// WireMock admin-port health check
const healthStatus = ref<'healthy' | 'unhealthy' | 'unknown'>('unknown')
let healthInterval: ReturnType<typeof setInterval> | null = null

async function checkHealth() {
  try {
    const result = await fetchHealth()
    healthStatus.value = result.status === 'healthy' ? 'healthy' : 'unhealthy'
  } catch {
    healthStatus.value = 'unhealthy'
  }
}

// 30s admin-port ping is fine when the tab is visible, but a hidden tab
// has no reason to keep waking up WireMock's Jetty admin pool — pause
// on `document.visibilitychange` and resume when the tab is reopened.
function startHealthInterval() {
  if (healthInterval) return
  healthInterval = setInterval(checkHealth, 30000)
}
function stopHealthInterval() {
  if (healthInterval) {
    clearInterval(healthInterval)
    healthInterval = null
  }
}
function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    void checkHealth()
    startHealthInterval()
  } else {
    stopHealthInterval()
  }
}

onMounted(() => {
  void checkHealth()
  startHealthInterval()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  stopHealthInterval()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <BaseSidebar
    :sections="navSections"
    home-route-name="projects"
    app-name="WireMate UI"
    :version="appVersion"
  >
    <template #logo="{ size }">
      <WireMateLogo :size="size" />
    </template>

    <template #status>
      <!-- WireMock admin-port health indicator -->
      <div class="flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs"
        :class="healthStatus === 'healthy'
          ? (isDark ? 'bg-emerald-500/10 text-emerald-400' : 'bg-emerald-50 text-emerald-700')
          : healthStatus === 'unhealthy'
            ? (isDark ? 'bg-red-500/10 text-red-400' : 'bg-red-50 text-red-700')
            : (isDark ? 'bg-gray-700 text-gray-400' : 'bg-gray-100 text-gray-500')"
      >
        <span class="relative flex h-2 w-2">
          <span v-if="healthStatus === 'healthy'" class="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
          <span class="relative inline-flex rounded-full h-2 w-2"
            :class="healthStatus === 'healthy' ? 'bg-emerald-500' : healthStatus === 'unhealthy' ? 'bg-red-500' : 'bg-gray-400'"
          ></span>
        </span>
        WireMock {{ healthStatus === 'healthy' ? 'Connected' : healthStatus === 'unhealthy' ? 'Disconnected' : 'Checking...' }}
      </div>
    </template>

    <template #footer>
      <!--
        Footer: version + GitHub link. Replaces BaseSidebar's default
        "appName vVersion" line so the GitHub icon can sit alongside.
      -->
      <div class="flex items-center justify-center gap-2 text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-500'">
        <span>WireMate UI v{{ appVersion }}</span>
        <a
          href="https://github.com/mixaverros88/WireMate"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="View WireMate source on GitHub (opens in a new tab)"
          class="transition-colors rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
          :class="isDark ? 'text-gray-500 hover:text-gray-300' : 'text-gray-500 hover:text-gray-700'"
        >
          <svg class="w-4 h-4" viewBox="0 0 24 24" fill="currentColor" role="img" aria-hidden="true">
            <title>GitHub</title>
            <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
          </svg>
        </a>
      </div>
    </template>
  </BaseSidebar>
</template>
