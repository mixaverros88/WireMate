<template>
  <div class="min-h-screen" :class="t.pageBg">
    <div class="pt-8">
      <!-- Reusable PageHeader — same width (max-w-4xl) and shape as
           the one on /notifications. Pass the page-specific icon
           through the `icon` slot and the action buttons through
           `actions`. -->
      <PageHeader
        title="WireMock Stubs"
        subtitle="Manage and view all API stub mappings"
        icon-color="emerald"
      >
        <template #icon="{ iconClass }">
          <PuzzlePieceIcon class="w-5 h-5" :class="iconClass" />
        </template>
        <template #actions>
          <!-- Refresh — kept visually identical to NotificationsView's
               refresh button so the page-level affordance is consistent
               across views. -->
          <button
            @click="handleRefresh"
            type="button"
            :disabled="isLoading"
            class="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDark
              ? 'bg-gray-800 text-gray-100 border-gray-700 hover:bg-gray-700'
              : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-100'"
            title="Refresh"
          >
            <ArrowPathIcon class="w-4 h-4" :class="{ 'animate-spin': isLoading }" />
            <span>Refresh</span>
          </button>
          <!--
            Delete all stubs on the WireMock server in one call. Routes
            through `DELETE /__admin/mappings`, which wipes both
            persistent and ephemeral mappings on the server, so we gate
            it behind a confirm modal. Disabled when the list is empty
            or while a deletion is already in flight.
          -->
          <button
            @click="openDeleteAllModal"
            type="button"
            :disabled="isDeletingAll || totalStubs === 0"
            class="inline-flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isDark
              ? 'bg-red-600 text-white border-red-600 hover:bg-red-500'
              : 'bg-red-600 text-white border-red-600 hover:bg-red-700'"
            :title="totalStubs === 0 ? 'No stubs to delete' : 'Delete every stub on the WireMock server'"
          >
            <TrashIcon class="w-4 h-4" :class="{ 'animate-pulse': isDeletingAll }" />
            <span>{{ isDeletingAll ? 'Deleting…' : 'Delete All' }}</span>
          </button>
        </template>
      </PageHeader>
    </div>

    <!-- Main Content -->
    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 pb-8">

      <!--
        Compact search toolbar — no panel chrome, just a single row of
        inline controls. Leading magnifier inside the input, ghost
        reset that only appears when there's something to clear, and
        the result-count summary lives on the right of the row so the
        whole search affordance reads as one continuous toolbar.
      -->
      <div class="mb-6">
        <div class="flex flex-wrap items-center gap-2">
          <!-- Search input with leading magnifier icon -->
          <div class="relative flex-1 min-w-[200px]">
            <MagnifyingGlassIcon
              class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 pointer-events-none"
              :class="isDark ? 'text-gray-500' : 'text-gray-400'"
            />
            <input
              v-model="searchQuery"
              type="text"
              placeholder="Search by URL or method..."
              class="w-full pl-9 pr-3 py-2 border rounded-lg text-sm outline-none transition-colors focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
              :class="isDark
                ? 'bg-gray-800 border-gray-700 text-white placeholder-gray-400'
                : 'bg-white border-gray-300 text-gray-900 placeholder-gray-500'"
            />
          </div>
          <select
            v-model="filterMethod"
            :class="isDark
              ? 'bg-gray-800 border-gray-700 text-gray-100'
              : 'bg-white border-gray-300 text-gray-700'"
            class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
          >
            <option value="">All methods</option>
            <option value="GET">GET</option>
            <option value="POST">POST</option>
            <option value="PUT">PUT</option>
            <option value="DELETE">DELETE</option>
            <option value="PATCH">PATCH</option>
            <option value="HEAD">HEAD</option>
          </select>
          <select
            v-model="filterStatus"
            :class="isDark
              ? 'bg-gray-800 border-gray-700 text-gray-100'
              : 'bg-white border-gray-300 text-gray-700'"
            class="px-3 py-2 border rounded-lg text-sm outline-none focus:ring-2 focus:ring-emerald-500 focus:border-emerald-500"
          >
            <option value="">All statuses</option>
            <option value="2xx">2xx Success</option>
            <option value="3xx">3xx Redirect</option>
            <option value="4xx">4xx Client Error</option>
            <option value="5xx">5xx Server Error</option>
          </select>
          <!--
            Reset action — only rendered when at least one filter is set.
            Bordered amber pill so it visibly stands out from the muted
            search/filter controls; the user explicitly asked for this
            to be more noticeable than the prior ghost button.
          -->
          <button
            v-if="searchQuery || filterMethod || filterStatus"
            type="button"
            @click="resetFilters"
            class="inline-flex items-center gap-1.5 px-3 py-2 border rounded-lg text-sm font-semibold cursor-pointer transition-colors"
            :class="isDark
              ? 'text-amber-300 border-amber-500/50 bg-amber-500/10 hover:bg-amber-500/20'
              : 'text-amber-700 border-amber-300 bg-amber-50 hover:bg-amber-100'"
            title="Reset search and filters"
            aria-label="Reset search and filters"
          >
            <XMarkIcon class="w-4 h-4" aria-hidden="true" />
            Reset filters
          </button>
          <!--
            Inline result count on the right of the toolbar — replaces
            both the old big "Total Stubs" stat and the separate
            "Showing X of Y" line below the panel.
          -->
          <span :class="t.mutedText" class="ml-auto text-sm shrink-0">
            <span class="font-semibold" :class="t.emeraldText">{{ filteredStubs.length }}</span>
            of {{ totalStubs }} stubs
          </span>
        </div>
      </div>

      <!-- Loading State -->
      <div v-if="isLoading" class="flex justify-center items-center py-12">
        <BaseSpinner />
      </div>

      <!-- Empty State -->
      <div v-else-if="filteredStubs.length === 0" :class="t.card" class="border rounded-lg p-12 text-center">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
        </svg>
        <h3 :class="t.primaryText" class="mt-4 text-lg font-medium">
          {{ totalStubs === 0 ? 'No stubs found' : 'No stubs match your filters' }}
        </h3>
        <p :class="t.mutedText" class="mt-2">
          {{ totalStubs === 0 ? 'Create your first stub mapping in WireMock' : 'Try adjusting your search or filter' }}
        </p>
      </div>

      <!--
        Stubs Grid. Renders the full *filtered* list — pagination was
        removed in favour of loading everything once and filtering
        client-side. The result-count chip in the toolbar above shows
        how many entries are visible vs. the total.
      -->
      <div v-else class="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div
          v-for="stub in filteredStubs"
          :key="stub.id"
          class="rounded-xl border p-4 flex flex-col justify-between transition-all"
          :class="isDark
            ? 'bg-gray-900 border-gray-700 hover:bg-gray-950'
            : 'bg-white border-gray-200 shadow-sm hover:bg-gray-100'"
        >
          <div>
            <!-- Row 1 — identity badges: method, status, content-type, URL,
                 match type, priority, persistence. Mirrors the mock card in
                 ProjectDetailView. -->
            <div class="flex items-center flex-wrap gap-2 mb-2">
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-bold uppercase tracking-wide"
                :class="methodColor(stub.request.method)"
                :title="`HTTP method: ${stub.request.method || 'ANY'}`">
                {{ stub.request.method || 'ANY' }}
              </span>
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold"
                :class="statusBadgeClasses(stub.response.status || 200)"
                :title="`HTTP ${stub.response.status || 200}`">
                {{ stub.response.status || 200 }}
              </span>
              <span v-if="responseContentType(stub)"
                class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono max-w-[16rem] truncate border"
                :class="isDark ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30' : 'bg-emerald-50 text-emerald-700 border-emerald-200'"
                :title="`Response Content-Type: ${responseContentType(stub)}`">
                {{ responseContentType(stub) }}
              </span>
              <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono max-w-[24rem] truncate border"
                :class="isDark ? 'bg-indigo-500/10 text-indigo-300 border-indigo-500/30' : 'bg-indigo-50 text-indigo-700 border-indigo-200'"
                :title="getDisplayUrl(stub.request)">
                {{ getDisplayUrl(stub.request) }}
              </span>
              <span class="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-medium uppercase tracking-wide border"
                :class="isDark ? 'border-gray-700 text-gray-400' : 'border-gray-200 text-gray-500'"
                :title="`URL match type: ${urlMatchLabel(stub.request)}`">
                {{ urlMatchLabel(stub.request) }}
              </span>
              <span v-if="typeof stub.priority === 'number'"
                class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-semibold border"
                :class="priorityBadgeClasses(stub.priority)"
                :title="`Priority ${stub.priority} (lower = higher precedence)`">
                <FlagIcon class="w-3 h-3" />
                P{{ stub.priority }}
              </span>
              <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium"
                :class="stub.persistent
                  ? (isDark ? 'bg-emerald-500/10 text-emerald-300' : 'bg-emerald-50 text-emerald-700')
                  : (isDark ? 'bg-red-500/10 text-red-300' : 'bg-red-50 text-red-700')"
                :title="stub.persistent ? 'Survives WireMock resets' : 'Cleared on WireMock reset'">
                <BookmarkIcon class="w-3 h-3" />
                {{ stub.persistent ? 'Persistent' : 'Ephemeral' }}
              </span>
            </div>

            <!-- Row 2 — stub name (primary heading) + raw id underneath. The
                 id is the real identifier on this page (names are optional on
                 raw WireMock stubs), so it stays visible rather than being
                 hidden behind a card click. -->
            <div class="flex items-center gap-2">
              <CodeBracketIcon class="w-5 h-5 shrink-0" :class="t.emeraldText" />
              <h3 class="text-sm font-semibold truncate" :class="t.primaryTextSoft" :title="stub.name || 'Unnamed stub'">
                {{ stub.name || 'Unnamed stub' }}
              </h3>
            </div>
            <code class="block text-[11px] font-mono mt-1 break-all" :class="t.mutedText" :title="stub.id">
              {{ stub.id }}
            </code>

            <!-- Row 3 — Request / Response summary, two side-by-side boxes. -->
            <div class="mt-3 grid grid-cols-1 md:grid-cols-2 gap-2">
              <div class="rounded-lg border p-2.5"
                :class="isDark ? 'border-sky-500/30 bg-sky-500/5' : 'border-sky-200 bg-sky-50/60'">
                <div class="flex items-center gap-1.5 mb-1.5">
                  <ArrowDownTrayIcon class="w-3.5 h-3.5" :class="isDark ? 'text-sky-300' : 'text-sky-700'" />
                  <h4 class="text-[10px] font-semibold uppercase tracking-wide" :class="isDark ? 'text-sky-300' : 'text-sky-700'">
                    Request Matching
                  </h4>
                </div>
                <dl class="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-[11px]">
                  <template v-for="row in requestSummary(stub)" :key="row.label">
                    <dt class="font-medium" :class="t.dimTextAlt">{{ row.label }}</dt>
                    <dd class="font-mono truncate" :class="isDark ? 'text-gray-200' : 'text-gray-800'" :title="row.value">{{ row.value }}</dd>
                  </template>
                </dl>
              </div>
              <div class="rounded-lg border p-2.5"
                :class="isDark ? 'border-emerald-500/30 bg-emerald-500/5' : 'border-emerald-200 bg-emerald-50/60'">
                <div class="flex items-center gap-1.5 mb-1.5">
                  <ArrowUpTrayIcon class="w-3.5 h-3.5" :class="isDark ? 'text-emerald-300' : 'text-emerald-700'" />
                  <h4 class="text-[10px] font-semibold uppercase tracking-wide" :class="isDark ? 'text-emerald-300' : 'text-emerald-700'">
                    Response Definition
                  </h4>
                </div>
                <dl class="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-[11px]">
                  <template v-for="row in responseSummary(stub)" :key="row.label">
                    <dt class="font-medium" :class="t.dimTextAlt">{{ row.label }}</dt>
                    <dd class="font-mono truncate" :class="isDark ? 'text-gray-200' : 'text-gray-800'" :title="row.value">{{ row.value }}</dd>
                  </template>
                </dl>
              </div>
            </div>
          </div>

          <!--
            Bottom action bar — mirrors the mock-card layout in
            ProjectDetailView. The card itself is no longer clickable; the
            leftmost "View Stub" button is the way to open the raw stub.
              · View Stub      — open the raw WireMock stub at /stubs/:id.
              · Edit WireMate  — round-trip to the WireMate mock editor that
                                 owns this stub. Disabled for raw WireMock
                                 stubs that don't carry `metadata.projectId`.
              · Project        — jump to the parent project (WireMate stubs only).
              · Logs           — open the request journal pre-filtered to this URL.
              · Delete         — opens the confirm modal.
          -->
          <div class="flex items-center flex-wrap gap-2 mt-3 pt-3 border-t" :class="t.divider">
            <button
              type="button"
              @click="viewStub(stub.id)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-indigo-400 hover:bg-indigo-500/10' : 'text-indigo-600 hover:bg-indigo-50'"
              title="Open the raw WireMock stub"
            >
              <CubeIcon class="w-4 h-4" />
              View Stub
            </button>
            <button
              type="button"
              @click="viewWireMateMock(stub)"
              :disabled="!wiremateProjectId(stub)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              :class="isDark
                ? 'text-emerald-400 hover:bg-emerald-500/10 disabled:hover:bg-transparent'
                : 'text-emerald-600 hover:bg-emerald-50 disabled:hover:bg-transparent'"
              :title="wiremateProjectId(stub)
                ? 'Open this stub in the WireMate mock editor'
                : 'Not a WireMate-managed stub (no projectId in metadata)'"
            >
              <PencilSquareIcon class="w-4 h-4" />
              Edit WireMate
            </button>
            <RouterLink
              v-if="wiremateProjectId(stub)"
              :to="{ name: 'project', params: { id: wiremateProjectId(stub)! } }"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-sky-400 hover:bg-sky-500/10' : 'text-sky-600 hover:bg-sky-50'"
              title="Open the WireMate project this stub belongs to"
            >
              <FolderIcon class="w-4 h-4" />
              Project
            </RouterLink>
            <button
              type="button"
              @click="viewStubLogs(stub)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-sky-400 hover:bg-sky-500/10' : 'text-sky-600 hover:bg-sky-50'"
              title="View request logs for this stub"
            >
              <ClipboardDocumentListIcon class="w-4 h-4" />
              Logs
            </button>
            <button
              type="button"
              @click="handleDeleteStub(stub)"
              class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
              :class="isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-500 hover:bg-red-50'"
              title="Permanently delete this stub on the WireMock server"
            >
              <TrashIcon class="w-4 h-4" />
              Delete
            </button>
          </div>
        </div>
      </div>

    </div>

    <!-- Delete confirmation modal -->
    <BaseConfirmModal
      v-if="showDeleteAllModal"
      title="Delete every stub on WireMock?"
      :message="`This will call DELETE /__admin/mappings and wipe all ${totalStubs} stub${totalStubs === 1 ? '' : 's'} currently on the WireMock server (both persistent and ephemeral). This action cannot be undone.`"
      confirm-text="Yes, delete all"
      cancel-text="Cancel"
      submitting-text="Deleting all..."
      variant="danger"
      :submitting="isDeletingAll"
      @confirm="confirmDeleteAll"
      @cancel="cancelDeleteAll"
    />

    <BaseConfirmModal
      v-if="showDeleteModal"
      title="Delete Stub"
      :message="`Are you sure you want to delete this stub${stubToDelete?.name ? ` '${stubToDelete.name}'` : ''}? This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      submitting-text="Deleting..."
      variant="danger"
      :submitting="isDeletingStub"
      @confirm="confirmDeleteStub"
      @cancel="cancelDeleteStub"
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
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { BaseConfirmModal, BaseSpinner, BaseToast, BaseToastEnum, useDebouncedRef, useTheme, useThemeClasses, useToast } from 'mgv-backoffice'
import { fetchAllStubs, deleteStub, deleteAllStubs } from '../services/stubService'
import type { StubMapping, StubMappingsResponse } from '../services/stubService'
import { ClipboardDocumentListIcon, FolderIcon, ArrowPathIcon, PuzzlePieceIcon, MagnifyingGlassIcon, XMarkIcon, TrashIcon, PencilSquareIcon, CubeIcon, FlagIcon, BookmarkIcon, CodeBracketIcon, ArrowDownTrayIcon, ArrowUpTrayIcon } from '@heroicons/vue/24/outline'
import PageHeader from '../components/PageHeader.vue'

// Router & Theme
const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()

const t = useThemeClasses()
// Query-param helpers — keep URL parsing/coercion in one place.
function readQueryString(key: string): string {
  return typeof route.query[key] === 'string' ? (route.query[key] as string) : ''
}

// State — searchQuery/filters are seeded from the URL so deep links and
// back/forward navigation restore the full view state. Pagination state
// was removed: we load everything once and filter client-side, so there
// is no "current page" to track.
const stubs = ref<StubMapping[]>([])
const isLoading = ref(false)
const searchQuery = ref(readQueryString('search'))
const filterMethod = ref(readQueryString('method'))
const { showToast, toastMessage, toastType, showToastMessage } = useToast()
const filterStatus = ref(readQueryString('status'))
const debouncedSearch = useDebouncedRef(searchQuery, 300)
const showDeleteModal = ref(false)
const isDeletingStub = ref(false)
const stubToDelete = ref<StubMapping | null>(null)
const showDeleteAllModal = ref(false)
const isDeletingAll = ref(false)

// Computed
const totalStubs = computed(() => stubs.value.length)

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
    // No limit — the admin endpoint returns every stub in one shot and
    // we render the full filtered list. Acceptable for the typical
    // 10–500-mapping case the page targets; at much higher counts the
    // page will get slow and we'd want a server-side search endpoint
    // or list virtualization.
    const response: StubMappingsResponse = await fetchAllStubs()
    stubs.value = response.mappings || []
  } catch (error) {
    // Thread the backend's ProblemDetail `detail` text into the toast
    // (already extracted by the shared http helper) so the user sees
    // the real reason — not a generic "Failed to load stubs".
    const message = error instanceof Error ? error.message : 'Failed to load stubs'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isLoading.value = false
  }
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
  if (!stubToDelete.value || isDeletingStub.value) return
  const id = stubToDelete.value.id
  isDeletingStub.value = true

  try {
    await deleteStub(id)
    stubs.value = stubs.value.filter((stub) => stub.id !== id)
    showToastMessage('Stub deleted successfully', BaseToastEnum.SUCCESS)
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to delete stub'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isDeletingStub.value = false
    showDeleteModal.value = false
    stubToDelete.value = null
  }
}

function cancelDeleteStub() {
  if (isDeletingStub.value) return
  showDeleteModal.value = false
  stubToDelete.value = null
}

function openDeleteAllModal() {
  if (isDeletingAll.value || totalStubs.value === 0) return
  showDeleteAllModal.value = true
}

async function confirmDeleteAll() {
  if (isDeletingAll.value) return
  isDeletingAll.value = true
  try {
    await deleteAllStubs()
    // Wipe local state — no need to refetch since WireMock's mappings
    // are now empty by definition.
    stubs.value = []
    showToastMessage('All stubs deleted', BaseToastEnum.SUCCESS)
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to delete all stubs'
    showToastMessage(message, BaseToastEnum.ERROR)
  } finally {
    isDeletingAll.value = false
    showDeleteAllModal.value = false
  }
}

function cancelDeleteAll() {
  if (isDeletingAll.value) return
  showDeleteAllModal.value = false
}

function getDisplayUrl(request: StubMapping['request']): string {
  return request.url || request.urlPath || request.urlPattern || request.urlPathPattern || '/'
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

// Card visual helpers — ported from the mock cards in ProjectDetailView so
// the stub cards render with the same badge palette, match-type chip, and
// Request/Response summary boxes.
interface DetailRow { label: string; value: string }

function methodColor(method?: string): string {
  const m = (method || 'ANY').toUpperCase()
  if (m === 'GET') return isDark.value ? 'bg-blue-500/15 text-blue-400' : 'bg-blue-100 text-blue-700'
  if (m === 'POST') return isDark.value ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'
  if (m === 'PUT') return isDark.value ? 'bg-amber-500/15 text-amber-400' : 'bg-amber-100 text-amber-700'
  if (m === 'DELETE') return isDark.value ? 'bg-red-500/15 text-red-400' : 'bg-red-100 text-red-700'
  if (m === 'PATCH') return isDark.value ? 'bg-purple-500/15 text-purple-400' : 'bg-purple-100 text-purple-700'
  if (m === 'HEAD') return isDark.value ? 'bg-sky-500/15 text-sky-400' : 'bg-sky-100 text-sky-700'
  return isDark.value ? 'bg-gray-500/15 text-gray-400' : 'bg-gray-100 text-gray-600'
}

function statusBucket(status: number): '2xx' | '3xx' | '4xx' | '5xx' | 'other' {
  if (status >= 200 && status < 300) return '2xx'
  if (status >= 300 && status < 400) return '3xx'
  if (status >= 400 && status < 500) return '4xx'
  if (status >= 500 && status < 600) return '5xx'
  return 'other'
}

function statusBadgeClasses(status: number): string {
  const bucket = statusBucket(status)
  if (bucket === '2xx') return isDark.value ? 'bg-emerald-500/15 text-emerald-300' : 'bg-emerald-100 text-emerald-700'
  if (bucket === '3xx') return isDark.value ? 'bg-sky-500/15 text-sky-300' : 'bg-sky-100 text-sky-700'
  if (bucket === '4xx') return isDark.value ? 'bg-amber-500/15 text-amber-300' : 'bg-amber-100 text-amber-700'
  if (bucket === '5xx') return isDark.value ? 'bg-red-500/15 text-red-300' : 'bg-red-100 text-red-700'
  return isDark.value ? 'bg-gray-800 text-gray-400' : 'bg-gray-100 text-gray-600'
}

function priorityBadgeClasses(priority: number): string {
  if (priority <= 1) return isDark.value ? 'bg-rose-500/15 text-rose-300 border-rose-500/30' : 'bg-rose-50 text-rose-700 border-rose-200'
  if (priority <= 3) return isDark.value ? 'bg-amber-500/15 text-amber-300 border-amber-500/30' : 'bg-amber-50 text-amber-700 border-amber-200'
  if (priority <= 5) return isDark.value ? 'bg-yellow-500/15 text-yellow-300 border-yellow-500/30' : 'bg-yellow-50 text-yellow-700 border-yellow-200'
  if (priority <= 10) return isDark.value ? 'bg-sky-500/15 text-sky-300 border-sky-500/30' : 'bg-sky-50 text-sky-700 border-sky-200'
  return isDark.value ? 'bg-slate-500/15 text-slate-300 border-slate-500/30' : 'bg-slate-50 text-slate-700 border-slate-200'
}

function urlMatchLabel(request: StubMapping['request']): string {
  if (request.urlPattern) return 'regex'
  if (request.urlPathPattern) return 'path regex'
  if (request.urlPath) return 'path'
  if (request.url) return 'exact'
  return 'any'
}

function responseContentType(stub: StubMapping): string | null {
  const headers = stub.response.headers
  if (!headers) return null
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== 'content-type') continue
    const raw = Array.isArray(value) ? value[0] : value
    const trimmed = typeof raw === 'string' ? raw.trim() : ''
    if (trimmed) return trimmed
  }
  return null
}

function responseBodyKind(stub: StubMapping): string | null {
  const r = stub.response
  if (r.jsonBody !== undefined && r.jsonBody !== null) return 'JSON'
  const body = typeof r.body === 'string' ? r.body.trim() : ''
  if (!body) return null
  if ((body.startsWith('{') && body.endsWith('}')) || (body.startsWith('[') && body.endsWith(']'))) return 'JSON'
  if (body.startsWith('<') && body.endsWith('>')) return 'XML'
  return 'Text'
}

function requestSummary(stub: StubMapping): DetailRow[] {
  const rows: DetailRow[] = []
  const req = stub.request
  rows.push({ label: 'Method', value: req.method || 'ANY' })
  rows.push({ label: 'Match', value: urlMatchLabel(req) })
  if (req.headers) {
    for (const [name, value] of Object.entries(req.headers)) {
      rows.push({ label: name, value: formatMetadataValue(value) })
    }
  }
  if (req.queryParameters) {
    for (const [name, value] of Object.entries(req.queryParameters)) {
      rows.push({ label: `?${name}`, value: formatMetadataValue(value) })
    }
  }
  if (req.body) {
    rows.push({ label: 'Body', value: formatMetadataValue(req.body) })
  }
  return rows
}

function responseSummary(stub: StubMapping): DetailRow[] {
  const rows: DetailRow[] = []
  const res = stub.response
  rows.push({ label: 'Status', value: String(res.status ?? 200) })
  const bodyKind = responseBodyKind(stub)
  if (bodyKind) rows.push({ label: 'Body', value: bodyKind })
  if (res.headers) {
    for (const [name, value] of Object.entries(res.headers)) {
      rows.push({ label: name, value: formatMetadataValue(value) })
    }
  }
  if (typeof res.fixedDelayMilliseconds === 'number' && res.fixedDelayMilliseconds > 0) {
    const ms = res.fixedDelayMilliseconds
    rows.push({ label: 'Delay', value: ms >= 1000 ? `${(ms / 1000).toFixed(ms % 1000 === 0 ? 0 : 1)}s` : `${ms}ms` })
  }
  return rows
}

watch(
  [debouncedSearch, filterMethod, filterStatus],
  ([newSearch, newMethod, newStatus]) => {
    const query: Record<string, string> = { ...route.query } as Record<string, string>

    if (newSearch) { query.search = newSearch as string } else { delete query.search }
    if (newMethod) { query.method = newMethod as string } else { delete query.method }
    if (newStatus) { query.status = newStatus as string } else { delete query.status }
    // Any leftover `page` / `pageSize` from a previous URL gets stripped
    // here so the address bar doesn't carry dead state after pagination
    // was removed from the page.
    delete query.page
    delete query.pageSize

    const currentSearch = readQueryString('search')
    const currentMethod = readQueryString('method')
    const currentStatus = readQueryString('status')
    if (
      currentSearch === (newSearch || '') &&
      currentMethod === (newMethod || '') &&
      currentStatus === (newStatus || '') &&
      !readQueryString('page') &&
      !readQueryString('pageSize')
    ) {
      return
    }

    router.replace({ query })
  },
)

watch(
  () => [route.query.search, route.query.method, route.query.status],
  ([newSearch, newMethod, newStatus]) => {
    const search = typeof newSearch === 'string' ? newSearch : ''
    const method = typeof newMethod === 'string' ? newMethod : ''
    const status = typeof newStatus === 'string' ? newStatus : ''

    if (searchQuery.value !== search) searchQuery.value = search
    if (filterMethod.value !== method) filterMethod.value = method
    if (filterStatus.value !== status) filterStatus.value = status
  },
)

onMounted(() => {
  loadStubs()
})
</script>

<style scoped>
pre {
  user-select: text;
}
</style>
