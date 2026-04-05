<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {
  FolderIcon,
  BookOpenIcon,
  CogIcon,
  PlusIcon,
  Bars3Icon,
  XMarkIcon,
  ServerStackIcon,
  ExclamationCircleIcon,
  BellIcon,
} from '@heroicons/vue/24/outline'
import WireMateLogo from './WireMateLogo.vue'
import { useTheme } from '../composables/useTheme'
import { fetchHealth } from '../services/settingsService'

const router = useRouter()
const route = useRoute()
const { isDark, toggleTheme } = useTheme()
const mobileOpen = ref(false)

// Injected by Vite `define` at build time (see vite.config.ts).
const appVersion = __APP_VERSION__

// Health check
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

onMounted(() => {
  checkHealth()
  healthInterval = setInterval(checkHealth, 30000) // every 30s
})

onUnmounted(() => {
  if (healthInterval) clearInterval(healthInterval)
})

const navSections = [
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

function goHome() {
  router.push({ name: 'projects' })
  mobileOpen.value = false
}

function isActive(name: string): boolean {
  return route.name === name
}

function navigate(name: string) {
  router.push({ name })
  mobileOpen.value = false
}

function toggleMobile() {
  mobileOpen.value = !mobileOpen.value
}

function closeMobile() {
  mobileOpen.value = false
}
</script>

<template>
  <div>
    <!-- Mobile top bar (hidden on desktop via scoped CSS to avoid flex/hidden conflict) -->
    <div class="mobile-topbar fixed top-0 inset-x-0 z-30 flex items-center justify-between px-4 py-3 border-b transition-colors"
      :class="isDark ? 'bg-gray-900 border-gray-800' : 'bg-white border-gray-200'">
      <button type="button" @click="goHome" class="cursor-pointer">
        <WireMateLogo :size="28" />
      </button>
      <button type="button" @click="toggleMobile" class="p-2 rounded-lg cursor-pointer"
        :class="isDark ? 'text-gray-400 hover:bg-gray-800' : 'text-gray-600 hover:bg-gray-100'">
        <Bars3Icon v-if="!mobileOpen" class="w-6 h-6" />
        <XMarkIcon v-else class="w-6 h-6" />
      </button>
    </div>

    <!-- Mobile overlay -->
    <div v-if="mobileOpen" class="mobile-overlay fixed inset-0 z-40 bg-black/50" @click="closeMobile" />

    <!--
      Sidebar — desktop: always visible; mobile: slides in/out.
      Suppress pointer events ONLY below lg when the off-screen aside would
      otherwise intercept clicks. Previously applied via an inline style
      at all widths, with an inner wrapper hack to restore desktop clicks.
      That was fragile because any future intermediate element would block
      desktop nav clicks silently.
    -->
    <aside
      class="fixed top-0 left-0 h-screen w-60 flex flex-col border-r transition-transform duration-300 lg:pointer-events-auto"
      :class="[
        isDark ? 'bg-gray-900 border-gray-800' : 'bg-white border-gray-200',
        mobileOpen ? 'translate-x-0 z-50' : '-translate-x-full max-lg:pointer-events-none lg:translate-x-0 z-50',
      ]"
    >
      <div class="flex flex-col h-full">
        <!-- Logo -->
        <div class="px-5 pt-5 pb-4 flex items-center justify-center relative">
          <button type="button" @click="goHome" class="cursor-pointer">
            <WireMateLogo :size="52" />
          </button>
          <!-- Mobile close -->
          <button type="button" @click="closeMobile" class="lg:hidden p-1.5 rounded-lg cursor-pointer absolute right-3 top-4"
            :class="isDark ? 'text-gray-400 hover:bg-gray-800' : 'text-gray-500 hover:bg-gray-100'">
            <XMarkIcon class="w-5 h-5" />
          </button>
        </div>

        <!-- Nav -->
        <nav class="flex-1 overflow-y-auto px-3 space-y-6">
          <div v-for="section in navSections" :key="section.title">
            <!--
              Section header: in light mode use gray-500 (4.6:1) instead of
              gray-400 (2.6:1) — small uppercase text at 10px is the most
              contrast-sensitive copy in the whole app.
            -->
            <p class="px-2 mb-2 text-[10px] font-semibold uppercase tracking-wider"
              :class="isDark ? 'text-gray-500' : 'text-gray-500'">
              {{ section.title }}
            </p>
            <ul class="space-y-0.5">
              <li v-for="item in section.items" :key="item.name">
                <!--
                  py-2.5 lifts the nav row to ~44px for touch parity.
                  focus-visible ring is explicit because the global outline is suppressed.
                -->
                <button
                  type="button"
                  :aria-current="isActive(item.name) ? 'page' : undefined"
                  @click="navigate(item.name)"
                  class="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors w-full text-left cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                  :class="isActive(item.name)
                    ? (isDark
                        ? 'bg-emerald-500/15 text-emerald-300'
                        : 'bg-emerald-50 text-emerald-700')
                    : (isDark
                        ? 'text-gray-400 hover:text-gray-100 hover:bg-gray-800'
                        : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100')"
                >
                  <component :is="item.icon" class="w-[18px] h-[18px] shrink-0" />
                  {{ item.label }}
                </button>
              </li>
            </ul>
          </div>
        </nav>

        <!-- Bottom: Theme toggle & Version -->
        <div class="px-4 py-4 border-t transition-colors space-y-3"
          :class="isDark ? 'border-gray-800' : 'border-gray-200'">
          <button
            type="button"
            @click="toggleTheme"
            :title="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
            :aria-label="isDark ? 'Switch to light mode' : 'Switch to dark mode'"
            :aria-pressed="isDark"
            class="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-sm font-medium transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
            :class="isDark
              ? 'text-gray-400 hover:text-gray-100 hover:bg-gray-800'
              : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'"
          >
            <!-- Label always shows the action (what clicking will do), never the current state. -->
            {{ isDark ? 'Switch to light' : 'Switch to dark' }}
            <span class="ml-auto relative w-9 h-5 rounded-full transition-colors"
              :class="isDark ? 'bg-emerald-600' : 'bg-gray-300'">
              <span class="absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-transform duration-300"
                :class="isDark ? 'translate-x-4' : 'translate-x-0'" />
            </span>
          </button>
          <!-- WireMock health indicator -->
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
          <!--
            Footer:
            * In light mode, text-gray-500 (4.6:1) replaces text-gray-400 (2.6:1) — passes AA.
            * GitHub link gets aria-label + SVG title + focus-visible ring so
              screen-reader users and keyboard users can perceive it.
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
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
@media (min-width: 1024px) {
  /* Hide mobile top bar on desktop */
  .mobile-topbar {
    display: none !important;
  }
}
</style>
