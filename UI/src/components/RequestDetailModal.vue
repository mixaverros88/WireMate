<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      role="dialog"
      aria-modal="true"
      @click="handleClose"
    >
      <div
        class="relative w-full max-w-3xl mx-4 rounded-lg shadow-xl max-h-[90vh] overflow-y-auto"
        :class="[
          isDark
            ? 'bg-gray-900 border border-gray-700'
            : 'bg-white border border-gray-200'
        ]"
        @click.stop
      >
        <!-- Header -->
        <div class="sticky top-0 z-10 flex items-center justify-between p-6" :class="isDark ? 'bg-gray-800 border-b border-gray-700' : 'bg-gray-50 border-b border-gray-200'">
          <div>
            <h3 :class="isDark ? 'text-white' : 'text-gray-900'" class="text-lg font-semibold">
              Request Details
            </h3>
            <p v-if="request.id" :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm mt-1">
              ID: {{ request.id }}
            </p>
          </div>
          <button
            @click="handleClose"
            class="p-1 rounded transition-colors"
            :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-700' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
          >
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Content -->
        <div class="p-6 space-y-6">
          <!-- Request Details -->
          <div>
            <!-- Method, Status, Match badge, timestamp -->
            <div class="flex flex-wrap items-center gap-3 pb-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <span :class="getMethodBgColor(request.request.method)" class="px-3 py-1 rounded text-sm font-bold text-white">
                {{ request.request.method }}
              </span>
              <span v-if="effectiveStatus !== null" :class="getStatusCodeColor(effectiveStatus)" class="px-3 py-1 rounded text-sm font-bold text-white">
                {{ effectiveStatus }}
              </span>
              <span
                v-if="request.wasMatched !== undefined"
                class="px-3 py-1 rounded text-xs font-semibold"
                :class="request.wasMatched
                  ? (isDark ? 'bg-emerald-900/50 text-emerald-300 border border-emerald-700' : 'bg-emerald-100 text-emerald-700 border border-emerald-200')
                  : (isDark ? 'bg-amber-900/50 text-amber-300 border border-amber-700' : 'bg-amber-100 text-amber-700 border border-amber-200')"
              >
                {{ request.wasMatched ? 'Matched' : 'Unmatched' }}
              </span>
              <span :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm">
                {{ formatDate(request.request.loggedDateString || request.request.loggedDate) }}
              </span>
            </div>

            <!-- Request URL -->
            <div class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-2">
                URL
              </p>
              <p :class="isDark ? 'text-gray-300 bg-gray-800' : 'text-gray-700 bg-gray-100'" class="font-mono text-sm p-3 rounded break-all">
                {{ request.request.absoluteUrl }}
              </p>
              <p v-if="request.request.url && request.request.url !== request.request.absoluteUrl"
                :class="isDark ? 'text-gray-500' : 'text-gray-500'" class="font-mono text-xs mt-2 break-all">
                Path: {{ request.request.url }}
              </p>
            </div>

            <!-- Request Info -->
            <div class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Request Info
              </p>
              <div class="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
                <div>
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Client IP</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.clientIp }}</p>
                </div>
                <div>
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Scheme</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.scheme }}</p>
                </div>
                <div>
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Host</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.host }}</p>
                </div>
                <div>
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Port</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.port }}</p>
                </div>
                <div>
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Protocol</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.protocol }}</p>
                </div>
                <div v-if="request.request.browserProxyRequest !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Browser Proxy</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.request.browserProxyRequest ? 'yes' : 'no' }}</p>
                </div>
              </div>
            </div>

            <!-- Query Parameters -->
            <div v-if="hasQueryParams" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Query Parameters
              </p>
              <div :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2">
                <div v-for="(param, key) in request.request.queryParams" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ formatQueryValues(param) }}</p>
                </div>
              </div>
            </div>

            <!-- Cookies -->
            <div v-if="hasCookies" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Cookies
              </p>
              <div :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2">
                <div v-for="(value, key) in request.request.cookies" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ value }}</p>
                </div>
              </div>
            </div>

            <!-- Form Parameters -->
            <div v-if="hasFormParams" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Form Parameters
              </p>
              <div :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2">
                <div v-for="(param, key) in request.request.formParams" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ formatFormValue(param) }}</p>
                </div>
              </div>
            </div>

            <!-- Request Headers -->
            <div class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Request Headers
              </p>
              <div v-if="!request.request.headers || Object.keys(request.request.headers).length === 0" :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-sm">
                No headers
              </div>
              <div v-else :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2">
                <div v-for="(value, key) in request.request.headers" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ value }}</p>
                </div>
              </div>
            </div>

            <!-- Request Body -->
            <div v-if="request.request.body" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Request Body
              </p>
              <pre :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'" class="rounded p-3 text-xs overflow-auto max-h-64">{{ formatJson(request.request.body) }}</pre>
            </div>

            <!-- Actual Response Status & Headers -->
            <div v-if="request.response" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Response ({{ request.response.status }})
              </p>
              <div v-if="request.response.headers && Object.keys(request.response.headers).length > 0" :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2 mb-3">
                <div v-for="(value, key) in request.response.headers" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ value }}</p>
                </div>
              </div>
              <pre v-if="request.response.body" :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'" class="rounded p-3 text-xs overflow-auto max-h-64">{{ formatJson(request.response.body) }}</pre>
              <p v-else :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-sm italic">Empty response body</p>
            </div>

            <!-- Response Definition (stub-defined) -->
            <div v-if="request.responseDefinition" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Response Definition ({{ request.responseDefinition.status }})
              </p>
              <div v-if="request.responseDefinition.headers && Object.keys(request.responseDefinition.headers).length > 0" :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2 mb-3">
                <div v-for="(value, key) in request.responseDefinition.headers" :key="key" class="text-sm">
                  <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ value }}</p>
                </div>
              </div>
              <pre v-if="request.responseDefinition.body" :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'" class="rounded p-3 text-xs overflow-auto max-h-64">{{ formatJson(request.responseDefinition.body) }}</pre>
            </div>

            <!-- Matched Stub Mapping -->
            <div v-if="request.stubMapping" class="py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
              <div class="flex items-center justify-between mb-3">
                <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium">
                  Matched Stub
                </p>
                <button
                  v-if="editableMockParams"
                  type="button"
                  @click="goToEditMock"
                  class="inline-flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors duration-300 ease-in-out cursor-pointer"
                  :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'"
                  title="Open this mock in the editor"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                  </svg>
                  Edit Mock
                </button>
              </div>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm mb-3">
                <div v-if="request.stubMapping.id">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Stub ID</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1 break-all">{{ request.stubMapping.id }}</p>
                </div>
                <div v-if="request.stubMapping.name">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Name</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1 break-all">{{ request.stubMapping.name }}</p>
                </div>
                <div v-if="request.stubMapping.persistent !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Persistent</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.stubMapping.persistent ? 'yes' : 'no' }}</p>
                </div>
                <div v-if="request.stubMapping.priority !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Priority</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.stubMapping.priority }}</p>
                </div>
              </div>

              <div v-if="stubMetadataEntries.length > 0" class="mb-3">
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs mb-2">Metadata</p>
                <div :class="isDark ? 'bg-gray-800' : 'bg-gray-100'" class="rounded p-3 space-y-2">
                  <div v-for="[key, value] in stubMetadataEntries" :key="key" class="text-sm">
                    <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="font-medium">{{ key }}</p>
                    <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono text-xs mt-1 break-all">{{ stringifyValue(value) }}</p>
                  </div>
                </div>
              </div>

              <div v-if="request.stubMapping.request">
                <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs mb-2">Request Pattern</p>
                <pre :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'" class="rounded p-3 text-xs overflow-auto max-h-48">{{ JSON.stringify(request.stubMapping.request, null, 2) }}</pre>
              </div>
            </div>

            <!-- Timing -->
            <div v-if="hasTiming" class="py-4">
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs uppercase tracking-wide font-medium mb-3">
                Timing (ms)
              </p>
              <div class="grid grid-cols-2 sm:grid-cols-3 gap-4 text-sm">
                <div v-if="request.timing?.totalTime !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Total</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.timing.totalTime }}</p>
                </div>
                <div v-if="request.timing?.serveTime !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Serve</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.timing.serveTime }}</p>
                </div>
                <div v-if="request.timing?.processTime !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Process</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.timing.processTime }}</p>
                </div>
                <div v-if="request.timing?.responseSendTime !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Response Send</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.timing.responseSendTime }}</p>
                </div>
                <div v-if="request.timing?.addedDelay !== undefined">
                  <p :class="isDark ? 'text-gray-500' : 'text-gray-600'" class="text-xs">Added Delay</p>
                  <p :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="font-mono mt-1">{{ request.timing.addedDelay }}</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="sticky bottom-0 flex justify-end gap-3 p-6 border-t" :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-gray-50 border-gray-200'">
          <BaseButton
            description="Close"
            :color="BaseButtonEnum.WHITE"
            @click="handleClose"
          />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { BaseButton, BaseButtonEnum } from 'mgv-backoffice'
import { useTheme } from '../composables/useTheme'
import type { LoggedRequest, QueryParam } from '../types/requestJournal'
import { methodBadgeSolid, statusBadgeSolid } from '../utils/httpColors'

interface Props {
  // The full LoggedRequest is passed in from the parent rather than re-fetched
  // by ID. This keeps the Logs page's traffic isolated to a single endpoint
  // (POST /__admin/requests/find) — any row the user clicks is already in the
  // list returned by that call.
  request: LoggedRequest
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
}>()

const { isDark } = useTheme()
const router = useRouter()

const request = computed(() => props.request)

const getMethodBgColor = methodBadgeSolid
const getStatusCodeColor = statusBadgeSolid

const effectiveStatus = computed<number | null>(() => {
  return (
    request.value.response?.status
    ?? request.value.responseDefinition?.status
    ?? null
  )
})

const hasQueryParams = computed(
  () => !!request.value.request.queryParams && Object.keys(request.value.request.queryParams).length > 0,
)

const hasCookies = computed(
  () => !!request.value.request.cookies && Object.keys(request.value.request.cookies).length > 0,
)

const hasFormParams = computed(
  () => !!request.value.request.formParams && Object.keys(request.value.request.formParams).length > 0,
)

const stubMetadataEntries = computed<[string, unknown][]>(() => {
  const md = request.value.stubMapping?.metadata
  return md ? Object.entries(md) : []
})

// Derive the params needed to route to the mock editor. Only surfaces the
// Edit button when both the stub id and a metadata.projectId are present
// (WireMate-generated stubs tag themselves with projectId in metadata).
const editableMockParams = computed<{ projectId: string; mockId: string } | null>(() => {
  const stub = request.value.stubMapping
  if (!stub?.id) return null
  const projectId = stub.metadata?.projectId
  if (typeof projectId !== 'string' || !projectId) return null
  return { projectId, mockId: stub.id }
})

function goToEditMock() {
  const params = editableMockParams.value
  if (!params) return
  router.push({ name: 'edit-mock', params: { id: params.projectId, mockId: params.mockId } })
  emit('close')
}

const hasTiming = computed(() => {
  const t = request.value.timing
  if (!t) return false
  return (
    t.totalTime !== undefined
    || t.serveTime !== undefined
    || t.processTime !== undefined
    || t.responseSendTime !== undefined
    || t.addedDelay !== undefined
  )
})

function formatDate(dateStr: string | number): string {
  try {
    return new Date(dateStr).toLocaleString()
  } catch {
    return String(dateStr)
  }
}

function formatJson(content: string): string {
  try {
    return JSON.stringify(JSON.parse(content), null, 2)
  } catch {
    return content
  }
}

function formatQueryValues(param: QueryParam): string {
  if (!param) return ''
  const values = param.values ?? []
  return values.join(', ')
}

function formatFormValue(value: QueryParam | string[] | string): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.join(', ')
  if (value && typeof value === 'object' && Array.isArray(value.values)) {
    return value.values.join(', ')
  }
  return String(value)
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function handleClose() {
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    handleClose()
  }
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
})
</script>
