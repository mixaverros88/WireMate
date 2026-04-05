<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BaseButton, BaseSpinner, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import { ArrowLeftIcon, FolderIcon, PlusIcon, EyeIcon, TrashIcon, MagnifyingGlassIcon, DocumentDuplicateIcon, ArrowRightStartOnRectangleIcon, CodeBracketIcon, ClipboardDocumentListIcon, ArrowUpTrayIcon, ArrowDownTrayIcon, InboxIcon, FaceFrownIcon, AdjustmentsHorizontalIcon, XMarkIcon, LockClosedIcon, ClockIcon, ExclamationTriangleIcon, ArrowsRightLeftIcon, BoltIcon, PaperAirplaneIcon, FlagIcon, BookmarkIcon, ChevronDownIcon, CubeIcon, CommandLineIcon, ArrowPathIcon, PencilSquareIcon, CheckIcon } from '@heroicons/vue/24/outline'
import type { Project } from '../types/project'
import type { MockResponse, StringMatcher, BodyPattern } from '../types/mock'
import { fetchProject, renameProject } from '../services/projectService'
import { deleteMock, cloneMock, moveMock } from '../services/mockService'
import { mockApi } from '../services/api'
import { checkStubExists } from '../services/stubService'
import ConfirmModal from '../components/ConfirmModal.vue'
import CloneModal from '../components/CloneModal.vue'
import MoveModal from '../components/MoveModal.vue'
import ImportPostmanModal from '../components/ImportPostmanModal.vue'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { buildPostmanCollection, buildPostmanItemFromMock } from '../utils/postmanExport'
import { WIREMOCK_BASE_URL } from '../config'

const route = useRoute()
const router = useRouter()
const { isDark } = useTheme()
const project = ref<Project | null>(null)
const mocks = ref<MockResponse[]>([])
const loading = ref(true)
const error = ref('')
const showDeleteModal = ref(false)
const mockToDelete = ref<MockResponse | null>(null)
const showCloneMockModal = ref(false)
const mockToClone = ref<MockResponse | null>(null)
const cloneMockModalRef = ref<InstanceType<typeof CloneModal> | null>(null)
const showMoveModal = ref(false)
const mockToMove = ref<MockResponse | null>(null)
const moveModalRef = ref<InstanceType<typeof MoveModal> | null>(null)
const showImportPostmanModal = ref(false)
const searchQuery = ref('')
const { showToast, toastMessage, toastType, showToastMessage } = useToast()

const projectId = route.params.id as string

// ── Inline project rename ─────────────────────────────────────
// Rather than navigating to a dedicated edit page, the user can rename the
// project right from the detail header: click the pencil → input becomes
// editable → save (Enter / check) PUTs to /api/projects/{id} → on success
// `project.name` updates in place and stays in sync with the URL slug used
// by mocks under this project. Cancel (Esc / X) reverts.
const isEditingProjectName = ref(false)
const projectNameDraft = ref('')
const renamingProject = ref(false)
const renameError = ref('')
const projectNameInput = ref<HTMLInputElement | null>(null)

function startEditProjectName() {
  if (!project.value) return
  projectNameDraft.value = project.value.name
  isEditingProjectName.value = true
  renameError.value = ''
  // Focus the input on the next tick so the user can immediately type.
  setTimeout(() => {
    projectNameInput.value?.focus()
    projectNameInput.value?.select()
  }, 0)
}

function cancelEditProjectName() {
  isEditingProjectName.value = false
  projectNameDraft.value = ''
  renameError.value = ''
}

async function saveProjectName() {
  if (!project.value) return
  const next = projectNameDraft.value.trim()
  if (!next) {
    renameError.value = 'Project name cannot be empty'
    return
  }
  if (next === project.value.name) {
    // No-op: skip the round-trip when the name hasn't actually changed.
    cancelEditProjectName()
    return
  }
  renamingProject.value = true
  renameError.value = ''
  try {
    const updated = await renameProject(project.value.id, next)
    // Patch the header label immediately so the rename feels snappy,
    // then re-fetch the project so the mocks list picks up any
    // server-side changes the rename triggered (URLs are namespaced
    // under the project slug, so a rename rewrites every mock's URL on
    // the backend — without a re-fetch the cards would show stale paths).
    project.value = { ...project.value, ...updated, name: updated.name ?? next }
    try {
      const refreshed = await fetchProject(project.value.id)
      project.value = refreshed
      mocks.value = refreshed.mocks ?? []
    } catch {
      // Re-fetch failures are non-fatal — the rename itself succeeded.
      // The user can hit refresh to pick up the updated mock URLs.
    }
    isEditingProjectName.value = false
    showToastMessage(`Project renamed to "${project.value.name}"`, BaseToastEnum.SUCCESS)
  } catch (e) {
    renameError.value = e instanceof Error ? e.message : 'Failed to rename project'
  } finally {
    renamingProject.value = false
  }
}

// ── Advanced filter state ─────────────────────────────────────
// All filters default to "Any" (unset) so returning users see every mock
// until they opt in. `method` and `statusBucket` are single-select; the
// feature flags below are tri-state ("any" | "yes" | "no"), cycled by
// clicking the chip. Keep this list in sync with the chip <button>s in
// the template and the matching section of `filteredMocks`.
const filtersOpen = ref(false)
const methodFilter = ref<string>('') // '' = any, otherwise HTTP method uppercase
const statusBucketFilter = ref<'' | '2xx' | '3xx' | '4xx' | '5xx' | 'other'>('')

type TriState = 'any' | 'yes' | 'no'
const authFilter = ref<TriState>('any')
const delayFilter = ref<TriState>('any')
const faultFilter = ref<TriState>('any')
const proxyFilter = ref<TriState>('any')
const scenarioFilter = ref<TriState>('any')
const webhookFilter = ref<TriState>('any')

const FEATURE_FILTERS: Array<{ label: string; ref: typeof authFilter }> = [
  { label: 'Auth', ref: authFilter },
  { label: 'Delay', ref: delayFilter },
  { label: 'Fault', ref: faultFilter },
  { label: 'Proxy', ref: proxyFilter },
  { label: 'Scenario', ref: scenarioFilter },
  { label: 'Webhook', ref: webhookFilter },
]

function cycleTriState(r: typeof authFilter) {
  r.value = r.value === 'any' ? 'yes' : r.value === 'yes' ? 'no' : 'any'
}

// Count of filters currently contributing to the result (used for the
// badge + the empty-state message).
const activeFilterCount = computed(() => {
  let n = 0
  if (searchQuery.value.trim()) n++
  if (methodFilter.value) n++
  if (statusBucketFilter.value) n++
  if (authFilter.value !== 'any') n++
  if (delayFilter.value !== 'any') n++
  if (faultFilter.value !== 'any') n++
  if (proxyFilter.value !== 'any') n++
  if (scenarioFilter.value !== 'any') n++
  if (webhookFilter.value !== 'any') n++
  return n
})

function clearAllFilters() {
  searchQuery.value = ''
  methodFilter.value = ''
  statusBucketFilter.value = ''
  authFilter.value = 'any'
  delayFilter.value = 'any'
  faultFilter.value = 'any'
  proxyFilter.value = 'any'
  scenarioFilter.value = 'any'
  webhookFilter.value = 'any'
}

// ── Per-mock feature detectors ────────────────────────────────
// Helpers that keep `filteredMocks` legible. Each returns whether a given
// mock "has" the feature represented by that filter.
function mockHasAuth(m: MockResponse): boolean {
  const c = m.request.basicAuthCredentials
  return !!c && !!(c.username || c.password)
}
function mockHasDelay(m: MockResponse): boolean {
  const r = m.response
  if (typeof r.fixedDelayMilliseconds === 'number' && r.fixedDelayMilliseconds > 0) return true
  if (r.delayDistribution) return true
  if (r.chunkedDribbleDelay) return true
  return false
}
function mockHasFault(m: MockResponse): boolean {
  return !!m.response.fault
}
function mockHasProxy(m: MockResponse): boolean {
  return !!m.response.proxyBaseUrl
}
function mockHasScenario(m: MockResponse): boolean {
  return !!(m.scenarioName && m.scenarioName.trim())
}
function mockHasWebhook(m: MockResponse): boolean {
  // Modern (WireMock 3.1.0+) shape: the stub carries a `serveEventListeners`
  // array and webhook config lives in the entry whose `name` is `"webhook"`.
  // We only treat a listener as "configured" when its parameters include a
  // URL — an empty listener stub is a no-op and shouldn't light up the badge
  // or the filter.
  const fromListener = m.serveEventListeners?.some(
    l => l.name === 'webhook' && typeof (l.parameters as { url?: unknown })?.url === 'string' && (l.parameters as { url: string }).url.trim() !== '',
  )
  if (fromListener) return true
  // Legacy (pre-3.1.0) shape — kept so stubs saved before the migration
  // still show the chip and match the filter. On next save they're rewritten
  // to `serveEventListeners` by CreateMock.vue.
  return !!m.postServeActions?.webhook?.url
}
function matchesTri(flag: TriState, has: boolean): boolean {
  return flag === 'any' || (flag === 'yes' ? has : !has)
}
function statusBucket(status: number): '2xx' | '3xx' | '4xx' | '5xx' | 'other' {
  if (status >= 200 && status < 300) return '2xx'
  if (status >= 300 && status < 400) return '3xx'
  if (status >= 400 && status < 500) return '4xx'
  if (status >= 500 && status < 600) return '5xx'
  return 'other'
}

const filteredMocks = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return mocks.value.filter(mock => {
    // Text search (name or URL)
    if (query) {
      const name = mock.name.toLowerCase()
      const url = getMockUrl(mock).toLowerCase()
      if (!name.includes(query) && !url.includes(query)) return false
    }
    // HTTP method
    if (methodFilter.value && mock.request.method !== methodFilter.value) return false
    // Status bucket
    if (statusBucketFilter.value && statusBucket(mock.response.status) !== statusBucketFilter.value) return false
    // Tri-state feature toggles
    if (!matchesTri(authFilter.value, mockHasAuth(mock))) return false
    if (!matchesTri(delayFilter.value, mockHasDelay(mock))) return false
    if (!matchesTri(faultFilter.value, mockHasFault(mock))) return false
    if (!matchesTri(proxyFilter.value, mockHasProxy(mock))) return false
    if (!matchesTri(scenarioFilter.value, mockHasScenario(mock))) return false
    if (!matchesTri(webhookFilter.value, mockHasWebhook(mock))) return false
    return true
  })
})

// ── Grouping by URL ──────────────────────────────────────────
// Mocks that target the same URL often compete for the same incoming
// request — WireMock picks the one with the lowest `priority` (lower =
// higher precedence). Flattening them into a blind list makes it easy
// to miss several cards share a URL, so we bucket by URL and let the
// template render a collapsible frame around each 2+-mock cluster.
// Within each group the mocks are sorted by `priority` ascending; mocks
// without an explicit priority land at the bottom (WireMock defaults
// them to 5, but older data may not carry the field).
interface MockGroup {
  url: string
  mocks: MockResponse[]
  // True iff this group represents an actual URL collision (2+ mocks).
  // Single-mock "groups" render flush with the grid — no frame, no header.
  inGroup: boolean
}

const groupedFilteredMocks = computed<MockGroup[]>(() => {
  // Preserve first-seen order of URL buckets (Map keeps insertion order)
  // so the list doesn't reshuffle as new mocks land — only the
  // within-bucket ordering changes.
  const buckets = new Map<string, MockResponse[]>()
  for (const m of filteredMocks.value) {
    const url = getMockUrl(m)
    const arr = buckets.get(url)
    if (arr) arr.push(m)
    else buckets.set(url, [m])
  }

  const byPriorityAsc = (a: MockResponse, b: MockResponse) => {
    const ap = typeof a.priority === 'number' ? a.priority : Number.POSITIVE_INFINITY
    const bp = typeof b.priority === 'number' ? b.priority : Number.POSITIVE_INFINITY
    if (ap !== bp) return ap - bp
    // Stable tiebreaker on name so equal priorities render deterministically.
    return a.name.localeCompare(b.name)
  }

  const out: MockGroup[] = []
  for (const [url, members] of buckets) {
    const sorted = members.slice().sort(byPriorityAsc)
    out.push({
      url,
      mocks: sorted,
      inGroup: sorted.length > 1,
    })
  }
  return out
})

// URLs whose group is currently collapsed. Defaults to empty (expanded).
// Held as a Set so toggling is O(1); we clone on write so Vue picks up
// the reactivity.
const collapsedGroups = ref<Set<string>>(new Set())

function toggleGroupCollapse(url: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(url)) next.delete(url)
  else next.add(url)
  collapsedGroups.value = next
}

function isGroupCollapsed(url: string): boolean {
  return collapsedGroups.value.has(url)
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await fetchProject(projectId)
    project.value = data
    mocks.value = data.mocks ?? []
  } catch (_e) {
    error.value = 'Failed to load project details'
  } finally {
    loading.value = false
  }
  // Kick off the per-mock WireMock stub existence check in the background
  // so the identity row can render "stub exists" / "stub missing" chips.
  // We don't await this — the mocks list should render immediately, and
  // the chips fade in as results arrive.
  void refreshAllStubStatuses()
})

// `silent: true` skips the page-wide loading spinner. The template hides
// the whole project-details branch while `loading` is true and replaces it
// with a centered <BaseSpinner>, which collapses the page and resets the
// user's scroll position. For background re-fetches triggered by an
// in-card action (e.g. "click to create" from createStubForMock), we want
// the data to refresh underneath the user without yanking them to the top.
//
// `skipStubStatuses: true` skips the per-mock WireMock probe sweep that
// normally runs at the tail of this function. The probe sweep fires one
// GET /__admin/mappings/{id} per mock, so re-running it after every
// in-page mutation (e.g. right after a successful click-to-create
// republish) is wasteful: we already know the just-republished stub
// exists, and the other mocks' statuses haven't changed. The on-mount
// sweep is the one source of truth for "is this stub on WireMock right
// now"; everything else updates that ref directly.
async function loadMocks(opts: { silent?: boolean; skipStubStatuses?: boolean } = {}) {
  if (!opts.silent) {
    loading.value = true
  }
  error.value = ''
  try {
    const data = await fetchProject(projectId)
    project.value = data
    mocks.value = data.mocks ?? []
  } catch (_e) {
    error.value = 'Failed to load project'
  } finally {
    if (!opts.silent) {
      loading.value = false
    }
  }
  if (!opts.skipStubStatuses) {
    void refreshAllStubStatuses()
  }
}

// ── Per-mock WireMock stub status ───────────────────────────────
// For each mock we track whether a matching stub currently lives on the
// WireMock admin side. The backend creates stubs alongside mocks, but
// WireMock is ephemeral in a lot of setups (in-memory resets, container
// restarts) and can drift from our DB — surfacing the delta in-card
// lets users spot & fix the gap without leaving the project page.
//
// States:
//   'loading'  — check in flight
//   'exists'   — GET /__admin/mappings/{id} returned 200
//   'missing'  — returned 404, user can re-push the stub
//   'creating' — POST /__admin/mappings in flight after a user click
//   'error'    — the admin port didn't respond with 200/404 (network
//                down, CORS, auth). Rendered as a neutral warning so we
//                don't mislabel an unreachable server as "missing".
type StubStatus = 'loading' | 'exists' | 'missing' | 'creating' | 'error'
const stubStatuses = ref<Record<string, StubStatus>>({})

async function refreshStubStatus(mockId: string) {
  stubStatuses.value = { ...stubStatuses.value, [mockId]: 'loading' }
  try {
    const exists = await checkStubExists(mockId)
    stubStatuses.value = { ...stubStatuses.value, [mockId]: exists ? 'exists' : 'missing' }
  } catch (_e) {
    stubStatuses.value = { ...stubStatuses.value, [mockId]: 'error' }
  }
}

// Cap how many stub-existence probes run in parallel. WireMock's admin
// port sits behind a small Jetty thread pool and starts returning 503
// when it sees more than a handful of in-flight requests, so firing a
// probe per mock all at once (6+ at page load) causes the majority to
// bounce and makes the stub chip flicker between 'error' and 'missing'.
// A small concurrency window keeps the UI snappy — each chip still
// updates as its own probe resolves — without overrunning the admin
// port's capacity.
const STUB_PROBE_CONCURRENCY = 3

async function refreshAllStubStatuses() {
  const items = mocks.value.slice()
  let cursor = 0
  const workers = Array.from({ length: Math.min(STUB_PROBE_CONCURRENCY, items.length) }, async () => {
    while (true) {
      const idx = cursor++
      if (idx >= items.length) return
      await refreshStubStatus(items[idx].id)
    }
  })
  await Promise.all(workers)
}

async function createStubForMock(mock: MockResponse) {
  stubStatuses.value = { ...stubStatuses.value, [mock.id]: 'creating' }
  try {
    // Route the re-install through the WireMate backend rather than
    // hitting the WireMock admin API directly — the backend handles
    // the WireMock sync on its side, so we get a single source of
    // truth instead of the UI writing to two stores.
    //
    // We use PUT /api/mocks/{id} (not POST /api/mocks) because the
    // mock row already exists in our DB — only the WireMock-side
    // stub is missing. PUT preserves the id so the stub lands back
    // on WireMock under the same id the rest of the UI references,
    // whereas POST would mint a fresh duplicate.
    await mockApi.republish(mock.id)
    stubStatuses.value = { ...stubStatuses.value, [mock.id]: 'exists' }
    showToastMessage(`Stub created for '${mock.name}'`, BaseToastEnum.SUCCESS)
    // Re-fetch so any server-side side effects (updated timestamps,
    // metadata touch-ups) show up in the list.
    //   - `silent: true` keeps the mocks list mounted so the user stays
    //     scrolled where they were when they clicked — flipping the
    //     page-wide `loading` would swap the list out for a centered
    //     spinner and reset scroll to the top.
    //   - `skipStubStatuses: true` suppresses the per-mock
    //     GET /__admin/mappings/{id} probe sweep. We already set this
    //     mock's status to 'exists' above, and the on-mount sweep is the
    //     authoritative refresh — re-probing every mock after a single
    //     click-to-create is wasted traffic against WireMock's admin port.
    await loadMocks({ silent: true, skipStubStatuses: true })
  } catch (e) {
    // Revert to 'missing' so the user can retry; they're the ones who
    // bumped us out of it.
    stubStatuses.value = { ...stubStatuses.value, [mock.id]: 'missing' }
    showToastMessage(
      e instanceof Error ? `Failed to create stub: ${e.message}` : 'Failed to create stub',
      BaseToastEnum.ERROR,
    )
  }
}

function goBack() {
  router.push({ name: 'projects' })
}

// Navigates to the standalone Create Mock page, pre-binding the form to
// this project via a query param. CreateMock.vue hides its Project ID
// input when the prop is present, and on success the view routes back
// to this project so the user lands on the new mock in context.
function openCreateMock() {
  if (!project.value) return
  router.push({
    name: 'create-mock',
    query: { projectId: project.value.id },
  })
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function methodColor(method: string): string {
  // Each method gets its own hue so GET/POST/PATCH don't visually collapse
  // into the same green. Mirrors the palette used by the stub cards (blue
  // for GET, emerald for POST, amber for PUT, red for DELETE, purple for
  // PATCH, sky for HEAD, gray for OPTIONS/ANY/unknown).
  const m = method.toUpperCase()
  if (m === 'GET') return isDark.value ? 'bg-blue-500/15 text-blue-400' : 'bg-blue-100 text-blue-700'
  if (m === 'POST') return isDark.value ? 'bg-emerald-500/15 text-emerald-400' : 'bg-emerald-100 text-emerald-700'
  if (m === 'PUT') return isDark.value ? 'bg-amber-500/15 text-amber-400' : 'bg-amber-100 text-amber-700'
  if (m === 'DELETE') return isDark.value ? 'bg-red-500/15 text-red-400' : 'bg-red-100 text-red-700'
  if (m === 'PATCH') return isDark.value ? 'bg-purple-500/15 text-purple-400' : 'bg-purple-100 text-purple-700'
  if (m === 'HEAD') return isDark.value ? 'bg-sky-500/15 text-sky-400' : 'bg-sky-100 text-sky-700'
  return isDark.value ? 'bg-gray-500/15 text-gray-400' : 'bg-gray-100 text-gray-600'
}

function getMockUrl(mock: MockResponse): string {
  return mock.request.url || mock.request.urlPath || mock.request.urlPattern || mock.request.urlPathPattern || '—'
}

// Extract the response Content-Type header, preferring the stub's own
// advertisement over any body-shape sniffing. Matches case-insensitively
// because headers are case-insensitive on the wire and we've seen users
// enter any of "Content-Type", "content-type", "CONTENT-TYPE".
//
// Returns the first non-empty value; multi-valued headers (string[])
// reuse the first entry — a response Content-Type is never legitimately
// more than one value.
function responseContentType(mock: MockResponse): string | null {
  const headers = mock.response.headers
  if (!headers) return null
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== 'content-type') continue
    const raw = Array.isArray(value) ? value[0] : value
    const trimmed = typeof raw === 'string' ? raw.trim() : ''
    if (trimmed) return trimmed
  }
  return null
}

// Describes a response body's shape (JSON / XML / text / empty) so the card
// can hint at the payload type without dumping the body itself.
function responseBodyKind(mock: MockResponse): string | null {
  const r = mock.response
  if (r.jsonBody !== undefined && r.jsonBody !== null) return 'JSON'
  if (typeof r.bodyFileName === 'string' && r.bodyFileName.trim()) return 'File'
  if (typeof r.base64Body === 'string' && r.base64Body.trim()) return 'Binary'
  const body = typeof r.body === 'string' ? r.body.trim() : ''
  if (!body) return null
  // Sniff JSON/XML without parsing — cheap and good enough for a hint.
  if ((body.startsWith('{') && body.endsWith('}')) || (body.startsWith('[') && body.endsWith(']'))) return 'JSON'
  if (body.startsWith('<') && body.endsWith('>')) return 'XML'
  return 'Text'
}

// Returns the human-readable delay summary, or null if no delay configured.
function formatDelay(mock: MockResponse): string | null {
  const r = mock.response
  if (typeof r.fixedDelayMilliseconds === 'number' && r.fixedDelayMilliseconds > 0) {
    const ms = r.fixedDelayMilliseconds
    return ms >= 1000 ? `${(ms / 1000).toFixed(ms % 1000 === 0 ? 0 : 1)}s` : `${ms}ms`
  }
  if (r.delayDistribution) return 'random'
  if (r.chunkedDribbleDelay) return 'chunked'
  return null
}

// ── Card detail summaries ────────────────────────────────────
// `requestSummary` / `responseSummary` produce the rows rendered in the
// two-box detail strip on each mock card. Each entry is `{ label, value }`
// and gets its own row in the card — collections like headers and query
// params are expanded so the user sees the actual names + matchers
// inline instead of a collapsed `Headers: 3` count.

interface DetailRow { label: string; value: string }

// Compact, human-readable rendering of a WireMock StringMatcher. The
// label flags the matcher kind and the value (or absence sentinel) is
// shown right after — e.g. `~ ^/api/.+`, `= active`, `(absent)`.
function summarizeStringMatcher(m: StringMatcher | undefined | null): string {
  if (!m) return '—'
  if (m.absent) return '(absent)'
  if (typeof m.equalTo === 'string') return `= ${m.equalTo}`
  if (typeof m.contains === 'string') return `*= ${m.contains}`
  if (typeof m.matches === 'string') return `~ ${m.matches}`
  if (typeof m.doesNotMatch === 'string') return `!~ ${m.doesNotMatch}`
  if (typeof m.equalToJson === 'string') return `=json ${m.equalToJson}`
  if (typeof m.equalToXml === 'string') return `=xml ${m.equalToXml}`
  if (typeof m.binaryEqualTo === 'string') return `=bin ${m.binaryEqualTo}`
  if (m.matchesJsonPath !== undefined) {
    const expr = typeof m.matchesJsonPath === 'string' ? m.matchesJsonPath : m.matchesJsonPath.expression
    return `jsonPath ${expr}`
  }
  if (m.matchesXPath !== undefined) {
    const expr = typeof m.matchesXPath === 'string' ? m.matchesXPath : m.matchesXPath.expression
    return `xPath ${expr}`
  }
  if (typeof m.before === 'string') return `before ${m.before}`
  if (typeof m.after === 'string') return `after ${m.after}`
  if (typeof m.equalToDateTime === 'string') return `=dt ${m.equalToDateTime}`
  return '—'
}

// Same idea for body-pattern entries. They share most fields with
// StringMatcher but live in an unkeyed array, so we add a `[i]` label
// upstream and just render the matcher contents here.
function summarizeBodyPattern(p: BodyPattern): string {
  if (p.absent) return '(absent)'
  if (typeof p.equalTo === 'string') return `= ${p.equalTo}`
  if (typeof p.contains === 'string') return `*= ${p.contains}`
  if (typeof p.matches === 'string') return `~ ${p.matches}`
  if (typeof p.doesNotMatch === 'string') return `!~ ${p.doesNotMatch}`
  if (p.equalToJson !== undefined) {
    const v = typeof p.equalToJson === 'string' ? p.equalToJson : JSON.stringify(p.equalToJson)
    return `=json ${v}`
  }
  if (p.matchesJsonSchema !== undefined) {
    const v = typeof p.matchesJsonSchema === 'string' ? p.matchesJsonSchema : JSON.stringify(p.matchesJsonSchema)
    return `jsonSchema ${v}`
  }
  if (p.matchesJsonPath !== undefined) {
    const expr = typeof p.matchesJsonPath === 'string' ? p.matchesJsonPath : p.matchesJsonPath.expression
    return `jsonPath ${expr}`
  }
  if (typeof p.equalToXml === 'string') return `=xml ${p.equalToXml}`
  if (p.matchesXPath !== undefined) {
    const expr = typeof p.matchesXPath === 'string' ? p.matchesXPath : p.matchesXPath.expression
    return `xPath ${expr}`
  }
  if (typeof p.binaryEqualTo === 'string') return `=bin ${p.binaryEqualTo}`
  if (typeof p.before === 'string') return `before ${p.before}`
  if (typeof p.after === 'string') return `after ${p.after}`
  if (typeof p.equalToDateTime === 'string') return `=dt ${p.equalToDateTime}`
  return '—'
}

function requestSummary(mock: MockResponse): DetailRow[] {
  const rows: DetailRow[] = []
  const req = mock.request
  rows.push({ label: 'Method', value: req.method })
  rows.push({ label: 'Match', value: urlMatchLabel(mock) })
  if (req.headers) {
    for (const [name, matcher] of Object.entries(req.headers)) {
      rows.push({ label: name, value: summarizeStringMatcher(matcher) })
    }
  }
  if (req.queryParameters) {
    for (const [name, matcher] of Object.entries(req.queryParameters)) {
      rows.push({ label: `?${name}`, value: summarizeStringMatcher(matcher) })
    }
  }
  if (req.cookies) {
    for (const [name, matcher] of Object.entries(req.cookies)) {
      rows.push({ label: `cookie:${name}`, value: summarizeStringMatcher(matcher) })
    }
  }
  if (Array.isArray(req.bodyPatterns)) {
    req.bodyPatterns.forEach((p, i) => {
      rows.push({ label: `Body[${i}]`, value: summarizeBodyPattern(p) })
    })
  }
  if (req.basicAuthCredentials?.username) rows.push({ label: 'Basic auth', value: req.basicAuthCredentials.username })
  if (req.customMatcher?.name) rows.push({ label: 'Custom matcher', value: req.customMatcher.name })
  return rows
}

function responseSummary(mock: MockResponse): DetailRow[] {
  const rows: DetailRow[] = []
  const res = mock.response
  const statusLabel = res.statusMessage ? `${res.status} ${res.statusMessage}` : String(res.status)
  rows.push({ label: 'Status', value: statusLabel })
  const bodyKind = responseBodyKind(mock)
  if (bodyKind) rows.push({ label: 'Body', value: bodyKind })
  if (res.headers) {
    for (const [name, value] of Object.entries(res.headers)) {
      const rendered = Array.isArray(value) ? value.join(', ') : String(value)
      rows.push({ label: name, value: rendered })
    }
  }
  const delay = formatDelay(mock)
  if (delay) rows.push({ label: 'Delay', value: delay })
  if (Array.isArray(res.transformers) && res.transformers.length > 0) {
    res.transformers.forEach(t => rows.push({ label: 'Transformer', value: t }))
  }
  if (res.fault) rows.push({ label: 'Fault', value: res.fault })
  if (res.proxyBaseUrl) rows.push({ label: 'Proxy', value: res.proxyBaseUrl })
  return rows
}

// Feature descriptor — keeps the template lean by centralising icon, tooltip,
// and detection rules. Only features that apply to the mock are rendered.
// `prominent` flags features that deserve top-billing (auth + fault): these
// surface in the header row next to method/status so callers can spot
// security/error behaviours without scanning the chip stack further down.
interface FeatureChip {
  key: string
  label: string
  title: string
  icon: unknown
  lightClasses: string
  darkClasses: string
  prominent?: boolean
}

function mockFeatureChips(mock: MockResponse): FeatureChip[] {
  const chips: FeatureChip[] = []
  if (mockHasAuth(mock)) {
    chips.push({
      key: 'auth',
      label: 'Auth',
      title: 'Basic authentication required',
      icon: LockClosedIcon,
      lightClasses: 'bg-indigo-50 text-indigo-700 border-indigo-200',
      darkClasses: 'bg-indigo-500/10 text-indigo-300 border-indigo-500/30',
      prominent: true,
    })
  }
  if (mockHasFault(mock)) {
    chips.push({
      key: 'fault',
      label: mock.response.fault ?? 'Fault',
      title: 'Fault simulation enabled',
      icon: ExclamationTriangleIcon,
      lightClasses: 'bg-red-50 text-red-700 border-red-200',
      darkClasses: 'bg-red-500/10 text-red-300 border-red-500/30',
      prominent: true,
    })
  }
  const delay = formatDelay(mock)
  if (delay) {
    chips.push({
      key: 'delay',
      label: `Delay · ${delay}`,
      // Surface chunked-dribble specifically in the tooltip so reviewers
      // reading the top row know *why* the chip is there — "chunked" on
      // its own is cryptic without the wider "trickle the body over N
      // chunks" context.
      title: delay === 'chunked'
        ? 'Chunked dribble delay — response body is trickled in parts'
        : 'Response delay configured',
      icon: ClockIcon,
      lightClasses: 'bg-amber-50 text-amber-700 border-amber-200',
      darkClasses: 'bg-amber-500/10 text-amber-300 border-amber-500/30',
      // Delay (including chunked dribble) is promoted to the top of the
      // card alongside auth/fault so latency-shaping behaviour is visible
      // at-a-glance, not buried in the secondary chip grid.
      prominent: true,
    })
  }
  if (mockHasProxy(mock)) {
    chips.push({
      key: 'proxy',
      label: 'Proxy',
      title: mock.response.proxyBaseUrl ?? 'Proxy to upstream',
      icon: ArrowsRightLeftIcon,
      lightClasses: 'bg-sky-50 text-sky-700 border-sky-200',
      darkClasses: 'bg-sky-500/10 text-sky-300 border-sky-500/30',
    })
  }
  if (mockHasScenario(mock)) {
    chips.push({
      key: 'scenario',
      label: mock.scenarioName ?? 'Scenario',
      title: 'Part of a WireMock scenario',
      icon: BoltIcon,
      lightClasses: 'bg-purple-50 text-purple-700 border-purple-200',
      darkClasses: 'bg-purple-500/10 text-purple-300 border-purple-500/30',
    })
  }
  if (mockHasWebhook(mock)) {
    chips.push({
      key: 'webhook',
      label: 'Webhook',
      title: 'Post-serve webhook configured',
      icon: PaperAirplaneIcon,
      lightClasses: 'bg-teal-50 text-teal-700 border-teal-200',
      darkClasses: 'bg-teal-500/10 text-teal-300 border-teal-500/30',
      // Promoted to the top identity row alongside auth/fault/delay so
      // the side-effect behaviour of a stub (it fires an outbound call
      // after serving) is visible without opening the card. The chip
      // rail rendered via `prominentChips` is the only place feature
      // chips surface — non-prominent chips would never render.
      prominent: true,
    })
  }
  const body = responseBodyKind(mock)
  if (body) {
    chips.push({
      key: 'body',
      label: body,
      title: `Response body type: ${body}`,
      icon: CodeBracketIcon,
      lightClasses: 'bg-emerald-50 text-emerald-700 border-emerald-200',
      darkClasses: 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30',
    })
  }
  return chips
}

// All feature chips currently render in the top identity row via
// `prominentChips` — there's no secondary chip strip anymore, so a
// companion `regularChips` helper would be dead code and is omitted.
function prominentChips(mock: MockResponse): FeatureChip[] {
  return mockFeatureChips(mock).filter(c => c.prominent)
}

// Human label for the URL match type — surfaces how picky the match is so
// developers don't have to open the mock to find out it's a regex.
function urlMatchLabel(mock: MockResponse): string {
  const r = mock.request
  if (r.urlPattern) return 'regex'
  if (r.urlPathPattern) return 'path regex'
  if (r.urlPath) return 'path'
  if (r.url) return 'exact'
  return 'any'
}

function statusBadgeClasses(status: number): string {
  const bucket = statusBucket(status)
  if (bucket === '2xx') return isDark.value ? 'bg-emerald-500/15 text-emerald-300' : 'bg-emerald-100 text-emerald-700'
  if (bucket === '3xx') return isDark.value ? 'bg-sky-500/15 text-sky-300' : 'bg-sky-100 text-sky-700'
  if (bucket === '4xx') return isDark.value ? 'bg-amber-500/15 text-amber-300' : 'bg-amber-100 text-amber-700'
  if (bucket === '5xx') return isDark.value ? 'bg-red-500/15 text-red-300' : 'bg-red-100 text-red-700'
  return isDark.value ? 'bg-gray-800 text-gray-400' : 'bg-gray-100 text-gray-600'
}

function editMock(mock: MockResponse) {
  router.push({ name: 'edit-mock', params: { id: projectId, mockId: mock.id } })
}

function viewMockLogs(mock: MockResponse) {
  const url = getMockUrl(mock)
  const search = url && url !== '—' ? url : mock.name
  router.push({ name: 'logs', query: { search } })
}

// Opens the raw WireMock stub for a mock at /stubs/:id. The mock's id matches
// the stub's id on the WireMock side, so we can route directly without any
// extra lookup. Handy for inspecting the wire-level payload or checking what
// WireMock actually stored vs. what the UI thinks it stored.
function viewMockStub(mock: MockResponse) {
  router.push({ name: 'stub-detail', params: { id: mock.id } })
}

// Copy the backend-persisted `curl` invocation for this mock. We use the
// stored value rather than regenerating from the current state because
// regeneration would drift if the stub was edited through the raw
// WireMock admin API (in which case the mock row in our DB may be
// stale but its stored curl still reflects the last known-good form).
// Falls back to the async Clipboard API where available; logs a toast on
// either success or failure so users always get feedback.
async function copyCurlFromMock(mock: MockResponse) {
  const curl = mock.curl?.trim()
  if (!curl) {
    showToastMessage('No cURL available for this mock', BaseToastEnum.ERROR)
    return
  }
  try {
    await navigator.clipboard.writeText(curl)
    showToastMessage('cURL copied to clipboard', BaseToastEnum.SUCCESS)
  } catch (_e) {
    showToastMessage('Failed to copy cURL to clipboard', BaseToastEnum.ERROR)
  }
}

function handleDeleteMock(mock: MockResponse) {
  mockToDelete.value = mock
  showDeleteModal.value = true
}

async function confirmDeleteMock() {
  if (!mockToDelete.value) return
  try {
    await deleteMock(mockToDelete.value.id)
    mocks.value = mocks.value.filter(m => m.id !== mockToDelete.value!.id)
    showToastMessage('Mock deleted', BaseToastEnum.SUCCESS)
  } catch (e) {
    // Recoverable per-action error -> toast. Writing to the page-level
    // `error` ref here would wipe the whole ProjectDetail view for what
    // is typically a transient backend failure.
    showToastMessage(
      e instanceof Error ? e.message : 'Failed to delete mock',
      BaseToastEnum.ERROR,
    )
  } finally {
    showDeleteModal.value = false
    mockToDelete.value = null
  }
}

function cancelDeleteMock() {
  showDeleteModal.value = false
  mockToDelete.value = null
}

// Suggested name prefilled in the clone modal. We append a fresh UUID
// rather than a generic "(copy)" suffix so repeated clones of the same
// mock produce unique, non-colliding names without the user having to
// edit the field themselves. Regenerated each time the modal opens so
// two clones from the same source mock never collide.
const cloneSuggestedName = ref('')

function handleCloneMock(mock: MockResponse) {
  mockToClone.value = mock
  cloneSuggestedName.value = `${mock.name} ${crypto.randomUUID()}`
  showCloneMockModal.value = true
}

async function confirmCloneMock(name: string) {
  if (!mockToClone.value || !cloneMockModalRef.value) return
  cloneMockModalRef.value.submitting = true
  try {
    await cloneMock(mockToClone.value.id, { name })
    // Re-fetch the full list so the UI reflects authoritative server state
    // (order, metadata, any server-side side effects) rather than just
    // appending the cloned mock locally.
    await loadMocks()
    showToastMessage(`Mock cloned as '${name}'`, BaseToastEnum.SUCCESS)
  } catch (e) {
    // Per-action error -> toast, NOT the page-level fatal `error` ref.
    // Writing to `error` here used to wipe the entire ProjectDetail view
    // for a recoverable backend validation (e.g. duplicate-name 400),
    // forcing the user back to the project list. Move it lives at L782
    // already does the right thing with a toast; mirror that.
    showToastMessage(
      e instanceof Error ? e.message : 'Failed to clone mock',
      BaseToastEnum.ERROR,
    )
  } finally {
    if (cloneMockModalRef.value) cloneMockModalRef.value.submitting = false
    showCloneMockModal.value = false
    mockToClone.value = null
  }
}

function cancelCloneMock() {
  showCloneMockModal.value = false
  mockToClone.value = null
}

function handleMoveMock(mock: MockResponse) {
  mockToMove.value = mock
  showMoveModal.value = true
}

async function confirmMoveMock(targetProjectId: string) {
  if (!mockToMove.value || !moveModalRef.value) return
  moveModalRef.value.submitting = true
  try {
    await moveMock(mockToMove.value.id, { targetProjectId })
    const movedMockName = mockToMove.value.name
    mocks.value = mocks.value.filter(m => m.id !== mockToMove.value!.id)
    showToastMessage(`Mock '${movedMockName}' moved successfully`, BaseToastEnum.SUCCESS)
  } catch (e) {
    showToastMessage(e instanceof Error ? e.message : 'Failed to move mock', BaseToastEnum.ERROR)
  } finally {
    if (moveModalRef.value) moveModalRef.value.submitting = false
    showMoveModal.value = false
    mockToMove.value = null
  }
}

function cancelMoveMock() {
  showMoveModal.value = false
  mockToMove.value = null
}

function openImportPostman() {
  showImportPostmanModal.value = true
}

async function onPostmanImported(_count: number) {
  showImportPostmanModal.value = false
  await loadMocks()
}

function onPostmanCancel() {
  showImportPostmanModal.value = false
}

// ── Postman collection export ─────────────────────────────────────
// Exports produce a Postman Collection v2.1 so the JSON can be imported
// directly via Postman → File → Import. Single-mock export wraps one mock
// in a one-item collection (Postman requires a collection, not a bare item).

function triggerDownload(filename: string, payload: unknown) {
  const json = JSON.stringify(payload, null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

function exportMockAsPostman(mock: MockResponse) {
  const item = buildPostmanItemFromMock(mock, WIREMOCK_BASE_URL)
  const collection = {
    info: {
      name: mock.name,
      description: 'Exported from WireMate. Single-mock Postman collection.',
      schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json',
    },
    item: [item],
  }
  const safe = mock.name.replace(/[^a-zA-Z0-9_-]/g, '_') || 'mock'
  triggerDownload(`${safe}.postman_collection.json`, collection)
}

function exportAllMocksAsPostman() {
  const collectionName = project.value?.name ?? 'WireMate Project'
  const collection = buildPostmanCollection(mocks.value, collectionName, WIREMOCK_BASE_URL)
  const safe = (project.value?.name ?? 'project').replace(/[^a-zA-Z0-9_-]/g, '_') || 'project'
  triggerDownload(`${safe}.postman_collection.json`, collection)
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
        Back to Projects
      </button>

      <!-- Loading -->
      <div v-if="loading" class="flex justify-center py-20">
        <BaseSpinner />
      </div>

      <!-- Error -->
      <div v-else-if="error" class="text-center py-20">
        <p class="text-red-500 mb-4">{{ error }}</p>
        <BaseButton
          description="Back to Projects"
          :color="BaseButtonEnum.GREEN"
          @click="goBack"
        />
      </div>

      <!-- Project details -->
      <div v-else-if="project">
        <div class="rounded-lg border p-6 transition-colors"
          :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
          <div class="flex items-center gap-4 mb-6">
            <div class="w-12 h-12 rounded-lg flex items-center justify-center"
              :class="isDark ? 'bg-emerald-500/10' : 'bg-emerald-50'">
              <FolderIcon class="w-6 h-6" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
            </div>
            <div class="flex-1 min-w-0">
              <!-- Inline-editable project name. Display mode shows the heading
                   with a pencil icon next to it; edit mode swaps in an input
                   with check / X actions. Enter saves, Esc cancels. -->
              <div v-if="!isEditingProjectName" class="flex items-center gap-2">
                <h2 class="text-xl font-semibold truncate" :class="isDark ? 'text-gray-100' : 'text-gray-800'">{{ project.name }}</h2>
                <button
                  type="button"
                  @click="startEditProjectName"
                  class="p-1 rounded transition-colors cursor-pointer"
                  :class="isDark ? 'text-gray-500 hover:text-emerald-300 hover:bg-emerald-500/10' : 'text-gray-400 hover:text-emerald-600 hover:bg-emerald-50'"
                  title="Rename project"
                  aria-label="Rename project"
                >
                  <PencilSquareIcon class="w-4 h-4" />
                </button>
              </div>
              <div v-else class="flex items-center gap-2">
                <input
                  ref="projectNameInput"
                  v-model="projectNameDraft"
                  type="text"
                  :disabled="renamingProject"
                  class="flex-1 min-w-0 rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="isDark
                    ? 'bg-gray-800 border-gray-600 text-gray-100 focus:ring-emerald-500/40 focus:border-emerald-500'
                    : 'bg-white border-gray-300 text-gray-900 focus:ring-emerald-500/40 focus:border-emerald-500'"
                  @keyup.enter="saveProjectName"
                  @keyup.esc="cancelEditProjectName"
                />
                <button
                  type="button"
                  @click="saveProjectName"
                  :disabled="renamingProject || !projectNameDraft.trim()"
                  class="p-1.5 rounded transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                  :class="isDark ? 'text-emerald-300 hover:bg-emerald-500/10' : 'text-emerald-600 hover:bg-emerald-50'"
                  title="Save"
                  aria-label="Save project name"
                >
                  <CheckIcon class="w-4 h-4" />
                </button>
                <button
                  type="button"
                  @click="cancelEditProjectName"
                  :disabled="renamingProject"
                  class="p-1.5 rounded transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                  :class="isDark ? 'text-gray-400 hover:bg-gray-700' : 'text-gray-500 hover:bg-gray-100'"
                  title="Cancel"
                  aria-label="Cancel rename"
                >
                  <XMarkIcon class="w-4 h-4" />
                </button>
              </div>
              <p v-if="renameError" class="text-xs mt-1 text-red-500">{{ renameError }}</p>
              <p class="text-sm" :class="isDark ? 'text-gray-500' : 'text-gray-400'">ID: {{ project.id }}</p>
            </div>
          </div>

          <div class="border-t pt-4" :class="isDark ? 'border-gray-700' : 'border-gray-100'">
            <div>
              <p class="text-xs uppercase tracking-wide mb-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Created</p>
              <p class="text-sm" :class="isDark ? 'text-gray-300' : 'text-gray-700'">{{ formatDate(project.createdAt) }}</p>
            </div>
          </div>
        </div>

        <!-- Mocks section -->
        <div class="mt-8">
          <div class="flex items-center justify-between mb-4">
            <h2 class="text-lg font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">
              Mocks
              <span v-if="!loading && mocks.length" class="ml-2 text-sm font-normal"
                :class="isDark ? 'text-gray-500' : 'text-gray-400'">({{ mocks.length }})</span>
            </h2>
            <div class="flex items-center gap-2">
              <button
                v-if="mocks.length > 0"
                class="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium transition-colors cursor-pointer border"
                :class="isDark
                  ? 'text-sky-400 border-sky-500/40 hover:bg-sky-500/10'
                  : 'text-sky-700 border-sky-300 hover:bg-sky-50'"
                @click="exportAllMocksAsPostman"
                title="Download a Postman collection you can run against the mocks to test them"
              >
                <ArrowDownTrayIcon class="w-4 h-4" />
                Test in Postman
              </button>
              <button
                class="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium transition-colors cursor-pointer border"
                :class="isDark
                  ? 'text-emerald-400 border-emerald-500/40 hover:bg-emerald-500/10'
                  : 'text-emerald-700 border-emerald-300 hover:bg-emerald-50'"
                @click="openImportPostman"
                title="Import stubs from a Postman collection"
              >
                <ArrowUpTrayIcon class="w-4 h-4" />
                Import Stubs From Postman Collection
              </button>
              <button
                class="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-medium text-white transition-colors cursor-pointer"
                :class="isDark ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-emerald-600 hover:bg-emerald-700'"
                @click="openCreateMock"
              >
                <PlusIcon class="w-4 h-4" />
                Create Mock
              </button>
            </div>
          </div>

          <!-- Search + filters -->
          <div v-if="mocks.length > 0" class="mb-4 space-y-2">
            <!-- Search row -->
            <div class="flex items-center gap-2">
              <div class="flex-1 flex items-center gap-2 rounded-lg border px-3 py-2 transition-colors"
                :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
                <MagnifyingGlassIcon class="w-4 h-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'" />
                <input
                  v-model="searchQuery"
                  type="text"
                  placeholder="Filter by name or URL..."
                  class="flex-1 bg-transparent outline-none text-sm placeholder-gray-500"
                  :class="isDark ? 'text-gray-100 placeholder-gray-600' : 'text-gray-900 placeholder-gray-400'"
                />
                <Transition
                  enter-active-class="transition-opacity duration-150 ease-out"
                  leave-active-class="transition-opacity duration-100 ease-in"
                  enter-from-class="opacity-0"
                  enter-to-class="opacity-100"
                  leave-from-class="opacity-100"
                  leave-to-class="opacity-0"
                >
                  <button v-if="searchQuery" type="button" @click="searchQuery = ''"
                    class="cursor-pointer transition-colors" :class="isDark ? 'text-gray-500 hover:text-gray-300' : 'text-gray-400 hover:text-gray-600'"
                    title="Clear search">
                    <XMarkIcon class="w-4 h-4" />
                  </button>
                </Transition>
              </div>
              <button
                type="button"
                @click="filtersOpen = !filtersOpen"
                class="inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium transition-colors cursor-pointer"
                :class="filtersOpen
                  ? (isDark ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40' : 'bg-emerald-50 text-emerald-700 border-emerald-200')
                  : (isDark ? 'bg-gray-900 text-gray-300 border-gray-700 hover:bg-gray-800' : 'bg-white text-gray-700 border-gray-200 hover:bg-gray-50')"
                :title="filtersOpen ? 'Hide filters' : 'Show filters'"
              >
                <AdjustmentsHorizontalIcon class="w-4 h-4" />
                Filters
                <Transition
                  enter-active-class="transition-all duration-200 ease-out"
                  leave-active-class="transition-all duration-150 ease-in"
                  enter-from-class="opacity-0 scale-75"
                  enter-to-class="opacity-100 scale-100"
                  leave-from-class="opacity-100 scale-100"
                  leave-to-class="opacity-0 scale-75"
                >
                  <span v-if="activeFilterCount > 0" class="inline-flex items-center justify-center min-w-[1.25rem] h-5 px-1.5 rounded-full text-xs font-semibold"
                    :class="isDark ? 'bg-emerald-500 text-emerald-950' : 'bg-emerald-600 text-white'">
                    {{ activeFilterCount }}
                  </span>
                </Transition>
              </button>
              <!-- Clear-all button fades in/out instead of popping -->
              <Transition
                enter-active-class="transition-all duration-200 ease-out"
                leave-active-class="transition-all duration-150 ease-in"
                enter-from-class="opacity-0 -translate-x-1"
                enter-to-class="opacity-100 translate-x-0"
                leave-from-class="opacity-100 translate-x-0"
                leave-to-class="opacity-0 -translate-x-1"
              >
                <button
                  v-if="activeFilterCount > 0"
                  type="button"
                  @click="clearAllFilters"
                  class="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
                  title="Clear every active filter"
                >
                  <XMarkIcon class="w-3.5 h-3.5" />
                  Clear all
                </button>
              </Transition>
            </div>

            <!--
              Filters panel (collapsible).
              Uses the `grid-rows: 0fr ↔ 1fr` trick so the panel expands to
              its natural content height rather than an arbitrary max-height
              — and a single transition animates height + opacity together
              for a smooth open/close. The inner wrapper needs
              `overflow-hidden` so the content is clipped while the row
              tween is in progress.
            -->
            <div
              class="grid transition-all duration-300 ease-out"
              :class="filtersOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'"
              :aria-hidden="!filtersOpen"
            >
              <div class="overflow-hidden min-h-0">
            <div class="rounded-lg border p-4 transition-colors"
              :class="isDark ? 'bg-gray-900 border-gray-700' : 'bg-white border-gray-200'">
              <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <!-- Method -->
                <div>
                  <label class="block text-xs font-semibold uppercase tracking-wide mb-1"
                    :class="isDark ? 'text-gray-400' : 'text-gray-500'">HTTP Method</label>
                  <select v-model="methodFilter"
                    class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isDark ? 'bg-gray-800 border-gray-600 text-gray-100 focus:ring-emerald-500' : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500'">
                    <option value="">Any method</option>
                    <option v-for="m in ['GET','POST','PUT','DELETE','PATCH','HEAD','OPTIONS','TRACE','ANY']" :key="m" :value="m">{{ m }}</option>
                  </select>
                </div>
                <!-- Status -->
                <div>
                  <label class="block text-xs font-semibold uppercase tracking-wide mb-1"
                    :class="isDark ? 'text-gray-400' : 'text-gray-500'">Response Status</label>
                  <select v-model="statusBucketFilter"
                    class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isDark ? 'bg-gray-800 border-gray-600 text-gray-100 focus:ring-emerald-500' : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500'">
                    <option value="">Any status</option>
                    <option value="2xx">2xx — Success</option>
                    <option value="3xx">3xx — Redirect</option>
                    <option value="4xx">4xx — Client error</option>
                    <option value="5xx">5xx — Server error</option>
                    <option value="other">Other</option>
                  </select>
                </div>
              </div>
              <!-- Feature toggles -->
              <div class="mt-4">
                <label class="block text-xs font-semibold uppercase tracking-wide mb-2"
                  :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                  Features
                  <span class="ml-1 font-normal normal-case tracking-normal"
                    :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                    (click to cycle Any → Yes → No)
                  </span>
                </label>
                <div class="flex flex-wrap gap-2">
                  <button
                    v-for="f in FEATURE_FILTERS"
                    :key="f.label"
                    type="button"
                    @click="cycleTriState(f.ref)"
                    class="inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors cursor-pointer"
                    :class="f.ref.value === 'yes'
                      ? (isDark ? 'bg-emerald-500/15 text-emerald-300 border-emerald-500/40' : 'bg-emerald-50 text-emerald-700 border-emerald-300')
                      : f.ref.value === 'no'
                        ? (isDark ? 'bg-red-500/15 text-red-300 border-red-500/40' : 'bg-red-50 text-red-700 border-red-300')
                        : (isDark ? 'bg-gray-800 text-gray-400 border-gray-700 hover:bg-gray-700' : 'bg-gray-50 text-gray-500 border-gray-200 hover:bg-gray-100')"
                  >
                    <span>{{ f.label }}</span>
                    <span class="text-[10px] uppercase tracking-wide opacity-70">
                      {{ f.ref.value === 'any' ? '—' : f.ref.value === 'yes' ? 'yes' : 'no' }}
                    </span>
                  </button>
                </div>
              </div>
            </div>
              </div>
            </div>
          </div>

          <!-- Empty state — project has no mocks yet -->
          <div v-if="mocks.length === 0" class="flex flex-col items-center justify-center text-center py-16 rounded-lg border transition-colors"
            :class="isDark ? 'bg-gray-900/50 border-gray-800' : 'bg-gray-50 border-gray-200'">
            <InboxIcon class="w-12 h-12 mb-3" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
            <p class="text-sm font-medium" :class="isDark ? 'text-gray-300' : 'text-gray-600'">
              No mocks yet
            </p>
            <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              Create your first mock to get started.
            </p>
          </div>

          <!-- Empty state — search/filter returned no matches -->
          <div
            v-else-if="filteredMocks.length === 0"
            class="flex flex-col items-center justify-center text-center py-16 rounded-lg border transition-colors"
            :class="isDark ? 'bg-gray-900/50 border-gray-800' : 'bg-gray-50 border-gray-200'"
          >
            <FaceFrownIcon class="w-12 h-12 mb-3" :class="isDark ? 'text-gray-600' : 'text-gray-300'" />
            <p class="text-sm font-medium" :class="isDark ? 'text-gray-300' : 'text-gray-600'">
              <template v-if="searchQuery && activeFilterCount === 1">No mocks match “{{ searchQuery }}”</template>
              <template v-else>No mocks match the current filters</template>
            </p>
            <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              Try adjusting your search or
              <button type="button" @click="clearAllFilters"
                class="underline underline-offset-2 cursor-pointer"
                :class="isDark ? 'text-emerald-400 hover:text-emerald-300' : 'text-emerald-600 hover:text-emerald-700'">
                clear {{ activeFilterCount > 1 ? 'all filters' : 'the search' }}
              </button>.
            </p>
          </div>

          <!-- Mocks list -->
          <!--
            Mocks that share a URL are rendered as a single cluster inside a
            dashed amber frame with a collapsible header. The header carries
            the shared URL, the count, and a chevron that toggles the
            cluster open/closed — handy when a URL has many competing
            mocks and the user wants to focus on one section at a time.

            Within each cluster, mocks are ordered by `priority` ascending
            (WireMock picks the lowest — the top card is the one that
            actually matches). Isolated mocks (URL appears once) render
            exactly as before: the wrapper uses `display: contents` so it
            vanishes from layout and the single card becomes a direct grid
            child, with no frame or header chrome around it.
          -->
          <div v-if="mocks.length > 0 && filteredMocks.length > 0" class="grid grid-cols-1 gap-4">
            <div
              v-for="group in groupedFilteredMocks"
              :key="group.url"
              :class="group.inGroup
                ? (isDark
                    ? 'rounded-xl border-2 border-dashed border-amber-500/40 bg-amber-500/[0.03] overflow-hidden'
                    : 'rounded-xl border-2 border-dashed border-amber-300 bg-amber-50/40 overflow-hidden')
                : 'contents'"
            >
              <!-- Group header — clickable toggle, only for URLs with 2+ mocks -->
              <button v-if="group.inGroup" type="button"
                @click="toggleGroupCollapse(group.url)"
                class="w-full flex items-center flex-wrap gap-2 px-3 py-2 text-left transition-colors cursor-pointer"
                :class="isDark ? 'hover:bg-amber-500/[0.06]' : 'hover:bg-amber-100/50'"
                :aria-expanded="!isGroupCollapsed(group.url)"
                :title="isGroupCollapsed(group.url) ? 'Expand group' : 'Collapse group'">
                <ChevronDownIcon class="w-4 h-4 shrink-0 transition-transform"
                  :class="[
                    isDark ? 'text-amber-300' : 'text-amber-700',
                    isGroupCollapsed(group.url) ? '-rotate-90' : ''
                  ]" />
                <DocumentDuplicateIcon class="w-4 h-4 shrink-0"
                  :class="isDark ? 'text-amber-300' : 'text-amber-700'" />
                <span class="text-xs font-semibold"
                  :class="isDark ? 'text-amber-200' : 'text-amber-800'">
                  Shared URL
                </span>
                <code class="text-xs font-mono px-1.5 py-0.5 rounded max-w-[24rem] truncate border"
                  :class="isDark
                    ? 'bg-amber-500/10 border-amber-500/30 text-amber-200'
                    : 'bg-white border-amber-200 text-amber-900'"
                  :title="group.url">
                  {{ group.url }}
                </code>
                <span class="text-[11px]"
                  :class="isDark ? 'text-amber-300/80' : 'text-amber-700'">
                  · {{ group.mocks.length }} mocks, ordered by priority (lowest matches first)
                </span>
              </button>

              <!--
                Cards container:
                  - For multi-mock groups: a padded, bordered block that
                    stacks cards vertically. `v-show` toggles visibility
                    via the header chevron, preserving scroll and form
                    state on collapse.
                  - For single-mock groups: `display: contents` so the
                    card becomes a direct grid child of the outer grid,
                    keeping spacing identical to the un-grouped case.
              -->
              <div
                v-show="!(group.inGroup && isGroupCollapsed(group.url))"
                :class="group.inGroup
                  ? ['p-3 space-y-3 border-t', isDark ? 'border-amber-500/30' : 'border-amber-200']
                  : 'contents'"
              >
              <div
                v-for="mock in group.mocks"
                :key="mock.id"
                class="rounded-xl border p-4 flex flex-col justify-between transition-all"
                :class="isDark
                  ? 'bg-gray-900 border-gray-700 hover:bg-gray-950'
                  : 'bg-white border-gray-200 shadow-sm hover:bg-gray-100'"
            >
              <!-- Top: method + url badge + prominent features + meta -->
              <div>
                <!--
                  Row 1 — the "identity" row.
                    Method chip + status chip + URL badge (monospace, truncated),
                    followed by URL match type, priority, persistence, and any
                    prominent features (auth, fault). The URL lives here rather
                    than on its own line below the name so the request shape
                    is readable at a glance, the way developers parse API docs.
                -->
                <div class="flex items-center flex-wrap gap-2 mb-2">
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-bold uppercase tracking-wide"
                    :class="methodColor(mock.request.method)">
                    {{ mock.request.method }}
                  </span>
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-semibold"
                    :class="statusBadgeClasses(mock.response.status)"
                    :title="`HTTP ${mock.response.status}${mock.response.statusMessage ? ' · ' + mock.response.statusMessage : ''}`">
                    {{ mock.response.status }}
                  </span>
                  <!--
                    Content-Type chip — surfaces the stub's advertised
                    response MIME type at the top of the card so the
                    payload's shape is obvious without opening the mock.
                    Rendered only when the stub actually sets one; we
                    don't want to invent a default on a card that may
                    legitimately return an empty body (e.g. 204).
                  -->
                  <span v-if="responseContentType(mock)"
                    class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono max-w-[16rem] truncate border"
                    :class="isDark ? 'bg-emerald-500/10 text-emerald-300 border-emerald-500/30' : 'bg-emerald-50 text-emerald-700 border-emerald-200'"
                    :title="`Response Content-Type: ${responseContentType(mock)}`">
                    {{ responseContentType(mock) }}
                  </span>
                  <!--
                    URL badge — monospaced and capped with `max-w-[24rem]
                    truncate` so a long path doesn't wrap the chip row. Full
                    URL is preserved in the tooltip.
                  -->
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono max-w-[24rem] truncate border"
                    :class="isDark ? 'bg-gray-800 text-gray-300 border-gray-700' : 'bg-gray-50 text-gray-700 border-gray-200'"
                    :title="getMockUrl(mock)">
                    {{ getMockUrl(mock) }}
                  </span>
                  <span class="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-medium uppercase tracking-wide border"
                    :class="isDark ? 'border-gray-700 text-gray-400' : 'border-gray-200 text-gray-500'"
                    :title="`URL match type: ${urlMatchLabel(mock)}`">
                    {{ urlMatchLabel(mock) }}
                  </span>
                  <span v-if="typeof mock.priority === 'number'"
                    class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-semibold"
                    :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-600'"
                    :title="`Priority ${mock.priority} (lower = higher precedence)`">
                    <FlagIcon class="w-3 h-3" />
                    P{{ mock.priority }}
                  </span>
                  <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px] font-medium"
                    :class="mock.persistent
                      ? (isDark ? 'bg-emerald-500/10 text-emerald-300' : 'bg-emerald-50 text-emerald-700')
                      : (isDark ? 'bg-gray-800 text-gray-400' : 'bg-gray-100 text-gray-500')"
                    :title="mock.persistent ? 'Survives WireMock resets' : 'Cleared on WireMock reset'">
                    <BookmarkIcon class="w-3 h-3" />
                    {{ mock.persistent ? 'Persistent' : 'Ephemeral' }}
                  </span>
                  <!--
                    Prominent feature chips (auth, fault) live in the top
                    row so security-relevant behaviours are front-and-centre
                    instead of lost in the chip grid below.
                  -->
                  <span v-for="chip in prominentChips(mock)" :key="chip.key"
                    class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full border text-[10px] font-medium"
                    :class="isDark ? chip.darkClasses : chip.lightClasses"
                    :title="chip.title">
                    <component :is="chip.icon" class="w-3 h-3" />
                    {{ chip.label }}
                  </span>

                </div>

                <!-- Row 2 — mock name (primary heading) -->
                <div class="flex items-center gap-2">
                  <CodeBracketIcon class="w-5 h-5 shrink-0" :class="isDark ? 'text-emerald-400' : 'text-emerald-600'" />
                  <h3 class="text-sm font-semibold truncate" :class="isDark ? 'text-gray-100' : 'text-gray-800'">
                    {{ mock.name }}
                  </h3>
                </div>

                <!--
                  Row 3 — optional description. Only renders when present
                  so the card doesn't grow a blank line for unconfigured
                  mocks. `line-clamp-2` caps it to two lines so long blurbs
                  don't stretch the card.
                -->
                <p v-if="mock.description && mock.description.trim()"
                  class="text-xs mt-1.5 line-clamp-2"
                  :class="isDark ? 'text-gray-400' : 'text-gray-600'"
                  :title="mock.description">
                  {{ mock.description }}
                </p>

                <!--
                  Row 4 — scenario walkthrough. Surfaces the required→new
                  state transition inline so users can see the flow without
                  opening the edit form.
                -->
                <div v-if="mockHasScenario(mock)"
                  class="mt-2 flex items-center gap-1.5 text-[11px]"
                  :class="isDark ? 'text-purple-300' : 'text-purple-700'">
                  <BoltIcon class="w-3.5 h-3.5" />
                  <span class="font-semibold">{{ mock.scenarioName }}</span>
                  <span v-if="mock.requiredScenarioState"
                    class="px-1.5 py-0.5 rounded font-mono"
                    :class="isDark ? 'bg-purple-500/10' : 'bg-purple-50'">
                    {{ mock.requiredScenarioState }}
                  </span>
                  <template v-if="mock.newScenarioState">
                    <span :class="isDark ? 'text-gray-500' : 'text-gray-400'">→</span>
                    <span class="px-1.5 py-0.5 rounded font-mono"
                      :class="isDark ? 'bg-purple-500/10' : 'bg-purple-50'">
                      {{ mock.newScenarioState }}
                    </span>
                  </template>
                </div>

                <!--
                  Row 5 — Request / Response summary.
                  Two side-by-side boxes that capture the shape of the
                  matcher (left) and the canned response (right) without
                  the user having to open the edit form. Each box renders
                  a tight key/value grid driven by `requestSummary` /
                  `responseSummary`; entries with no meaningful data are
                  filtered out so empty mocks don't pad the card with
                  zero-count rows.
                -->
                <div class="mt-3 grid grid-cols-1 md:grid-cols-2 gap-2">
                  <div class="rounded-lg border p-2.5"
                    :class="isDark ? 'border-sky-500/30 bg-sky-500/5' : 'border-sky-200 bg-sky-50/60'">
                    <div class="flex items-center gap-1.5 mb-1.5">
                      <ArrowDownTrayIcon class="w-3.5 h-3.5"
                        :class="isDark ? 'text-sky-300' : 'text-sky-700'" />
                      <h4 class="text-[10px] font-semibold uppercase tracking-wide"
                        :class="isDark ? 'text-sky-300' : 'text-sky-700'">
                        Request Matching
                      </h4>
                    </div>
                    <dl class="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-[11px]">
                      <template v-for="row in requestSummary(mock)" :key="row.label">
                        <dt class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                          {{ row.label }}
                        </dt>
                        <dd class="font-mono truncate" :class="isDark ? 'text-gray-200' : 'text-gray-800'"
                          :title="row.value">
                          {{ row.value }}
                        </dd>
                      </template>
                    </dl>
                  </div>
                  <div class="rounded-lg border p-2.5"
                    :class="isDark ? 'border-emerald-500/30 bg-emerald-500/5' : 'border-emerald-200 bg-emerald-50/60'">
                    <div class="flex items-center gap-1.5 mb-1.5">
                      <ArrowUpTrayIcon class="w-3.5 h-3.5"
                        :class="isDark ? 'text-emerald-300' : 'text-emerald-700'" />
                      <h4 class="text-[10px] font-semibold uppercase tracking-wide"
                        :class="isDark ? 'text-emerald-300' : 'text-emerald-700'">
                        Response Definition
                      </h4>
                    </div>
                    <dl class="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5 text-[11px]">
                      <template v-for="row in responseSummary(mock)" :key="row.label">
                        <dt class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                          {{ row.label }}
                        </dt>
                        <dd class="font-mono truncate" :class="isDark ? 'text-gray-200' : 'text-gray-800'"
                          :title="row.value">
                          {{ row.value }}
                        </dd>
                      </template>
                    </dl>
                  </div>
                </div>

              </div>

              <!-- Bottom: actions -->
              <div class="flex items-center gap-2 mt-3 pt-3 border-t"
                :class="isDark ? 'border-gray-800' : 'border-gray-100'">
                <button
                  @click="editMock(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-emerald-400 hover:bg-emerald-500/10'
                    : 'text-emerald-600 hover:bg-emerald-50'"
                >
                  <EyeIcon class="w-4 h-4" />
                  View
                </button>
                <button
                  @click="viewMockLogs(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-sky-400 hover:bg-sky-500/10'
                    : 'text-sky-600 hover:bg-sky-50'"
                  title="View request logs for this mock"
                >
                  <ClipboardDocumentListIcon class="w-4 h-4" />
                  Logs
                </button>
                <!--
                  Stub slot.
                    • When the matching stub is missing from WireMock we
                      swap this action out for a "click to create" call,
                      so the same slot that usually opens the raw stub
                      becomes the one-click way to re-push it. On
                      success the stub status flips to `exists` and the
                      slot reverts to the normal navigation button.
                    • 'creating' → show a dimmed spinner in the same
                      slot so the user gets immediate in-flight
                      feedback without the button visually jumping.
                    • Any other state ('exists', 'loading', 'error',
                      unknown) → the regular Stub button that routes
                      to /stubs/:id — we don't want to mislabel it as
                      missing just because we haven't finished probing
                      WireMock yet.
                -->
                <button
                  v-if="stubStatuses[mock.id] === 'missing'"
                  @click="createStubForMock(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-amber-300 hover:bg-amber-500/10'
                    : 'text-amber-700 hover:bg-amber-50'"
                  title="No matching stub on WireMock — click to create it from this mock"
                >
                  click to create
                </button>
                <button
                  v-else-if="stubStatuses[mock.id] === 'creating'"
                  disabled
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium cursor-not-allowed opacity-70"
                  :class="isDark ? 'text-amber-300' : 'text-amber-700'"
                  title="Creating stub on WireMock…"
                >
                  <ArrowPathIcon class="w-4 h-4 animate-spin" />
                  Creating…
                </button>
                <button
                  v-else
                  @click="viewMockStub(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-indigo-400 hover:bg-indigo-500/10'
                    : 'text-indigo-600 hover:bg-indigo-50'"
                  title="Open the raw WireMock stub"
                >
                  <CubeIcon class="w-4 h-4" />
                  Stub
                </button>
                <!--
                  Copy cURL — surfaces the backend-persisted `curl`
                  string. Disabled and visibly dimmed when the mock has
                  no curl (e.g. pattern-based URLs where a runnable
                  cURL can't be generated), so users understand the
                  affordance is conditional rather than broken.
                -->
                <button
                  @click="copyCurlFromMock(mock)"
                  :disabled="!mock.curl"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                  :class="isDark
                    ? 'text-teal-400 hover:bg-teal-500/10 disabled:hover:bg-transparent'
                    : 'text-teal-600 hover:bg-teal-50 disabled:hover:bg-transparent'"
                  :title="mock.curl ? 'Copy cURL to clipboard' : 'No cURL available for this mock'"
                >
                  <CommandLineIcon class="w-4 h-4" />
                  cURL
                </button>
                <button
                  @click="handleCloneMock(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-amber-400 hover:bg-amber-500/10'
                    : 'text-amber-600 hover:bg-amber-50'"
                >
                  <DocumentDuplicateIcon class="w-4 h-4" />
                  Clone
                </button>
                <button
                  @click="handleMoveMock(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-purple-400 hover:bg-purple-500/10'
                    : 'text-purple-600 hover:bg-purple-50'"
                >
                  <ArrowRightStartOnRectangleIcon class="w-4 h-4" />
                  Move
                </button>
                <button
                  @click="exportMockAsPostman(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-sky-400 hover:bg-sky-500/10'
                    : 'text-sky-600 hover:bg-sky-50'"
                  title="Export as Postman collection"
                >
                  <ArrowDownTrayIcon class="w-4 h-4" />
                  Export
                </button>
                <button
                  @click="handleDeleteMock(mock)"
                  class="flex-1 inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                  :class="isDark
                    ? 'text-red-400 hover:bg-red-500/10'
                    : 'text-red-500 hover:bg-red-50'"
                >
                  <TrashIcon class="w-4 h-4" />
                  Delete
                </button>
              </div>
            </div>
              </div><!-- /cards container -->
            </div><!-- /group container -->
          </div><!-- /outer grid -->
        </div>
      </div>
    </div>

    <!-- Delete confirmation modal -->
    <ConfirmModal
      v-if="showDeleteModal"
      title="Delete Mock"
      :message="`Are you sure you want to delete mock '${mockToDelete?.name}'? The mock will be removed from both the database and the WireMock service. This action cannot be undone.`"
      confirm-text="Yes, delete"
      cancel-text="Cancel"
      variant="danger"
      @confirm="confirmDeleteMock"
      @cancel="cancelDeleteMock"
    />

    <!-- Clone mock modal -->
    <CloneModal
      v-if="showCloneMockModal"
      ref="cloneMockModalRef"
      title="Clone Mock"
      :message="`Create a copy of '${mockToClone?.name}'.`"
      :initial-name="cloneSuggestedName"
      placeholder="Enter mock name..."
      confirm-text="Clone"
      @confirm="confirmCloneMock"
      @cancel="cancelCloneMock"
    />

    <!-- Move mock modal -->
    <MoveModal
      v-if="showMoveModal"
      ref="moveModalRef"
      title="Move Mock"
      :message="`Select the project to move '${mockToMove?.name}' to.`"
      :current-project-id="projectId"
      @confirm="confirmMoveMock"
      @cancel="cancelMoveMock"
    />

    <!-- Import from Postman modal -->
    <ImportPostmanModal
      v-if="showImportPostmanModal"
      :project-id="projectId"
      @imported="onPostmanImported"
      @cancel="onPostmanCancel"
    />

    <!-- Toast notification -->
    <BaseToast
      v-if="showToast"
      :description="toastMessage"
      :mode="toastType"
    />
  </div>
</template>
