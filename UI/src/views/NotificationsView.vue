<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { BellAlertIcon, ArrowPathIcon, ChevronLeftIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'
import { fetchNotificationsPage, DEFAULT_PAGE_SIZE } from '../services/notificationService'
import { useTheme } from '../composables/useTheme'
import type { Notification } from '../types/notification'

const { isDark } = useTheme()

const notifications = ref<Notification[]>([])
const loading = ref(true)
const error = ref('')

const limit = ref(DEFAULT_PAGE_SIZE)
const offset = ref(0)
const total = ref(0)

const currentPage = computed(() => Math.floor(offset.value / limit.value) + 1)
const totalPages = computed(() => (total.value === 0 ? 1 : Math.ceil(total.value / limit.value)))
const rangeStart = computed(() => (total.value === 0 ? 0 : offset.value + 1))
const rangeEnd = computed(() => Math.min(offset.value + notifications.value.length, total.value))

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const page = await fetchNotificationsPage({ limit: limit.value, offset: offset.value })
    // Defensive: if the backend returns a shape the service couldn't
    // normalize we still want the template's `notifications.length` to
    // work, so coerce to a real array. Without this, an undefined value
    // here would throw during render and leave the spinner stuck.
    notifications.value = Array.isArray(page.items) ? page.items : []
    total.value = typeof page.total === 'number' ? page.total : notifications.value.length
    // Guard against a zero/NaN limit slipping through — currentPage and
    // totalPages both divide by it.
    if (typeof page.limit === 'number' && page.limit > 0) {
      limit.value = page.limit
    }
    if (typeof page.offset === 'number' && page.offset >= 0) {
      offset.value = page.offset
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load notifications'
  } finally {
    loading.value = false
  }
}

function prevPage() {
  if (offset.value === 0) return
  offset.value = Math.max(0, offset.value - limit.value)
  load()
}

function nextPage() {
  if (offset.value + limit.value >= total.value) return
  offset.value = offset.value + limit.value
  load()
}

function refresh() {
  offset.value = 0
  load()
}

onMounted(load)
</script>

<template>
  <div class="max-w-4xl mx-auto px-4 sm:px-6 py-8">
    <!-- Header -->
    <div class="flex items-center justify-between mb-8">
      <div class="flex items-center gap-3">
        <div class="w-10 h-10 rounded-lg flex items-center justify-center"
          :class="isDark ? 'bg-amber-500/10' : 'bg-amber-50'">
          <BellAlertIcon class="w-5 h-5" :class="isDark ? 'text-amber-400' : 'text-amber-600'" />
        </div>
        <div>
          <h1 class="text-2xl font-bold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">Notifications</h1>
          <p class="text-sm" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
            Sync deltas between database and WireMock
          </p>
        </div>
      </div>
      <button @click="refresh" type="button"
        class="p-2 rounded-lg transition-colors cursor-pointer"
        :class="isDark ? 'text-gray-400 hover:text-gray-100 hover:bg-gray-800' : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'"
        title="Refresh">
        <ArrowPathIcon class="w-5 h-5" :class="{ 'animate-spin': loading }" />
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading && notifications.length === 0" class="text-center py-20">
      <ArrowPathIcon class="w-8 h-8 mx-auto mb-3 animate-spin" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
      <p :class="isDark ? 'text-gray-400' : 'text-gray-500'">Loading notifications...</p>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="text-center py-20">
      <p class="text-red-500 mb-4">{{ error }}</p>
      <button @click="load" type="button" class="text-sm font-medium text-emerald-500 hover:text-emerald-400 cursor-pointer">Try again</button>
    </div>

    <!-- Empty -->
    <div v-else-if="notifications.length === 0" class="text-center py-20">
      <BellAlertIcon class="w-16 h-16 mx-auto mb-4" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
      <h2 class="text-lg font-semibold mb-2" :class="isDark ? 'text-gray-200' : 'text-gray-700'">No notifications</h2>
      <p :class="isDark ? 'text-gray-400' : 'text-gray-500'">Everything is in sync between the database and WireMock.</p>
    </div>

    <!-- Notification list + pagination -->
    <template v-else>
      <div class="space-y-3">
        <div
          v-for="notification in notifications"
          :key="notification.id"
          class="rounded-xl border p-4 transition-colors"
          :class="isDark
            ? 'bg-gray-900 border-gray-700 hover:bg-gray-950'
            : 'bg-white border-gray-200 shadow-sm hover:bg-gray-100'"
        >
          <div class="flex items-start justify-between gap-4">
            <div class="flex items-start gap-3 min-w-0">
              <div class="w-8 h-8 rounded-lg flex items-center justify-center shrink-0 mt-0.5"
                :class="isDark ? 'bg-amber-500/10' : 'bg-amber-50'">
                <BellAlertIcon class="w-4 h-4" :class="isDark ? 'text-amber-400' : 'text-amber-600'" />
              </div>
              <div class="min-w-0">
                <!--
                  `notification.name` may contain mixed HTML + text coming
                  from the backend (e.g. <b>, <a>, <code>, line breaks).
                  v-html renders those tags instead of escaping them.
                  The backend is an internal trusted service and the
                  content is admin-authored, so inline HTML is considered
                  safe here. If that ever changes, sanitize this value
                  (e.g. DOMPurify) before rendering.
                -->
                <div
                  v-html="notification.name"
                  class="notification-body text-sm font-medium break-words"
                  :class="isDark ? 'text-gray-100' : 'text-gray-800'"
                ></div>
                <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  Created: {{ formatDate(notification.createdAt) }}
                </p>
              </div>
            </div>
            <span class="text-xs whitespace-nowrap shrink-0" :class="isDark ? 'text-gray-600' : 'text-gray-400'">
              #{{ notification.id }}
            </span>
          </div>
        </div>
      </div>

      <!-- Pagination controls -->
      <div class="flex items-center justify-between mt-6 pt-4 border-t"
        :class="isDark ? 'border-gray-800' : 'border-gray-200'">
        <p class="text-sm" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
          Showing <span class="font-medium" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ rangeStart }}–{{ rangeEnd }}</span>
          of <span class="font-medium" :class="isDark ? 'text-gray-200' : 'text-gray-700'">{{ total }}</span>
        </p>
        <div class="flex items-center gap-2">
          <button
            type="button"
            @click="prevPage"
            :disabled="offset === 0 || loading"
            class="flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
            :class="isDark ? 'text-gray-300 hover:bg-gray-800' : 'text-gray-700 hover:bg-gray-100'"
          >
            <ChevronLeftIcon class="w-4 h-4" /> Prev
          </button>
          <span class="text-sm px-2" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
            Page {{ currentPage }} / {{ totalPages }}
          </span>
          <button
            type="button"
            @click="nextPage"
            :disabled="offset + limit >= total || loading"
            class="flex items-center gap-1 px-3 py-1.5 rounded-lg text-sm transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
            :class="isDark ? 'text-gray-300 hover:bg-gray-800' : 'text-gray-700 hover:bg-gray-100'"
          >
            Next <ChevronRightIcon class="w-4 h-4" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
/*
 * Minimal defaults for rich HTML rendered via v-html inside a notification
 * card. Tailwind resets most tag styling globally, so we opt back in to
 * the common tags a notification body is likely to use (paragraphs, links,
 * lists, inline code / preformatted blocks). Scoped so nothing leaks into
 * the rest of the app.
 */
.notification-body :deep(p) {
  margin: 0 0 0.35rem;
}
.notification-body :deep(p:last-child) {
  margin-bottom: 0;
}
.notification-body :deep(a) {
  color: #059669; /* emerald-600 */
  text-decoration: underline;
  word-break: break-all;
}
.notification-body :deep(a:hover) {
  color: #047857; /* emerald-700 */
}
.notification-body :deep(strong),
.notification-body :deep(b) {
  font-weight: 600;
}
.notification-body :deep(em),
.notification-body :deep(i) {
  font-style: italic;
}
.notification-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85em;
  padding: 0.05rem 0.3rem;
  border-radius: 0.25rem;
  background: rgba(107, 114, 128, 0.15);
}
.notification-body :deep(pre) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 0.85em;
  padding: 0.5rem 0.75rem;
  border-radius: 0.4rem;
  background: rgba(107, 114, 128, 0.15);
  overflow-x: auto;
  margin: 0.35rem 0;
}
.notification-body :deep(ul),
.notification-body :deep(ol) {
  margin: 0.25rem 0 0.25rem 1.25rem;
  padding: 0;
}
.notification-body :deep(ul) {
  list-style: disc;
}
.notification-body :deep(ol) {
  list-style: decimal;
}
.notification-body :deep(li) {
  margin: 0.1rem 0;
}
.notification-body :deep(br) {
  line-height: 1.5;
}
</style>
