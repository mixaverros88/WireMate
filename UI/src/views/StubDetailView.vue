<script setup lang="ts">
import { ref, onMounted, computed, useTemplateRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BaseSpinner, BaseButton, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import { ArrowLeftIcon } from '@heroicons/vue/24/outline'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { getStub, deleteStub } from '../services/stubService'
import type { StubMapping } from '../services/stubService'
import { mockApi } from '../services/api'
import type {
  CreateMockPayload,
  MockResponse,
  RequestDefinition,
  ResponseDefinition,
} from '../types/mock'
import ConfirmModal from '../components/ConfirmModal.vue'
import SelectProjectModal from '../components/SelectProjectModal.vue'
import WireMateLogo from '../components/WireMateLogo.vue'

const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()

const stub = ref<StubMapping | null>(null)
const loading = ref(true)
const error = ref('')
const showDeleteModal = ref(false)

const stubId = route.params.id as string

// Toast — timer cleanup handled inside the composable.
const { showToast, toastMessage, toastType, showToastMessage } = useToast(2500)

// ── WireMate mock adoption ─────────────────────────────────────
// A stub on the admin port *might* already be a WireMate-managed mock
// (same UUID on both sides of the sync). We probe GET /api/mocks/{id} on
// mount — if it resolves, the stub is a known mock and we route straight
// to its editor; if it 404s, we surface a "create WireMate Stub" action
// that lets the user adopt the raw stub into a WireMate project.
//   'idle'     — not yet probed
//   'loading'  — probe in flight
//   'exists'   — mock with this id is present in the WireMate backend
//   'missing'  — 404 from /api/mocks/{id} (stub is not a WireMate mock yet)
//   'creating' — adoption POST in flight
//   'error'    — probe failed for a non-404 reason (e.g. backend down);
//                treated as neutral so we don't mislabel a down backend
//                as 'missing' and let the user create a duplicate
type MockStatus = 'idle' | 'loading' | 'exists' | 'missing' | 'creating' | 'error'
const mockStatus = ref<MockStatus>('idle')
const existingMock = ref<MockResponse | null>(null)
const mockStatusError = ref('')

// Controls the project-picker modal that drives the adoption flow.
// `submitting` tracks the POST /api/mocks round-trip so the modal's
// action button can show a loading state; the ref is exposed by the
// child via defineExpose.
const showSelectProjectModal = ref(false)
const selectProjectModalRef = useTemplateRef<{ submitting: boolean }>('selectProjectModalRef')

async function checkMockExistence() {
  mockStatus.value = 'loading'
  mockStatusError.value = ''
  existingMock.value = null
  try {
    existingMock.value = await mockApi.fetchById(stubId)
    mockStatus.value = 'exists'
  } catch (e) {
    const message = e instanceof Error ? e.message : ''
    // The generic API helper surfaces the server body in the thrown
    // message — probe for a 404 shape so we can differentiate
    // "definitely missing" from "couldn't tell". Anything non-404 stays
    // 'error' so a down backend doesn't look like a missing mock.
    if (/404|not found/i.test(message)) {
      mockStatus.value = 'missing'
    } else {
      mockStatus.value = 'error'
      mockStatusError.value = message || 'Failed to check mock status'
    }
  }
}

function goToMockEditor() {
  if (!existingMock.value) return
  router.push({
    name: 'edit-mock',
    params: {
      id: existingMock.value.projectId,
      mockId: existingMock.value.id,
    },
  })
}

function openCreateMockModal() {
  showSelectProjectModal.value = true
}

function closeCreateMockModal() {
  // Guard against closing mid-POST; the modal also blocks its own Cancel
  // button while `submitting` is true but the backdrop click reaches here.
  if (selectProjectModalRef.value?.submitting) return
  showSelectProjectModal.value = false
}

// Build a CreateMockPayload from the raw stub. The stubService's
// `RequestConfig` / `ResponseConfig` types are loosely-typed siblings of
// WireMock's stub JSON — on the wire they match the WireMate
// `RequestDefinition` / `ResponseDefinition` shape (headers use StringMatcher
// etc.), so we cast through here rather than re-typing every nested field.
function buildCreatePayload(s: StubMapping, projectId: string): CreateMockPayload {
  const payload: CreateMockPayload = {
    name: s.name || `Adopted stub ${s.id.substring(0, 8)}`,
    projectId,
    request: s.request as unknown as RequestDefinition,
    response: s.response as unknown as ResponseDefinition,
    // WireMate mocks default to persistent; the stub's own flag may be
    // `undefined` when it was added directly via the WireMock admin API.
    persistent: s.persistent ?? true,
  }
  if (typeof s.priority === 'number') payload.priority = s.priority

  // Carry over any existing metadata so nothing the user may have
  // stored on the raw stub is silently dropped — but stamp the
  // WireMate `projectId` tag regardless. That tag is what
  // StubsView's "WireMate Stub" button keys off to round-trip back
  // to the editor, so every mock WireMate creates must carry it.
  payload.metadata = {
    ...(s.metadata ?? {}),
    projectId,
  }

  return payload
}

async function handleCreateMock(projectId: string) {
  if (!stub.value) return
  const modal = selectProjectModalRef.value
  if (modal) modal.submitting = true
  mockStatus.value = 'creating'
  try {
    const payload = buildCreatePayload(stub.value, projectId)
    const created = await mockApi.create(payload)
    existingMock.value = created
    mockStatus.value = 'exists'
    showSelectProjectModal.value = false
    showToastMessage(`Stub adopted into WireMate as '${created.name}'`, BaseToastEnum.SUCCESS)
  } catch (e) {
    // Flip back to 'missing' so the button reverts to "create" and the
    // user can pick a different project or retry.
    mockStatus.value = 'missing'
    const message = e instanceof Error ? e.message : 'Failed to create mock'
    showToastMessage(`Failed to create mock: ${message}`, BaseToastEnum.ERROR)
  } finally {
    if (modal) modal.submitting = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    stub.value = await getStub(stubId)
    // Only probe the backend once the raw stub loaded — no point asking
    // "is this a mock?" if we don't even have a valid stub.
    checkMockExistence()
  } catch (_e) {
    error.value = 'Failed to load stub details'
  } finally {
    loading.value = false
  }
})

function goBack() {
  router.push({ name: 'stubs' })
}

function handleDelete() {
  showDeleteModal.value = true
}

async function confirmDelete() {
  if (!stub.value) return
  try {
    await deleteStub(stub.value.id)
    router.push({ name: 'stubs' })
  } catch (_e) {
    error.value = 'Failed to delete stub'
  } finally {
    showDeleteModal.value = false
  }
}

function cancelDelete() {
  showDeleteModal.value = false
}

const displayUrl = computed(() => {
  if (!stub.value) return '/'
  return stub.value.request.url || stub.value.request.urlPath || stub.value.request.urlPattern || stub.value.request.urlPathPattern || '/'
})

const requestJson = computed(() => {
  if (!stub.value) return ''
  return JSON.stringify(stub.value.request, null, 2)
})

const responseJson = computed(() => {
  if (!stub.value) return ''
  return JSON.stringify(stub.value.response, null, 2)
})

const fullJson = computed(() => {
  if (!stub.value) return ''
  return JSON.stringify(stub.value, null, 2)
})

function methodColor(method?: string): string {
  const m = (method || 'ANY').toUpperCase()
  const colors: Record<string, string> = {
    GET: 'bg-blue-500',
    POST: 'bg-green-500',
    PUT: 'bg-yellow-500',
    DELETE: 'bg-red-500',
    PATCH: 'bg-purple-500',
    HEAD: 'bg-gray-500',
  }
  return colors[m] || 'bg-gray-500'
}

function statusColor(status?: number): string {
  const code = status || 200
  if (isDark.value) {
    if (code >= 200 && code < 300) return 'bg-green-500/15 text-green-400'
    if (code >= 300 && code < 400) return 'bg-blue-500/15 text-blue-400'
    if (code >= 400 && code < 500) return 'bg-yellow-500/15 text-yellow-400'
    return 'bg-red-500/15 text-red-400'
  }
  if (code >= 200 && code < 300) return 'bg-green-100 text-green-800'
  if (code >= 300 && code < 400) return 'bg-blue-100 text-blue-800'
  if (code >= 400 && code < 500) return 'bg-yellow-100 text-yellow-800'
  return 'bg-red-100 text-red-800'
}

function formatDate(dateString?: string): string {
  if (!dateString) return '—'
  try {
    return new Date(dateString).toLocaleString()
  } catch {
    return dateString
  }
}

async function copyToClipboard(text: string, label = 'Text') {
  try {
    await navigator.clipboard.writeText(text)
    showToastMessage(`${label} copied to clipboard`, BaseToastEnum.SUCCESS)
  } catch (_e) {
    showToastMessage('Failed to copy to clipboard', BaseToastEnum.ERROR)
  }
}
</script>

<template>
  <div>
    <div class="max-w-5xl mx-auto px-4 sm:px-6 py-6 sm:py-8">
      <!-- Back button -->
      <button
        class="inline-flex items-center gap-2 mb-6 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
        :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
        @click="goBack"
      >
        <ArrowLeftIcon class="w-4 h-4" />
        Back to Stubs
      </button>

      <!-- Loading -->
      <div v-if="loading" class="flex justify-center py-20">
        <BaseSpinner />
      </div>

      <!-- Error -->
      <div v-else-if="error && !stub" class="text-center py-20">
        <p class="text-red-500 mb-4">{{ error }}</p>
        <BaseButton
          description="Back to Stubs"
          :color="BaseButtonEnum.GREEN"
          @click="goBack"
        />
      </div>

      <!-- Stub details -->
      <div v-else-if="stub">
        <!-- Header card -->
        <div class="rounded-lg border p-6 transition-colors"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex items-start justify-between mb-6">
            <div class="flex items-center gap-4">
              <div class="w-12 h-12 rounded-lg flex items-center justify-center"
                :class="isDark ? 'bg-emerald-500/10' : 'bg-emerald-50'">
                <svg class="w-6 h-6" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                </svg>
              </div>
              <div>
                <h2 class="text-xl font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">
                  {{ stub.name || 'Unnamed Stub' }}
                </h2>
                <p class="text-sm font-mono mt-0.5" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  {{ stub.id }}
                </p>
              </div>
            </div>
            <div class="flex items-center gap-2 shrink-0">
              <!--
                WireMate mock round-trip. Three states worth rendering
                (loading is implicit — the button is disabled with a
                spinner glyph; error is neutral so a down backend isn't
                mislabelled as 'missing'):
                  exists  → 'WireMate Stub' link into the mock editor
                  missing → 'create WireMate Stub' opens the project
                            picker that POSTs /api/mocks on confirm
                  creating→ disabled, spinner label (the POST fires from
                            within the modal but we already flip status
                            here so the header reflects it)
              -->
              <button
                v-if="mockStatus === 'exists'"
                @click="goToMockEditor"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-emerald-400 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'"
                title="Open this stub in the WireMate mock editor"
              >
                <WireMateLogo :size="14" />
                WireMate Stub
              </button>
              <button
                v-else-if="mockStatus === 'missing'"
                @click="openCreateMockModal"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-amber-300 hover:bg-amber-500/10' : 'text-amber-700 hover:bg-amber-50'"
                title="Adopt this raw stub into a WireMate project"
              >
                <WireMateLogo :size="14" />
                create WireMate Stub
              </button>
              <button
                v-else-if="mockStatus === 'creating'"
                disabled
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium cursor-not-allowed opacity-70"
                :class="isDark ? 'text-amber-300' : 'text-amber-700'"
              >
                <svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                </svg>
                Creating…
              </button>
              <button
                v-else-if="mockStatus === 'loading'"
                disabled
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium cursor-not-allowed opacity-60"
                :class="isDark ? 'text-gray-500' : 'text-gray-400'"
              >
                <svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
                  <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                </svg>
                Checking…
              </button>
              <button
                v-else-if="mockStatus === 'error'"
                @click="checkMockExistence"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-gray-400 hover:bg-gray-700' : 'text-gray-500 hover:bg-gray-100'"
                :title="mockStatusError || 'Retry the WireMate mock probe'"
              >
                <WireMateLogo :size="14" />
                Retry check
              </button>

              <button
                @click="handleDelete"
                class="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
                Delete
              </button>
            </div>
          </div>

          <!-- Summary badges -->
          <div class="flex flex-wrap items-center gap-3 mb-6">
            <span :class="methodColor(stub.request.method)" class="px-3 py-1 rounded text-xs font-bold text-white uppercase">
              {{ stub.request.method || 'ANY' }}
            </span>
            <span :class="statusColor(stub.response.status)" class="px-3 py-1 rounded-full text-sm font-semibold">
              {{ stub.response.status || 200 }}
            </span>
            <span v-if="stub.persistent"
              class="text-xs px-2 py-1 rounded font-medium"
              :class="isDark ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'">
              Persistent
            </span>
            <span v-if="stub.priority"
              class="text-xs px-2 py-1 rounded font-medium"
              :class="isDark ? 'bg-gray-700 text-gray-300' : 'bg-gray-100 text-gray-600'">
              Priority: {{ stub.priority }}
            </span>
          </div>

          <!-- URL -->
          <div class="rounded-lg p-3 mb-4" :class="isDark ? 'bg-gray-800' : 'bg-gray-50'">
            <p class="text-xs uppercase tracking-wide mb-1 font-medium" :class="isDark ? 'text-gray-500' : 'text-gray-400'">URL</p>
            <p class="font-mono text-sm break-all" :class="isDark ? 'text-gray-200' : 'text-gray-800'">{{ displayUrl }}</p>
          </div>

          <!-- Metadata (only render the section when at least one field has a value) -->
          <div
            v-if="stub.createdDate || stub.response.fixedDelayMilliseconds"
            class="grid grid-cols-2 gap-4 border-t pt-4"
            :class="isDark ? 'border-gray-700' : 'border-gray-100'"
          >
            <div v-if="stub.createdDate">
              <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Created</p>
              <p class="text-sm" :class="isDark ? 'text-gray-300' : 'text-gray-700'">{{ formatDate(stub.createdDate) }}</p>
            </div>
            <div v-if="stub.response.fixedDelayMilliseconds">
              <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Response Delay</p>
              <p class="text-sm" :class="isDark ? 'text-gray-300' : 'text-gray-700'">{{ stub.response.fixedDelayMilliseconds }}ms</p>
            </div>
          </div>
        </div>

        <!-- Request section -->
        <div class="mt-6 rounded-lg border transition-colors"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex items-center justify-between px-6 py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
            <h3 class="text-base font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">Request</h3>
            <button
              @click="copyToClipboard(requestJson, 'Request JSON')"
              class="text-xs font-medium px-2 py-1 rounded transition-colors cursor-pointer"
              :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
            >
              Copy
            </button>
          </div>
          <pre class="mx-4 mb-4 mt-2 px-5 py-4 rounded-lg overflow-auto text-sm font-mono" :class="isDark ? 'bg-gray-800/70 text-gray-300' : 'bg-gray-100 text-gray-700'">{{ requestJson }}</pre>
        </div>

        <!-- Response section -->
        <div class="mt-6 rounded-lg border transition-colors"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex items-center justify-between px-6 py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
            <h3 class="text-base font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">Response</h3>
            <button
              @click="copyToClipboard(responseJson, 'Response JSON')"
              class="text-xs font-medium px-2 py-1 rounded transition-colors cursor-pointer"
              :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
            >
              Copy
            </button>
          </div>
          <pre class="mx-4 mb-4 mt-2 px-5 py-4 rounded-lg overflow-auto text-sm font-mono" :class="isDark ? 'bg-gray-800/70 text-gray-300' : 'bg-gray-100 text-gray-700'">{{ responseJson }}</pre>
        </div>

        <!-- Full JSON section -->
        <div class="mt-6 rounded-lg border transition-colors"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex items-center justify-between px-6 py-4 border-b" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
            <h3 class="text-base font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">Full Mapping JSON</h3>
            <button
              @click="copyToClipboard(fullJson, 'Full mapping JSON')"
              class="text-xs font-medium px-2 py-1 rounded transition-colors cursor-pointer"
              :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
            >
              Copy
            </button>
          </div>
          <pre class="mx-4 mb-4 mt-2 px-5 py-4 rounded-lg overflow-auto text-sm font-mono max-h-96" :class="isDark ? 'bg-gray-800/70 text-gray-300' : 'bg-gray-100 text-gray-700'">{{ fullJson }}</pre>
        </div>
      </div>
    </div>

    <!-- Toast Notifications -->
    <BaseToast
      v-if="showToast"
      :description="toastMessage"
      :mode="toastType"
    />

    <!-- Delete confirmation modal -->
    <ConfirmModal
      v-if="showDeleteModal"
      title="Delete Stub"
      :message="`Are you sure you want to delete stub '${stub?.name || stub?.id?.substring(0, 8) + '...'}'? This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />

    <!--
      Project picker for adopting this raw stub into WireMate. Lists the
      backend's projects, then POSTs /api/mocks on confirm. The stub is
      created under the user-selected project and its `metadata.projectId`
      is stamped so the round-trip "WireMate Stub" button keeps working.
    -->
    <SelectProjectModal
      v-if="showSelectProjectModal"
      ref="selectProjectModalRef"
      title="Create WireMate Stub"
      message="Pick a project to adopt this stub into. It will be saved as a new WireMate mock using the existing stub id."
      confirm-text="Create"
      submitting-text="Creating..."
      @confirm="handleCreateMock"
      @cancel="closeCreateMockModal"
    />
  </div>
</template> 