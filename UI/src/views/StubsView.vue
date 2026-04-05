<template>
  <div class="min-h-screen" :class="isDark ? 'bg-gray-900' : 'bg-gray-50'">
    <!-- Header -->
    <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border-b">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h1 :class="isDark ? 'text-white' : 'text-gray-900'" class="text-3xl font-bold">
              WireMock Stubs
            </h1>
            <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="mt-2">
              Manage and view all API stub mappings
            </p>
          </div>
          <div class="flex items-center gap-3">
            <BaseButton
              :is-loading="isDeletingUnmatched"
              description="Delete Unmatched"
              :color="BaseButtonEnum.RED"
              @click="handleDeleteUnmatched"
            />
            <BaseButton
              :is-loading="isLoading"
              description="Refresh"
              :color="BaseButtonEnum.GREEN"
              @click="handleRefresh"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- Main Content -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
      <!-- Stats and Filters -->
      <div class="mb-8">
        <div :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-6 mb-6">
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div>
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm font-medium">
                Total Stubs
              </p>
              <p class="text-3xl font-bold text-emerald-600 mt-1">
                {{ totalStubs }}
              </p>
            </div>
            <div class="flex-1">
              <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-sm font-medium mb-2">
                Search & Filter
              </label>
              <div class="flex gap-2">
                <input
                  v-model="searchQuery"
                  type="text"
                  placeholder="Search by URL or method..."
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white placeholder-gray-400' : 'bg-gray-50 border-gray-300 text-gray-900 placeholder-gray-500'"
                  class="flex-1 px-4 py-2 border rounded-lg focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none"
                />
                <select
                  v-model="filterMethod"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                  class="px-4 py-2 border rounded-lg focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none"
                >
                  <option value="">All Methods</option>
                  <option value="GET">GET</option>
                  <option value="POST">POST</option>
                  <option value="PUT">PUT</option>
                  <option value="DELETE">DELETE</option>
                  <option value="PATCH">PATCH</option>
                  <option value="HEAD">HEAD</option>
                </select>
                <select
                  v-model="filterStatus"
                  :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
                  class="px-4 py-2 border rounded-lg focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none"
                >
                  <option value="">All Statuses</option>
                  <option value="2xx">2xx Success</option>
                  <option value="3xx">3xx Redirect</option>
                  <option value="4xx">4xx Client Error</option>
                  <option value="5xx">5xx Server Error</option>
                </select>
                <button
                  type="button"
                  :disabled="!searchQuery && !filterMethod && !filterStatus"
                  @click="resetFilters"
                  :class="isDark
                    ? 'bg-gray-700 border-gray-600 text-gray-200 hover:bg-gray-600 disabled:opacity-50 disabled:cursor-not-allowed'
                    : 'bg-gray-50 border-gray-300 text-gray-700 hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed'"
                  class="px-4 py-2 border rounded-lg focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none transition-colors duration-300 ease-in-out cursor-pointer"
                  title="Reset search and filters"
                >
                  Reset
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Pagination Controls -->
        <div class="flex flex-col sm:flex-row sm:items-end gap-4 pt-4 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <!-- Page Size -->
          <div>
            <label :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="block text-xs font-medium mb-1">
              Page Size
            </label>
            <select
              v-model.number="pageSize"
              @change="onPageSizeChange"
              :class="isDark ? 'bg-gray-700 border-gray-600 text-white' : 'bg-gray-50 border-gray-300 text-gray-900'"
              class="px-3 py-2 border rounded-lg focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500 outline-none text-sm"
            >
              <option :value="25">25</option>
              <option :value="50">50</option>
              <option :value="100">100</option>
              <option :value="250">250</option>
              <option :value="500">500</option>
            </select>
          </div>
          <!-- Prev / Page info / Next -->
          <div class="flex items-center gap-2">
            <button
              type="button"
              :disabled="currentPage <= 1"
              @click="goToPage(currentPage - 1)"
              :class="isDark
                ? 'bg-gray-700 border-gray-600 text-gray-200 hover:bg-gray-600 disabled:opacity-40 disabled:cursor-not-allowed'
                : 'bg-gray-50 border-gray-300 text-gray-700 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed'"
              class="px-3 py-2 border rounded-lg text-sm font-medium transition-colors duration-300 ease-in-out cursor-pointer"
            >
              Prev
            </button>
            <span :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-medium px-2">
              Page {{ currentPage }} / {{ totalPages }}
            </span>
            <button
              type="button"
              :disabled="currentPage >= totalPages"
              @click="goToPage(currentPage + 1)"
              :class="isDark
                ? 'bg-gray-700 border-gray-600 text-gray-200 hover:bg-gray-600 disabled:opacity-40 disabled:cursor-not-allowed'
                : 'bg-gray-50 border-gray-300 text-gray-700 hover:bg-gray-100 disabled:opacity-40 disabled:cursor-not-allowed'"
              class="px-3 py-2 border rounded-lg text-sm font-medium transition-colors duration-300 ease-in-out cursor-pointer"
            >
              Next
            </button>
          </div>
        </div>

        <div :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm">
          Showing {{ filteredStubs.length }} of {{ totalStubs }} stubs
          <span v-if="serverTotal > totalStubs" class="ml-1">({{ serverTotal }} total on server)</span>
        </div>
      </div>

      <!-- Loading State -->
      <div v-if="isLoading" class="flex justify-center items-center py-12">
        <BaseSpinner />
      </div>

      <!-- Empty State -->
      <div v-else-if="filteredStubs.length === 0" :class="isDark ? 'bg-gray-800 border-gray-700' : 'bg-white border-gray-200'" class="border rounded-lg p-12 text-center">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <h3 :class="isDark ? 'text-white' : 'text-gray-900'" class="mt-4 text-lg font-medium">
          {{ totalStubs === 0 ? 'No stubs found' : 'No stubs match your filters' }}
        </h3>
        <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="mt-2">
          {{ totalStubs === 0 ? 'Create your first stub mapping in WireMock' : 'Try adjusting your search or filter' }}
        </p>
      </div>

      <!-- Stubs Grid -->
      <div v-else class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div
          v-for="stub in filteredStubs"
          :key="stub.id"
          :class="isDark ? 'bg-gray-800 border-gray-700 hover:bg-gray-900' : 'bg-white border-gray-200 hover:bg-gray-100'"
          class="border rounded-lg p-6 hover:shadow-lg transition-all cursor-pointer"
          @click="viewStub(stub.id)"
        >
          <!-- Stub Header -->
          <div class="flex items-start justify-between mb-4">
            <div class="flex-1">
              <!--
                Row layout:
                  1. Method badge on its own — the HTTP verb is the
                     single most scannable field, so it gets top-row
                     prominence without the name crowding it.
                  2. Stub name — rendered above the URL so long
                     human-readable names can wrap/truncate on their
                     own line before the monospaced URL underneath.
                  3. URL — monospaced; the canonical identifier most
                     users are scanning for.
              -->
              <div class="flex items-center gap-2 mb-2">
                <span :class="getMethodBgColor(stub.request.method)" class="px-2 py-1 rounded text-xs font-bold text-white">
                  {{ stub.request.method || 'ANY' }}
                </span>
              </div>
              <p v-if="stub.name" :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-medium truncate mb-1">
                {{ stub.name }}
              </p>
              <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-sm font-mono truncate break-all">
                {{ getDisplayUrl(stub.request) }}
              </p>
            </div>
            <div class="flex items-center gap-1 ml-2 shrink-0">
              <!--
                WireMate mock — round-trip from the raw stub back to the
                mock editor that owns it. Only wired up when the stub
                carries the `metadata.projectId` tag that WireMate
                stamps on its own stubs; stubs added through the raw
                WireMock API don't have that tag, so the button is
                disabled with an explanatory tooltip rather than routed
                to a broken URL.
              -->
              <button
                @click.stop="viewWireMateMock(stub)"
                :disabled="!wiremateProjectId(stub)"
                class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                :class="isDark
                  ? 'text-emerald-400 hover:bg-emerald-500/10 disabled:hover:bg-transparent'
                  : 'text-emerald-600 hover:bg-emerald-50 disabled:hover:bg-transparent'"
                :title="wiremateProjectId(stub)
                  ? 'Open this stub in the WireMate mock editor'
                  : 'Not a WireMate-managed stub (no projectId in metadata)'"
              >
                <WireMateLogo :size="14" />
                WireMate Stub
              </button>
              <button
                @click.stop="viewStubLogs(stub)"
                class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-sky-400 hover:bg-sky-500/10' : 'text-sky-600 hover:bg-sky-50'"
                title="View request logs for this stub"
              >
                <ClipboardDocumentListIcon class="w-4 h-4" />
                Logs
              </button>
              <button
                @click.stop="handleDeleteStub(stub)"
                class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
                title="Delete stub"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                </svg>
                Delete
              </button>
            </div>
          </div>

          <!-- Status Code -->
          <div class="mb-4 flex items-center gap-3">
            <span :class="getStatusCodeColor(stub.response.status)" class="px-3 py-1 rounded-full text-sm font-semibold">
              {{ stub.response.status || 200 }}
            </span>
            <span v-if="stub.priority" :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs">
              Priority: {{ stub.priority }}
            </span>
            <span v-if="stub.persistent"
              class="text-xs px-2 py-1 rounded"
              :class="isDark ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'">
              Persistent
            </span>
          </div>

          <!-- Metadata -->
          <div v-if="hasMetadata(stub.metadata)"
            :class="isDark ? 'bg-gray-900' : 'bg-gray-100'"
            class="rounded p-3 mb-4">
            <p :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="text-xs font-medium mb-2 uppercase tracking-wide">
              Metadata
            </p>
            <div class="space-y-1">
              <div v-for="(value, key) in stub.metadata" :key="key" class="flex items-start gap-2 text-xs">
                <span :class="isDark ? 'text-gray-500' : 'text-gray-500'" class="font-mono shrink-0">{{ key }}:</span>
                <code :class="isDark ? 'text-gray-300' : 'text-gray-700'"
                  class="font-mono break-all whitespace-pre-wrap">{{ formatMetadataValue(value) }}</code>
              </div>
            </div>
          </div>

          <!-- Stub Details -->
          <div :class="isDark ? 'border-gray-700' : 'border-gray-200'" class="border-t pt-4 text-xs space-y-2">
            <div class="flex items-start gap-2">
              <span :class="isDark ? 'text-gray-400' : 'text-gray-600'" class="shrink-0">ID:</span>
              <code :class="isDark ? 'text-gray-300 bg-gray-900' : 'text-gray-700 bg-gray-100'"
                class="px-2 py-1 rounded font-mono break-all">
                {{ stub.id }}
              </code>
            </div>
            <div v-if="stub.createdDate" class="flex justify-between">
              <span :class="isDark ? 'text-gray-400' : 'text-gray-600'">Created:</span>
              <span :class="isDark ? 'text-gray-300' : 'text-gray-700'">
                {{ formatDate(stub.createdDate) }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Bottom Pagination -->
      <div v-if="totalPages > 1 && !isLoading" class="mt-8 flex items-center justify-center gap-2">
        <button
          type="button"
          :disabled="currentPage <= 1"
          @click="goToPage(1)"
          :class="isDark
            ? 'bg-gray-800 border-gray-600 text-gray-300 hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed'
            : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed'"
          class="px-3 py-2 border rounded-lg text-sm transition-colors duration-300 ease-in-out cursor-pointer"
        >
          First
        </button>
        <button
          type="button"
          :disabled="currentPage <= 1"
          @click="goToPage(currentPage - 1)"
          :class="isDark
            ? 'bg-gray-800 border-gray-600 text-gray-300 hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed'
            : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed'"
          class="px-3 py-2 border rounded-lg text-sm transition-colors duration-300 ease-in-out cursor-pointer"
        >
          Prev
        </button>
        <span :class="isDark ? 'text-gray-300' : 'text-gray-700'" class="text-sm font-medium px-3">
          {{ currentPage }} / {{ totalPages }}
        </span>
        <button
          type="button"
          :disabled="currentPage >= totalPages"
          @click="goToPage(currentPage + 1)"
          :class="isDark
            ? 'bg-gray-800 border-gray-600 text-gray-300 hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed'
            : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed'"
          class="px-3 py-2 border rounded-lg text-sm transition-colors duration-300 ease-in-out cursor-pointer"
        >
          Next
        </button>
        <button
          type="button"
          :disabled="currentPage >= totalPages"
          @click="goToPage(totalPages)"
          :class="isDark
            ? 'bg-gray-800 border-gray-600 text-gray-300 hover:bg-gray-700 disabled:opacity-40 disabled:cursor-not-allowed'
            : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50 disabled:opacity-40 disabled:cursor-not-allowed'"
          class="px-3 py-2 border rounded-lg text-sm transition-colors duration-300 ease-in-out cursor-pointer"
        >
          Last
        </button>
      </div>
    </div>

    <!-- Delete confirmation modal -->
    <ConfirmModal
      v-if="showDeleteModal"
      title="Delete Stub"
      :message="`Are you sure you want to delete this stub${stubToDelete?.name ? ` '${stubToDelete.name}'` : ''}? This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDeleteStub"
      @cancel="cancelDeleteStub"
    />

    <!-- Delete unmatched stubs confirmation modal -->
    <ConfirmModal
      v-if="showDeleteUnmatchedModal"
      title="Delete Unmatched Stubs"
      message="Are you sure you want to delete all stubs that have not been matched by any request? This action cannot be undone."
      confirm-text="Yes, delete unmatched"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDeleteUnmatched"
      @cancel="cancelDeleteUnmatched"
    />

    <!-- Toast Notifications -->
    <BaseToast
      v-if="showToast"
      :description="toastMessage"
      :mode="toastType"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BaseSpinner, BaseToast, BaseButton, BaseToastEnum, BaseButtonEnum } from 'mgv-backoffice'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { useDebouncedRef } from '../composables/useDebounce'
import { fetchAllStubs, deleteStub, deleteUnmatchedStubs } from '../services/stubService'
import type { StubMapping, StubMappingsResponse } from '../services/stubService'
import ConfirmModal from '../components/ConfirmModal.vue'
import { ClipboardDocumentListIcon } from '@heroicons/vue/24/outline'
import WireMateLogo from '../components/WireMateLogo.vue'
import { methodBadgeBright, statusBadgeTinted } from '../utils/httpColors'

// Router & Theme
const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()

// Query-param helpers — keep URL parsing/coercion in one place.
const ALLOWED_PAGE_SIZES = [25, 50, 100, 250, 500] as const
function readQueryString(key: string): string {
  return typeof route.query[key] === 'string' ? (route.query[key] as string) : ''
}
function readQueryInt(key: string, fallback: number, validate?: (n: number) => boolean): number {
  const raw = route.query[key]
  const parsed = typeof raw === 'string' ? parseInt(raw, 10) : NaN
  if (!Number.isFinite(parsed) || parsed <= 0) return fallback
  if (validate && !validate(parsed)) return fallback
  return parsed
}

// State — searchQuery/filters/pagination are all seeded from the URL so deep
// links and back/forward navigation restore the full view state.
const stubs = ref<StubMapping[]>([])
const isLoading = ref(false)
const searchQuery = ref(readQueryString('search'))
const filterMethod = ref(readQueryString('method'))
const { showToast, toastMessage, toastType, showToastMessage } = useToast()
const filterStatus = ref(readQueryString('status'))
const debouncedSearch = useDebouncedRef(searchQuery, 300)
const showDeleteModal = ref(false)
const stubToDelete = ref<StubMapping | null>(null)
const showDeleteUnmatchedModal = ref(false)
const isDeletingUnmatched = ref(false)

// Pagination state (seeded from URL)
const pageSize = ref(
  readQueryInt('pageSize', 100, (n) => (ALLOWED_PAGE_SIZES as readonly number[]).includes(n)),
)
const currentPage = ref(readQueryInt('page', 1))
const serverTotal = ref(0)

// Computed
const totalStubs = computed(() => stubs.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(serverTotal.value / pageSize.value)))
const currentOffset = computed(() => (currentPage.value - 1) * pageSize.value)

const filteredStubs = computed(() => {
  return stubs.value.filter((stub) => {
    const matchesSearch =
      !searchQuery.value ||
      getDisplayUrl(stub.request).toLowerCase().includes(searchQuery.value.toLowerCase()) ||
      (stub.name?.toLowerCase().includes(searchQuery.value.toLowerCase())) ||
      stub.id.toLowerCase().includes(searchQuery.value.toLowerCase())

    const matchesMethod =
      !filterMethod.value ||
      (stub.request.method || 'ANY').toUpperCase() === filterMethod.value.toUpperCase()

    let matchesStatus = true
    if (filterStatus.value) {
      const code = stub.response.status || 200
      switch (filterStatus.value) {
        case '2xx': matchesStatus = code >= 200 && code < 300; break
        case '3xx': matchesStatus = code >= 300 && code < 400; break
        case '4xx': matchesStatus = code >= 400 && code < 500; break
        case '5xx': matchesStatus = code >= 500; break
      }
    }

    return matchesSearch && matchesMethod && matchesStatus
  })
})

// Methods
async function loadStubs() {
  isLoading.value = true
  try {
    const response: StubMappingsResponse = await fetchAllStubs({
      limit: pageSize.value,
      offset: currentOffset.value,
    })
    stubs.value = response.mappings || []
    serverTotal.value = response.meta?.total ?? stubs.value.length
  } catch (error) {
    console.error('Failed to load stubs:', error)
    showToastMessage('Failed to load stubs', BaseToastEnum.ERROR)
  } finally {
    isLoading.value = false
  }
}

function goToPage(page: number) {
  if (page < 1 || page > totalPages.value) return
  currentPage.value = page
  loadStubs()
}

function onPageSizeChange() {
  currentPage.value = 1
  loadStubs()
}

function resetFilters() {
  searchQuery.value = ''
  filterMethod.value = ''
  filterStatus.value = ''
}

function viewStub(id: string) {
  router.push({ name: 'stub-detail', params: { id } })
}

// Open the Logs view pre-filtered to this stub's URL. Mirrors the
// behaviour of the "Logs" button on mock cards in ProjectDetailView.
function viewStubLogs(stub: StubMapping) {
  const url = getDisplayUrl(stub.request)
  const search = url && url !== '/' ? url : stub.name || ''
  router.push({ name: 'logs', query: { search } })
}

// WireMate-generated stubs tag themselves with `metadata.projectId` so we
// can round-trip from a raw stub on the admin port back to the mock
// editor that owns it. Returns the projectId when that metadata is
// present; null otherwise (e.g. stubs added through the raw WireMock
// API without WireMate's tagging — the Edit button is disabled in that
// case rather than routed to a broken URL).
function wiremateProjectId(stub: StubMapping): string | null {
  const pid = stub.metadata?.projectId
  return typeof pid === 'string' && pid ? pid : null
}

function viewWireMateMock(stub: StubMapping) {
  const projectId = wiremateProjectId(stub)
  if (!projectId || !stub.id) return
  router.push({ name: 'edit-mock', params: { id: projectId, mockId: stub.id } })
}

async function handleRefresh() {
  await loadStubs()
  showToastMessage('Stubs refreshed', BaseToastEnum.SUCCESS)
}

function handleDeleteStub(stub: StubMapping) {
  stubToDelete.value = stub
  showDeleteModal.value = true
}

async function confirmDeleteStub() {
  if (!stubToDelete.value) return
  const id = stubToDelete.value.id

  try {
    await deleteStub(id)
    stubs.value = stubs.value.filter((stub) => stub.id !== id)
    showToastMessage('Stub deleted successfully', BaseToastEnum.SUCCESS)
  } catch (error) {
    console.error('Failed to delete stub:', error)
    showToastMessage('Failed to delete stub', BaseToastEnum.ERROR)
  } finally {
    showDeleteModal.value = false
    stubToDelete.value = null
  }
}

function cancelDeleteStub() {
  showDeleteModal.value = false
  stubToDelete.value = null
}

function handleDeleteUnmatched() {
  showDeleteUnmatchedModal.value = true
}

async function confirmDeleteUnmatched() {
  isDeletingUnmatched.value = true
  try {
    await deleteUnmatchedStubs()
    showToastMessage('Unmatched stubs deleted successfully', BaseToastEnum.SUCCESS)
    await loadStubs()
  } catch (error) {
    console.error('Failed to delete unmatched stubs:', error)
    showToastMessage('Failed to delete unmatched stubs', BaseToastEnum.ERROR)
  } finally {
    isDeletingUnmatched.value = false
    showDeleteUnmatchedModal.value = false
  }
}

function cancelDeleteUnmatched() {
  showDeleteUnmatchedModal.value = false
}

function getDisplayUrl(request: StubMapping['request']): string {
  return request.url || request.urlPath || request.urlPattern || request.urlPathPattern || '/'
}

function hasMetadata(metadata: StubMapping['metadata']): boolean {
  return !!metadata && Object.keys(metadata).length > 0
}

function formatMetadataValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

const getMethodBgColor = methodBadgeBright
function getStatusCodeColor(status?: number): string {
  return statusBadgeTinted(status, isDark.value)
}

function formatDate(dateString: string): string {
  try {
    const date = new Date(dateString)
    return date.toLocaleString()
  } catch {
    return dateString
  }
}

// Sync search, filter, and pagination state to URL query params (debounced search via debouncedSearch).
watch(
  [debouncedSearch, filterMethod, filterStatus, currentPage, pageSize],
  ([newSearch, newMethod, newStatus, newPage, newSize]) => {
    const query: Record<string, string> = { ...route.query } as Record<string, string>

    if (newSearch) { query.search = newSearch } else { delete query.search }
    if (newMethod) { query.method = newMethod } else { delete query.method }
    if (newStatus) { query.status = newStatus } else { delete query.status }
    if (newPage && newPage > 1) { query.page = String(newPage) } else { delete query.page }
    if (newSize && newSize !== 100) { query.pageSize = String(newSize) } else { delete query.pageSize }

    // Avoid redundant navigation if nothing actually changed.
    const currentSearch = readQueryString('search')
    const currentMethod = readQueryString('method')
    const currentStatus = readQueryString('status')
    const currentPageParam = readQueryString('page')
    const currentPageSizeParam = readQueryString('pageSize')
    const nextPageParam = query.page ?? ''
    const nextPageSizeParam = query.pageSize ?? ''
    if (
      currentSearch === (newSearch || '') &&
      currentMethod === (newMethod || '') &&
      currentStatus === (newStatus || '') &&
      currentPageParam === nextPageParam &&
      currentPageSizeParam === nextPageSizeParam
    ) {
      return
    }

    router.replace({ query })
  },
)

// React to external URL changes (e.g. navigating from a mock card with a preset search,
// or using browser back/forward through the pagination history).
watch(
  () => [route.query.search, route.query.method, route.query.status, route.query.page, route.query.pageSize],
  ([newSearch, newMethod, newStatus, newPage, newSize]) => {
    const search = typeof newSearch === 'string' ? newSearch : ''
    const method = typeof newMethod === 'string' ? newMethod : ''
    const status = typeof newStatus === 'string' ? newStatus : ''
    const page = typeof newPage === 'string' ? Math.max(1, parseInt(newPage, 10) || 1) : 1
    const parsedSize = typeof newSize === 'string' ? parseInt(newSize, 10) : NaN
    const size = (ALLOWED_PAGE_SIZES as readonly number[]).includes(parsedSize) ? parsedSize : 100

    if (searchQuery.value !== search) searchQuery.value = search
    if (filterMethod.value !== method) filterMethod.value = method
    if (filterStatus.value !== status) filterStatus.value = status

    let shouldReload = false
    if (pageSize.value !== size) {
      pageSize.value = size
      shouldReload = true
    }
    if (currentPage.value !== page) {
      currentPage.value = page
      shouldReload = true
    }
    if (shouldReload) loadStubs()
  },
)

// Lifecycle
onMounted(() => {
  loadStubs()
})
</script>

<style scoped>
/* Ensure proper text selection in code blocks */
pre {
  user-select: text;
}
</style>