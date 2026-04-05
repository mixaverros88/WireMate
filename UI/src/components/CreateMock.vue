<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { BaseButton, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import { mockApi } from '../services/api'
import type { KeyValuePair, MockResponse, CreateMockPayload, RequestDefinition, ResponseDefinition, BodyPattern, StringMatcher, WebhookDefinition } from '../types/mock'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { WIREMOCK_BASE_URL } from '../config'
import { checkStubExists } from '../services/stubService'
import { fetchProject } from '../services/projectService'
import { checkMockUrlExists, checkMockNameExists } from '../services/mockService'
import { useDebouncedRef } from '../composables/useDebounce'
import {
  type CookieRow,
  COOKIE_ATTRS_METADATA_KEY,
  createCookieRow,
  cookieStr,
  isValidCookieDomain,
  isValidCookiePath,
  cookieToSetCookieString,
  parseSetCookieString,
} from '../utils/cookies'

const { isDark } = useTheme()

const props = defineProps<{
  projectId?: string
  mock?: MockResponse
}>()

const emit = defineEmits<{
  created: [mockId: string]
  updated: [mockId: string]
}>()

const isEditMode = computed(() => !!props.mock)

// ── Matcher row type ──────────────────────────────────────────
interface MatcherRow {
  key: string
  matcherType: string
  value: string
  // When true, the comparison is case-insensitive. WireMock honours this
  // on `equalTo`, `contains`, `matches`, and `doesNotMatch` — for other
  // matcher kinds (absent, date-based) the field is ignored.
  caseInsensitive: boolean
}

interface BodyPatternRow {
  matcherType: string
  value: string
  ignoreArrayOrder: boolean
  ignoreExtraElements: boolean
  // Optional advanced fields, only meaningful for specific matcher types:
  //   • matchesJsonPath          → varyBy (sub-expression for templating)
  //                              → jsonPathSubMatcher / jsonPathSubMatcherValue
  //                                 (sub-matcher applied to the value the
  //                                  JSONPath expression extracted)
  //   • matchesXPath             → xPathNamespaces (prefix → URI map)
  //   • equalToXml               → enablePlaceholders, xPathNamespaces
  //   • matchesJsonSchema        → schemaVersion (e.g. V202012)
  //   • before/after/equalToDateTime → truncate/offset options
  varyBy?: string
  enablePlaceholders?: boolean
  xPathNamespaces?: Array<{ prefix: string; uri: string }>
  truncateExpected?: string
  truncateActual?: string
  expectedOffset?: number
  expectedOffsetUnit?: string
  schemaVersion?: string
  // JSONPath object-form sub-matcher. Default `''` (or `'none'`) emits the
  // bare-expression form on the wire; any other kind is paired with the
  // expression in `{ matchesJsonPath: { expression, [kind]: value } }`.
  jsonPathSubMatcher?: string
  jsonPathSubMatcherValue?: string
}

// Sub-matcher kinds WireMock accepts inside the object form of
// `matchesJsonPath`. `absent` doesn't need a value (asserts the JSONPath
// hit is missing); every other kind takes a string.
const JSONPATH_SUB_MATCHERS = [
  { value: '', label: '(none — bare expression)' },
  { value: 'equalTo', label: 'equalTo' },
  { value: 'contains', label: 'contains' },
  { value: 'matches', label: 'matches (regex)' },
  { value: 'doesNotMatch', label: 'doesNotMatch (regex)' },
  { value: 'equalToJson', label: 'equalToJson' },
  { value: 'equalToXml', label: 'equalToXml' },
  { value: 'absent', label: 'absent (no extracted value)' },
] as const

// Matcher kinds that respect `caseInsensitive`. Used to decide whether
// the checkbox should be shown next to a row's matcher dropdown.
const CASE_INSENSITIVE_MATCHERS = new Set([
  'equalTo', 'contains', 'matches', 'doesNotMatch',
])

function supportsCaseInsensitive(matcherType: string): boolean {
  return CASE_INSENSITIVE_MATCHERS.has(matcherType)
}

interface MetadataRow {
  key: string
  value: string
  valueType: 'text' | 'json'
  locked?: boolean
}

// Metadata keys that are auto-managed by WireMate. They are rendered as
// readonly rows (key, value type, AND value), cannot be removed by the
// user, and always get their canonical value regardless of what may
// have been stored on the mock.
//
// `projectId` is the canonical key name (renamed from the legacy
// `project`); legacy mocks that still carry `project` are migrated on
// load — see populateFromMock() below.
const LOCKED_METADATA_KEYS = new Set(['projectId', 'generatedBy'])

// Canonical builder for the system-managed metadata rows. Kept in one
// place so the default form, the edit pre-fill, and the reset handler all
// agree on which keys are locked and what their values are. Both the
// `projectId` and `generatedBy` rows are fully locked: the user cannot
// rename the key, change the value type, or edit the value.
function buildLockedMetadataRows(projectId: string): MetadataRow[] {
  return [
    { key: 'projectId', value: projectId, valueType: 'text', locked: true },
    { key: 'generatedBy', value: 'wiremate', valueType: 'text', locked: true },
  ]
}

interface ProxyHeaderRow {
  key: string
  value: string
}

// ── Collapsible section state ─────────────────────────────────
// `true` = collapsed (header only), `false` = expanded (body shown).
// The three primary sections (general, requestMatching, responseDefinition)
// default to expanded because users almost always want to fill them in;
// every other section is optional and starts collapsed to keep the form
// scannable on first load.
const collapsedSections = ref<Record<string, boolean>>({
  general: false,
  scenario: true,
  requestMatching: false,
  responseDefinition: false,
  responseTransformers: true,
  metadata: false,
  cookies: true,
  basicAuth: true,
  faultSimulation: true,
  proxying: true,
  webhooks: true,
  chunkedDribble: true,
})

function toggleSection(section: string) {
  collapsedSections.value[section] = !collapsedSections.value[section]
}

// ── Form state: General ───────────────────────────────────────
const mockName = ref('')
const description = ref('')
const internalProjectId = ref(props.projectId ?? '')
// Display-only project name resolved from `internalProjectId`. Always shown
// alongside the UUID in the General section so the user can confirm at a
// glance which project this mock belongs to. Populated via /api/projects/{id}.
const projectName = ref('')
// URL-friendly slug derived from `projectName`. Used as a forced namespace
// prefix on the request URL — every mock under "Acme Project" lives under
// /acme-project/... so the user never mixes mocks across projects by accident.
function slugifyProjectName(name: string): string {
  return name
    .toLowerCase()
    .trim()
    .replace(/\s+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
}
const projectSlug = computed(() => slugifyProjectName(projectName.value))
// `/{slug}` if we know the slug, otherwise empty so legacy/no-project flows
// behave like the pre-namespacing UI.
const projectUrlPrefix = computed(() => projectSlug.value ? `/${projectSlug.value}` : '')

// Fetch project name whenever the resolved project id changes. Runs on every
// entry into the form (create-with-projectId-query-param, edit-mode-after-
// populateFromMock, and the rarely-used create-without-projectId flow once
// the user pastes an id). Failures are swallowed — the UUID stays visible.
watch(
  internalProjectId,
  async (id) => {
    const trimmed = (id ?? '').trim()
    if (!trimmed) {
      projectName.value = ''
      return
    }
    try {
      const proj = await fetchProject(trimmed)
      projectName.value = proj.name ?? ''
    } catch {
      projectName.value = ''
    }
  },
  { immediate: true },
)
const persistent = ref(true)
// Priority defaults to 5 to match WireMock's own default (lower = higher
// priority). Pre-populating the field means a brand-new mock is immediately
// valid, and users can still override or clear it before saving.
const priority = ref<number | undefined>(5)

// ── Form state: Scenario state machine ────────────────────────
// All three fields are optional and only become meaningful as a triple:
//   • scenarioName          — id of the workflow this stub belongs to
//   • requiredScenarioState — state the scenario must be in for this
//                             stub to match
//   • newScenarioState      — state to transition to after this stub
//                             serves a request
// Empty strings are normalised to `undefined` at save-time so a partial
// fill doesn't pollute the wire payload.
const scenarioName = ref('')
const requiredScenarioState = ref('')
const newScenarioState = ref('')

// ── Form state: Metadata ──────────────────────────────────────
const metadataRows = ref<MetadataRow[]>([])

// One-way sync from `internalProjectId` → the (read-only) `projectId`
// metadata row. The metadata row is system-managed and locked, so the
// project-id input on the form (or the route's ?projectId param) is the
// only source of truth; this watcher just keeps the metadata row's
// displayed value in step with it. Compare before assigning to avoid
// triggering deep watchers on metadataRows for no-op updates.
watch(internalProjectId, (id) => {
  const projectRow = metadataRows.value.find(r => r.key === 'projectId')
  if (projectRow && projectRow.value !== id) {
    projectRow.value = id
  }
})

// ── Form state: Request ───────────────────────────────────────
const httpMethod = ref('GET')
const urlMatchType = ref<'url' | 'urlPath' | 'urlPattern' | 'urlPathPattern'>('url')
const urlValue = ref('/')

// Ensure the URL always starts with /
watch(urlValue, (newVal) => {
  if (!newVal.startsWith('/')) {
    urlValue.value = '/' + newVal.replace(/^\/+/, '')
  }
})

// ── URL collision check (live) ────────────────────────────────
// As the user types in the URL field, ping the backend with the current
// project id + the full namespaced URL (project slug prefix + user-typed
// portion — i.e. exactly what would be saved). Debounced 300ms so we
// don't fire a request per keystroke.
//
// Backend contract:
//   200 OK → a mock at that URL already exists under this project,
//            so we surface a "URL already exists" warning under the
//            input.
//   404    → no collision; keep the UI silent (per product spec we
//            do not render anything in this case).
// Other failures are swallowed silently — this is a soft, advisory
// check and a transient backend hiccup shouldn't decorate the form
// with a scary error while the user is mid-edit.
const urlExists = ref(false)
const debouncedUrlValue = useDebouncedRef(urlValue, 300)
// Sequence guard for the existence check. Each watcher firing bumps the
// counter and snapshots its own value; when the awaited fetch resolves,
// we only commit the result if our snapshot is still the latest. Without
// this, a slow earlier request can land *after* a fast later one and
// flip `urlExists` to a stale truth — surfacing a warning that doesn't
// match what the user is currently typing.
let urlCheckSeq = 0

watch(
  () => [internalProjectId.value, debouncedUrlValue.value, projectUrlPrefix.value] as const,
  async ([projId, urlQuery, prefix]) => {
    const seq = ++urlCheckSeq
    const trimmedProjId = (projId ?? '').trim()
    const trimmedUrl = (urlQuery ?? '').trim()
    // Skip the probe for empty inputs or the seed `/` placeholder —
    // those are either invalid or the project root and provide no
    // actionable signal.
    if (!trimmedProjId || !trimmedUrl || trimmedUrl === '/') {
      urlExists.value = false
      return
    }
    // Send the same fully-qualified URL the form will actually persist:
    // project namespace prefix + user-typed path. Anything else risks
    // false negatives (collision exists in storage but probe misses it
    // because the prefix wasn't applied).
    const path = trimmedUrl.startsWith('/') ? trimmedUrl : `/${trimmedUrl}`
    const fullUrl = (prefix || '') + path
    try {
      const exists = await checkMockUrlExists(trimmedProjId, fullUrl)
      if (seq !== urlCheckSeq) return // stale — a newer call is in flight
      // In edit mode, the URL the user is editing belongs to the mock
      // they're already editing — don't warn them about themselves.
      if (exists && isEditMode.value && props.mock) {
        const ownUrl = props.mock.request.url
          || props.mock.request.urlPath
          || props.mock.request.urlPattern
          || props.mock.request.urlPathPattern
          || ''
        urlExists.value = ownUrl !== fullUrl
      } else {
        urlExists.value = exists
      }
    } catch {
      if (seq !== urlCheckSeq) return
      // Soft check — never let a backend hiccup pollute the form.
      urlExists.value = false
    }
  },
)

// ── Name collision check (live) ───────────────────────────────
// Mirrors the URL collision check above: as the user types in the
// Mock Name field, ping the backend with the current project id +
// the trimmed mock name. Debounced 300ms to avoid one request per
// keystroke.
//
// Backend contract:
//   GET /api/projects/{projectId}/exists/by-name?name={name}
//     200 OK true  → a mock with that name already exists, surface
//                    a "Name already exists" warning under the input.
//     200 OK false → no collision; keep the UI silent.
// Other failures are swallowed silently — like the URL probe this is
// a soft, advisory signal and shouldn't decorate the form with a
// scary error while the user is mid-edit.
const nameExists = ref(false)
const debouncedMockName = useDebouncedRef(mockName, 300)
// Same sequence guard as the URL probe — protects against stale-write
// races when a slow earlier name check resolves after a fast later one.
let nameCheckSeq = 0

watch(
  () => [internalProjectId.value, debouncedMockName.value] as const,
  async ([projId, nameQuery]) => {
    const seq = ++nameCheckSeq
    const trimmedProjId = (projId ?? '').trim()
    const trimmedName = (nameQuery ?? '').trim()
    // Skip the probe for empty inputs — the required-field error already
    // covers the empty-name case and there's nothing to collide with.
    if (!trimmedProjId || !trimmedName) {
      nameExists.value = false
      return
    }
    try {
      const exists = await checkMockNameExists(trimmedProjId, trimmedName)
      if (seq !== nameCheckSeq) return // stale — a newer call is in flight
      // In edit mode, the name the user sees on entry belongs to the mock
      // they're already editing — don't warn them about themselves.
      if (exists && isEditMode.value && props.mock) {
        nameExists.value = (props.mock.name ?? '') !== trimmedName
      } else {
        nameExists.value = exists
      }
    } catch {
      if (seq !== nameCheckSeq) return
      // Soft check — never let a backend hiccup pollute the form.
      nameExists.value = false
    }
  },
)

// Edit-mode bootstrap: when the form is populated from an existing mock the
// stored URL already includes the `/{slug}` prefix (because we add it on
// save). The user-typed portion shown in the input is the part *after* the
// prefix, so we strip it once both pieces are available. Guarded by a flag
// so the strip only runs for the initial load — after that, the user is in
// charge of `urlValue` and re-triggering would clobber their edits.
const initialUrlSlugStripped = ref(false)
watch(
  () => [projectSlug.value, urlValue.value] as const,
  ([slug, current]) => {
    if (!isEditMode.value || initialUrlSlugStripped.value) return
    if (!slug || !current) return
    const prefix = `/${slug}`
    if (current === prefix) {
      urlValue.value = '/'
      initialUrlSlugStripped.value = true
    } else if (current.startsWith(prefix + '/')) {
      urlValue.value = current.slice(prefix.length)
      initialUrlSlugStripped.value = true
    } else {
      // Stored URL doesn't start with the current slug (legacy mock or
      // project rename). Leave it untouched and don't try again.
      initialUrlSlugStripped.value = true
    }
  },
)
const requestHeaders = ref<MatcherRow[]>([])
const queryParams = ref<MatcherRow[]>([])
const bodyPatterns = ref<BodyPatternRow[]>([])

// ── Form state: Cookies ───────────────────────────────────────
// A single list holds both request-side matchers and response-side
// `Set-Cookie` rows — each row's `direction` field distinguishes them.
// The Cookies section exposes two "+ Add" buttons (one per direction) so
// the user picks the role at the moment of creation rather than editing a
// toggle afterwards.
const cookies = ref<CookieRow[]>([])
function addCookieRow(direction: CookieRow['direction']) {
  cookies.value.push(createCookieRow({ direction }))
}

// ── Form state: Basic Auth ────────────────────────────────────
const enableBasicAuth = ref(false)
const basicAuthUsername = ref('')
const basicAuthPassword = ref('')
// Two ways to match Basic Auth on a WireMock stub:
//   'plain'  → `request.basicAuthCredentials = { username, password }`.
//              WireMock decodes the incoming Authorization header and
//              compares against the literal values we store.
//   'base64' → `request.headers.Authorization.equalTo = "Basic <b64>"`.
//              A straight header-matcher with the encoded value; handy
//              when you want to see the exact Authorization header
//              value the stub expects.
// Same username/password flow populates both — we only differ in how
// the payload is shaped on submit.
const basicAuthMode = ref<'plain' | 'base64'>('plain')

// ── Form state: Response ──────────────────────────────────────
const responseStatus = ref(200)
// Status Message is editable as a free-text field, but defaults to the
// canonical reason phrase for the selected Status Code (e.g. 201 →
// "Created"). The two-way binding lives in `responseStatusMessage`
// below: blank → fall back to the canonical phrase; otherwise, ship the
// user-typed value. The "Reset" button next to the input clears any
// override so the canonical phrase comes back.
const responseStatusMessageOverride = ref<string | null>(null)
const canonicalStatusMessage = computed(() => {
  const entry = HTTP_STATUS_CODES.find(s => s.value === responseStatus.value)
  if (!entry) return ''
  return entry.label.replace(/^\d+\s*/, '')
})
const responseStatusMessage = computed<string>({
  get: () => responseStatusMessageOverride.value ?? canonicalStatusMessage.value,
  set: (v: string) => {
    // Treat an empty value or one matching the canonical phrase as
    // "no override", so the field re-tracks the status code dropdown.
    if (!v.trim() || v.trim() === canonicalStatusMessage.value) {
      responseStatusMessageOverride.value = null
    } else {
      responseStatusMessageOverride.value = v
    }
  },
})
const isStatusMessageOverridden = computed(() => responseStatusMessageOverride.value !== null)

// Response transformers — WireMock extension names that mutate the
// outgoing response (e.g. `response-template` for Handlebars). Users add
// names through chips; `transformerParameters` is a free-form JSON map
// passed verbatim to whatever extension consumes them.
const responseTransformers = ref<string[]>([])
const responseTransformerInput = ref('')
const responseTransformerParametersJson = ref('')

// Common transformers available out of the box / via popular extensions.
// Used as quick-add chips alongside the free-text input — clicking a
// chip appends the name without typing.
const COMMON_TRANSFORMERS = [
  'response-template',
  'webhook-template',
  'wiremock-body-transformer',
] as const

function addResponseTransformerValue(name: string) {
  const trimmed = name.trim()
  if (!trimmed) return
  // Case-sensitive uniqueness — WireMock extension names are
  // case-sensitive, so "ResponseTemplate" and "response-template" would
  // be different lookups.
  if (responseTransformers.value.includes(trimmed)) return
  responseTransformers.value.push(trimmed)
}
function addResponseTransformer() {
  if (responseTransformerInput.value.trim()) {
    addResponseTransformerValue(responseTransformerInput.value)
    responseTransformerInput.value = ''
  }
}
function removeResponseTransformer(index: number) {
  responseTransformers.value.splice(index, 1)
}

// Validate the transformer-parameters JSON live so we can flag a typo
// before the user clicks Save. Empty input is valid (it just means "no
// parameters").
const transformerParametersError = computed<string>(() => {
  const v = responseTransformerParametersJson.value.trim()
  if (!v) return ''
  try {
    const parsed = JSON.parse(v)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return 'transformerParameters must be a JSON object (e.g. { "key": "value" }).'
    }
    return ''
  } catch (e) {
    return `Invalid JSON: ${e instanceof Error ? e.message : 'parse error'}`
  }
})

// Response-headers grid: each row is a single key/value pair. WireMock
// can technically emit a header multiple times on the wire (Vary, Link
// etc.) by passing a string[], but the form keeps it to one value per
// row to stay simple — users that genuinely need duplicates can add
// another row with the same key.
const responseHeaders = ref<KeyValuePair[]>([{ key: 'Content-Type', value: 'application/json' }])
// The on-wire body shape (jsonBody vs body vs base64Body) is derived from
// the response's Content-Type header instead of being a separate user
// choice — see `responseBodyFormat` below. Users rarely want the body
// payload type to disagree with the Content-Type they advertise, and in
// the old form they'd silently end up with e.g. `body: "<xml/>"` served
// with `Content-Type: application/json`.
const responseBody = ref('')
// Echo of the most recently uploaded file (filename + size in bytes) for
// the base64 upload helper. Declared up here so `populateFromMock` (run
// from an immediate watcher) can clear them without hitting the TDZ —
// the upload handler functions live further down with the other
// computeds.
const uploadedFileName = ref<string>('')
const uploadedFileSize = ref<number>(0)
const responseBodyFileError = ref<string>('')

// ── Form state: Delay ─────────────────────────────────────────
const enableDelay = ref(false)
const delayType = ref<'uniform' | 'lognormal' | 'fixed'>('uniform')
const delayLower = ref(50)
const delayUpper = ref(100)
const delayMedian = ref(100)
const delaySigma = ref(0.1)
const delayFixed = ref(100)

// ── Form state: Chunked Dribble Delay ─────────────────────────
const enableChunkedDribble = ref(false)
const chunkedNumberOfChunks = ref(5)
const chunkedTotalDuration = ref(1000)

// ── Form state: Fault Simulation ──────────────────────────────
const enableFault = ref(false)
const faultType = ref<'EMPTY_RESPONSE' | 'MALFORMED_RESPONSE_CHUNK' | 'RANDOM_DATA_THEN_CLOSE' | 'CONNECTION_RESET_BY_PEER'>('EMPTY_RESPONSE')

// ── Form state: Proxying ──────────────────────────────────────
const enableProxy = ref(false)
const proxyBaseUrl = ref('')
const additionalProxyHeaders = ref<ProxyHeaderRow[]>([])
const removeProxyHeaders = ref<string[]>([])
const removeProxyHeaderInput = ref('')

// ── Form state: Webhooks ──────────────────────────────────────
const enableWebhook = ref(false)
const webhookUrl = ref('')
const webhookMethod = ref('POST')
const webhookHeaders = ref<KeyValuePair[]>([])
const webhookBody = ref('')
const webhookDelayMs = ref(0)

// ── UI state ──────────────────────────────────────────────────
const isSubmitting = ref(false)
// Toast is rendered as `toastMode` in this component's template; alias from useToast.
const { showToast, toastMessage, toastType: toastMode, showToastMessage } = useToast()
// Tracks whether the user has attempted to submit at least once. Before the
// first submit attempt the errors panel still shows live (for guidance) but
// the submit button does not flash red; after a failed submit it does.
const submitAttempted = ref(false)

// ── WireMock stub status (edit mode only) ──────────────────────
// Mirrors the per-card behavior on ProjectDetailView: probe the admin
// port for the matching stub, and if it's missing surface a "click to
// create" action in the right aside so the user can re-install the stub
// from this mock without leaving the edit page.
//   'idle'     — not yet probed / not in edit mode
//   'loading'  — probe in flight
//   'exists'   — stub is present on WireMock
//   'missing'  — stub is absent, user can re-push
//   'creating' — re-push in flight
//   'error'    — probe failed (admin port unreachable); treated as
//                neutral so we don't mislabel a down admin as missing
type StubStatus = 'idle' | 'loading' | 'exists' | 'missing' | 'creating' | 'error'
const stubStatus = ref<StubStatus>('idle')
const stubErrorMsg = ref('')

async function refreshStubStatus() {
  if (!props.mock) return
  const id = props.mock.id
  stubStatus.value = 'loading'
  stubErrorMsg.value = ''
  try {
    const exists = await checkStubExists(id)
    stubStatus.value = exists ? 'exists' : 'missing'
  } catch (e) {
    stubStatus.value = 'error'
    stubErrorMsg.value = e instanceof Error ? e.message : 'Probe failed'
  }
}

// Re-install the stub on WireMock from the mock we were opened with.
// Uses the SAME flow as ProjectDetailView — POST /api/mocks/{id}/republish
// so the backend re-pushes the stored mock to WireMock under the existing
// id. We deliberately don't send the live form values: the form may
// contain unsaved edits, but "click to create" is about restoring the
// stored mock to WireMock, not about persisting unsaved form changes.
async function handleCreateStub() {
  if (!props.mock) return
  const m = props.mock
  stubStatus.value = 'creating'
  stubErrorMsg.value = ''
  try {
    await mockApi.republish(m.id)
    stubStatus.value = 'exists'
    showToastMessage(`Stub created for '${m.name}'`, BaseToastEnum.SUCCESS, 4000)
  } catch (e) {
    // Flip back to 'missing' so the user can retry.
    stubStatus.value = 'missing'
    stubErrorMsg.value = e instanceof Error ? e.message : 'Failed to create stub'
    showToastMessage(
      e instanceof Error ? `Failed to create stub: ${e.message}` : 'Failed to create stub',
      BaseToastEnum.ERROR,
    )
  }
}

// Kick off the probe whenever the mock prop lands. `immediate: true`
// covers the case where EditMockView has already resolved the mock by
// the time CreateMock mounts; the watcher also fires if the parent
// swaps in a different mock later.
watch(
  () => props.mock?.id,
  (id) => {
    if (id) {
      refreshStubStatus()
    } else {
      stubStatus.value = 'idle'
    }
  },
  { immediate: true },
)

const HTTP_METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS', 'TRACE', 'ANY'] as const

const MATCHER_TYPES = [
  { value: 'equalTo', label: 'Equal To' },
  { value: 'matches', label: 'Regex' },
  { value: 'doesNotMatch', label: 'Not Regex' },
  { value: 'contains', label: 'Contains' },
  { value: 'absent', label: 'Absent' },
  { value: 'before', label: 'Before (date)' },
  { value: 'after', label: 'After (date)' },
  { value: 'equalToDateTime', label: 'Equal DateTime' },
] as const

// Body-pattern matcher kinds we expose in the form. Trimmed to the
// canonical WireMock body matchers — JSON Schema, the date-time
// matchers, and both regex matchers (`matches` / `doesNotMatch`) were
// removed per product spec; they're rare for body patterns or already
// covered by other matcher kinds.
const BODY_MATCHER_TYPES = [
  { value: 'equalTo', label: 'Equal To (string)' },
  { value: 'contains', label: 'Contains' },
  { value: 'equalToJson', label: 'Equal To JSON' },
  { value: 'matchesJsonPath', label: 'JSONPath' },
  { value: 'equalToXml', label: 'Equal To XML' },
  { value: 'matchesXPath', label: 'XPath' },
  { value: 'binaryEqualTo', label: 'Binary (base64)' },
  { value: 'absent', label: 'Absent' },
] as const

// Body matcher kinds that honour the DateTime truncate/offset options.
const DATETIME_BODY_MATCHERS = new Set(['before', 'after', 'equalToDateTime'])
// Body matcher kinds that honour the xPathNamespaces fieldset.
const XML_NAMESPACE_BODY_MATCHERS = new Set(['matchesXPath', 'equalToXml'])
// Matcher kinds (in any context — query / header / cookie / body) that
// represent a date-time comparison and so can be authored via a calendar
// picker. WireMock also accepts the relative shorthand (`now +1 days`)
// which the user can type directly into the value field — the picker is
// just a convenience for picking absolute timestamps.
const DATETIME_MATCHERS = new Set(['before', 'after', 'equalToDateTime'])

/**
 * Pull out the `YYYY-MM-DDTHH:mm` (and optional `:ss`) portion of an ISO-8601
 * string so it can be bound to a native `<input type="datetime-local">`.
 * Returns '' when the value isn't a parseable ISO timestamp (e.g. it's a
 * `now +1 days` shorthand) — the picker then renders empty without
 * clobbering the user's text. */
function dateTimePickerValue(value: string | undefined): string {
  if (!value) return ''
  const m = value.match(/^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?)/)
  return m ? m[1] : ''
}

/**
 * Convert a `<input type="datetime-local">` value (e.g. `2024-01-01T12:30`)
 * into a WireMock-compatible ISO-8601 timestamp. We treat the picker's
 * value as UTC and append `Z` — users who need a specific local offset can
 * still hand-edit the text input afterwards. Empty pick clears the value. */
function setDateTimeFromPicker(row: { value: string }, picked: string): void {
  if (!picked) {
    row.value = ''
    return
  }
  const withSeconds = picked.length === 16 ? `${picked}:00` : picked
  row.value = `${withSeconds}Z`
}

// WireMock's supported truncation tokens for date-time matchers.
const TRUNCATE_OPTIONS = [
  { value: '', label: '(none)' },
  { value: 'first millisecond of second', label: 'first millisecond of second' },
  { value: 'first second of minute', label: 'first second of minute' },
  { value: 'first minute of hour', label: 'first minute of hour' },
  { value: 'first hour of day', label: 'first hour of day' },
  { value: 'first day of month', label: 'first day of month' },
  { value: 'first day of next month', label: 'first day of next month' },
  { value: 'first day of year', label: 'first day of year' },
  { value: 'first day of next year', label: 'first day of next year' },
] as const

// Units accepted on `expectedOffsetUnit`. Mirrors java.time.temporal.ChronoUnit.
const OFFSET_UNITS = [
  { value: '', label: '(unit)' },
  { value: 'SECONDS', label: 'seconds' },
  { value: 'MINUTES', label: 'minutes' },
  { value: 'HOURS', label: 'hours' },
  { value: 'DAYS', label: 'days' },
  { value: 'MONTHS', label: 'months' },
  { value: 'YEARS', label: 'years' },
] as const

// JSON Schema versions WireMock recognises (mirrors EverIt-org/json-schema).
const JSON_SCHEMA_VERSIONS = [
  { value: '', label: '(default — Draft-07)' },
  { value: 'V4', label: 'Draft 4' },
  { value: 'V6', label: 'Draft 6' },
  { value: 'V7', label: 'Draft 7' },
  { value: 'V201909', label: 'Draft 2019-09' },
  { value: 'V202012', label: 'Draft 2020-12' },
] as const

const REGEX_EXAMPLES: Record<string, { examples: string[]; tip: string }> = {
  matches: {
    examples: ['.*\\.json$', '^[A-Z]{2,4}$', '\\d{3}-\\d{4}', 'Bearer\\s+.+'],
    tip: 'Java-style regex. Must match the entire value.',
  },
  doesNotMatch: {
    examples: ['.*test.*', 'internal-.*', '\\d+'],
    tip: 'Matches when the regex does NOT match the value.',
  },
  urlPattern: {
    examples: ['/api/v[0-9]+/users/.*', '.*/orders/\\d+\\?.*', '/files/.*\\.pdf'],
    tip: 'Full URL regex including query string.',
  },
  urlPathPattern: {
    examples: ['/api/v[0-9]+/users/[a-f0-9-]+', '/items/\\d+', '/files/.*\\.json'],
    tip: 'Path-only regex (no query string).',
  },
  matchesJsonPath: {
    examples: ['$.name', '$.items[0].id', '$.users[?(@.age > 18)]', '$..status'],
    tip: 'JSONPath expression to match against the request body.',
  },
  matchesXPath: {
    examples: ['//user/name', '/root/items/item[@id="1"]', '//order[status="active"]'],
    tip: 'XPath expression to match against XML body.',
  },
}

function getRegexExamples(matcherType: string): { examples: string[]; tip: string } | null {
  return REGEX_EXAMPLES[matcherType] ?? null
}

const FAULT_TYPES = [
  {
    value: 'EMPTY_RESPONSE',
    label: 'Empty Response',
    description: 'Accept the request but return an empty response (0 bytes) and close the connection. Simulates a server that accepts then drops.',
  },
  {
    value: 'MALFORMED_RESPONSE_CHUNK',
    label: 'Malformed Response Chunk',
    description: 'Send an HTTP status line, then garbled binary data, then close the connection. Simulates a server returning corrupted data.',
  },
  {
    value: 'RANDOM_DATA_THEN_CLOSE',
    label: 'Random Data Then Close',
    description: 'Send random garbage bytes instead of a valid HTTP response, then close the connection. Simulates a non-HTTP server on the port.',
  },
  {
    value: 'CONNECTION_RESET_BY_PEER',
    label: 'Connection Reset by Peer',
    description: 'Immediately reset the TCP connection (RST). Simulates a server that abruptly drops before sending any response.',
  },
] as const

// Common HTTP status codes grouped by class. Covers 1xx–5xx ranges
// typically seen in mock scenarios; keep list focused so the dropdown is usable.
const HTTP_STATUS_CODES = [
  { value: 100, label: '100 Continue' },
  { value: 101, label: '101 Switching Protocols' },
  { value: 200, label: '200 OK' },
  { value: 201, label: '201 Created' },
  { value: 202, label: '202 Accepted' },
  { value: 204, label: '204 No Content' },
  { value: 206, label: '206 Partial Content' },
  { value: 301, label: '301 Moved Permanently' },
  { value: 302, label: '302 Found' },
  { value: 303, label: '303 See Other' },
  { value: 304, label: '304 Not Modified' },
  { value: 307, label: '307 Temporary Redirect' },
  { value: 308, label: '308 Permanent Redirect' },
  { value: 400, label: '400 Bad Request' },
  { value: 401, label: '401 Unauthorized' },
  { value: 403, label: '403 Forbidden' },
  { value: 404, label: '404 Not Found' },
  { value: 405, label: '405 Method Not Allowed' },
  { value: 406, label: '406 Not Acceptable' },
  { value: 408, label: '408 Request Timeout' },
  { value: 409, label: '409 Conflict' },
  { value: 410, label: '410 Gone' },
  { value: 415, label: '415 Unsupported Media Type' },
  { value: 418, label: "418 I'm a teapot" },
  { value: 422, label: '422 Unprocessable Entity' },
  { value: 429, label: '429 Too Many Requests' },
  { value: 500, label: '500 Internal Server Error' },
  { value: 501, label: '501 Not Implemented' },
  { value: 502, label: '502 Bad Gateway' },
  { value: 503, label: '503 Service Unavailable' },
  { value: 504, label: '504 Gateway Timeout' },
  { value: 505, label: '505 HTTP Version Not Supported' },
] as const

// Common Content-Type values shown in the response-header dropdown when the
// header key is exactly `Content-Type` (case-insensitive).
const COMMON_RESPONSE_HEADERS = [
  'Content-Type',
  'Content-Length',
  'Content-Encoding',
  'Content-Disposition',
  'Content-Language',
  'Cache-Control',
  'ETag',
  'Expires',
  'Last-Modified',
  'Pragma',
  'Accept-Ranges',
  'Access-Control-Allow-Origin',
  'Access-Control-Allow-Methods',
  'Access-Control-Allow-Headers',
  'Access-Control-Allow-Credentials',
  'Access-Control-Expose-Headers',
  'Access-Control-Max-Age',
  'Authorization',
  'Location',
  'Retry-After',
  'Server',
  'Set-Cookie',
  'Strict-Transport-Security',
  'Transfer-Encoding',
  'Vary',
  'WWW-Authenticate',
  'X-Content-Type-Options',
  'X-Frame-Options',
  'X-Request-Id',
  'X-Correlation-Id',
  'X-RateLimit-Limit',
  'X-RateLimit-Remaining',
  'X-RateLimit-Reset',
] as const

const CONTENT_TYPES = [
  'application/json',
  'application/xml',
  'application/x-www-form-urlencoded',
  'application/octet-stream',
  'application/pdf',
  'application/javascript',
  'application/zip',
  'multipart/form-data',
  'text/plain',
  'text/html',
  'text/css',
  'text/csv',
  'text/xml',
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/svg+xml',
  'image/webp',
] as const

const DELAY_TYPE_DESCRIPTIONS: Record<'uniform' | 'lognormal' | 'fixed', string> = {
  uniform: 'Random delay drawn uniformly between a lower and upper bound — every value in the range is equally likely. Use to simulate variable latency.',
  lognormal: 'Log-normally distributed delay with a median and sigma — produces a long tail where most responses cluster near the median but some are much slower. Closest to real-world network latency.',
  fixed: 'Every response is delayed by exactly the same number of milliseconds. Use for deterministic timing tests.',
}

// ── Helper: extract matcher type from a StringMatcher object ──
// NOTE: Declared BEFORE the populate watcher below because the watcher runs
// with `immediate: true`, which executes during setup. If STRING_MATCHER_KEYS
// were declared later, the immediate-fire path (edit mode, mock already loaded)
// would hit a TDZ ReferenceError.
const STRING_MATCHER_KEYS = [
  'equalTo', 'matches', 'doesNotMatch', 'contains',
  'before', 'after', 'equalToDateTime',
  'equalToJson', 'matchesJsonPath',
  'equalToXml', 'matchesXPath', 'binaryEqualTo',
] as const satisfies readonly (keyof StringMatcher)[]

function extractMatcher(val: StringMatcher): { matcherType: string; matcherValue: string; caseInsensitive: boolean } {
  const caseInsensitive = val.caseInsensitive === true
  for (const type of STRING_MATCHER_KEYS) {
    const v = val[type]
    if (v !== undefined) {
      return { matcherType: type, matcherValue: typeof v === 'string' ? v : JSON.stringify(v), caseInsensitive }
    }
  }
  if (val.absent === true) return { matcherType: 'absent', matcherValue: 'true', caseInsensitive }
  return { matcherType: 'equalTo', matcherValue: '', caseInsensitive }
}

// ── Basic Auth base64 helpers ─────────────────────────────────
// Safe UTF-8 ⇄ base64 round-trip. Plain `btoa(`${u}:${p}`)` throws on any
// non-Latin-1 character (e.g. an é in a username), so we encode the
// username:password pair through TextEncoder first, then base64 the bytes.
// `decodeBasicAuth` mirrors the inverse path and is tolerant of malformed
// input — it returns null when the value can't be decoded so callers can
// fall back gracefully (e.g. leave the form empty, ask the user to re-enter).
function encodeBasicAuth(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary)
}

function decodeBasicAuth(b64: string): { username: string; password: string } | null {
  try {
    const binary = atob(b64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    const decoded = new TextDecoder().decode(bytes)
    const idx = decoded.indexOf(':')
    if (idx === -1) return null
    return { username: decoded.slice(0, idx), password: decoded.slice(idx + 1) }
  } catch {
    return null
  }
}

// Live preview value for the base64 mode — the literal string that will
// land in `request.headers.Authorization.equalTo`. Computed reactively so
// the user sees the encoded value update as they type. Empty when either
// field is blank to avoid showing "Basic Og==" (the encoding of just `:`).
const basicAuthBase64Preview = computed(() => {
  if (!basicAuthUsername.value && !basicAuthPassword.value) return ''
  return `Basic ${encodeBasicAuth(basicAuthUsername.value, basicAuthPassword.value)}`
})

// ── Populate from existing mock (edit mode) ───────────────────
// Watch the prop so the form resyncs if the parent swaps in a different mock
// (e.g. EditMockView loads the mock asynchronously and passes it in later).
watch(
  () => props.mock,
  (m) => {
    if (m) {
      populateFromMock(m)
    } else {
      // Default metadata: system-managed locked rows (project + generatedBy).
      // Users cannot delete or edit these — they're shipped on every mock.
      if (internalProjectId.value) {
        metadataRows.value = buildLockedMetadataRows(internalProjectId.value)
      } else {
        // No project yet → still seed `generatedBy` so it's always present.
        metadataRows.value = [{ key: 'generatedBy', value: 'wiremate', valueType: 'text', locked: true }]
      }
    }
  },
  { immediate: true },
)

function populateFromMock(m: MockResponse) {
  mockName.value = m.name
  description.value = m.description ?? ''
  internalProjectId.value = m.projectId
  // Allow the slug-strip watcher to run for this freshly-loaded mock.
  initialUrlSlugStripped.value = false
  persistent.value = m.persistent
  // When editing, only pre-fill priority if the mock actually has one set —
  // leave the field blank otherwise so the user isn't surprised by an
  // auto-assigned default they didn't choose.
  priority.value = m.priority
  // Top-level optional fields. WireMock omits these when unset; mirror
  // that on the form so blank inputs don't suddenly look "filled" after
  // a round-trip.
  scenarioName.value = m.scenarioName ?? ''
  requiredScenarioState.value = m.requiredScenarioState ?? ''
  newScenarioState.value = m.newScenarioState ?? ''

  // Metadata
  if (m.metadata && Object.keys(m.metadata).length > 0) {
    const rows: MetadataRow[] = Object.entries(m.metadata)
      // The cookie-attributes blob is system-managed — it's surfaced and
      // edited through the Cookies section, not the raw metadata grid.
      .filter(([key]) => key !== COOKIE_ATTRS_METADATA_KEY)
      // Drop any legacy `project` key — we render it as `projectId` below
      // (see the unshift). Without this filter, a mock saved under the old
      // schema would render with both `project` AND `projectId` rows.
      .filter(([key]) => key !== 'project')
      .map(([key, value]) => {
        const isObject = typeof value === 'object' && value !== null
        const isLocked = LOCKED_METADATA_KEYS.has(key)
        // For `generatedBy` we always force the canonical value, regardless of
        // whatever the persisted mock happened to carry — the user can't
        // tamper with this field, so neither can stale data.
        const canonicalValue = key === 'generatedBy'
          ? 'wiremate'
          : (typeof value === 'string' ? value : JSON.stringify(value, null, 2))
        return {
          key,
          value: canonicalValue,
          valueType: key === 'generatedBy' ? 'text' as const : (isObject ? 'json' as const : 'text' as const),
          locked: isLocked,
        }
      })
    // Guarantee the projectId row is present. The mock's owning project
    // is authoritative — we use `m.projectId`, not whatever may (or may
    // not) have been persisted in the metadata blob — so legacy mocks
    // stored under the old `project` key still get the canonical
    // `projectId` row here.
    if (!rows.some(r => r.key === 'projectId')) {
      rows.unshift({ key: 'projectId', value: m.projectId, valueType: 'text', locked: true })
    }
    // Guarantee `generatedBy` is present even if the stored mock predates it.
    if (!rows.some(r => r.key === 'generatedBy')) {
      rows.push({ key: 'generatedBy', value: 'wiremate', valueType: 'text', locked: true })
    }
    metadataRows.value = rows
    collapsedSections.value.metadata = false
  } else {
    // Ensure system-managed metadata always exists in edit mode
    metadataRows.value = buildLockedMetadataRows(m.projectId)
  }

  // Request - Method
  httpMethod.value = m.request.method

  // Request - URL
  if (m.request.url) {
    urlMatchType.value = 'url'
    urlValue.value = m.request.url
  } else if (m.request.urlPath) {
    urlMatchType.value = 'urlPath'
    urlValue.value = m.request.urlPath
  } else if (m.request.urlPattern) {
    urlMatchType.value = 'urlPattern'
    urlValue.value = m.request.urlPattern
  } else if (m.request.urlPathPattern) {
    urlMatchType.value = 'urlPathPattern'
    urlValue.value = m.request.urlPathPattern
  }

  // Request - Headers
  if (m.request.headers) {
    requestHeaders.value = Object.entries(m.request.headers).map(([key, val]) => {
      const { matcherType, matcherValue, caseInsensitive } = extractMatcher(val)
      return { key, matcherType, value: matcherValue, caseInsensitive }
    })
  }

  // Request - Cookies
  // Hydrate request-side cookies into CookieRow entries with
  // direction='request'. The response-side rows are added later from the
  // Set-Cookie parsing below; both groups live together in `cookies.value`.
  if (m.request.cookies && Object.keys(m.request.cookies).length > 0) {
    const requestCookieRows: CookieRow[] = []
    for (const [key, val] of Object.entries(m.request.cookies)) {
      const { matcherType, matcherValue, caseInsensitive } = extractMatcher(val)
      requestCookieRows.push(createCookieRow({
        direction: 'request',
        key,
        matcherType,
        value: matcherValue,
        caseInsensitive,
      }))
    }
    cookies.value = requestCookieRows
    collapsedSections.value.cookies = false
  }

  // Request - Query parameters
  if (m.request.queryParameters) {
    queryParams.value = Object.entries(m.request.queryParameters).map(([key, val]) => {
      const { matcherType, matcherValue, caseInsensitive } = extractMatcher(val)
      return { key, matcherType, value: matcherValue, caseInsensitive }
    })
  }

  // Request - Body patterns
  if (m.request.bodyPatterns && m.request.bodyPatterns.length > 0) {
    const BODY_MATCHER_KEYS = [
      'equalToJson', 'matchesJsonPath', 'matchesJsonSchema', 'equalTo', 'contains',
      'matches', 'doesNotMatch', 'equalToXml', 'matchesXPath', 'binaryEqualTo',
      'before', 'after', 'equalToDateTime',
    ] as const satisfies readonly (keyof BodyPattern)[]

    bodyPatterns.value = m.request.bodyPatterns.map((bp) => {
      const row: BodyPatternRow = {
        matcherType: 'equalToJson',
        value: '',
        ignoreArrayOrder: false,
        ignoreExtraElements: false,
      }
      for (const type of BODY_MATCHER_KEYS) {
        const val = bp[type]
        if (val !== undefined) {
          row.matcherType = type
          row.value = typeof val === 'string' ? val : JSON.stringify(val, null, 2)
          break
        }
      }
      if (bp.absent === true) {
        row.matcherType = 'absent'
        row.value = 'true'
      }
      row.ignoreArrayOrder = bp.ignoreArrayOrder ?? false
      row.ignoreExtraElements = bp.ignoreExtraElements ?? false

      // Hydrate advanced per-matcher fields. Each branch reads from the
      // raw BodyPattern shape — matchesJsonPath can come back as either a
      // bare expression string or the object form
      // `{ expression, varyBy?, equalTo?|contains?|matches?|… }`. When
      // we see a sub-matcher key we hoist it onto the row so the UI
      // dropdown reflects it and the round-trip preserves the shape.
      if (row.matcherType === 'matchesJsonPath') {
        const raw = bp.matchesJsonPath
        if (raw && typeof raw === 'object') {
          const obj = raw as Record<string, unknown>
          row.value = String(obj.expression ?? '')
          const varyBy = obj['vary-by'] ?? obj.varyBy
          if (typeof varyBy === 'string') row.varyBy = varyBy
          // Detect any of the known sub-matcher kinds. We pull only the
          // first one we recognise — WireMock's wire shape never carries
          // two competing sub-matchers in the same object.
          const subKinds = ['equalTo', 'contains', 'matches', 'doesNotMatch', 'equalToJson', 'equalToXml', 'absent']
          for (const kind of subKinds) {
            if (kind in obj) {
              row.jsonPathSubMatcher = kind
              const sv = obj[kind]
              if (kind === 'absent') {
                row.jsonPathSubMatcherValue = ''
              } else if (typeof sv === 'string') {
                row.jsonPathSubMatcherValue = sv
              } else {
                // equalToJson can come in as a parsed object; stringify
                // so the textarea shows the schema/document.
                row.jsonPathSubMatcherValue = JSON.stringify(sv)
              }
              break
            }
          }
        }
      }
      if (row.matcherType === 'matchesJsonSchema') {
        if (bp.schemaVersion) row.schemaVersion = bp.schemaVersion
      }
      if (row.matcherType === 'matchesXPath') {
        const raw = bp.matchesXPath
        if (raw && typeof raw === 'object') {
          row.value = String((raw as { expression?: unknown }).expression ?? '')
        }
      }
      if (XML_NAMESPACE_BODY_MATCHERS.has(row.matcherType) && bp.xPathNamespaces) {
        row.xPathNamespaces = Object.entries(bp.xPathNamespaces)
          .map(([prefix, uri]) => ({ prefix, uri: String(uri) }))
      }
      if (row.matcherType === 'equalToXml' && bp.enablePlaceholders) {
        row.enablePlaceholders = true
      }
      if (DATETIME_BODY_MATCHERS.has(row.matcherType)) {
        if (typeof bp.truncateExpected === 'string') row.truncateExpected = bp.truncateExpected
        if (typeof bp.truncateActual === 'string') row.truncateActual = bp.truncateActual
        if (typeof bp.expectedOffset === 'number') row.expectedOffset = bp.expectedOffset
        if (typeof bp.expectedOffsetUnit === 'string') row.expectedOffsetUnit = bp.expectedOffsetUnit
      }
      return row
    })
  }

  // Request - Basic Auth
  // Two shapes are possible on a stored mock:
  //   1) `basicAuthCredentials: { username, password }` — populated by the
  //      "plain" mode of this form, decoded by WireMock from the incoming
  //      Authorization header.
  //   2) `headers.Authorization.equalTo = "Basic <b64>"` — populated by the
  //      "base64" mode (or hand-edited stubs from a curl example). We need
  //      to detect this AFTER the headers loop has run so we can both decode
  //      the credentials AND remove the Authorization row from
  //      `requestHeaders` — leaving it there would double-emit the matcher
  //      on save (once via headers, once via the basic-auth section).
  if (m.request.basicAuthCredentials) {
    enableBasicAuth.value = true
    basicAuthMode.value = 'plain'
    basicAuthUsername.value = m.request.basicAuthCredentials.username
    basicAuthPassword.value = m.request.basicAuthCredentials.password
    collapsedSections.value.basicAuth = false
  } else {
    // Find the Authorization row, case-insensitively (HTTP header names are
    // case-insensitive; users may have typed `authorization`).
    const authIdx = requestHeaders.value.findIndex(h => h.key.trim().toLowerCase() === 'authorization')
    if (authIdx !== -1) {
      const authRow = requestHeaders.value[authIdx]
      // Only the equalTo matcher carries a literal value we can decode.
      // Regex/contains matchers stay as plain header rows.
      if (authRow.matcherType === 'equalTo' && authRow.value.startsWith('Basic ')) {
        const decoded = decodeBasicAuth(authRow.value.slice('Basic '.length).trim())
        if (decoded) {
          enableBasicAuth.value = true
          basicAuthMode.value = 'base64'
          basicAuthUsername.value = decoded.username
          basicAuthPassword.value = decoded.password
          requestHeaders.value.splice(authIdx, 1)
          collapsedSections.value.basicAuth = false
        }
      }
    }
  }

  // Response
  responseStatus.value = m.response.status
  // Honor a stored custom reason phrase if the user (or another tool)
  // set one explicitly. We compare against the canonical phrase for the
  // status code and only treat it as an override when it differs — that
  // way round-tripping a stub that just had `200 OK` doesn't leave the
  // override flag flipped on.
  if (typeof m.response.statusMessage === 'string' && m.response.statusMessage) {
    const canonical = HTTP_STATUS_CODES.find(s => s.value === m.response.status)?.label.replace(/^\d+\s*/, '') ?? ''
    responseStatusMessageOverride.value = m.response.statusMessage === canonical ? null : m.response.statusMessage
  } else {
    responseStatusMessageOverride.value = null
  }

  // Response transformers — array of extension names plus a free-form
  // parameter map. Hydrate both into form state; serialize the params
  // map back to a pretty-printed JSON string for editing.
  responseTransformers.value = Array.isArray(m.response.transformers)
    ? [...m.response.transformers]
    : []
  responseTransformerInput.value = ''
  if (m.response.transformerParameters && typeof m.response.transformerParameters === 'object') {
    try {
      responseTransformerParametersJson.value = JSON.stringify(m.response.transformerParameters, null, 2)
    } catch {
      responseTransformerParametersJson.value = ''
    }
  } else {
    responseTransformerParametersJson.value = ''
  }

  if (m.response.headers) {
    // Parse `Set-Cookie` entries out of the generic headers grid and hydrate
    // them into the Cookies section. The same header can be serialized as
    // either a single string or a string[] (WireMock accepts both); support
    // both shapes so we don't lose cookies when editing.
    const parsedCookies: CookieRow[] = []
    const remainingHeaders: KeyValuePair[] = []
    for (const [key, value] of Object.entries(m.response.headers)) {
      if (key.toLowerCase() === 'set-cookie') {
        const rawValues = Array.isArray(value) ? value : [value]
        for (const raw of rawValues) {
          const parsed = parseSetCookieString(String(raw))
          // parseSetCookieString seeds via createCookieRow, which defaults
          // direction='response' — exactly what we want for Set-Cookie.
          if (parsed) parsedCookies.push(parsed)
        }
        continue
      }
      // Non-cookie headers: a string[] (WireMock's multi-value form)
      // is split into one row per value so each can be edited
      // individually. Single-value headers stay as a plain row.
      if (Array.isArray(value)) {
        for (const v of value) {
          remainingHeaders.push({ key, value: String(v) })
        }
      } else {
        remainingHeaders.push({ key, value: String(value) })
      }
    }
    responseHeaders.value = remainingHeaders
    if (parsedCookies.length > 0) {
      // Append response cookies to whatever request-side rows the earlier
      // pass already pushed into `cookies.value`.
      cookies.value = [...cookies.value, ...parsedCookies]
      collapsedSections.value.cookies = false
    }
  }

  // The body type is now inferred from Content-Type, so we only seed the
  // textarea contents.
  // Reset any "uploaded file" echo state from a previous edit-mode load.
  // The persisted mock only stores the base64 payload (not the original
  // filename), so we can't reconstruct the chip — the user can re-upload
  // if they want to swap the asset.
  uploadedFileName.value = ''
  uploadedFileSize.value = 0
  responseBodyFileError.value = ''
  if (m.response.jsonBody !== undefined) {
    responseBody.value = JSON.stringify(m.response.jsonBody, null, 2)
  } else if (m.response.base64Body) {
    responseBody.value = m.response.base64Body
  } else if (m.response.bodyFileName) {
    // Legacy bodyFileName mocks: the file-reference UI was removed, so we
    // load the path string into the body textarea as a graceful migration.
    // It will be re-saved as a plain `body` rather than `bodyFileName`.
    responseBody.value = m.response.bodyFileName
  } else if (m.response.body) {
    responseBody.value = m.response.body
  }

  // Delay
  if (m.response.delayDistribution) {
    enableDelay.value = true
    delayType.value = m.response.delayDistribution.type
    if (m.response.delayDistribution.type === 'uniform') {
      delayLower.value = m.response.delayDistribution.lower ?? 50
      delayUpper.value = m.response.delayDistribution.upper ?? 100
    } else if (m.response.delayDistribution.type === 'lognormal') {
      delayMedian.value = m.response.delayDistribution.median ?? 100
      delaySigma.value = m.response.delayDistribution.sigma ?? 0.1
    } else if (m.response.delayDistribution.type === 'fixed') {
      delayFixed.value = m.response.delayDistribution.milliseconds ?? 100
    }
  } else if (m.response.fixedDelayMilliseconds) {
    enableDelay.value = true
    delayType.value = 'fixed'
    delayFixed.value = m.response.fixedDelayMilliseconds
  }

  // Chunked dribble delay
  if (m.response.chunkedDribbleDelay) {
    enableChunkedDribble.value = true
    chunkedNumberOfChunks.value = m.response.chunkedDribbleDelay.numberOfChunks
    chunkedTotalDuration.value = m.response.chunkedDribbleDelay.totalDuration
    collapsedSections.value.chunkedDribble = false
  }

  // Fault
  if (m.response.fault) {
    enableFault.value = true
    faultType.value = m.response.fault
    collapsedSections.value.faultSimulation = false
  }

  // Proxying
  if (m.response.proxyBaseUrl) {
    enableProxy.value = true
    proxyBaseUrl.value = m.response.proxyBaseUrl
    if (m.response.additionalProxyRequestHeaders) {
      additionalProxyHeaders.value = Object.entries(m.response.additionalProxyRequestHeaders).map(([key, value]) => ({ key, value }))
    }
    if (m.response.removeProxyRequestHeaders) {
      removeProxyHeaders.value = [...m.response.removeProxyRequestHeaders]
    }
    collapsedSections.value.proxying = false
  }

  // Webhooks
  // Prefer the modern `serveEventListeners` array (WireMock 3.1.0+). A
  // single stub can carry multiple listeners — we only model the
  // "webhook" one in this UI, so pick the first entry with that name.
  // Fall back to the legacy `postServeActions.webhook` map so stubs
  // created before the migration still round-trip cleanly; on next save
  // they'll be rewritten in the new shape by `buildMockPayload`.
  const webhookListener = m.serveEventListeners?.find(l => l.name === 'webhook')
  const legacyWebhook = m.postServeActions?.webhook
  // Listener `parameters` is intentionally opaque on the wire, so we
  // narrow through a typed view of just the fields this UI renders.
  const webhookParams = webhookListener
    ? (webhookListener.parameters as Partial<WebhookDefinition>)
    : legacyWebhook
  if (webhookParams?.url) {
    enableWebhook.value = true
    webhookUrl.value = webhookParams.url
    // Default to POST — WireMock's own default when `method` is omitted.
    webhookMethod.value = webhookParams.method ?? 'POST'
    if (webhookParams.headers) {
      webhookHeaders.value = Object.entries(webhookParams.headers).map(([key, value]) => ({ key, value }))
    }
    webhookBody.value = webhookParams.body ?? ''
    webhookDelayMs.value = webhookParams.delay?.milliseconds ?? 0
    collapsedSections.value.webhooks = false
  }
}

// ── Computed classes ──────────────────────────────────────────
const inputClasses = computed(() =>
  isDark.value
    ? 'bg-gray-800 border-gray-600 text-gray-100 placeholder-gray-500 focus:ring-emerald-500 focus:border-emerald-500'
    : 'bg-white border-gray-300 text-gray-800 focus:ring-emerald-500 focus:border-emerald-500'
)

const sectionClasses = computed(() =>
  isDark.value
    ? 'bg-gray-900 border-gray-700'
    : 'bg-white border-gray-200'
)

const labelClasses = computed(() =>
  isDark.value ? 'text-gray-300' : 'text-gray-600'
)

const headingClasses = computed(() =>
  isDark.value ? 'text-gray-100' : 'text-gray-700'
)

const collapsibleHeaderClasses = computed(() =>
  isDark.value
    ? 'bg-gray-800/50 hover:bg-gray-800 border-gray-700'
    : 'bg-gray-50 hover:bg-gray-100 border-gray-200'
)

// ── Required field red border helper ──────────────────────────
function requiredInputClasses(fieldValue: string | number | undefined) {
  const isEmpty = fieldValue === undefined || fieldValue === null || String(fieldValue).trim() === ''
  if (isEmpty) {
    return isDark.value
      ? 'bg-gray-800 border-red-500 text-gray-100 placeholder-gray-500 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30'
      : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30'
  }
  return inputClasses.value
}

// ── Key/value row validator ──────────────────────────────────
// Used by every key-matcher-value row in the form (request headers, response
// headers, proxy headers, webhook headers, query params).
// A row is invalid when the user has filled in the key but left the value
// blank. For matcher-based rows, the `absent` matcher is a valid zero-value
// case (it asserts the key is NOT present), so we skip the requirement there.
function isMatcherRowValueMissing(row: { key?: string; value?: string; matcherType?: string }): boolean {
  const key = (row.key ?? '').trim()
  if (!key) return false
  if (row.matcherType === 'absent') return false
  return !(row.value ?? '').trim()
}

// Inverse of isMatcherRowValueMissing: a row is also invalid when the user
// supplied a value (or a non-default matcher) but left the key blank — a
// header/query/cookie pattern without a name has nothing to bind to. We
// skip the check for `absent` because the value field is disabled there.
function isMatcherRowKeyMissing(row: { key?: string; value?: string; matcherType?: string }): boolean {
  if (row.matcherType === 'absent') return false
  const hasValue = !!(row.value ?? '').trim()
  if (!hasValue) return false
  return !(row.key ?? '').trim()
}

// ── Body pattern combination validator ────────────────────────
// WireMock body patterns are AND-combined, so stacking multiple patterns is
// a valid and powerful feature (e.g. "contains `error`" AND "matches regex").
// A few combinations, however, are nonsensical and produce stubs that can
// never match:
//   - More than one literal-body matcher (equalTo / equalToJson / equalToXml
//     / binaryEqualTo) — a body can only equal one thing at a time.
//   - `absent` combined with any other matcher — a body can't simultaneously
//     be missing and contain a value.
//   - `equalToJson` with a value that isn't parseable JSON.
//   - `equalToXml` with a value that isn't parseable XML.
// Returns an array of user-facing issue strings.
const LITERAL_BODY_MATCHER_SET = new Set(['equalTo', 'equalToJson', 'equalToXml', 'binaryEqualTo'])
function findBodyPatternIssues(rows: Array<{ matcherType?: string; value?: string }>): string[] {
  const issues: string[] = []
  if (!rows || rows.length === 0) return issues

  const literalCount = rows.filter(r => LITERAL_BODY_MATCHER_SET.has(r.matcherType ?? '')).length
  if (literalCount > 1) {
    issues.push('Only one literal-body matcher (equalTo, equalToJson, equalToXml, binaryEqualTo) is allowed — a request body can only equal one thing.')
  }

  const absentCount = rows.filter(r => r.matcherType === 'absent').length
  if (absentCount > 0 && rows.length > 1) {
    issues.push('`absent` cannot be combined with other body patterns — a body can\'t simultaneously be missing and contain a value.')
  }
  if (absentCount > 1) {
    issues.push('Only one `absent` body pattern is allowed.')
  }

  // Per-row format validation. Empty values are tolerated (the user is
  // mid-edit); only flag when something has been typed and it doesn't
  // parse. We index the row in the message so the user can locate it.
  rows.forEach((r, i) => {
    const value = (r.value ?? '').trim()
    if (!value) return
    if (r.matcherType === 'equalToXml' && !isValidXml(value)) {
      issues.push(`Body pattern #${i + 1} (equalToXml) is not valid XML.`)
    }
    if (r.matcherType === 'equalToJson' && !isValidJson(value)) {
      issues.push(`Body pattern #${i + 1} (equalToJson) is not valid JSON.`)
    }
  })

  return issues
}

// Per-row helper used by the textarea's inline validation. Returns a
// short user-facing error string when the row's value fails the format
// implied by its matcher kind, or '' when valid / not applicable.
function bodyPatternRowFormatError(row: { matcherType?: string; value?: string }): string {
  const value = (row.value ?? '').trim()
  if (!value) return ''
  if (row.matcherType === 'equalToXml' && !isValidXml(value)) {
    return 'Not valid XML.'
  }
  if (row.matcherType === 'equalToJson' && !isValidJson(value)) {
    return 'Not valid JSON.'
  }
  return ''
}

const badgeClasses = computed(() =>
  isDark.value
    ? 'bg-emerald-500/15 text-emerald-400'
    : 'bg-emerald-50 text-emerald-700'
)

// ── Helpers ───────────────────────────────────────────────────
function addRow(list: KeyValuePair[]) {
  list.push({ key: '', value: '' })
}

function removeRow(list: any[], index: number) {
  list.splice(index, 1)
}

function isContentTypeHeader(key: string | undefined): boolean {
  return (key ?? '').trim().toLowerCase() === 'content-type'
}

// Returns the dropdown options for a Content-Type header. If the current
// value isn't in our standard list (e.g. it came from an imported mock),
// prepend it so the select still displays the existing value instead of
// silently blanking it out.
function contentTypeOptions(current: string | undefined): readonly string[] {
  const value = (current ?? '').trim()
  if (!value || (CONTENT_TYPES as readonly string[]).includes(value)) {
    return CONTENT_TYPES
  }
  return [value, ...CONTENT_TYPES]
}

function responseHeaderOptions(current: string | undefined): string[] {
  const value = (current ?? '').trim()
  const list = [...COMMON_RESPONSE_HEADERS] as string[]
  if (value && !list.includes(value) && value !== '__custom__') {
    list.unshift(value)
  }
  return list
}

function addMatcherRow(list: MatcherRow[]) {
  list.push({ key: '', matcherType: 'equalTo', value: '', caseInsensitive: false })
}

function addBodyPatternRow() {
  bodyPatterns.value.push({ matcherType: 'equalToJson', value: '', ignoreArrayOrder: false, ignoreExtraElements: false })
}

function addRemoveProxyHeader() {
  if (removeProxyHeaderInput.value.trim()) {
    addRemoveProxyHeaderValue(removeProxyHeaderInput.value)
    removeProxyHeaderInput.value = ''
  }
}

// Adds a header to the remove-proxy list directly (used by the example
// quick-add chips). Normalises via trim and deduplicates case-insensitively
// — HTTP header names are case-insensitive, so "Cookie" and "cookie" would
// be redundant entries.
function addRemoveProxyHeaderValue(name: string) {
  const trimmed = name.trim()
  if (!trimmed) return
  const exists = removeProxyHeaders.value.some(
    h => h.toLowerCase() === trimmed.toLowerCase(),
  )
  if (exists) return
  removeProxyHeaders.value.push(trimmed)
}

function removeRemoveProxyHeader(index: number) {
  removeProxyHeaders.value.splice(index, 1)
}

// Validate that a string is well-formed base64 (after stripping whitespace /
// newlines, which are harmless in both RFC 4648 and most real-world payloads).
// The empty string is treated as valid — callers use it only when there's
// something to check. We also reject lengths that can't possibly decode
// (`length % 4 !== 0` after padding), which catches the common "off by a
// character" copy/paste issue.
function isValidBase64(str: string): boolean {
  if (!str.trim()) return true
  const compact = str.replace(/\s+/g, '')
  if (!compact) return true
  if (compact.length % 4 !== 0) return false
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(compact)) return false
  // `atob` throws on malformed input that slipped past the regex (rare, but
  // the native decoder is the authority on what's really decodable).
  try {
    atob(compact)
    return true
  } catch {
    return false
  }
}

// Validate that a string is well-formed XML by running it through the
// browser's DOMParser and checking for a `<parsererror>` node — the only
// reliable signal DOMParser provides since it never throws on bad input.
function isValidXml(str: string): boolean {
  if (!str.trim()) return true
  try {
    const doc = new DOMParser().parseFromString(str, 'application/xml')
    return !doc.getElementsByTagName('parsererror').length
  } catch {
    return false
  }
}

function isValidJson(str: string): boolean {
  if (!str.trim()) return true
  try {
    JSON.parse(str)
    return true
  } catch {
    return false
  }
}

// ── Metadata row validation ───────────────────────────────────
// Keys are serialized verbatim as JSON object property names. Any string is
// technically a valid JSON key, but in practice a key containing whitespace
// (spaces, tabs, newlines) almost always indicates a user typo — it would
// require awkward bracket-notation access and breaks common filter tooling.
// `locked` rows (e.g. the auto-managed `project` row) bypass this check
// because the value is controlled programmatically.
function isMetadataKeyInvalid(row: { key?: string; locked?: boolean }): boolean {
  if (row.locked) return false
  const key = row.key ?? ''
  if (!key) return false // empty key is flagged separately by the value-requires-key rule
  return /\s/.test(key)
}

// If a metadata row has a key, it must also have a value. Matches the
// behavior already applied to headers and query params. `locked` rows are
// managed internally, so skip.
function isMetadataValueMissing(row: { key?: string; value?: string; locked?: boolean }): boolean {
  if (row.locked) return false
  const key = (row.key ?? '').trim()
  if (!key) return false
  return !(row.value ?? '').trim()
}

// ── ms → seconds live conversion helper ───────────────────────
// Every form field that collects a duration in milliseconds (delays,
// chunked dribble, webhook firing delay) renders a tiny "= 1.50 s" hint
// below the input so users can sanity-check the magnitude without doing
// mental math. Returns an empty string for undefined/zero so the caller
// can `v-if` it away cleanly.
function formatMsAsSeconds(ms: number | undefined | null): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return ''
  const seconds = ms / 1000
  // < 1s → show 3 decimals for precision (e.g. 0.250 s).
  // >= 1s → 2 decimals is plenty (e.g. 1.50 s, 12.34 s).
  const decimals = seconds < 1 ? 3 : 2
  return `= ${seconds.toFixed(decimals)} s`
}

// ── URL path validation ───────────────────────────────────────
// Validates that the value is a proper URL path:
//   - Must start with /
//   - Only allows: letters, digits, -, _, ., ~, /, :, @, !, $, &, ', (, ), *, +, ,, ;, =, %, {, }
//   - For regex URL match types, also allow regex chars: ^, $, ., *, +, ?, (, ), [, ], {, }, |, \
//   - Must have at least one segment after the leading /
function isValidUrlPath(value: string): boolean {
  const trimmed = value.trim()
  if (!trimmed || trimmed === '/') return false
  if (!trimmed.startsWith('/')) return false

  if (urlMatchType.value === 'urlPattern' || urlMatchType.value === 'urlPathPattern') {
    // Regex patterns: allow most printable chars but reject spaces and control chars
    return /^\/[^\s]+$/.test(trimmed)
  }

  // Strict path validation for exact match types (url / urlPath)
  return /^\/[a-zA-Z0-9\-._~/:@!$&'()*+,;=%{}]+$/.test(trimmed)
}

// ── Response body format inference ────────────────────────────
// The body-type dropdown used to be an independent toggle, but it's now
// derived from the `Content-Type` response header so the two can never
// disagree (serving `<xml/>` with `Content-Type: application/json` was
// a common footgun in the old UI).
//
// Resolution order:
//   1. Honor the first response header whose key, case-insensitively,
//      is `Content-Type`. If the row has a blank value we fall back to
//      the next step.
//   2. Default to `application/json` — matches the form's seeded
//      Content-Type row and keeps backward compatibility with pre-flip
//      saved mocks that just had `jsonBody` set.
const responseContentType = computed<string>(() => {
  for (const h of responseHeaders.value) {
    if (h.key.trim().toLowerCase() === 'content-type' && h.value.trim()) {
      return h.value.trim()
    }
  }
  return 'application/json'
})

// Classify the response body into one of four payload shapes based on
// the Content-Type. We tolerate structured-suffix types (e.g.
// `application/problem+json`, `application/atom+xml`) and the common
// binary families so the UI does the right thing without the user
// having to think about WireMock's three body fields.
//
//   - 'json'  → validate as JSON, emit as `jsonBody`
//   - 'xml'   → validate as XML, emit as `body` (WireMock has no
//               dedicated xmlBody field — literal text is correct)
//   - 'base64'→ validate as base64, emit as `base64Body` (binary types)
//   - 'text'  → no format validation, emit as `body`
const responseBodyFormat = computed<'json' | 'xml' | 'base64' | 'text'>(() => {
  const ct = responseContentType.value.toLowerCase().split(';')[0].trim()
  if (!ct) return 'text'
  if (/^(application|text)\/([a-z0-9.+\-]*\+)?json$/.test(ct)) return 'json'
  if (/^(application|text|image)\/([a-z0-9.+\-]*\+)?xml$/.test(ct)) return 'xml'
  // Binary-ish MIME families that users will want to paste as base64.
  // We exclude `image/svg+xml` (caught by the xml branch above) because
  // SVG is literal XML text, not base64.
  if (
    ct === 'application/octet-stream'
    || ct === 'application/pdf'
    || ct === 'application/zip'
    || ct === 'application/x-zip-compressed'
    || ct === 'application/gzip'
    || ct.startsWith('image/')
    || ct.startsWith('audio/')
    || ct.startsWith('video/')
    || ct.startsWith('font/')
  ) return 'base64'
  return 'text'
})

// Human-readable label for the detected body format — shown as a small
// pill next to the Response Body label so the user knows what the form
// is validating against.
const responseBodyFormatLabel = computed(() => {
  switch (responseBodyFormat.value) {
    case 'json':   return 'JSON'
    case 'xml':    return 'XML'
    case 'base64': return 'Base64'
    default:       return 'Text'
  }
})

// Live validation error for the response body, scoped to the currently
// detected format. Returns '' when valid or when the field is empty.
const responseBodyFormatError = computed(() => {
  const body = responseBody.value
  if (!body.trim()) return ''
  switch (responseBodyFormat.value) {
    case 'json':   return isValidJson(body) ? '' : `Invalid JSON for Content-Type ${responseContentType.value}.`
    case 'xml':    return isValidXml(body) ? '' : `Invalid XML for Content-Type ${responseContentType.value}.`
    case 'base64': return isValidBase64(body) ? '' : `Invalid base64 for Content-Type ${responseContentType.value}.`
    default:       return ''
  }
})

// Description shown under the Fault Simulation dropdown for the currently
// selected fault type.
const selectedFaultDescription = computed(() => {
  const match = FAULT_TYPES.find(f => f.value === faultType.value)
  return match?.description ?? ''
})

// ── Response body file upload ─────────────────────────────────
// Surfaced as an "Upload file" button whenever the user has picked a
// Content-Type. The handler dispatches based on `responseBodyFormat`:
//
//   - JSON  → read as text, validate as parseable JSON.
//   - XML   → read as text, validate as parseable XML.
//   - Text  → read as text, no parse validation (free-form).
//   - Base64 → read as data URL, strip `data:<mime>;base64,` prefix,
//             write the bare base64 payload into `responseBody`.
//
// A pre-read MIME / extension check rejects files that obviously don't
// match the chosen Content-Type — e.g. uploading `foo.txt` while the
// header says `image/png`. Any post-read parse failure (e.g. malformed
// JSON) is surfaced via `responseBodyFileError` and the uploaded file
// is rolled back so the user is left in a known state. The state refs
// themselves are declared further up alongside `responseBody` so the
// immediate edit-mode watcher can touch them without tripping a
// temporal-dead-zone error.

// `accept` attribute for the file picker. Narrows to the exact MIME when
// possible (e.g. `image/png`) and broadens to a family when the response
// header is a wildcard or unknown subtype. Falls back to `*/*` so users
// can still upload anything they like.
const responseBodyFileAccept = computed<string>(() => {
  const ct = responseContentType.value.toLowerCase().split(';')[0].trim()
  if (!ct) return '*/*'
  // Narrow MIME — use as-is.
  if (/^[a-z]+\/[a-z0-9.+\-]+$/.test(ct)) return ct
  return '*/*'
})

// Lightweight MIME-vs-Content-Type compatibility check, used to reject
// obviously mismatched uploads before we read them. We're forgiving when
// the browser reports an empty/octet-stream MIME (some OSes do that for
// uncommon types) and fall back to extension sniffing in that case.
const EXTENSION_TO_MIME: Record<string, string> = {
  json: 'application/json',
  xml: 'application/xml',
  txt: 'text/plain',
  csv: 'text/csv',
  html: 'text/html',
  htm: 'text/html',
  md: 'text/markdown',
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  gif: 'image/gif',
  webp: 'image/webp',
  svg: 'image/svg+xml',
  pdf: 'application/pdf',
  zip: 'application/zip',
  gz: 'application/gzip',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  mp4: 'video/mp4',
  webm: 'video/webm',
}

function isFileCompatibleWithContentType(file: File, contentType: string): boolean {
  const ct = contentType.toLowerCase().split(';')[0].trim()
  if (!ct) return true
  const fileMime = (file.type || '').toLowerCase().trim()
  // No browser-reported MIME — fall back to the file extension.
  const ext = file.name.includes('.') ? file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase() : ''
  const inferred = fileMime || EXTENSION_TO_MIME[ext] || ''
  if (!inferred) return true // unknown — let the user proceed and validate the parsed contents instead
  if (inferred === ct) return true
  // Family match: image/png against image/* etc. — lets the user upload
  // any image variant when the Content-Type is a generic family. Both
  // sides must share their primary type.
  const [topA, subA] = ct.split('/')
  const [topB, subB] = inferred.split('/')
  if (topA === topB && (subA === '*' || subB === '*')) return true
  return false
}

function handleResponseBodyFileUpload(ev: Event): void {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  // Allow the same file to be picked twice in a row by clearing the
  // input — `change` only fires when the value actually changes.
  input.value = ''
  if (!file) return
  responseBodyFileError.value = ''

  const ct = responseContentType.value
  if (!isFileCompatibleWithContentType(file, ct)) {
    responseBodyFileError.value = `File "${file.name}" doesn't match Content-Type ${ct}. Pick a file matching that type or change the Content-Type header.`
    return
  }

  const reader = new FileReader()
  reader.onerror = () => {
    responseBodyFileError.value = 'Failed to read file. Try a different file.'
  }
  reader.onload = () => {
    const result = reader.result
    if (typeof result !== 'string') {
      responseBodyFileError.value = 'Unexpected file reader result.'
      return
    }
    // Snapshot existing body before we overwrite, so we can roll back
    // when the contents fail format validation.
    const prevBody = responseBody.value
    if (responseBodyFormat.value === 'base64') {
      // Data URLs are `data:<mime>;base64,<payload>`. Slice off everything
      // up to the first comma to get the bare base64 payload WireMock
      // expects in `base64Body`.
      const commaIdx = result.indexOf(',')
      responseBody.value = commaIdx >= 0 ? result.slice(commaIdx + 1) : result
    } else if (responseBodyFormat.value === 'json') {
      if (!isValidJson(result)) {
        responseBody.value = prevBody
        responseBodyFileError.value = `File "${file.name}" is not valid JSON.`
        return
      }
      responseBody.value = result
    } else if (responseBodyFormat.value === 'xml') {
      if (!isValidXml(result)) {
        responseBody.value = prevBody
        responseBodyFileError.value = `File "${file.name}" is not valid XML.`
        return
      }
      responseBody.value = result
    } else {
      // Plain text — no parse validation.
      responseBody.value = result
    }
    uploadedFileName.value = file.name
    uploadedFileSize.value = file.size
  }

  if (responseBodyFormat.value === 'base64') {
    reader.readAsDataURL(file)
  } else {
    reader.readAsText(file)
  }
}

function clearUploadedFile(): void {
  responseBody.value = ''
  uploadedFileName.value = ''
  uploadedFileSize.value = 0
  responseBodyFileError.value = ''
}

// Pretty-print byte counts for the uploaded-file echo line. Keeps it
// tiny — KB/MB are enough granularity for a stub-authoring UI.
function formatFileSize(bytes: number): string {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

// ── Chunked Dribble Delay live feedback ───────────────────────
// WireMock's chunked dribble splits the response body into N chunks and
// releases them evenly over `totalDuration` ms. These computeds surface
// the per-chunk numbers so users can sanity-check whether their settings
// produce the throttling behavior they expect (e.g. "10 chunks over
// 2000 ms = one chunk every 200 ms"). They render as inline hints under
// the inputs — no submit-time validation is added around them.
const chunkedDribbleActive = computed(() => enableChunkedDribble.value && !enableFault.value)

// ms per chunk, rounded to the nearest integer. Only computed when both
// inputs are positive finite numbers — otherwise we return null so the
// template can v-if away the hint cleanly.
const chunkedMsPerChunk = computed<number | null>(() => {
  const chunks = chunkedNumberOfChunks.value
  const total = chunkedTotalDuration.value
  if (typeof chunks !== 'number' || !Number.isFinite(chunks) || chunks < 1) return null
  if (typeof total !== 'number' || !Number.isFinite(total) || total < 1) return null
  return Math.round(total / chunks)
})

// Approximate bytes per chunk. Uses the literal string length of the
// response body — accurate for ASCII, a close approximation for UTF-8.
// Honest enough for a "does this look right?" preview. Returns null when
// there's no body to dribble or when chunk count is invalid; the
// template uses this to distinguish the empty-body warning from the
// live-stats line.
const chunkedBytesPerChunk = computed<number | null>(() => {
  const chunks = chunkedNumberOfChunks.value
  if (typeof chunks !== 'number' || !Number.isFinite(chunks) || chunks < 1) return null
  const body = responseBody.value ?? ''
  if (body.length === 0) return null
  return Math.max(1, Math.round(body.length / chunks))
})

// Warn when chunked dribble is enabled but there's nothing to dribble —
// WireMock will just emit the (empty) body with no throttling effect.
const chunkedDribbleNoBody = computed(() => {
  if (!chunkedDribbleActive.value) return false
  return (responseBody.value ?? '').length === 0
})

// True when the delay's uniform range is inverted. Drives live inline
// feedback beside the lower/upper inputs; submit-time validation in
// handleSubmit() uses the same condition as its source of truth.
const delayRangeInvalid = computed(() => {
  if (!enableDelay.value || delayType.value !== 'uniform') return false
  const lo = delayLower.value
  const hi = delayUpper.value
  return typeof lo === 'number' && typeof hi === 'number' && lo > hi
})

const urlValidationError = computed(() => {
  const trimmed = urlValue.value.trim()
  if (!trimmed || trimmed === '/') return ''
  if (!isValidUrlPath(trimmed)) {
    if (urlMatchType.value === 'urlPattern' || urlMatchType.value === 'urlPathPattern') {
      return 'Invalid URL pattern. Must start with / and contain no spaces.'
    }
    return 'Invalid URL path. Only letters, digits, /, -, _, ., ~, and standard URL characters are allowed.'
  }
  return ''
})

// ── Absolute URL validation (proxy + webhook) ─────────────────
// The proxy base URL and webhook URL are full upstream addresses, so they
// must parse as absolute http(s) URLs. We rely on the browser's URL API for
// the parse (catches missing scheme, spaces, invalid hosts, etc.) and then
// tighten the allowed schemes to http/https — WireMock's HTTP client won't
// follow ftp:// or mailto: anyway.
function isValidAbsoluteUrl(value: string): boolean {
  const trimmed = (value ?? '').trim()
  if (!trimmed) return false
  try {
    const u = new URL(trimmed)
    return u.protocol === 'http:' || u.protocol === 'https:'
  } catch {
    return false
  }
}

const proxyBaseUrlError = computed(() => {
  if (!enableProxy.value) return ''
  const trimmed = proxyBaseUrl.value.trim()
  if (!trimmed) return '' // empty is flagged by required-field logic, not here
  return isValidAbsoluteUrl(trimmed)
    ? ''
    : 'Invalid URL. Must be an absolute http:// or https:// address (e.g. https://api.example.com).'
})

const webhookUrlError = computed(() => {
  if (!enableWebhook.value) return ''
  const trimmed = webhookUrl.value.trim()
  if (!trimmed) return '' // empty is flagged by required-field logic, not here
  return isValidAbsoluteUrl(trimmed)
    ? ''
    : 'Invalid URL. Must be an absolute http:// or https:// address (e.g. https://example.com/webhook).'
})

// ── Live validation — full report ─────────────────────────────
// Single source of truth for form-level validation. The right-side sticky
// panel renders this live so the user always sees every outstanding issue;
// handleSubmit() short-circuits when this list is non-empty. Issues are
// grouped by the section they belong to so the user knows where to look.
type ValidationIssue = { section: string; message: string }

const validationIssues = computed<ValidationIssue[]>(() => {
  const issues: ValidationIssue[] = []

  // General
  if (!mockName.value.trim()) issues.push({ section: 'General', message: 'Mock name is required.' })
  if (!internalProjectId.value.trim()) issues.push({ section: 'General', message: 'Project ID is required.' })
  // Backend collision check — `nameExists` is set true by the
  // existence-check fetch when another mock under the same project
  // already uses this name. Surface it in the validation table so the
  // user can't submit past it.
  if (nameExists.value) {
    issues.push({
      section: 'General',
      message: `A mock named "${mockName.value.trim()}" already exists in this project.`,
    })
  }

  // Metadata — key syntax + value required when key provided
  const invalidKeys = metadataRows.value
    .filter(isMetadataKeyInvalid)
    .map(r => (r.key ?? '').trim())
    .filter(Boolean)
  if (invalidKeys.length > 0) {
    issues.push({
      section: 'Metadata',
      message: `Keys cannot contain whitespace${invalidKeys.length ? ` (check: ${invalidKeys.join(', ')})` : ''}.`,
    })
  }
  const metaMissingValue = metadataRows.value
    .filter(isMetadataValueMissing)
    .map(r => (r.key ?? '').trim())
    .filter(Boolean)
  if (metaMissingValue.length > 0) {
    issues.push({
      section: 'Metadata',
      message: `Each key needs a value (missing for ${metaMissingValue.join(', ')}).`,
    })
  }
  // JSON-typed metadata values must parse. (Text-typed values are free-form.)
  const badJsonValues = metadataRows.value
    .filter(r => !r.locked && r.valueType === 'json' && r.value.trim() && !isValidJson(r.value))
    .map(r => (r.key ?? '').trim() || '(unnamed)')
  if (badJsonValues.length > 0) {
    issues.push({
      section: 'Metadata',
      message: `JSON value is not valid JSON (check: ${badJsonValues.join(', ')}).`,
    })
  }

  // Request Matching — URL
  if (!urlValue.value.trim() || urlValue.value.trim() === '/') {
    issues.push({ section: 'Request Matching', message: 'URL is required.' })
  } else if (!isValidUrlPath(urlValue.value)) {
    issues.push({
      section: 'Request Matching',
      message: urlValidationError.value || 'Invalid URL path.',
    })
  }
  // Backend collision check — `urlExists` is set true by the existence-
  // check fetch when another mock under the same project already
  // matches this URL. Mirrors the name-collision rule above so both
  // uniqueness errors block submission via the validation table.
  if (urlExists.value) {
    issues.push({
      section: 'Request Matching',
      message: `A mock with the URL "${urlValue.value.trim()}" already exists in this project.`,
    })
  }

  // Response Definition — body format must match Content-Type. Fault &
  // proxy bypass the canned response, so don't block saves over bodies
  // the server will never send.
  if (
    !enableFault.value
    && !enableProxy.value
    && responseBodyFormatError.value
  ) {
    issues.push({ section: 'Response Definition', message: responseBodyFormatError.value })
  }

  // Basic Auth
  if (enableBasicAuth.value && !basicAuthUsername.value.trim()) {
    issues.push({ section: 'Basic Authentication', message: 'Username is required when Basic Auth matching is enabled.' })
  }
  if (enableBasicAuth.value && !basicAuthPassword.value.trim()) {
    issues.push({ section: 'Basic Authentication', message: 'Password is required when Basic Auth matching is enabled.' })
  }

  // Key/value consistency across every "name → value" list. Rules:
  // - If the user filled a key they must supply a value.
  // - `absent` matchers are the exception (by definition they carry no value).
  const keyValueSections: Array<[string, string, Array<{ key?: string; value?: string; matcherType?: string }>]> = [
    ['Request Matching', 'query parameter', queryParams.value],
    ['Request Matching', 'header', requestHeaders.value],
    ['Response Definition', 'header', responseHeaders.value],
    ['Proxying', 'proxy header', additionalProxyHeaders.value],
    ['Webhooks', 'header', webhookHeaders.value],
  ]
  for (const [section, noun, rows] of keyValueSections) {
    const missingValue = rows.filter(isMatcherRowValueMissing)
    if (missingValue.length > 0) {
      const names = missingValue.map(r => (r.key ?? '').trim()).filter(Boolean).join(', ')
      issues.push({
        section,
        message: `Each ${noun} with a name also needs a value${names ? ` (missing for ${names})` : ''}.`,
      })
    }
    const missingKey = rows.filter(isMatcherRowKeyMissing)
    if (missingKey.length > 0) {
      issues.push({
        section,
        message: `Each ${noun} with a value also needs a name (${missingKey.length} row${missingKey.length === 1 ? '' : 's'} missing a name).`,
      })
    }
  }

  // Cookies — a named cookie must carry a value. Request cookies with the
  // `absent` matcher are an exception (they intentionally assert *no* value),
  // which is what `isMatcherRowValueMissing` already handles via matcherType.
  // For response cookies the default matcherType is `equalTo`, so this rule
  // enforces "if you typed a name, type a value too".
  const missingCookieValues = cookies.value.filter(isMatcherRowValueMissing)
  if (missingCookieValues.length > 0) {
    const names = missingCookieValues.map(c => cookieStr(c.key)).filter(Boolean).join(', ')
    issues.push({
      section: 'Cookies',
      message: `Each cookie with a name also needs a value${names ? ` (missing for ${names})` : ''}.`,
    })
  }
  // Mirror rule in the other direction: a value-without-key cookie has
  // nothing to bind to. Only meaningful for non-absent matchers (which is
  // already filtered inside isMatcherRowKeyMissing).
  const missingCookieKeys = cookies.value.filter(isMatcherRowKeyMissing)
  if (missingCookieKeys.length > 0) {
    issues.push({
      section: 'Cookies',
      message: `Each cookie with a value also needs a name (${missingCookieKeys.length} row${missingCookieKeys.length === 1 ? '' : 's'} missing a name).`,
    })
  }
  // Cookies — Domain / Path shape checks. Only meaningful on response rows
  // (browsers ignore these attributes on inbound requests, and our request
  // UI doesn't expose them), so scope the check accordingly.
  const badDomainCookies = cookies.value.filter(
    c => c.direction === 'response' && cookieStr(c.domain) && !isValidCookieDomain(c.domain),
  )
  if (badDomainCookies.length > 0) {
    const names = badDomainCookies.map(c => cookieStr(c.key) || '(unnamed)').join(', ')
    issues.push({
      section: 'Cookies',
      message: `Invalid Domain attribute (check: ${names}). Use a hostname like example.com or a leading-dot form like .example.com.`,
    })
  }
  const badPathCookies = cookies.value.filter(
    c => c.direction === 'response' && cookieStr(c.path) && !isValidCookiePath(c.path),
  )
  if (badPathCookies.length > 0) {
    const names = badPathCookies.map(c => cookieStr(c.key) || '(unnamed)').join(', ')
    issues.push({
      section: 'Cookies',
      message: `Invalid Path attribute (check: ${names}). Paths must start with "/" and contain no whitespace, "?" or "#".`,
    })
  }

  // Body-pattern combination sanity checks.
  for (const issue of bodyPatternIssues.value) {
    issues.push({ section: 'Request Matching', message: `Body patterns — ${issue}` })
  }

  // Webhook
  if (enableWebhook.value && !webhookUrl.value.trim()) {
    issues.push({ section: 'Webhooks', message: 'URL is required when a webhook is enabled.' })
  }
  if (enableWebhook.value && webhookUrl.value.trim() && !isValidAbsoluteUrl(webhookUrl.value)) {
    issues.push({ section: 'Webhooks', message: 'URL must be an absolute http:// or https:// address.' })
  }

  // Proxy
  if (enableProxy.value && !proxyBaseUrl.value.trim()) {
    issues.push({ section: 'Proxying', message: 'Base URL is required when proxying is enabled.' })
  }
  if (enableProxy.value && proxyBaseUrl.value.trim() && !isValidAbsoluteUrl(proxyBaseUrl.value)) {
    issues.push({ section: 'Proxying', message: 'Base URL must be an absolute http:// or https:// address.' })
  }

  // Chunked Dribble (bypassed when fault is on — WireMock ignores delays then)
  if (!enableFault.value && enableChunkedDribble.value) {
    if (typeof chunkedNumberOfChunks.value !== 'number' || chunkedNumberOfChunks.value < 1) {
      issues.push({ section: 'Chunked Dribble Delay', message: 'Number of chunks must be at least 1.' })
    }
    if (typeof chunkedTotalDuration.value !== 'number' || chunkedTotalDuration.value < 1) {
      issues.push({ section: 'Chunked Dribble Delay', message: 'Total duration must be at least 1 ms.' })
    }
  }

  // Uniform delay
  if (!enableFault.value && enableDelay.value && delayType.value === 'uniform') {
    if (typeof delayLower.value !== 'number' || delayLower.value < 0) {
      issues.push({ section: 'Response Delay', message: 'Lower bound must be a non-negative number.' })
    }
    if (typeof delayUpper.value !== 'number' || delayUpper.value < 0) {
      issues.push({ section: 'Response Delay', message: 'Upper bound must be a non-negative number.' })
    }
    if (
      typeof delayLower.value === 'number' &&
      typeof delayUpper.value === 'number' &&
      delayLower.value > delayUpper.value
    ) {
      issues.push({ section: 'Response Delay', message: 'Lower bound cannot be greater than upper bound.' })
    }
  }

  // Response transformer parameters must parse as a JSON object when
  // present — invalid JSON or arrays/primitives would be rejected by
  // WireMock and silently dropped from the wire payload.
  if (transformerParametersError.value) {
    issues.push({ section: 'Response Transformers', message: transformerParametersError.value })
  }

  return issues
})

// Grouped view for the sticky side panel — `[section, [message, ...]]`
// pairs in their original order of appearance so the panel reads top-down
// in the same order as the form sections.
const validationIssuesBySection = computed<Array<[string, string[]]>>(() => {
  const groups = new Map<string, string[]>()
  for (const { section, message } of validationIssues.value) {
    if (!groups.has(section)) groups.set(section, [])
    groups.get(section)!.push(message)
  }
  return Array.from(groups.entries())
})

// ── Build matcher object from a MatcherRow ────────────────────
function buildMatcherObject(row: MatcherRow): Record<string, any> {
  if (row.matcherType === 'absent') return { absent: true }
  const obj: Record<string, any> = { [row.matcherType]: row.value }
  // Only emit `caseInsensitive` when both the user opted in AND the
  // matcher kind actually honours it. WireMock silently ignores the
  // flag on date/JSON/XML/binary matchers, but emitting it would still
  // pollute the wire payload, so suppress it here.
  if (row.caseInsensitive && supportsCaseInsensitive(row.matcherType)) {
    obj.caseInsensitive = true
  }
  return obj
}

// ── Build body pattern object ─────────────────────────────────
function buildBodyPatternObject(row: BodyPatternRow): Record<string, any> {
  if (row.matcherType === 'absent') return { absent: true }
  const obj: Record<string, any> = {}
  if (row.matcherType === 'equalToJson') {
    try {
      obj.equalToJson = JSON.parse(row.value)
    } catch {
      obj.equalToJson = row.value
    }
    if (row.ignoreArrayOrder) obj.ignoreArrayOrder = true
    if (row.ignoreExtraElements) obj.ignoreExtraElements = true
  } else if (
    row.matcherType === 'matchesJsonPath'
    && (
      (row.varyBy ?? '').trim()
      || ((row.jsonPathSubMatcher ?? '').trim() && row.jsonPathSubMatcher !== '')
    )
  ) {
    // JSONPath in the object form — used when the user pairs the
    // expression with a sub-matcher (`equalTo`, `contains`, `matches`,
    // etc. applied to the extracted value) or with `vary-by` for
    // templated stubs that need to vary by a sub-expression. WireMock
    // expects the hyphenated `vary-by` on the wire, which is what its
    // docs and tests use. Bare-expression JSONPath stays as a plain
    // string a few branches below.
    const sub: Record<string, unknown> = { expression: row.value }
    if ((row.varyBy ?? '').trim()) {
      sub['vary-by'] = row.varyBy!.trim()
    }
    const subKind = row.jsonPathSubMatcher ?? ''
    if (subKind) {
      if (subKind === 'absent') {
        sub.absent = true
      } else if (subKind === 'equalToJson') {
        // Try to parse so the schema/document goes out as a real JSON
        // object — fall back to the raw string for partial edits.
        const raw = row.jsonPathSubMatcherValue ?? ''
        try {
          sub.equalToJson = JSON.parse(raw)
        } catch {
          sub.equalToJson = raw
        }
      } else {
        sub[subKind] = row.jsonPathSubMatcherValue ?? ''
      }
    }
    obj.matchesJsonPath = sub
  } else if (row.matcherType === 'matchesJsonSchema') {
    // JSON Schema body pattern. Try to parse the value as JSON so the
    // schema goes out as a real object — fall back to the raw string for
    // partial / in-progress edits (still valid per WireMock).
    try {
      obj.matchesJsonSchema = JSON.parse(row.value)
    } catch {
      obj.matchesJsonSchema = row.value
    }
    if ((row.schemaVersion ?? '').trim()) obj.schemaVersion = row.schemaVersion
  } else if (row.matcherType === 'matchesXPath') {
    // XPath always serialises as a bare expression string. Namespaces,
    // when present, ride alongside as a sibling `xPathNamespaces` map —
    // same shape WireMock's docs use:
    //   { "matchesXPath": "/users/user[@id='1']",
    //     "xPathNamespaces": { "ns": "http://example.com/ns" } }
    obj.matchesXPath = row.value
    const ns = (row.xPathNamespaces ?? []).filter(n => n.prefix.trim() && n.uri.trim())
    if (ns.length > 0) {
      obj.xPathNamespaces = Object.fromEntries(ns.map(n => [n.prefix.trim(), n.uri.trim()]))
    }
  } else if (row.matcherType === 'equalToXml') {
    obj.equalToXml = row.value
    if (row.enablePlaceholders) obj.enablePlaceholders = true
    const ns = (row.xPathNamespaces ?? []).filter(n => n.prefix.trim() && n.uri.trim())
    if (ns.length > 0) {
      obj.xPathNamespaces = Object.fromEntries(ns.map(n => [n.prefix.trim(), n.uri.trim()]))
    }
  } else {
    obj[row.matcherType] = row.value
  }
  // DateTime truncation / offset options only apply to the date matchers.
  if (DATETIME_BODY_MATCHERS.has(row.matcherType)) {
    if ((row.truncateExpected ?? '').trim()) obj.truncateExpected = row.truncateExpected
    if ((row.truncateActual ?? '').trim()) obj.truncateActual = row.truncateActual
    if (typeof row.expectedOffset === 'number' && Number.isFinite(row.expectedOffset)) {
      obj.expectedOffset = row.expectedOffset
      if ((row.expectedOffsetUnit ?? '').trim()) obj.expectedOffsetUnit = row.expectedOffsetUnit
    }
  }
  return obj
}

// ── Build payload ─────────────────────────────────────────────
function buildRequest(): RequestDefinition {
  // Serialize 'url' match type as 'urlPath' in the payload
  const payloadUrlKey = urlMatchType.value === 'url' ? 'urlPath' : urlMatchType.value
  // Prepend the project namespace so every mock under "Acme Project" is
  // served under /acme-project/... See `projectUrlPrefix`. The user-typed
  // portion is guaranteed to start with `/`, so the joined path can never
  // produce a `//` boundary.
  const path = urlValue.value.startsWith('/') ? urlValue.value : `/${urlValue.value}`
  const fullPath = projectUrlPrefix.value + path
  const req: Record<string, unknown> = {
    method: httpMethod.value,
    [payloadUrlKey]: fullPath,
  }

  // Headers
  // We start with whatever the user typed in the Headers grid, then let the
  // base64-mode basic-auth block (further down) splice in `Authorization`.
  // If the user happened to also add an `Authorization` header by hand, the
  // base64 mode wins — having two competing matchers on the same header is
  // a stub schema violation, and the explicit basic-auth section is the
  // clearer source of truth.
  const filteredHeaders = requestHeaders.value.filter(h => h.key.trim())
  if (filteredHeaders.length > 0) {
    req.headers = Object.fromEntries(filteredHeaders.map(h => [h.key, buildMatcherObject(h)]))
  }

  // Request cookies — every CookieRow with direction='request' becomes a
  // matcher against the incoming Cookie header. Browsers only transmit
  // `name=value` pairs in that header, so attributes like Domain/Path/etc.
  // are ignored here even if the row has them set.
  // Rules:
  //   • Skip rows with no key (blank/in-progress entries).
  //   • For non-absent matchers, also skip rows with an empty value —
  //     matching `{}` or `{ equalTo: '' }` is rarely what the user wants
  //     and we already treat empty like that elsewhere.
  //   • If two rows target the same name, last one wins (Object key
  //     collision is unavoidable with WireMock's map-shaped schema).
  const requestCookies = cookies.value.filter(c =>
    c.direction === 'request'
    && cookieStr(c.key)
    && (c.matcherType === 'absent' || cookieStr(c.value)),
  )
  if (requestCookies.length > 0) {
    req.cookies = Object.fromEntries(
      requestCookies.map(c => [cookieStr(c.key), buildMatcherObject({
        key: cookieStr(c.key),
        matcherType: c.matcherType,
        value: cookieStr(c.value),
        caseInsensitive: c.caseInsensitive === true,
      })]),
    )
  }

  // Query parameters
  const filteredParams = queryParams.value.filter(q => q.key.trim())
  if (filteredParams.length > 0) {
    req.queryParameters = Object.fromEntries(filteredParams.map(q => [q.key, buildMatcherObject(q)]))
  }

  // Body patterns
  const filteredBody = bodyPatterns.value.filter(b => b.matcherType === 'absent' || b.value.trim())
  if (filteredBody.length > 0) {
    req.bodyPatterns = filteredBody.map(buildBodyPatternObject)
  }

  // Basic auth
  // Two emit shapes corresponding to the two UI modes:
  //   plain  → request.basicAuthCredentials = { username, password }
  //   base64 → request.headers.Authorization = { equalTo: 'Basic <b64>' }
  // For the base64 case we merge into the headers map (creating it if the
  // user hadn't added any other headers), and override any Authorization
  // matcher the user might have typed in by hand — see header note above.
  if (enableBasicAuth.value && basicAuthUsername.value.trim() && basicAuthPassword.value.trim()) {
    if (basicAuthMode.value === 'base64') {
      const encoded = encodeBasicAuth(basicAuthUsername.value, basicAuthPassword.value)
      // Strip any case-variant of the Authorization key the user added so we
      // don't end up with both "Authorization" and "authorization" entries.
      const headers: Record<string, unknown> = {}
      const existing = (req.headers as Record<string, unknown> | undefined) ?? {}
      for (const [k, v] of Object.entries(existing)) {
        if (k.toLowerCase() !== 'authorization') headers[k] = v
      }
      headers.Authorization = { equalTo: `Basic ${encoded}` }
      req.headers = headers
    } else {
      req.basicAuthCredentials = {
        username: basicAuthUsername.value,
        password: basicAuthPassword.value,
      }
    }
  }

  return req as unknown as RequestDefinition
}

function buildResponse(): ResponseDefinition {
  const res: Record<string, unknown> = {
    status: responseStatus.value,
  }

  // When fault simulation is on, WireMock discards the rest of the response
  // and emits the fault instead. Ship only `status` (schema requirement) +
  // `fault`, omitting headers/body/delays/chunked so we don't persist dead
  // data that would reappear if the user ever disables the fault later.
  if (enableFault.value) {
    res.fault = faultType.value
    return res as unknown as ResponseDefinition
  }

  // Proxying overrides status/message/headers/body (those come from the
  // upstream response), but delays and chunked-dribble still apply on the
  // proxied response, so we fall through to emit those below.
  if (!enableProxy.value) {
    // Only emit `statusMessage` when the user actually overrode the
    // canonical phrase. Sending it unconditionally would persist the
    // stock phrase ("OK", "Created", …) into the stub, which is just
    // noise on the wire and makes diffs across stubs look meaningful
    // when they aren't.
    if (isStatusMessageOverridden.value && responseStatusMessage.value.trim()) {
      res.statusMessage = responseStatusMessage.value
    }

    // Headers — build the map, then inject Set-Cookie from the Cookies
    // section so it wins over any Set-Cookie the user typed into the
    // headers grid by hand. When there's more than one cookie we emit the
    // header value as a string[] (WireMock's multi-value form); browsers
    // can't parse a comma-joined Set-Cookie because cookie Expires legally
    // contains commas.
    //
    // Regular headers are one row = one (key, value) pair. If the user
    // adds the same key twice we coalesce them into a string[] on the
    // wire so neither value is silently dropped.
    const filteredHeaders = responseHeaders.value.filter(h => h.key.trim())
    const headerMap: Record<string, string | string[]> = {}
    for (const h of filteredHeaders) {
      // Drop any Set-Cookie the user typed in — the dedicated Cookies
      // section is the single source of truth for this header.
      if (h.key.trim().toLowerCase() === 'set-cookie') continue
      const existing = headerMap[h.key]
      if (existing === undefined) {
        headerMap[h.key] = h.value
      } else if (Array.isArray(existing)) {
        existing.push(h.value)
      } else {
        headerMap[h.key] = [existing, h.value]
      }
    }
    // Only response-direction rows become Set-Cookie headers. Request-side
    // rows went out via `request.cookies` in buildRequest.
    const setCookieValues = cookies.value
      .filter(c => c.direction === 'response')
      .map(cookieToSetCookieString)
      .filter(Boolean)
    if (setCookieValues.length === 1) {
      headerMap['Set-Cookie'] = setCookieValues[0]
    } else if (setCookieValues.length > 1) {
      headerMap['Set-Cookie'] = setCookieValues
    }
    if (Object.keys(headerMap).length > 0) {
      res.headers = headerMap
    }

    // Body — the on-wire shape is picked by the Content-Type (see
    // `responseBodyFormat`), not a user toggle.
    if (responseBody.value.trim()) {
      if (responseBodyFormat.value === 'json') {
        // If the user's JSON is actually valid, emit it as `jsonBody`
        // so WireMock can reflect the parsed structure in templates /
        // equalToJson checks. When it isn't valid we fall back to
        // `body` so the mock still saves — the form already surfaces
        // the parse error, so the user knows what to fix.
        try {
          res.jsonBody = JSON.parse(responseBody.value)
        } catch {
          res.body = responseBody.value
        }
      } else if (responseBodyFormat.value === 'base64') {
        res.base64Body = responseBody.value
      } else {
        // Covers both 'xml' and 'text' — WireMock has no dedicated xml
        // body field, so XML is persisted as literal text.
        res.body = responseBody.value
      }
    }
  }

  // Delay
  if (enableDelay.value) {
    if (delayType.value === 'uniform') {
      res.delayDistribution = { type: 'uniform', lower: delayLower.value, upper: delayUpper.value }
    } else if (delayType.value === 'lognormal') {
      res.delayDistribution = { type: 'lognormal', median: delayMedian.value, sigma: delaySigma.value }
    } else if (delayType.value === 'fixed') {
      res.fixedDelayMilliseconds = delayFixed.value
    }
  }

  // Chunked dribble delay
  if (enableChunkedDribble.value) {
    res.chunkedDribbleDelay = {
      numberOfChunks: chunkedNumberOfChunks.value,
      totalDuration: chunkedTotalDuration.value,
    }
  }

  // Proxying
  if (enableProxy.value && proxyBaseUrl.value.trim()) {
    res.proxyBaseUrl = proxyBaseUrl.value
    const proxyHeaders = additionalProxyHeaders.value.filter(h => h.key.trim())
    if (proxyHeaders.length > 0) {
      res.additionalProxyRequestHeaders = Object.fromEntries(proxyHeaders.map(h => [h.key, h.value]))
    }
    if (removeProxyHeaders.value.length > 0) {
      res.removeProxyRequestHeaders = [...removeProxyHeaders.value]
    }
  }

  // Response transformers — apply to any response shape (canned, file
  // reference, even proxied — WireMock can post-process upstream
  // responses through the same extension pipeline). We only emit when
  // at least one transformer is configured.
  const cleanTransformers = responseTransformers.value
    .map(t => t.trim())
    .filter(t => t.length > 0)
  if (cleanTransformers.length > 0) {
    res.transformers = cleanTransformers
  }
  const paramsRaw = responseTransformerParametersJson.value.trim()
  if (paramsRaw) {
    try {
      const parsed = JSON.parse(paramsRaw)
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        res.transformerParameters = parsed as Record<string, unknown>
      }
      // Bad shapes (arrays, primitives) are silently dropped — the
      // form-level `transformerParametersError` already flags them, so
      // saving with one outstanding shouldn't be possible.
    } catch {
      /* validation already surfaces parse errors */
    }
  }

  return res as unknown as ResponseDefinition
}

// ── Submit ────────────────────────────────────────────────────
async function handleSubmit() {
  submitAttempted.value = true
  // Validation is derived live in `validationIssues`; just short-circuit
  // here if anything is outstanding. The sticky right-side report already
  // shows the user exactly what needs attention.
  if (validationIssues.value.length > 0) return

  isSubmitting.value = true
  try {
    const payload: CreateMockPayload = {
      name: mockName.value.trim(),
      description: description.value.trim() || undefined,
      projectId: internalProjectId.value.trim(),
      request: buildRequest(),
      response: buildResponse(),
      persistent: persistent.value,
    }

    // Optional top-level props
    // Coerce priority to a real number before sending. `v-model.number` on an
    // <input type="number"> can yield an empty string when the user clears the
    // field, which would otherwise pass `!== undefined && !== null` and ship a
    // non-numeric value to the backend. `Number.isFinite(Number(...))` rejects
    // undefined, null, '', and NaN in a single check.
    const priorityNum = Number(priority.value)
    if (Number.isFinite(priorityNum)) {
      payload.priority = priorityNum
    }

    // Top-level optional fields. We only ship them when the user has
    // actually set them — sending empty strings would persist meaningless
    // values into the stub mapping (and confuse anyone reading the JSON).
    if (scenarioName.value.trim()) payload.scenarioName = scenarioName.value.trim()
    if (requiredScenarioState.value.trim()) payload.requiredScenarioState = requiredScenarioState.value.trim()
    if (newScenarioState.value.trim()) payload.newScenarioState = newScenarioState.value.trim()
    if (metadataRows.value.length > 0) {
      const filteredMeta = metadataRows.value.filter(r => r.key.trim())
      if (filteredMeta.length > 0) {
        payload.metadata = Object.fromEntries(filteredMeta.map(r => {
          try { return [r.key, JSON.parse(r.value)] } catch { return [r.key, r.value] }
        }))
      }
    }

    // Note: browser cookie attributes (Domain, Path, Expires, Max-Age,
    // Secure, HttpOnly, SameSite) round-trip inside the Set-Cookie header
    // value itself — no metadata sidecar is needed.

    // Webhooks
    // Emitted in the WireMock 3.1.0+ `serveEventListeners` shape:
    // a top-level array of {name, parameters} entries. The listener's
    // `name` is the literal string "webhook" (the extension id WireMock
    // looks up), and all webhook config (method/url/headers/body/delay)
    // lives inside `parameters`. The legacy `postServeActions` map is no
    // longer produced — existing mocks in that shape still load via
    // `populateFromMock`, and are rewritten on next save.
    if (enableWebhook.value && webhookUrl.value.trim()) {
      const parameters: Record<string, unknown> = {
        method: webhookMethod.value,
        url: webhookUrl.value,
      }
      const whHeaders = webhookHeaders.value.filter(h => h.key.trim())
      if (whHeaders.length > 0) {
        parameters.headers = Object.fromEntries(whHeaders.map(h => [h.key, h.value]))
      }
      if (webhookBody.value.trim()) parameters.body = webhookBody.value
      if (webhookDelayMs.value > 0) parameters.delay = { type: 'fixed', milliseconds: webhookDelayMs.value }
      payload.serveEventListeners = [{ name: 'webhook', parameters }]
    }

    // Ship the same cURL the user sees in the "Test with cURL" panel so the
    // backend can persist it alongside the mock. `curlCommand` is empty for
    // pattern-based URL matchers (regex/path-pattern) because those don't
    // correspond to a single runnable request — in that case we send nothing
    // rather than a misleading approximation.
    const curlToSend = curlCommand.value.trim()
    if (curlToSend) {
      payload.curl = curlToSend
    }

    if (isEditMode.value && props.mock) {
      const result = await mockApi.update(props.mock.id, payload)
      showToastMessage(`Mock "${result.name}" updated successfully!`, BaseToastEnum.SUCCESS, 4000)
      emit('updated', result.id)
    } else {
      const result = await mockApi.create(payload)
      showToastMessage(`Mock "${result.name}" created successfully!`, BaseToastEnum.SUCCESS, 4000)
      emit('created', result.id)
      resetForm()
    }
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : `Failed to ${isEditMode.value ? 'update' : 'create'} mock.`
    showToastMessage(msg, BaseToastEnum.ERROR, 5000)
  } finally {
    isSubmitting.value = false
  }
}

function resetForm() {
  mockName.value = ''
  description.value = ''
  urlValue.value = '/'
  // Re-seed priority to the WireMock default so the next mock starts from a
  // known baseline rather than an empty field.
  priority.value = 5
  // Top-level optional fields: clear so the next mock starts blank.
  scenarioName.value = ''
  requiredScenarioState.value = ''
  newScenarioState.value = ''
  // Metadata — preserve locked rows (e.g. project)
  metadataRows.value = metadataRows.value.filter(r => r.locked)
  // Request
  requestHeaders.value = []
  queryParams.value = []
  bodyPatterns.value = []
  cookies.value = []
  enableBasicAuth.value = false
  basicAuthUsername.value = ''
  basicAuthPassword.value = ''
  basicAuthMode.value = 'plain'
  // Response
  responseStatus.value = 200
  // Drop any user override of the status reason phrase so the field
  // tracks the canonical phrase ("OK") for the reset 200 status.
  responseStatusMessageOverride.value = null
  responseHeaders.value = [{ key: 'Content-Type', value: 'application/json' }]
  responseBody.value = ''
  uploadedFileName.value = ''
  uploadedFileSize.value = 0
  responseBodyFileError.value = ''
  // Transformers
  responseTransformers.value = []
  responseTransformerInput.value = ''
  responseTransformerParametersJson.value = ''
  enableDelay.value = false
  enableChunkedDribble.value = false
  enableFault.value = false
  enableProxy.value = false
  proxyBaseUrl.value = ''
  additionalProxyHeaders.value = []
  removeProxyHeaders.value = []
  enableWebhook.value = false
  webhookUrl.value = ''
  webhookBody.value = ''
  webhookHeaders.value = []
  // Collapse all
  Object.keys(collapsedSections.value).forEach(k => collapsedSections.value[k] = true)
  submitAttempted.value = false
}

// ── Copy to clipboard helper ─────────────────────────────────
const copiedField = ref<string | null>(null)

async function copyField(value: string, field: string) {
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = value
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copiedField.value = field
  setTimeout(() => { copiedField.value = null }, 2000)
}

// ── cURL builder ─────────────────────────────────────────────
// Follows the conventions outlined in
// https://medium.com/@MissAmaraKay/what-is-curl-and-why-is-it-all-over-api-docs-b141c33805e0
//   - Start simple: base URL + path, explicit method
//   - Always include an explicit -X <METHOD>
//   - Quote the URL for cross-platform safety
//   - One -H per header, one -d for body
//   - Never emit placeholder brackets — values are shell-escaped literally
const curlCopied = ref(false)

// Shell-escape a value so it is safe inside a single-quoted string.
// Any literal ' becomes '\'' (close quote, escaped quote, reopen quote).
function shEscape(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`
}

const curlCommand = computed(() => {
  const url = urlValue.value.trim()
  if (!url) return ''

  // cURL only makes sense for concrete URLs, not regex patterns.
  if (urlMatchType.value === 'urlPattern' || urlMatchType.value === 'urlPathPattern') {
    return ''
  }

  const configuredMethod = httpMethod.value.toUpperCase()
  const base = WIREMOCK_BASE_URL.replace(/\/$/, '')
  const userPath = url.startsWith('/') ? url : `/${url}`
  // Mirror the prefix that buildRequest() prepends so the cURL the user
  // copies actually hits the stub we register on WireMock.
  const path = projectUrlPrefix.value + userPath

  // Append query params (if any) to the path with proper encoding.
  const filteredParams = queryParams.value.filter(q => q.key.trim())
  let fullPath = path
  if (filteredParams.length > 0) {
    const qs = filteredParams
      .map(q => `${encodeURIComponent(q.key)}=${encodeURIComponent(q.value)}`)
      .join('&')
    fullPath += (fullPath.includes('?') ? '&' : '?') + qs
  }

  // Every body matcher except `absent` has a value that's worth surfacing
  // in the cURL preview. For literal kinds (equalTo / equalToJson /
  // equalToXml / binaryEqualTo) the value IS a concrete payload. For
  // shape kinds (contains / matches / doesNotMatch / matchesJsonPath /
  // matchesXPath) the value is the substring / regex / path expression
  // — not a guaranteed-matching body, but emitting it keeps the preview
  // reactive so the user can see their changes flow through and edit
  // the body manually if a stricter example is needed.
  const LITERAL_BODY_MATCHERS = new Set([
    'equalTo', 'contains',
    'equalToJson', 'matchesJsonPath',
    'equalToXml', 'matchesXPath',
    'binaryEqualTo',
  ])
  const literalBody = bodyPatterns.value.find(
    b => LITERAL_BODY_MATCHERS.has(b.matcherType) && b.value.trim(),
  )

  // `ANY` is a WireMock matcher, not a real HTTP verb — curl would reject it.
  // If the user wants any method, fall back to POST when a body is present,
  // otherwise GET.
  const method = configuredMethod === 'ANY'
    ? (literalBody ? 'POST' : 'GET')
    : configuredMethod

  const lines: string[] = []
  lines.push(`curl -X ${method} ${shEscape(`${base}${fullPath}`)}`)

  // Headers — one per line for readability.
  const filteredHeaders = requestHeaders.value.filter(h => h.key.trim())
  for (const h of filteredHeaders) {
    lines.push(`-H ${shEscape(`${h.key}: ${h.value}`)}`)
  }

  // Request-side cookies translate to a `-b 'name=value; ...'` flag because
  // that's what a browser actually puts into the outbound Cookie header.
  // We only include rows with an `equalTo` matcher and a non-empty value —
  // regex/contains/absent describe *what to match*, not a concrete value,
  // so they have no literal cURL representation. Response-side cookies
  // (direction='response') are emitted on the server reply via Set-Cookie
  // and are intentionally absent from the cURL preview.
  const literalRequestCookies = cookies.value.filter(
    c => c.direction === 'request'
      && c.matcherType === 'equalTo'
      && cookieStr(c.key)
      && cookieStr(c.value),
  )
  if (literalRequestCookies.length > 0) {
    const cookieHeader = literalRequestCookies
      .map(c => `${cookieStr(c.key)}=${cookieStr(c.value)}`)
      .join('; ')
    lines.push(`-b ${shEscape(cookieHeader)}`)
  }

  // Basic auth — shape mirrors the chosen UI mode so the preview always
  // matches what the stub will receive:
  //   plain  → `-u 'user:password'`  (curl encodes for you)
  //   base64 → `-H 'Authorization: Basic <b64>'`  (literal header, matches
  //            the exact value WireMock will compare against)
  if (enableBasicAuth.value && basicAuthUsername.value.trim()) {
    if (basicAuthMode.value === 'base64') {
      const encoded = encodeBasicAuth(basicAuthUsername.value, basicAuthPassword.value)
      lines.push(`-H ${shEscape(`Authorization: Basic ${encoded}`)}`)
    } else {
      lines.push(`-u ${shEscape(`${basicAuthUsername.value}:${basicAuthPassword.value}`)}`)
    }
  }

  // Body — emit for any method if the user has configured a literal-body matcher.
  // (WireMock accepts bodies on GET, so restricting by method would hide valid
  // test payloads from the preview.)
  if (literalBody) {
    const mt = literalBody.matcherType
    const isJsonPayload = mt === 'equalToJson' || mt === 'matchesJsonPath'
    const isXmlPayload = mt === 'equalToXml' || mt === 'matchesXPath'
    const hasContentType = filteredHeaders.some(h => h.key.toLowerCase() === 'content-type')
    if (!hasContentType) {
      const ct = isJsonPayload ? 'application/json'
        : isXmlPayload ? 'application/xml'
        : 'text/plain'
      lines.push(`-H ${shEscape(`Content-Type: ${ct}`)}`)
    }
    let bodyStr = literalBody.value.trim()
    // Pretty-print JSON bodies into a compact form. Skip for matchesJsonPath
    // because the value is a JSONPath expression, not parseable JSON.
    if (mt === 'equalToJson') {
      try { bodyStr = JSON.stringify(JSON.parse(bodyStr)) } catch { /* keep as-is if invalid JSON */ }
    }
    lines.push(`-d ${shEscape(bodyStr)}`)
  }

  return lines.join(' \\\n  ')
})

async function copyCurl() {
  try {
    await navigator.clipboard.writeText(curlCommand.value)
    curlCopied.value = true
    setTimeout(() => { curlCopied.value = false }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = curlCommand.value
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    curlCopied.value = true
    setTimeout(() => { curlCopied.value = false }, 2000)
  }
}

// Reactive body-pattern issue computeds — one per list so the template can
// display each list's issues independently.
const bodyPatternIssues = computed(() => findBodyPatternIssues(bodyPatterns.value))

// Count of active features for badges.
// Only count user-added metadata rows (locked rows like `project` are automatic and
// do not represent a user-enabled advanced feature).
const activeAdvancedCount = computed(() => {
  let count = 0
  if (metadataRows.value.some(r => !r.locked && (r.key.trim() || r.value.trim()))) count++
  if (cookies.value.length > 0) count++
  if (enableBasicAuth.value) count++
  if (enableFault.value) count++
  if (enableProxy.value) count++
  if (enableWebhook.value) count++
  if (enableChunkedDribble.value) count++
  return count
})
</script>

<template>
  <div class="max-w-7xl mx-auto">
    <!-- Toast notification -->
    <BaseToast
      v-if="showToast"
      :mode="toastMode"
      :description="toastMessage"
      :has-close-icon="true"
      positioning="TOP_RIGHT"
    />

    <!-- Header -->
    <div class="mb-8">
      <h1 class="text-2xl font-bold" :class="headingClasses">{{ isEditMode ? 'Edit Mock' : 'Create Mock' }}</h1>
      <p class="mt-1" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
        {{ isEditMode ? 'Update the WireMock stub mapping.' : 'Define a WireMock stub mapping with request and response specifications.' }}
      </p>
    </div>

    <!--
      Two-column layout on lg+: main form on the left, sticky right column
      holding the validation report, cURL preview, and submit button.
      Using CSS grid (rather than flex) so both columns are guaranteed to
      start at the same row baseline — the right column's sticky `top-4`
      offset only kicks in once the user scrolls past the panel, so at rest
      the General card and the Test with cURL card sit at exactly the same
      vertical position. Below lg the grid collapses to a single column and
      the side panel drops beneath the form.
    -->
    <div class="lg:grid lg:grid-cols-[minmax(0,1fr)_24rem] lg:gap-6 lg:items-start">
      <div class="min-w-0 lg:max-w-4xl">
    <form @submit.prevent="handleSubmit" class="space-y-6">

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  GENERAL (collapsible)                                  -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('general')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">General</h2>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.general ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.general" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">

        <!-- Read-only Mock ID (edit mode only) -->
        <div v-if="isEditMode && props.mock" class="mb-4">
          <label class="block text-sm font-medium mb-1" :class="labelClasses">Mock ID</label>
          <div class="flex items-center gap-2">
            <input :value="props.mock.id" readonly
              class="flex-1 rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none transition-colors cursor-default"
              :class="isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400' : 'bg-gray-50 border-gray-300 text-gray-500'" />
            <button type="button" @click="copyField(props.mock!.id, 'mockId')"
              class="inline-flex items-center justify-center w-9 h-9 rounded-lg border transition-colors cursor-pointer shrink-0"
              :class="copiedField === 'mockId'
                ? (isDark ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-400' : 'border-emerald-300 bg-emerald-50 text-emerald-600')
                : (isDark ? 'border-gray-600 text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'border-gray-300 text-gray-400 hover:text-gray-600 hover:bg-gray-50')">
              <svg v-if="copiedField !== 'mockId'" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
              </svg>
              <svg v-else class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Read-only Project info (always shown when a project is bound).
             Project name is fetched from /api/projects/{id}; UUID is the
             internal id. Neither field is editable — the binding is set by
             the route the user came in through. -->
        <div v-if="props.projectId || isEditMode" class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
          <div>
            <label class="block text-sm font-medium mb-1" :class="labelClasses">Project Name</label>
            <input :value="projectName || '—'" readonly
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none transition-colors cursor-default"
              :class="isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400' : 'bg-gray-50 border-gray-300 text-gray-500'" />
          </div>
          <div>
            <label class="block text-sm font-medium mb-1" :class="labelClasses">Project UUID</label>
            <div class="flex items-center gap-2">
              <input :value="internalProjectId" readonly
                class="flex-1 rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none transition-colors cursor-default"
                :class="isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400' : 'bg-gray-50 border-gray-300 text-gray-500'" />
              <button type="button" @click="copyField(internalProjectId, 'projectId')"
                class="inline-flex items-center justify-center w-9 h-9 rounded-lg border transition-colors cursor-pointer shrink-0"
                :class="copiedField === 'projectId'
                  ? (isDark ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-400' : 'border-emerald-300 bg-emerald-50 text-emerald-600')
                  : (isDark ? 'border-gray-600 text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'border-gray-300 text-gray-400 hover:text-gray-600 hover:bg-gray-50')">
                <svg v-if="copiedField !== 'projectId'" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
                </svg>
                <svg v-else class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              </button>
            </div>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div :class="{ 'md:col-span-2': props.projectId || isEditMode }">
            <label for="mockName" class="block text-sm font-medium mb-1" :class="[labelClasses, !mockName.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">Mock Name *</label>
            <input id="mockName" v-model="mockName" type="text" placeholder="e.g. create-event-success"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="requiredInputClasses(mockName)" />
            <!-- Name collision warning. Rendered only when the backend
                 confirmed (true) that a mock with this name already
                 exists under this project. Hidden in the empty / no-
                 collision / transient-error cases so the form stays
                 quiet while the user is mid-edit. -->
            <p v-if="nameExists" class="mt-2 text-xs font-medium"
              :class="isDark ? 'text-amber-300' : 'text-amber-700'">
              A mock with this name already exists in this project.
            </p>
          </div>
          <div v-if="!props.projectId && !isEditMode">
            <label for="projectId" class="block text-sm font-medium mb-1" :class="[labelClasses, !internalProjectId.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">Project ID *</label>
            <input id="projectId" v-model="internalProjectId" type="text" placeholder="UUID of the project"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="requiredInputClasses(internalProjectId)" />
          </div>
        </div>

        <div class="mt-4">
          <label for="description" class="block text-sm font-medium mb-1" :class="labelClasses">Description</label>
          <textarea id="description" v-model="description" rows="4" placeholder="Add notes or description for this mock..."
            class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
        </div>

        <div class="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label for="priority" class="block text-sm font-medium mb-1" :class="labelClasses">Priority</label>
            <input id="priority" v-model.number="priority" type="number" min="1" placeholder="5 (lower = higher priority)"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
          </div>
          <div class="flex flex-col gap-2 justify-end pb-1">
            <div class="flex items-center gap-2">
              <input id="persistent" v-model="persistent" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
              <label for="persistent" class="text-sm" :class="labelClasses">Persistent (survives resets)</label>
            </div>
          </div>
        </div>

        </div><!-- /general body -->
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  METADATA (collapsible)                                 -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('metadata')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Metadata</h2>
            <span v-if="metadataRows.length > 0" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">{{ metadataRows.length }}</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.metadata ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.metadata" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Arbitrary key-value pairs for tagging, filtering, and bulk operations.</p>
          <div class="flex items-center justify-end mb-2">
            <button type="button" @click="metadataRows.push({ key: '', value: '', valueType: 'text' })"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
          </div>
          <div v-for="(row, i) in metadataRows" :key="i" class="mb-2">
            <!--
              Accessible naming for dynamic key/value rows:
              the row itself has no visible label, so each control names
              itself via aria-label so screen readers announce "Metadata
              key 1", "Metadata value type 1", etc.
            -->
            <div class="flex gap-2 items-center">
              <input v-model="row.key" placeholder="Key" :readonly="row.locked"
                :aria-label="`Metadata key ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="row.locked
                  ? (isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400 cursor-default' : 'bg-gray-50 border-gray-300 text-gray-500 cursor-default')
                  : (isMetadataKeyInvalid(row) ? 'ring-2 ring-red-400 border-red-400' : inputClasses)" />
              <select v-model="row.valueType" :disabled="row.locked"
                :aria-label="`Metadata value type ${i + 1}`"
                class="w-24 rounded-lg border px-2 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="row.locked ? (isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400 cursor-default' : 'bg-gray-50 border-gray-300 text-gray-500 cursor-default') : inputClasses">
                <option value="text">Text</option>
                <option value="json">JSON</option>
              </select>
              <input v-if="row.valueType === 'text'" v-model="row.value" placeholder="Value" :readonly="row.locked"
                :aria-label="`Metadata value ${i + 1}`"
                class="flex-[2] rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="row.locked
                  ? (isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400 cursor-default' : 'bg-gray-50 border-gray-300 text-gray-500 cursor-default')
                  : (isMetadataValueMissing(row) ? 'ring-2 ring-red-400 border-red-400' : inputClasses)" />
              <textarea v-else v-model="row.value" placeholder='e.g. {"key": "value"}' :readonly="row.locked" rows="3"
                :aria-label="`Metadata JSON value ${i + 1}`"
                class="flex-[2] rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
                :class="[
                  row.locked ? (isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400 cursor-default' : 'bg-gray-50 border-gray-300 text-gray-500 cursor-default') : inputClasses,
                  !row.locked && (isMetadataValueMissing(row) || (row.value.trim() && !isValidJson(row.value))) ? 'ring-2 ring-red-400 border-red-400' : ''
                ]" />
              <span v-if="row.locked" class="text-xs px-2 py-1 rounded whitespace-nowrap"
                :class="isDark ? 'bg-gray-800 text-gray-500' : 'bg-gray-100 text-gray-400'"
                title="This metadata entry is auto-managed and cannot be edited or removed">locked</span>
              <button v-else type="button" @click="removeRow(metadataRows, i)"
                :aria-label="`Remove metadata row ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
            </div>
            <p v-if="isMetadataKeyInvalid(row)" class="text-xs mt-1 text-red-400">
              Key cannot contain whitespace. Use letters, digits, and punctuation like `_`, `-`, or `.`.
            </p>
            <p v-if="isMetadataValueMissing(row)" class="text-xs mt-1 text-red-400">
              Value is required when a key is provided.
            </p>
            <p v-if="!row.locked && row.valueType === 'json' && row.value.trim() && !isValidJson(row.value)"
              class="text-xs mt-1 text-red-400">Invalid JSON — please check your syntax.</p>
          </div>
          <p v-if="metadataRows.length === 0" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">No metadata defined.</p>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  REQUEST MATCHING (collapsible)                         -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('requestMatching')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Request Matching</h2>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.requestMatching ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.requestMatching" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">

        <!-- Method + URL -->
        <div class="grid grid-cols-1 md:grid-cols-12 gap-4 mb-6">
          <div class="md:col-span-2">
            <label for="httpMethod" class="block text-sm font-medium mb-1" :class="labelClasses">Method</label>
            <select id="httpMethod" v-model="httpMethod"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
              <option v-for="m in HTTP_METHODS" :key="m" :value="m">{{ m }}</option>
            </select>
          </div>
          <div class="md:col-span-3">
            <label for="urlMatchType" class="block text-sm font-medium mb-1" :class="labelClasses">URL Match</label>
            <select id="urlMatchType" v-model="urlMatchType"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
              <option value="url">Exact URL</option>
              <option value="urlPath">Exact Path</option>
              <option value="urlPattern">URL Regex</option>
              <option value="urlPathPattern">Path Regex</option>
            </select>
          </div>
          <div class="md:col-span-7">
            <label for="urlValue" class="block text-sm font-medium mb-1"
              :class="[labelClasses, (!urlValue.trim() || urlValue.trim() === '/' || urlValidationError) ? (isDark ? 'text-red-400' : 'text-red-500') : '']">URL *</label>
            <!--
              URL is namespaced under the project: every mock under "Acme
              Project" gets `/acme-project` prefixed automatically. The
              prefix renders as a non-editable badge attached to the left
              of the input so the user sees the full effective path while
              only typing the part that matters.
            -->
            <div class="flex">
              <span v-if="projectUrlPrefix"
                class="inline-flex items-center rounded-l-lg border border-r-0 px-3 py-2 text-sm font-mono whitespace-nowrap select-none cursor-default"
                :class="isDark ? 'bg-gray-800/70 border-gray-600 text-gray-400' : 'bg-gray-100 border-gray-300 text-gray-500'"
                :title="`Project namespace — locked to project '${projectName}'`">{{ projectUrlPrefix }}</span>
              <input id="urlValue" v-model="urlValue" type="text" placeholder="/api/v1/resource"
                class="flex-1 min-w-0 border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
                :class="[
                  projectUrlPrefix ? 'rounded-r-lg' : 'rounded-lg',
                  urlValidationError
                    ? (isDark ? 'bg-gray-800 border-red-500 text-gray-100 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30' : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30')
                    : requiredInputClasses(urlValue.trim() === '/' ? '' : urlValue),
                ]" />
            </div>
            <p v-if="urlValidationError" class="text-xs text-red-500 mt-1">{{ urlValidationError }}</p>

            <!-- URL collision warning. Rendered only when the backend
                 confirmed (200 OK) that a mock at the namespaced URL
                 already exists under this project. A 404 response — or
                 any transient error — leaves this hidden so the form
                 stays quiet while the user is mid-edit. -->
            <p v-if="urlExists" class="mt-2 text-xs font-medium"
              :class="isDark ? 'text-amber-300' : 'text-amber-700'">
              A mock with this URL already exists in this project.
            </p>

            <div v-if="getRegexExamples(urlMatchType)" class="mt-1.5 text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(urlMatchType)!.tip }}</span>
              <span class="ml-1">Examples:</span>
              <code v-for="(ex, idx) in getRegexExamples(urlMatchType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="urlValue = ex">{{ ex }}</code>
            </div>
          </div>
        </div>

        <!-- Query parameters with matcher types -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-2">
            <label class="block text-sm font-medium" :class="labelClasses">Query Parameters</label>
            <button type="button" @click="addMatcherRow(queryParams)"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
          </div>
          <div v-for="(param, i) in queryParams" :key="i" class="mb-2">
            <div class="flex gap-2 items-start">
              <input v-model="param.key" placeholder="Key"
                :aria-label="`Query parameter name ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowKeyMissing(param) ? requiredInputClasses('') : inputClasses" />
              <select v-model="param.matcherType"
                :aria-label="`Query parameter matcher type ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-if="DATETIME_MATCHERS.has(param.matcherType)" type="datetime-local" step="1"
                :value="dateTimePickerValue(param.value)"
                @input="(e) => setDateTimeFromPicker(param, (e.target as HTMLInputElement).value)"
                :aria-label="`Query parameter value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(param) ? requiredInputClasses('') : inputClasses" />
              <input v-else v-model="param.value" placeholder="Value" :disabled="param.matcherType === 'absent'"
                :aria-label="`Query parameter value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(param) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(queryParams, i)"
                :aria-label="`Remove query parameter ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg pt-1">&times;</button>
            </div>
            <label v-if="supportsCaseInsensitive(param.matcherType)"
              class="flex items-center gap-1.5 text-xs mt-1 ml-[calc(25%+0.5rem)] cursor-pointer"
              :class="labelClasses">
              <input type="checkbox" v-model="param.caseInsensitive"
                :aria-label="`Query parameter ${i + 1} case insensitive`"
                class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
              Case insensitive
            </label>
            <p v-if="isMatcherRowValueMissing(param)" class="text-xs text-red-500 mt-1 ml-[calc(25%+0.5rem)]">
              Query parameter value is required when a name is provided.
            </p>
            <p v-if="isMatcherRowKeyMissing(param)" class="text-xs text-red-500 mt-1">
              Query parameter name is required when a value is provided.
            </p>
            <div v-if="getRegexExamples(param.matcherType)" class="mt-1 ml-[calc(25%+0.5rem)] text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(param.matcherType)!.tip }}</span>
              <span class="ml-1">E.g.</span>
              <code v-for="(ex, idx) in getRegexExamples(param.matcherType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="param.value = ex">{{ ex }}</code>
            </div>
          </div>
          <p v-if="queryParams.length === 0" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">No query parameters defined.</p>
        </div>

        <!-- Request headers with matcher types -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-2">
            <label class="block text-sm font-medium" :class="labelClasses">Headers</label>
            <button type="button" @click="addMatcherRow(requestHeaders)"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
          </div>
          <div v-for="(header, i) in requestHeaders" :key="i" class="mb-2">
            <div class="flex gap-2 items-start">
              <input v-model="header.key" placeholder="Header name"
                :aria-label="`Request header name ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowKeyMissing(header) ? requiredInputClasses('') : inputClasses" />
              <select v-model="header.matcherType"
                :aria-label="`Request header matcher type ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-if="DATETIME_MATCHERS.has(header.matcherType)" type="datetime-local" step="1"
                :value="dateTimePickerValue(header.value)"
                @input="(e) => setDateTimeFromPicker(header, (e.target as HTMLInputElement).value)"
                :aria-label="`Request header expected value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
              <input v-else v-model="header.value" placeholder="Expected value" :disabled="header.matcherType === 'absent'"
                :aria-label="`Request header expected value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(requestHeaders, i)"
                :aria-label="`Remove request header ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg pt-1">&times;</button>
            </div>
            <label v-if="supportsCaseInsensitive(header.matcherType)"
              class="flex items-center gap-1.5 text-xs mt-1 ml-[calc(25%+0.5rem)] cursor-pointer"
              :class="labelClasses">
              <input type="checkbox" v-model="header.caseInsensitive"
                :aria-label="`Request header ${i + 1} case insensitive`"
                class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
              Case insensitive
            </label>
            <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1 ml-[calc(25%+0.5rem)]">
              Header value is required when a header name is provided.
            </p>
            <p v-if="isMatcherRowKeyMissing(header)" class="text-xs text-red-500 mt-1">
              Header name is required when a value is provided.
            </p>
            <div v-if="getRegexExamples(header.matcherType)" class="mt-1 ml-[calc(25%+0.5rem)] text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(header.matcherType)!.tip }}</span>
              <span class="ml-1">E.g.</span>
              <code v-for="(ex, idx) in getRegexExamples(header.matcherType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="header.value = ex">{{ ex }}</code>
            </div>
          </div>
          <p v-if="requestHeaders.length === 0" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">No request headers defined.</p>
        </div>

        <!-- Body patterns with multiple matcher types -->
        <div>
          <div class="flex items-center justify-between mb-2">
            <label class="block text-sm font-medium" :class="labelClasses">Body Patterns</label>
            <button type="button" @click="addBodyPatternRow()"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
          </div>
          <p class="text-xs mb-2" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
            Multiple patterns are allowed and AND-combined (e.g. `contains` + regex both apply).
          </p>
          <!--
            Nonsensical combinations (two literal-body matchers, or `absent`
            combined with anything) surface as a red notice so the user can
            fix them before submitting.
          -->
          <div v-if="bodyPatternIssues.length > 0" class="mb-3 rounded-lg border p-3 text-xs space-y-1"
            :class="isDark ? 'bg-red-500/10 border-red-500/30 text-red-300' : 'bg-red-50 border-red-200 text-red-700'">
            <p v-for="(issue, idx) in bodyPatternIssues" :key="idx">{{ issue }}</p>
          </div>
          <div v-for="(bp, i) in bodyPatterns" :key="i" class="mb-3 p-3 rounded-lg border" :class="isDark ? 'border-gray-700 bg-gray-800/30' : 'border-gray-100 bg-gray-50'">
            <div class="flex gap-2 mb-2 items-center">
              <select v-model="bp.matcherType"
                class="w-1/3 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in BODY_MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <div v-if="bp.matcherType === 'equalToJson'" class="flex items-center gap-3">
                <label class="flex items-center gap-1 text-xs" :class="labelClasses">
                  <input type="checkbox" v-model="bp.ignoreArrayOrder" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" /> Ignore array order
                </label>
                <label class="flex items-center gap-1 text-xs" :class="labelClasses">
                  <input type="checkbox" v-model="bp.ignoreExtraElements" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" /> Ignore extra elements
                </label>
              </div>
              <button type="button" @click="removeRow(bodyPatterns, i)" class="ml-auto text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
            </div>
            <!-- Datetime matchers swap the textarea for a calendar input,
                 sitting in place of the expected value. Picking writes an
                 ISO-8601 timestamp into bp.value. -->
            <input v-if="DATETIME_MATCHERS.has(bp.matcherType)" type="datetime-local" step="1"
              :value="dateTimePickerValue(bp.value)"
              @input="(e) => setDateTimeFromPicker(bp, (e.target as HTMLInputElement).value)"
              class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
              :class="inputClasses" />
            <textarea v-else-if="bp.matcherType !== 'absent'" v-model="bp.value" rows="3"
              :placeholder="bp.matcherType === 'equalToJson' ? '{ &quot;key&quot;: &quot;value&quot; }'
                : bp.matcherType === 'matchesJsonPath' ? '$.name'
                : bp.matcherType === 'equalToXml' ? '&lt;user id=&quot;1&quot;&gt;Alice&lt;/user&gt;'
                : 'Pattern...'"
              class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
              :class="[inputClasses, bodyPatternRowFormatError(bp) ? 'border-red-400' : '']" />
            <p v-if="bodyPatternRowFormatError(bp)" class="text-xs text-red-500 mt-1">
              {{ bodyPatternRowFormatError(bp) }}
            </p>
            <div v-if="getRegexExamples(bp.matcherType)" class="mt-1 text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(bp.matcherType)!.tip }}</span>
              <span class="ml-1">E.g.</span>
              <code v-for="(ex, idx) in getRegexExamples(bp.matcherType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="bp.value = ex">{{ ex }}</code>
            </div>

            <!--
              Per-matcher advanced fieldsets. Only the relevant block
              renders for the chosen matcher kind. Empty values aren't
              emitted on save (see buildBodyPatternObject) so users can
              leave any of these blank without polluting the wire payload.
            -->

            <!-- JSONPath: optional sub-matcher and vary-by. Setting either
                 promotes the pattern from the bare-expression form
                 (`matchesJsonPath: "$.name"`) to the object form
                 (`matchesJsonPath: { expression, equalTo|contains|… }`).
                 The sub-matcher is applied to the value the JSONPath
                 expression extracts from the request body. -->
            <div v-if="bp.matcherType === 'matchesJsonPath'" class="mt-2 space-y-3">
              <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div>
                  <label class="block text-xs font-medium mb-1" :class="labelClasses">
                    Sub-matcher <span class="font-normal" :class="isDark ? 'text-gray-500' : 'text-gray-400'">(optional)</span>
                  </label>
                  <select v-model="bp.jsonPathSubMatcher"
                    class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                    <option v-for="opt in JSONPATH_SUB_MATCHERS" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
                  </select>
                </div>
                <div v-if="bp.jsonPathSubMatcher && bp.jsonPathSubMatcher !== 'absent'">
                  <label class="block text-xs font-medium mb-1" :class="labelClasses">
                    Sub-matcher value
                  </label>
                  <input v-model="bp.jsonPathSubMatcherValue" type="text"
                    :placeholder="bp.jsonPathSubMatcher === 'equalToJson' ? '{ &quot;name&quot;: &quot;Alice&quot; }' : 'Alice'"
                    class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                </div>
              </div>
              <p v-if="bp.jsonPathSubMatcher" class="text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                Saves as <code class="font-mono">{{ '{' }} matchesJsonPath: {{ '{' }} expression, {{ bp.jsonPathSubMatcher }}{{ bp.jsonPathSubMatcher === 'absent' ? ': true' : ': "…"' }} {{ '}' }} {{ '}' }}</code>.
              </p>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">
                  vary-by <span class="font-normal" :class="isDark ? 'text-gray-500' : 'text-gray-400'">(optional)</span>
                </label>
                <input v-model="bp.varyBy" type="text" placeholder="$.tenantId"
                  class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  Sub-expression that controls templated stub variation. Leave blank for plain JSONPath matching.
                </p>
              </div>
            </div>

            <!-- JSON Schema: optional schemaVersion override. -->
            <div v-if="bp.matcherType === 'matchesJsonSchema'" class="mt-2">
              <label class="block text-xs font-medium mb-1" :class="labelClasses">Schema Version</label>
              <select v-model="bp.schemaVersion"
                class="w-full md:w-64 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="v in JSON_SCHEMA_VERSIONS" :key="v.value" :value="v.value">{{ v.label }}</option>
              </select>
              <p class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                The textarea above takes a JSON Schema document; this dropdown selects which draft WireMock validates against.
              </p>
            </div>

            <!-- equalToXml: enablePlaceholders toggle. -->
            <div v-if="bp.matcherType === 'equalToXml'" class="mt-2">
              <label class="flex items-center gap-2 text-xs cursor-pointer" :class="labelClasses">
                <input type="checkbox" v-model="bp.enablePlaceholders"
                  class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                Enable placeholders
                <span class="font-normal" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  — <code class="font-mono">${...}</code> tokens in the expected XML are treated as wildcards
                </span>
              </label>
            </div>

            <!-- xPathNamespaces — applies to matchesXPath and equalToXml.
                 Each entry is a (prefix → URI) pair; blank rows are dropped
                 on save. Required when matching XML that uses namespaces. -->
            <div v-if="bp.matcherType === 'matchesXPath' || bp.matcherType === 'equalToXml'" class="mt-3">
              <div class="flex items-center justify-between mb-1">
                <label class="block text-xs font-medium" :class="labelClasses">XML Namespaces</label>
                <button type="button"
                  @click="(bp.xPathNamespaces ||= []).push({ prefix: '', uri: '' })"
                  class="text-xs font-medium px-2 py-0.5 rounded border transition-colors cursor-pointer"
                  :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
              </div>
              <div v-for="(ns, nsi) in bp.xPathNamespaces || []" :key="nsi" class="flex gap-2 mb-1.5">
                <input v-model="ns.prefix" type="text" placeholder="prefix (e.g. soap)"
                  class="w-1/3 rounded-lg border px-3 py-1.5 text-xs font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                <input v-model="ns.uri" type="text" placeholder="URI (e.g. http://schemas.xmlsoap.org/soap/envelope/)"
                  class="flex-1 rounded-lg border px-3 py-1.5 text-xs font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                <button type="button" @click="bp.xPathNamespaces!.splice(nsi, 1)"
                  class="text-red-400 hover:text-red-600 px-2 text-lg leading-none">&times;</button>
              </div>
              <p v-if="!bp.xPathNamespaces?.length" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                No namespaces — add one to match XPath expressions in namespaced XML.
              </p>
            </div>

            <!-- DateTime advanced options — apply to before / after /
                 equalToDateTime. truncateExpected/Actual snap both sides
                 to a coarser unit before comparison; expectedOffset shifts
                 the expected value by N units. -->
            <div v-if="DATETIME_BODY_MATCHERS.has(bp.matcherType)" class="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3">
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Truncate Expected</label>
                <select v-model="bp.truncateExpected"
                  class="w-full rounded-lg border px-3 py-1.5 text-xs focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                  <option v-for="t in TRUNCATE_OPTIONS" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Truncate Actual</label>
                <select v-model="bp.truncateActual"
                  class="w-full rounded-lg border px-3 py-1.5 text-xs focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                  <option v-for="t in TRUNCATE_OPTIONS" :key="t.value" :value="t.value">{{ t.label }}</option>
                </select>
              </div>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Expected Offset</label>
                <input v-model.number="bp.expectedOffset" type="number" placeholder="e.g. 3"
                  class="w-full rounded-lg border px-3 py-1.5 text-xs focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              </div>
              <div>
                <label class="block text-xs font-medium mb-1" :class="labelClasses">Offset Unit</label>
                <select v-model="bp.expectedOffsetUnit"
                  class="w-full rounded-lg border px-3 py-1.5 text-xs focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                  <option v-for="u in OFFSET_UNITS" :key="u.value" :value="u.value">{{ u.label }}</option>
                </select>
              </div>
            </div>
          </div>
          <p v-if="bodyPatterns.length === 0" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">No body patterns defined.</p>
        </div>
        </div><!-- /request matching body -->
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  RESPONSE DEFINITION (collapsible)                      -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('responseDefinition')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Response Definition</h2>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.responseDefinition ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.responseDefinition" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">

        <!--
          Response fields are driven by two switches further down the form:
            - Fault simulation: WireMock discards the whole response (status,
              headers, body, delays) and emits the fault instead.
            - Proxying: WireMock forwards the request upstream; the canned
              status/headers/body are unused, but response delays still apply.
          We surface both states clearly and gate the affected inputs behind
          `fieldset[disabled]` so users can't waste effort configuring dead
          data.
        -->
        <div
          v-if="enableFault"
          class="mb-4 rounded-lg border p-3 text-sm"
          :class="isDark
            ? 'bg-amber-500/10 border-amber-500/30 text-amber-300'
            : 'bg-amber-50 border-amber-200 text-amber-700'"
        >
          <p class="font-medium">Fault simulation is active.</p>
          <p class="mt-0.5 text-xs opacity-90">
            WireMock will return the configured fault instead of this response,
            so status, headers, body, and delays are ignored. Disable Fault
            Simulation below to edit these fields.
          </p>
        </div>
        <div
          v-else-if="enableProxy"
          class="mb-4 rounded-lg border p-3 text-sm"
          :class="isDark
            ? 'bg-amber-500/10 border-amber-500/30 text-amber-300'
            : 'bg-amber-50 border-amber-200 text-amber-700'"
        >
          <p class="font-medium">Proxying is active.</p>
          <p class="mt-0.5 text-xs opacity-90">
            WireMock will forward matched requests to the configured upstream,
            so the canned status, headers, and body below are ignored. Response
            delays still apply on the returned proxy response. Disable
            Proxying below to serve a canned response instead.
          </p>
        </div>

        <fieldset :disabled="enableFault || enableProxy" :class="(enableFault || enableProxy) ? 'opacity-50 pointer-events-none select-none' : ''">

        <!-- Status -->
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-6">
          <div>
            <label for="responseStatus" class="block text-sm font-medium mb-1" :class="[labelClasses, !responseStatus ? (isDark ? 'text-red-400' : 'text-red-500') : '']">Status Code *</label>
            <select id="responseStatus" v-model.number="responseStatus"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="requiredInputClasses(responseStatus)">
              <option v-for="s in HTTP_STATUS_CODES" :key="s.value" :value="s.value">{{ s.label }}</option>
            </select>
          </div>
          <div>
            <div class="flex items-center justify-between mb-1">
              <label for="responseStatusMessage" class="block text-sm font-medium" :class="labelClasses">Status Message</label>
              <button v-if="isStatusMessageOverridden" type="button"
                @click="responseStatusMessageOverride = null"
                class="text-xs font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-emerald-400 hover:text-emerald-300' : 'text-emerald-600 hover:text-emerald-700'"
                title="Clear the override and re-track the canonical phrase for this status code">
                Reset
              </button>
            </div>
            <input id="responseStatusMessage" v-model="responseStatusMessage" type="text"
              :placeholder="canonicalStatusMessage || 'Custom reason phrase'"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
              :class="inputClasses" />
            <p class="text-xs mt-1" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
              <template v-if="!isStatusMessageOverridden">
                Defaults to the canonical phrase for the selected status code (<code class="font-mono">{{ canonicalStatusMessage || '—' }}</code>). Type to override.
              </template>
              <template v-else>
                Custom reason phrase — emitted as <code class="font-mono">statusMessage</code> on the response.
              </template>
            </p>
          </div>
        </div>

        <!-- Response headers -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-2">
            <label class="block text-sm font-medium" :class="labelClasses">Headers</label>
            <button type="button" @click="addRow(responseHeaders)"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
          </div>
          <div v-for="(header, i) in responseHeaders" :key="i" class="mb-2">
            <div class="flex gap-2 items-center">
              <!-- Header name: select from common headers or type custom -->
              <select v-if="!header._customKey"
                :aria-label="`Response header name ${i + 1}`"
                :value="responseHeaderOptions(header.key).includes(header.key) ? header.key : '__custom__'"
                @change="($event.target as HTMLSelectElement).value === '__custom__' ? (header._customKey = true, header.key = '') : header.key = ($event.target as HTMLSelectElement).value"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowKeyMissing(header) ? requiredInputClasses('') : inputClasses">
                <option value="" disabled>Select header...</option>
                <option v-for="h in responseHeaderOptions(header.key)" :key="h" :value="h">{{ h }}</option>
                <option value="__custom__">Custom...</option>
              </select>
              <div v-else class="flex-1 flex gap-1 items-center">
                <input v-model="header.key"
                  :aria-label="`Response header custom name ${i + 1}`"
                  placeholder="Custom header name"
                  class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="isMatcherRowKeyMissing(header) ? requiredInputClasses('') : inputClasses" />
                <button type="button" @click="header._customKey = false"
                  class="text-xs px-2 py-1 rounded border transition-colors whitespace-nowrap"
                  :class="isDark ? 'text-gray-400 border-gray-600 hover:bg-gray-700' : 'text-gray-500 border-gray-300 hover:bg-gray-100'"
                  title="Switch back to header list">List</button>
              </div>
              <!-- Header value -->
              <select v-if="isContentTypeHeader(header.key)" v-model="header.value"
                :aria-label="`Response header value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses">
                <option v-for="ct in contentTypeOptions(header.value)" :key="ct" :value="ct">{{ ct }}</option>
              </select>
              <input v-else v-model="header.value"
                :aria-label="`Response header value ${i + 1}`"
                placeholder="Header value (supports Handlebars templates)"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(responseHeaders, i)"
                :aria-label="`Remove response header ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
            </div>
            <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1">
              Header value is required when a header name is provided.
            </p>
            <p v-if="isMatcherRowKeyMissing(header)" class="text-xs text-red-500 mt-1">
              Header name is required when a value is provided.
            </p>

          </div>
        </div>

        <!-- Response body — the payload format is derived from the
             Content-Type response header (see `responseBodyFormat`), so
             there's no format dropdown here. A small pill next to the
             label shows the detected format; validation errors appear
             inline below the textarea. -->
        <div class="mb-6">
          <div class="flex items-center justify-between gap-4 mb-2">
            <div class="flex items-center gap-2">
              <label class="block text-sm font-medium" :class="labelClasses">Response Body</label>
              <span class="inline-flex items-center text-xs font-medium px-2 py-0.5 rounded-full"
                :class="isDark ? 'bg-emerald-900/40 text-emerald-300' : 'bg-emerald-100 text-emerald-800'"
                :title="'Validation and payload shape derived from Content-Type (' + responseContentType + ')'">
                {{ responseBodyFormatLabel }}
              </span>
            </div>
          </div>
          <!-- File upload helper. Reads the picked file based on the
               detected response body format:
                 • JSON / XML / Text  → file contents loaded as text and
                   validated against the format.
                 • Binary (base64)    → file read as a data URL, the
                   `data:<mime>;base64,` prefix stripped, the bare
                   base64 payload written into the body.
               Files with a MIME / extension that doesn't match the
               selected Content-Type are rejected before reading.
               Uploading always OVERWRITES the body — any previously
               typed or uploaded content is replaced. -->
          <div class="flex flex-wrap items-center gap-2 mb-2">
            <label class="inline-flex items-center gap-1.5 text-xs font-medium px-3 py-1.5 rounded-lg border cursor-pointer transition-colors"
              :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'"
              :title="`Pick a file matching ${responseContentType} — its contents will replace the response body. Accepted: ${responseBodyFileAccept}`">
              <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 16v2a2 2 0 002 2h12a2 2 0 002-2v-2M7 10l5-5m0 0l5 5m-5-5v12" />
              </svg>
              Upload file
              <input type="file" :accept="responseBodyFileAccept"
                @change="handleResponseBodyFileUpload"
                class="hidden" />
            </label>
            <span v-if="uploadedFileName" class="text-xs" :class="isDark ? 'text-gray-400' : 'text-gray-600'">
              <code class="font-mono">{{ uploadedFileName }}</code>
              <span v-if="uploadedFileSize" class="ml-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                ({{ formatFileSize(uploadedFileSize) }})
              </span>
              <button type="button" @click="clearUploadedFile"
                class="ml-2 text-red-400 hover:text-red-600"
                :aria-label="`Remove uploaded file ${uploadedFileName}`"
                title="Clear the uploaded file and reset the response body">&times;</button>
            </span>
            <span v-else class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              Pick a file matching <code class="font-mono">{{ responseContentType }}</code> — its contents will replace the body below.
            </span>
          </div>
          <p v-if="responseBodyFileError" class="text-xs text-red-500 mt-1 mb-1">{{ responseBodyFileError }}</p>
          <textarea v-model="responseBody" rows="6"
            aria-label="Response body"
            :placeholder="responseBodyFormat === 'json' ? '{ &quot;id&quot;: &quot;123&quot;, &quot;status&quot;: &quot;ok&quot; }' :
              responseBodyFormat === 'xml' ? '&lt;response&gt;&lt;status&gt;ok&lt;/status&gt;&lt;/response&gt;' :
              responseBodyFormat === 'base64' ? 'Base64-encoded binary payload — or click Upload file above' :
              'Plain-text response body…'"
            class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
            :class="[inputClasses, { 'border-red-400': !!responseBodyFormatError }]" />
          <p v-if="responseBodyFormatError" class="text-xs text-red-500 mt-1">{{ responseBodyFormatError }}</p>
        </div>

        </fieldset><!-- /disabled-when-fault-or-proxy (status/headers/body) -->

        <!--
          Delay lives in its own fieldset because proxy leaves response delays
          intact — they still apply to the proxied response. Only fault kills
          delays entirely.
        -->
        <fieldset :disabled="enableFault" :class="enableFault ? 'opacity-50 pointer-events-none select-none' : ''">
        <!-- Delay -->
        <div class="border-t pt-4 mb-4" :class="isDark ? 'border-gray-700' : 'border-gray-100'">
          <div class="flex items-center gap-2 mb-3">
            <input id="enableDelay" v-model="enableDelay" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
            <label for="enableDelay" class="text-sm font-medium" :class="labelClasses">Response Delay</label>
          </div>
          <div v-if="enableDelay" class="pl-2 space-y-3">
            <div>
              <label for="delayType" class="block text-sm font-medium mb-1" :class="labelClasses">Delay Type</label>
              <select id="delayType" v-model="delayType"
                class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option value="uniform">Uniform</option>
                <option value="lognormal">Log-normal</option>
                <option value="fixed">Fixed</option>
              </select>
              <p class="text-xs mt-1" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                {{ DELAY_TYPE_DESCRIPTIONS[delayType] }}
              </p>
            </div>
            <div v-if="delayType === 'uniform'">
              <div class="grid grid-cols-2 gap-4">
                <div>
                  <label class="block text-sm mb-1" :class="labelClasses">Lower (ms)</label>
                  <input v-model.number="delayLower" type="number" min="0"
                    class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="[inputClasses, delayRangeInvalid ? (isDark ? 'border-red-500' : 'border-red-400') : '']" />
                  <p v-if="formatMsAsSeconds(delayLower)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                    {{ formatMsAsSeconds(delayLower) }}
                  </p>
                </div>
                <div>
                  <label class="block text-sm mb-1" :class="labelClasses">Upper (ms)</label>
                  <input v-model.number="delayUpper" type="number" min="0"
                    class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="[inputClasses, delayRangeInvalid ? (isDark ? 'border-red-500' : 'border-red-400') : '']" />
                  <p v-if="formatMsAsSeconds(delayUpper)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                    {{ formatMsAsSeconds(delayUpper) }}
                  </p>
                </div>
              </div>
              <p v-if="delayRangeInvalid" class="text-xs text-red-500 mt-1">
                Lower bound cannot be greater than upper bound.
              </p>
            </div>
            <div v-if="delayType === 'lognormal'" class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm mb-1" :class="labelClasses">Median (ms)</label>
                <input v-model.number="delayMedian" type="number" min="0"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                <p v-if="formatMsAsSeconds(delayMedian)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                  {{ formatMsAsSeconds(delayMedian) }}
                </p>
              </div>
              <div>
                <label class="block text-sm mb-1" :class="labelClasses">Sigma</label>
                <input v-model.number="delaySigma" type="number" min="0" step="0.01"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              </div>
            </div>
            <div v-if="delayType === 'fixed'">
              <label class="block text-sm mb-1" :class="labelClasses">Delay (ms)</label>
              <input v-model.number="delayFixed" type="number" min="0"
                class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <p v-if="formatMsAsSeconds(delayFixed)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                {{ formatMsAsSeconds(delayFixed) }}
              </p>
            </div>
          </div>
        </div>

        </fieldset><!-- /disabled-when-fault (delay) -->
        </div><!-- /response definition body -->
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  SCENARIO (collapsible)                                 -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <!-- Scenario state machine — three optional fields that turn the
           stub into a state-machine step. WireMock lets a single
           scenarioName own multiple stubs, gated by requiredScenarioState
           and advanced via newScenarioState. The fields are always
           rendered (no enable toggle) because partial fills make sense
           during authoring; on save we only ship the trimmed non-empty
           ones, so leaving them blank produces no `scenarioName` etc.
           on the wire. -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('scenario')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Scenario</h2>
            <span v-if="scenarioName.trim()" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">{{ scenarioName.trim() }}</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.scenario ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.scenario" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-3" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
            Group stubs into a stateful workflow. Set <code class="font-mono">scenarioName</code> on every stub in the group; <code class="font-mono">requiredScenarioState</code> is the state the scenario must be in for this stub to match, and <code class="font-mono">newScenarioState</code> is the state to transition to after this stub serves a request.
          </p>
          <div class="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div>
              <label for="scenarioName" class="block text-xs font-medium mb-1" :class="labelClasses">Scenario Name</label>
              <input id="scenarioName" v-model="scenarioName" type="text" placeholder="e.g. login-flow"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
            </div>
            <div>
              <label for="requiredScenarioState" class="block text-xs font-medium mb-1" :class="labelClasses">Required State</label>
              <input id="requiredScenarioState" v-model="requiredScenarioState" type="text" placeholder="e.g. Started"
                :disabled="!scenarioName.trim()"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                :class="inputClasses" />
            </div>
            <div>
              <label for="newScenarioState" class="block text-xs font-medium mb-1" :class="labelClasses">New State (after match)</label>
              <input id="newScenarioState" v-model="newScenarioState" type="text" placeholder="e.g. Authenticated"
                :disabled="!scenarioName.trim()"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                :class="inputClasses" />
            </div>
          </div>
          <p v-if="!scenarioName.trim() && (requiredScenarioState.trim() || newScenarioState.trim())"
            class="text-xs mt-2" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
            State fields are ignored without a Scenario Name — set a name first.
          </p>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  RESPONSE TRANSFORMERS (collapsible)                    -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <!--
        Response Transformers — extension names that mutate the
        outgoing response (e.g. response-template for Handlebars
        interpolation). Apply to canned responses AND proxied
        responses; only Fault kills them entirely (no response is
        emitted to transform).
      -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('responseTransformers')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Response Transformers</h2>
            <span v-if="responseTransformers.length > 0" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">{{ responseTransformers.length }}</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.responseTransformers ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.responseTransformers" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <fieldset :disabled="enableFault" :class="enableFault ? 'opacity-50 pointer-events-none select-none' : ''">
            <p v-if="enableFault" class="text-xs mb-3" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
              Disabled while Fault Simulation is enabled — no response is emitted to transform.
            </p>
            <p class="text-xs mb-3" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              Extension names applied to the response in order. Click a chip to add a known transformer, or type a custom one.
            </p>
            <!-- Quick-add chips for the well-known transformers -->
            <div class="flex flex-wrap gap-1.5 mb-2">
              <code v-for="t in COMMON_TRANSFORMERS" :key="t"
                class="inline-flex items-center px-2 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="responseTransformers.includes(t) ? 'Already added' : 'Click to add'"
                @click="addResponseTransformerValue(t)">{{ t }}</code>
            </div>
            <!-- Free-text + selected-list -->
            <div class="flex gap-2 mb-2">
              <input v-model="responseTransformerInput" type="text" placeholder="Custom transformer name"
                @keyup.enter="addResponseTransformer"
                class="flex-1 rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <button type="button" @click="addResponseTransformer"
                class="px-3 py-2 text-xs font-medium rounded-lg cursor-pointer"
                :class="isDark ? 'text-emerald-400 hover:text-emerald-300 bg-gray-800' : 'text-emerald-600 hover:text-emerald-800 bg-gray-100'">Add</button>
            </div>
            <div v-if="responseTransformers.length > 0" class="flex flex-wrap gap-2 mb-2">
              <span v-for="(t, i) in responseTransformers" :key="i"
                class="inline-flex items-center gap-1 text-xs px-2 py-1 rounded font-mono"
                :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-600'">
                {{ t }}
                <button type="button" @click="removeResponseTransformer(i)"
                  :aria-label="`Remove transformer ${t}`"
                  class="text-red-400 hover:text-red-600">&times;</button>
              </span>
            </div>
            <p v-else class="text-xs italic mb-2" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              No transformers — the response is served verbatim.
            </p>

            <!-- transformerParameters — opaque to the UI; we just shuttle the
                 JSON map verbatim to whatever extension reads it. -->
            <div class="mt-3">
              <label class="block text-xs font-medium mb-1" :class="labelClasses">
                Transformer Parameters <span class="font-normal" :class="isDark ? 'text-gray-500' : 'text-gray-400'">(optional JSON object)</span>
              </label>
              <textarea v-model="responseTransformerParametersJson" rows="3"
                placeholder='{ "fieldName": "value", "templateUrl": "templates/foo.json.hbs" }'
                aria-label="Response transformer parameters JSON"
                class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
                :class="[inputClasses, transformerParametersError ? 'border-red-400' : '']" />
              <p v-if="transformerParametersError" class="text-xs text-red-500 mt-1">
                {{ transformerParametersError }}
              </p>
              <p v-else class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                Passed verbatim to the transformer extension. Leave blank if your transformers don't need parameters.
              </p>
            </div>
          </fieldset>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  COOKIES (collapsible)                                  -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('cookies')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Cookies</h2>
            <span v-if="cookies.length > 0" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">{{ cookies.length }}</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.cookies ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.cookies" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <!-- Info banner — explains both cookie directions so users know
               which "+ Add" button to pick for their use case. -->
          <div class="rounded-lg border p-3 mb-4 text-xs leading-relaxed"
            :class="isDark ? 'border-gray-700 bg-gray-800/40 text-gray-300' : 'border-gray-200 bg-gray-50 text-gray-600'">
            <p class="font-medium mb-1" :class="isDark ? 'text-gray-200' : 'text-gray-700'">
              About cookies
            </p>
            <p class="mb-2">
              Each row is either a <strong>request</strong> matcher or a <strong>response</strong> cookie — pick the direction when you add the row.
            </p>
            <ul class="list-disc pl-4 space-y-1">
              <li><strong>Request</strong>: WireMock matches <code class="font-mono">name=value</code> against the incoming <code class="font-mono">Cookie</code> header using the chosen matcher (equalTo, matches, contains, absent…). The cURL preview includes these as <code class="font-mono">-b 'name=value'</code>. Browsers don't transmit Domain/Path/Secure/etc. on requests, so those attributes don't apply here.</li>
              <li><strong>Response</strong>: the stub emits a <code class="font-mono">Set-Cookie</code> header with <code class="font-mono">name=value</code> plus any attributes you fill in (<strong>Domain</strong>, <strong>Path</strong>, <strong>Expires</strong>, <strong>Max-Age</strong>, <strong>Secure</strong>, <strong>HttpOnly</strong>, <strong>SameSite</strong>). The browser stores it and sends it back on subsequent requests the attributes allow.</li>
            </ul>
          </div>

          <!-- Two add buttons — one per direction — so the user picks the
               cookie's role at creation time. Direction is fixed after
               creation: to change it, delete the row and add a new one
               with the other button. -->

          <div class="flex items-center justify-end gap-2 mb-2">
            <button type="button" @click="addCookieRow('request')"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer"
              :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'"
              title="Add a cookie to match on the incoming request">+ Add request cookie</button>
            <button type="button" @click="addCookieRow('response')"
              class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer"
              :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'"
              title="Add a cookie to emit on the response via Set-Cookie">+ Add response cookie</button>
          </div>
          <div v-for="(cookie, i) in cookies" :key="i" class="mb-3 rounded-lg border p-3"
            :class="isDark ? 'border-gray-700 bg-gray-800/20' : 'border-gray-200 bg-gray-50/40'">
            <!-- Direction label — static, non-editable. The row's direction
                 is fixed at creation time by whichever + Add button the user
                 pressed. The label just keeps request rows visually distinct
                 from response rows in a mixed list, and reminds the user
                 what this row does; to change direction, delete the row and
                 add a new one with the desired button. -->
            <div class="flex items-center gap-2 mb-2">
              <span class="inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full"
                :class="isDark ? 'bg-emerald-900/40 text-emerald-300' : 'bg-emerald-100 text-emerald-800'">
                {{ cookie.direction === 'request' ? 'Request cookie' : 'Response cookie' }}
              </span>
              <span class="text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                {{ cookie.direction === 'request' ? 'Matches an incoming Cookie header' : 'Emits a Set-Cookie response header' }}
              </span>
            </div>

            <!-- Row 1 — name + (matcher type) + value.
                 • Request: name + matcher dropdown + value (mirrors the
                   Headers/Query/etc. matcher row style).
                 • Response: name + value (no matcher — response cookies are
                   literal values, not patterns). -->
            <div class="flex gap-2 items-start">
              <input v-model="cookie.key" placeholder="Cookie name"
                class="rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="[
                  isMatcherRowKeyMissing(cookie) ? requiredInputClasses('') : inputClasses,
                  cookie.direction === 'request' ? 'w-1/4' : 'w-1/3',
                ]" />
              <select v-if="cookie.direction === 'request'" v-model="cookie.matcherType"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-if="cookie.direction === 'request' && DATETIME_MATCHERS.has(cookie.matcherType)"
                type="datetime-local" step="1"
                :value="dateTimePickerValue(cookie.value)"
                @input="(e) => setDateTimeFromPicker(cookie, (e.target as HTMLInputElement).value)"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(cookie) ? requiredInputClasses('') : inputClasses" />
              <input v-else v-model="cookie.value" placeholder="Value"
                :disabled="cookie.direction === 'request' && cookie.matcherType === 'absent'"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(cookie) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(cookies, i)" class="text-red-400 hover:text-red-600 px-2 text-lg pt-1" title="Remove cookie">&times;</button>
            </div>
            <label v-if="cookie.direction === 'request' && supportsCaseInsensitive(cookie.matcherType)"
              class="flex items-center gap-1.5 text-xs mt-1 ml-[calc(25%+0.5rem)] cursor-pointer"
              :class="labelClasses">
              <input type="checkbox" v-model="cookie.caseInsensitive"
                :aria-label="`Cookie ${i + 1} case insensitive`"
                class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
              Case insensitive
            </label>
            <p v-if="isMatcherRowValueMissing(cookie)" class="text-xs text-red-500 mt-1">
              Cookie value is required when a cookie name is provided.
            </p>
            <p v-if="isMatcherRowKeyMissing(cookie)" class="text-xs text-red-500 mt-1">
              Cookie name is required when a value is provided.
            </p>
            <div v-if="cookie.direction === 'request' && getRegexExamples(cookie.matcherType)"
              class="mt-1 ml-[calc(25%+0.5rem)] text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(cookie.matcherType)!.tip }}</span>
              <span class="ml-1">E.g.</span>
              <code v-for="(ex, idx) in getRegexExamples(cookie.matcherType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="cookie.value = ex">{{ ex }}</code>
            </div>

            <!-- Row 2 — browser-side cookie attributes. Only meaningful for
                 response cookies; request rows hide this fieldset entirely
                 (browsers never transmit Domain/Path/Secure/etc. on
                 outbound requests). -->
            <fieldset v-if="cookie.direction === 'response'">
              <p class="text-xs font-medium mt-4 mb-2" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
                Cookie attributes <span class="font-normal" :class="isDark ? 'text-gray-500' : 'text-gray-400'">(optional — appended to the Set-Cookie header)</span>
              </p>

              <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div>
                  <label class="flex items-center gap-1 text-xs font-medium mb-1" :class="labelClasses">
                    Domain
                    <span class="cursor-help" :class="isDark ? 'text-gray-500' : 'text-gray-400'"
                      title="Hosts the cookie is sent to. e.g. 'example.com' (the cookie is also sent to all subdomains). Omit to scope it to the exact origin that set it.">ⓘ</span>
                  </label>
                  <input v-model="cookie.domain" type="text" placeholder="example.com"
                    class="w-full rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="cookieStr(cookie.domain) && !isValidCookieDomain(cookie.domain) ? requiredInputClasses('') : inputClasses" />
                  <p v-if="cookieStr(cookie.domain) && !isValidCookieDomain(cookie.domain)" class="text-xs text-red-500 mt-1">
                    Enter a valid host (e.g. <code class="font-mono">example.com</code> or <code class="font-mono">.example.com</code>) — no scheme, path, or spaces.
                  </p>
                </div>
                <div>
                  <label class="flex items-center gap-1 text-xs font-medium mb-1" :class="labelClasses">
                    Path
                    <span class="cursor-help" :class="isDark ? 'text-gray-500' : 'text-gray-400'"
                      title="URL path prefix the cookie is sent with. e.g. '/' (every request) or '/api' (only requests under /api).">ⓘ</span>
                  </label>
                  <input v-model="cookie.path" type="text" placeholder="/"
                    class="w-full rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="cookieStr(cookie.path) && !isValidCookiePath(cookie.path) ? requiredInputClasses('') : inputClasses" />
                  <p v-if="cookieStr(cookie.path) && !isValidCookiePath(cookie.path)" class="text-xs text-red-500 mt-1">
                    Path must start with <code class="font-mono">/</code> and contain no spaces, <code class="font-mono">?</code>, or <code class="font-mono">#</code>.
                  </p>
                </div>
                <div>
                  <label class="flex items-center gap-1 text-xs font-medium mb-1" :class="labelClasses">
                    Expires
                    <span class="cursor-help" :class="isDark ? 'text-gray-500' : 'text-gray-400'"
                      title="Absolute expiry timestamp. Pick a date and time — we'll convert it to the HTTP-date format browsers expect. When both Expires and Max-Age are set, browsers prefer Max-Age.">ⓘ</span>
                  </label>
                  <input v-model="cookie.expires" type="datetime-local"
                    class="w-full rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                </div>
                <div>
                  <label class="flex items-center gap-1 text-xs font-medium mb-1" :class="labelClasses">
                    Max-Age (seconds)
                    <span class="cursor-help" :class="isDark ? 'text-gray-500' : 'text-gray-400'"
                      title="Cookie lifetime in seconds from now. 0 or negative deletes the cookie immediately. Omit for a session cookie.">ⓘ</span>
                  </label>
                  <input v-model="cookie.maxAge" type="number" min="0" placeholder="3600"
                    class="w-full rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                </div>
                <div>
                  <label class="flex items-center gap-1 text-xs font-medium mb-1" :class="labelClasses">
                    SameSite
                    <span class="cursor-help" :class="isDark ? 'text-gray-500' : 'text-gray-400'"
                      title="Strict: never sent on cross-site requests. Lax (default): sent on top-level navigations only. None: sent on all cross-site requests (requires Secure).">ⓘ</span>
                  </label>
                  <select v-model="cookie.sameSite"
                    class="w-full rounded-lg border px-3 py-1.5 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                    <option value="">(unset)</option>
                    <option value="Strict">Strict</option>
                    <option value="Lax">Lax</option>
                    <option value="None">None</option>
                  </select>
                </div>
                <div class="flex flex-col justify-end gap-1 pt-1">
                  <label class="flex items-center gap-2 text-xs font-medium" :class="labelClasses"
                    title="Only send the cookie over HTTPS. Required if SameSite=None.">
                    <input type="checkbox" v-model="cookie.secure"
                      class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                    Secure
                  </label>
                  <label class="flex items-center gap-2 text-xs font-medium" :class="labelClasses"
                    title="Hide the cookie from JavaScript (document.cookie). Sent only with HTTP(S) requests.">
                    <input type="checkbox" v-model="cookie.httpOnly"
                      class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
                    HttpOnly
                  </label>
                </div>
              </div>

              <!-- SameSite=None without Secure is an invalid combination in
                   modern browsers — surface it inline so the user can fix it. -->
              <p v-if="cookie.sameSite === 'None' && !cookie.secure"
                class="text-xs mt-2" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
                Browsers reject <code class="font-mono">SameSite=None</code> without <code class="font-mono">Secure</code>. Enable Secure or pick a different SameSite.
              </p>
            </fieldset>
          </div>
          <p v-if="cookies.length === 0" class="text-xs italic" :class="isDark ? 'text-gray-500' : 'text-gray-400'">No cookies defined.</p>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  BASIC AUTH (collapsible)                               -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('basicAuth')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Basic Authentication</h2>
            <span v-if="enableBasicAuth" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">Active</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.basicAuth ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.basicAuth" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <div class="flex items-center gap-2 mb-4">
            <input id="enableBasicAuth" v-model="enableBasicAuth" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
            <label for="enableBasicAuth" class="text-sm" :class="labelClasses">Enable Basic Auth matching</label>
          </div>
          <div v-if="enableBasicAuth" class="space-y-4">
            <!--
              Mode selector — segmented control. Same username/password fields
              feed both modes, so flipping the toggle is non-destructive: it
              only changes how the payload is shaped on save.
                • Plain credentials → request.basicAuthCredentials = { user, pass }
                • Authorization header → request.headers.Authorization.equalTo
                  = 'Basic <b64>'
            -->
                  <div>
              <label class="block text-sm font-medium mb-2" :class="labelClasses">Match Mode</label>
              <div class="inline-flex rounded-lg border overflow-hidden" :class="isDark ? 'border-gray-600' : 'border-gray-300'">
                <button type="button" @click="basicAuthMode = 'plain'"
                  class="px-3 py-1.5 text-xs font-medium transition-colors cursor-pointer"
                  :class="basicAuthMode === 'plain'
                    ? (isDark ? 'bg-emerald-600 text-white' : 'bg-emerald-500 text-white')
                    : (isDark ? 'bg-gray-800 text-gray-300 hover:bg-gray-700' : 'bg-white text-gray-600 hover:bg-gray-50')">
                  Plain credentials
                </button>
                <button type="button" @click="basicAuthMode = 'base64'"
                  class="px-3 py-1.5 text-xs font-medium border-l transition-colors cursor-pointer"
                  :class="[
                    isDark ? 'border-gray-600' : 'border-gray-300',
                    basicAuthMode === 'base64'
                      ? (isDark ? 'bg-emerald-600 text-white' : 'bg-emerald-500 text-white')
                      : (isDark ? 'bg-gray-800 text-gray-300 hover:bg-gray-700' : 'bg-white text-gray-600 hover:bg-gray-50')
                  ]">
                  Authorization header (base64)
                </button>
              </div>
              <p class="text-xs mt-1.5" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                <template v-if="basicAuthMode === 'plain'">
                  WireMock decodes the incoming <code>Authorization</code> header and compares against the literal username/password.
                </template>
                <template v-else>
                  Stub matches the exact <code>Authorization: Basic &lt;base64&gt;</code> header value verbatim.
                </template>
              </p>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-medium mb-1"
                  :class="[labelClasses, !basicAuthUsername.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">
                  Username *
                </label>
                <input v-model="basicAuthUsername" type="text" placeholder="expected username"
                  autocomplete="off" spellcheck="false" autocorrect="off" autocapitalize="off"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="requiredInputClasses(basicAuthUsername.trim())" />
              </div>
              <div>
                <label class="block text-sm font-medium mb-1"
                  :class="[labelClasses, !basicAuthPassword.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">
                  Password *
                </label>
                <!--
                  Mock-side basic auth values, not real credentials. The
                  field stays as plain text so the user can verify what
                  they're typing matches the stub matcher; autocomplete is
                  disabled so password managers don't offer to save these
                  as the user's own credentials.
                -->
                <input v-model="basicAuthPassword" type="text" placeholder="expected password"
                  autocomplete="off" spellcheck="false" autocorrect="off" autocapitalize="off"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="requiredInputClasses(basicAuthPassword.trim())" />
              </div>
            </div>

            <div v-if="basicAuthMode === 'base64' && basicAuthBase64Preview" class="rounded-lg border p-3"
              :class="isDark ? 'border-gray-700 bg-gray-800/30' : 'border-gray-200 bg-gray-50'">
              <p class="text-xs font-medium mb-1" :class="labelClasses">Authorization header preview</p>
              <code class="text-xs font-mono break-all"
                :class="isDark ? 'text-emerald-300' : 'text-emerald-700'">{{ basicAuthBase64Preview }}</code>
            </div>
          </div>
        </div>
      </section>

      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('chunkedDribble')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Chunked Dribble Delay</h2>
            <span v-if="enableChunkedDribble" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">Active</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.chunkedDribble ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.chunkedDribble" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Trickle response bytes over time by splitting the body into chunks.</p>
          <div class="flex items-center gap-2 mb-4">
            <input id="enableChunkedDribble" v-model="enableChunkedDribble" type="checkbox"
              :disabled="enableFault"
              class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed" />
            <label for="enableChunkedDribble" class="text-sm"
              :class="[labelClasses, enableFault ? 'opacity-50 cursor-not-allowed' : '']">
              Enable chunked dribble delay
            </label>
          </div>
          <p v-if="enableFault" class="text-xs mb-3" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
            Chunked dribble is ignored while Fault Simulation is active.
          </p>
          <div v-if="enableChunkedDribble" class="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label class="block text-sm font-medium mb-1" :class="labelClasses">Number of Chunks</label>
              <input v-model.number="chunkedNumberOfChunks" type="number" min="1"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <p v-if="chunkedDribbleActive && chunkedBytesPerChunk !== null" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                ≈ {{ chunkedBytesPerChunk }} byte{{ chunkedBytesPerChunk === 1 ? '' : 's' }} per chunk
              </p>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1" :class="labelClasses">Total Duration (ms)</label>
              <input v-model.number="chunkedTotalDuration" type="number" min="1"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <p v-if="formatMsAsSeconds(chunkedTotalDuration)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                {{ formatMsAsSeconds(chunkedTotalDuration) }}<span v-if="chunkedDribbleActive && chunkedMsPerChunk !== null"> · ≈ {{ chunkedMsPerChunk }} ms per chunk</span>
              </p>
            </div>
          </div>
          <p v-if="chunkedDribbleNoBody" class="text-xs mt-3" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
            Chunked dribble has no effect without a response body.
          </p>
        </div>
      </section>

      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('faultSimulation')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Fault Simulation</h2>
            <span v-if="enableFault" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">Active</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.faultSimulation ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.faultSimulation" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Simulate network-level failures.</p>
          <div class="flex items-center gap-2 mb-4">
            <input id="enableFault" v-model="enableFault" type="checkbox"
              :disabled="enableProxy"
              class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed" />
            <label for="enableFault" class="text-sm"
              :class="[labelClasses, enableProxy ? 'opacity-50 cursor-not-allowed' : '']">
              Enable fault simulation
            </label>
          </div>
          <p v-if="enableProxy" class="text-xs mb-3" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
            Fault simulation is unavailable while Proxying is enabled.
          </p>
          <div v-if="enableFault">
            <label class="block text-sm font-medium mb-1" :class="labelClasses">Fault Type</label>
            <select v-model="faultType"
              class="w-full md:w-80 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
              <option v-for="ft in FAULT_TYPES" :key="ft.value" :value="ft.value">{{ ft.label }}</option>
            </select>
            <p class="text-xs mt-2" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
              {{ selectedFaultDescription }}
            </p>
          </div>
        </div>
      </section>

      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('proxying')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Proxying</h2>
            <span v-if="enableProxy" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">Active</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.proxying ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.proxying" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Forward matched requests to a real backend.</p>
          <div class="flex items-center gap-2 mb-4">
            <input id="enableProxy" v-model="enableProxy" type="checkbox"
              :disabled="enableFault"
              class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed" />
            <label for="enableProxy" class="text-sm"
              :class="[labelClasses, enableFault ? 'opacity-50 cursor-not-allowed' : '']">
              Enable proxy forwarding
            </label>
          </div>
          <p v-if="enableFault" class="text-xs mb-3" :class="isDark ? 'text-amber-400' : 'text-amber-600'">
            Proxying is unavailable while Fault Simulation is enabled.
          </p>
          <div v-if="enableProxy" class="space-y-4">
            <div>
              <label class="block text-sm font-medium mb-1"
                :class="[labelClasses, (!proxyBaseUrl.trim() || proxyBaseUrlError) ? (isDark ? 'text-red-400' : 'text-red-500') : '']">
                Proxy Base URL *
              </label>
              <input v-model="proxyBaseUrl" type="text" placeholder="https://api.example.com"
                class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="proxyBaseUrlError
                  ? (isDark ? 'bg-gray-800 border-red-500 text-gray-100 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30' : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30')
                  : requiredInputClasses(proxyBaseUrl.trim())" />
              <p v-if="proxyBaseUrlError" class="text-xs text-red-500 mt-1">{{ proxyBaseUrlError }}</p>
            </div>
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-sm font-medium" :class="labelClasses">Additional Proxy Headers</label>
                <button type="button" @click="additionalProxyHeaders.push({ key: '', value: '' })"
                  class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
              </div>
              <div v-for="(header, i) in additionalProxyHeaders" :key="i" class="mb-2">
                <div class="flex gap-2">
                  <input v-model="header.key" placeholder="Header name"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowKeyMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <input v-model="header.value" placeholder="Header value"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <button type="button" @click="removeRow(additionalProxyHeaders, i)" class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
                </div>
                <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1">
                  Header value is required when a header name is provided.
                </p>
                <p v-if="isMatcherRowKeyMissing(header)" class="text-xs text-red-500 mt-1">
                  Header name is required when a value is provided.
                </p>
              </div>
            </div>
            <div>
              <div class="flex items-center gap-2 mb-1 flex-wrap">
                <label class="block text-sm font-medium" :class="labelClasses">Remove Headers Before Proxying</label>
              </div>
              <p class="text-xs mb-2" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                A list of header names to remove from the request before forwarding.
              </p>
              <div class="flex gap-2 mb-2">
                <input v-model="removeProxyHeaderInput" type="text" placeholder="Header name to remove"
                  @keyup.enter="addRemoveProxyHeader"
                  class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                <button type="button" @click="addRemoveProxyHeader"
                  class="px-3 py-2 text-xs font-medium rounded-lg" :class="isDark ? 'text-emerald-400 hover:text-emerald-300 bg-gray-800' : 'text-emerald-600 hover:text-emerald-800 bg-gray-100'">Add</button>
              </div>
              <div v-if="removeProxyHeaders.length > 0" class="flex flex-wrap gap-2 mb-2">
                <span v-for="(h, i) in removeProxyHeaders" :key="i"
                  class="inline-flex items-center gap-1 text-xs px-2 py-1 rounded font-mono" :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-600'">
                  {{ h }}
                  <button type="button" @click="removeRemoveProxyHeader(i)" class="text-red-400 hover:text-red-600">&times;</button>
                </span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="rounded-xl border overflow-hidden transition-colors" :class="sectionClasses">
        <button type="button" @click="toggleSection('webhooks')"
          class="w-full flex items-center justify-between p-4 text-left transition-colors"
          :class="collapsibleHeaderClasses">
          <div class="flex items-center gap-3">
            <h2 class="text-base font-semibold" :class="headingClasses">Webhooks / Post-Serve Actions</h2>
            <span v-if="enableWebhook" class="text-xs px-2 py-0.5 rounded-full" :class="badgeClasses">Active</span>
          </div>
          <svg class="w-5 h-5 transition-transform" :class="[collapsedSections.webhooks ? '' : 'rotate-180', isDark ? 'text-gray-400' : 'text-gray-500']"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
        </button>
        <div v-show="!collapsedSections.webhooks" class="p-6 border-t" :class="isDark ? 'border-gray-700' : 'border-gray-200'">
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Fire an HTTP callback after the response is sent.</p>
          <div class="flex items-center gap-2 mb-4">
            <input id="enableWebhook" v-model="enableWebhook" type="checkbox" class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
            <label for="enableWebhook" class="text-sm" :class="labelClasses">Enable webhook</label>
          </div>
          <div v-if="enableWebhook" class="space-y-4">
            <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
              <div class="md:col-span-1">
                <label class="block text-sm font-medium mb-1" :class="labelClasses">Method</label>
                <select v-model="webhookMethod"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                  <option v-for="m in HTTP_METHODS.filter(m => m !== 'ANY')" :key="m" :value="m">{{ m }}</option>
                </select>
              </div>
              <div class="md:col-span-3">
                <label class="block text-sm font-medium mb-1"
                  :class="[labelClasses, (!webhookUrl.trim() || webhookUrlError) ? (isDark ? 'text-red-400' : 'text-red-500') : '']">
                  URL *
                </label>
                <input v-model="webhookUrl" type="text" placeholder="https://example.com/webhook"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="webhookUrlError
                    ? (isDark ? 'bg-gray-800 border-red-500 text-gray-100 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30' : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30')
                    : requiredInputClasses(webhookUrl.trim())" />
                <p v-if="webhookUrlError" class="text-xs text-red-500 mt-1">{{ webhookUrlError }}</p>
              </div>
            </div>
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-sm font-medium" :class="labelClasses">Headers</label>
                <button type="button" @click="addRow(webhookHeaders)"
                  class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
              </div>
              <div v-for="(header, i) in webhookHeaders" :key="i" class="mb-2">
                <div class="flex gap-2">
                  <input v-model="header.key" placeholder="Header name"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowKeyMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <input v-model="header.value" placeholder="Header value"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <button type="button" @click="removeRow(webhookHeaders, i)" class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
                </div>
                <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1">
                  Header value is required when a header name is provided.
                </p>
                <p v-if="isMatcherRowKeyMissing(header)" class="text-xs text-red-500 mt-1">
                  Header name is required when a value is provided.
                </p>
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1" :class="labelClasses">Body (supports Handlebars)</label>
              <textarea v-model="webhookBody" rows="3" placeholder='{"event": "stub-matched"}'
                class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
            </div>
            <div>
              <label class="block text-sm font-medium mb-1" :class="labelClasses">Delay before firing (ms)</label>
              <input v-model.number="webhookDelayMs" type="number" min="0" placeholder="0"
                class="w-full md:w-48 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <p v-if="formatMsAsSeconds(webhookDelayMs)" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                {{ formatMsAsSeconds(webhookDelayMs) }}
              </p>
            </div>
          </div>
        </div>
      </section>

    </form>
      </div>

      <aside class="lg:mt-0 lg:sticky lg:top-4 space-y-4">
        <div
          v-if="validationIssuesBySection.length > 0"
          class="rounded-xl border transition-colors"
          :class="isDark ? 'bg-amber-950/30 border-amber-800' : 'bg-amber-50 border-amber-200'"
        >
          <div class="flex items-center justify-between gap-2 px-4 py-3 border-b"
            :class="isDark ? 'border-amber-800' : 'border-amber-200'">
            <div class="flex items-center gap-2">
              <h2 class="text-sm font-semibold" :class="isDark ? 'text-amber-200' : 'text-amber-800'">
                Validation
              </h2>
            </div>
            <span class="text-xs px-2 py-0.5 rounded-full"
              :class="isDark ? 'bg-amber-900/60 text-amber-200' : 'bg-amber-100 text-amber-700'">
              {{ validationIssues.length }} issue{{ validationIssues.length === 1 ? '' : 's' }}
            </span>
          </div>
          <div class="p-4 max-h-[50vh] overflow-y-auto text-sm"
            :class="isDark ? 'text-amber-100' : 'text-amber-900'">
            <div v-for="([section, messages], idx) in validationIssuesBySection" :key="section"
              :class="idx > 0 ? 'mt-3 pt-3 border-t' : ''">
              <p class="text-xs font-semibold uppercase tracking-wide mb-1"
                :class="isDark ? 'text-amber-300' : 'text-amber-700'">{{ section }}</p>
              <ul class="list-disc list-inside space-y-1">
                <li v-for="(msg, i) in messages" :key="i" class="leading-snug">{{ msg }}</li>
              </ul>
            </div>
          </div>
        </div>

        <div v-if="curlCommand" class="rounded-xl border transition-colors" :class="sectionClasses">
          <div
            class="flex items-center justify-between gap-2 px-4 py-3 border-b"
            :class="isDark ? 'border-gray-700' : 'border-gray-200'"
          >
            <h2 class="text-sm font-semibold" :class="headingClasses">Test with cURL</h2>
            <div class="flex items-center gap-1">
              <button
                type="button"
                @click="copyCurl"
                class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                :class="curlCopied
                  ? (isDark ? 'bg-emerald-500/15 text-emerald-300' : 'bg-emerald-50 text-emerald-600')
                  : (isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100')"
              >
                {{ curlCopied ? 'Copied!' : 'Copy' }}
              </button>
            </div>
          </div>
          <div class="p-4">
            <pre
              class="rounded-lg p-3 overflow-x-auto max-h-[40vh] overflow-y-auto text-xs font-mono leading-relaxed whitespace-pre"
              :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'"
            >{{ curlCommand }}</pre>
          </div>
        </div>

        <div class="flex flex-col gap-2 [&_button]:cursor-pointer [&_button:disabled]:cursor-not-allowed">
          <BaseButton
            :description="isEditMode ? 'Update Mock' : 'Create Mock'"
            :color="BaseButtonEnum.GREEN"
            :is-loading="isSubmitting"
            :disabled="isSubmitting || validationIssues.length > 0"
            @click="handleSubmit"
          />
          <p v-if="validationIssues.length > 0" class="text-xs"
            :class="isDark ? 'text-amber-300' : 'text-amber-700'">
            Fix {{ validationIssues.length }} issue{{ validationIssues.length === 1 ? '' : 's' }} above to {{ isEditMode ? 'update' : 'create' }} the mock.
          </p>
          <p v-else-if="activeAdvancedCount > 0" class="text-xs" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
            {{ activeAdvancedCount }} advanced feature{{ activeAdvancedCount > 1 ? 's' : '' }} enabled
          </p>
        </div>

        <div
          v-if="isEditMode && stubStatus !== 'idle'"
          class="rounded-xl border p-4 transition-colors"
          :class="sectionClasses"
        >
          <div class="flex items-center justify-between mb-2">
            <h3 class="text-sm font-semibold" :class="isDark ? 'text-gray-100' : 'text-gray-800'">
              WireMock stub
            </h3>
            <button
              v-if="stubStatus !== 'loading' && stubStatus !== 'creating'"
              type="button"
              @click="refreshStubStatus"
              class="text-xs font-medium cursor-pointer transition-colors"
              :class="isDark ? 'text-gray-400 hover:text-gray-200' : 'text-gray-500 hover:text-gray-700'"
            >
              Refresh
            </button>
          </div>

          <div
            v-if="stubStatus === 'loading'"
            class="flex items-center gap-2 text-xs"
            :class="isDark ? 'text-gray-400' : 'text-gray-500'"
          >
            Checking WireMock…
          </div>

          <div
            v-else-if="stubStatus === 'exists'"
            class="flex items-center gap-2 text-xs"
            :class="isDark ? 'text-emerald-400' : 'text-emerald-600'"
          >
            Stub present on WireMock
          </div>

          <template v-else-if="stubStatus === 'missing'">
            <p class="text-xs mb-3" :class="isDark ? 'text-amber-300' : 'text-amber-700'">
              No matching stub on WireMock. Click below to push this mock to WireMock.
            </p>
            <button
              type="button"
              @click="handleCreateStub"
              class="w-full inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer border"
              :class="isDark
                ? 'text-amber-300 border-amber-500/40 hover:bg-amber-500/10'
                : 'text-amber-700 border-amber-300 hover:bg-amber-50'"
            >
              click to create
            </button>
          </template>

          <button
            v-else-if="stubStatus === 'creating'"
            type="button"
            disabled
            class="w-full inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-not-allowed opacity-70 border"
            :class="isDark
              ? 'text-amber-300 border-amber-500/40'
              : 'text-amber-700 border-amber-300'"
          >
            Creating…
          </button>

          <div v-else-if="stubStatus === 'error'" class="text-xs">
            <p :class="isDark ? 'text-amber-300' : 'text-amber-700'">
              Couldn't reach WireMock to check stub status.
            </p>
            <p
              v-if="stubErrorMsg"
              class="mt-1 break-words"
              :class="isDark ? 'text-gray-400' : 'text-gray-500'"
            >
              {{ stubErrorMsg }}
            </p>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>
