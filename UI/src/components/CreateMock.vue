<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { BaseButton, BaseToast, BaseButtonEnum, BaseToastEnum } from 'mgv-backoffice'
import { mockApi } from '../services/api'
import type { KeyValuePair, MockResponse, CreateMockPayload, RequestDefinition, ResponseDefinition, BodyPattern, StringMatcher, WebhookDefinition } from '../types/mock'
import { useTheme } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import { WIREMOCK_BASE_URL } from '../config'
import { checkStubExists } from '../services/stubService'
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
}

interface BodyPatternRow {
  matcherType: string
  value: string
  ignoreArrayOrder: boolean
  ignoreExtraElements: boolean
}

interface MetadataRow {
  key: string
  value: string
  valueType: 'text' | 'json'
  locked?: boolean
}

// Metadata keys that are auto-managed by WireMate. They are rendered as
// readonly rows, cannot be removed by the user, and always get their
// canonical value regardless of what may have been stored on the mock.
const LOCKED_METADATA_KEYS = new Set(['project', 'generatedBy'])

// Canonical builder for the system-managed metadata rows. Kept in one
// place so the default form, the edit pre-fill, and the reset handler all
// agree on which keys are locked and what their values are.
function buildLockedMetadataRows(projectId: string): MetadataRow[] {
  return [
    { key: 'project', value: projectId, valueType: 'text', locked: true },
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
  requestMatching: false,
  responseDefinition: false,
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
const persistent = ref(true)
// Priority defaults to 5 to match WireMock's own default (lower = higher
// priority). Pre-populating the field means a brand-new mock is immediately
// valid, and users can still override or clear it before saving.
const priority = ref<number | undefined>(5)

// ── Form state: Metadata ──────────────────────────────────────
const metadataRows = ref<MetadataRow[]>([])

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
// Status Message is no longer free-text — it is derived from the selected
// Status Code using the canonical reason phrase from `HTTP_STATUS_CODES`
// (e.g. 201 → "Created"). Keeping it as a computed means users can't end
// up with mismatched pairs like `200` + "Not Found", and the field in the
// form becomes a read-only display instead of an input.
const responseStatusMessage = computed(() => {
  const entry = HTTP_STATUS_CODES.find(s => s.value === responseStatus.value)
  if (!entry) return ''
  // Labels are shaped like "200 OK"; strip the leading numeric code so we
  // emit just the reason phrase on the wire.
  return entry.label.replace(/^\d+\s*/, '')
})
const responseHeaders = ref<KeyValuePair[]>([{ key: 'Content-Type', value: 'application/json' }])
// The on-wire body shape (jsonBody vs body vs base64Body) is derived from
// the response's Content-Type header instead of being a separate user
// choice — see `responseBodyFormat` below. Users rarely want the body
// payload type to disagree with the Content-Type they advertise, and in
// the old form they'd silently end up with e.g. `body: "<xml/>"` served
// with `Content-Type: application/json`.
//
// `useBodyFileName` is the one remaining user toggle: when true the
// textarea's contents are treated as a reference to a file under
// WireMock's `__files/` directory (emitted as `bodyFileName`) rather
// than an inline body.
const responseBody = ref('')
const useBodyFileName = ref(false)

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
// Uses the SAME flow as ProjectDetailView — PUT /api/mocks/{id} so the
// backend re-pushes the stub under the existing id. We rebuild the
// payload from props.mock (rather than the live form) to match the
// project-list behavior: the form may contain unsaved edits, but
// "click to create" is about restoring the stored mock to WireMock, not
// about persisting unsaved form changes.
async function handleCreateStub() {
  if (!props.mock) return
  const m = props.mock
  stubStatus.value = 'creating'
  stubErrorMsg.value = ''
  try {
    const payload: CreateMockPayload = {
      name: m.name,
      projectId: m.projectId,
      request: m.request,
      response: m.response,
      persistent: m.persistent,
    }
    if (m.description) payload.description = m.description
    if (typeof m.priority === 'number') payload.priority = m.priority
    if (m.scenarioName) payload.scenarioName = m.scenarioName
    if (m.requiredScenarioState) payload.requiredScenarioState = m.requiredScenarioState
    if (m.newScenarioState) payload.newScenarioState = m.newScenarioState
    if (m.metadata) payload.metadata = m.metadata
    if (m.serveEventListeners && m.serveEventListeners.length > 0) {
      payload.serveEventListeners = m.serveEventListeners
    }
    if (m.curl) payload.curl = m.curl

    await mockApi.update(m.id, payload)
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

const BODY_MATCHER_TYPES = [
  { value: 'equalToJson', label: 'Equal To JSON' },
  { value: 'matchesJsonPath', label: 'JSONPath' },
  { value: 'equalTo', label: 'Equal To (string)' },
  { value: 'contains', label: 'Contains' },
  { value: 'matches', label: 'Regex' },
  { value: 'doesNotMatch', label: 'Not Regex' },
  { value: 'equalToXml', label: 'Equal To XML' },
  { value: 'matchesXPath', label: 'XPath' },
  { value: 'binaryEqualTo', label: 'Binary (base64)' },
  { value: 'absent', label: 'Absent' },
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

function extractMatcher(val: StringMatcher): { matcherType: string; matcherValue: string } {
  for (const type of STRING_MATCHER_KEYS) {
    const v = val[type]
    if (v !== undefined) {
      return { matcherType: type, matcherValue: typeof v === 'string' ? v : JSON.stringify(v) }
    }
  }
  if (val.absent === true) return { matcherType: 'absent', matcherValue: 'true' }
  return { matcherType: 'equalTo', matcherValue: '' }
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
  persistent.value = m.persistent
  // When editing, only pre-fill priority if the mock actually has one set —
  // leave the field blank otherwise so the user isn't surprised by an
  // auto-assigned default they didn't choose.
  priority.value = m.priority

  // Metadata
  if (m.metadata && Object.keys(m.metadata).length > 0) {
    const rows: MetadataRow[] = Object.entries(m.metadata)
      // The cookie-attributes blob is system-managed — it's surfaced and
      // edited through the Cookies section, not the raw metadata grid.
      .filter(([key]) => key !== COOKIE_ATTRS_METADATA_KEY)
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
      const { matcherType, matcherValue } = extractMatcher(val)
      return { key, matcherType, value: matcherValue }
    })
  }

  // Request - Cookies
  // Hydrate request-side cookies into CookieRow entries with
  // direction='request'. The response-side rows are added later from the
  // Set-Cookie parsing below; both groups live together in `cookies.value`.
  if (m.request.cookies && Object.keys(m.request.cookies).length > 0) {
    const requestCookieRows: CookieRow[] = []
    for (const [key, val] of Object.entries(m.request.cookies)) {
      const { matcherType, matcherValue } = extractMatcher(val)
      requestCookieRows.push(createCookieRow({
        direction: 'request',
        key,
        matcherType,
        value: matcherValue,
      }))
    }
    cookies.value = requestCookieRows
    collapsedSections.value.cookies = false
  }

  // Request - Query parameters
  if (m.request.queryParameters) {
    queryParams.value = Object.entries(m.request.queryParameters).map(([key, val]) => {
      const { matcherType, matcherValue } = extractMatcher(val)
      return { key, matcherType, value: matcherValue }
    })
  }

  // Request - Body patterns
  if (m.request.bodyPatterns && m.request.bodyPatterns.length > 0) {
    const BODY_MATCHER_KEYS = [
      'equalToJson', 'matchesJsonPath', 'equalTo', 'contains',
      'matches', 'doesNotMatch', 'equalToXml', 'matchesXPath', 'binaryEqualTo',
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
  // `responseStatusMessage` is a computed derived from `responseStatus`, so
  // any stored custom reason phrase is intentionally ignored here — we now
  // always emit the canonical phrase for the selected status code.

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
      // Non-cookie headers: the UI's response headers grid only supports
      // single-string values, so flatten arrays by joining entries into
      // repeated rows. This preserves the data on round-trip even if the
      // user only edits one row.
      if (Array.isArray(value)) {
        for (const v of value) remainingHeaders.push({ key, value: String(v) })
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
  // textarea contents. The only exception is `bodyFileName`, which is a
  // distinct "load the body from a file on disk" mode that Content-Type
  // can't signal — we surface it via a dedicated checkbox.
  useBodyFileName.value = false
  if (m.response.jsonBody !== undefined) {
    responseBody.value = JSON.stringify(m.response.jsonBody, null, 2)
  } else if (m.response.base64Body) {
    responseBody.value = m.response.base64Body
  } else if (m.response.bodyFileName) {
    useBodyFileName.value = true
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

// ── Body pattern combination validator ────────────────────────
// WireMock body patterns are AND-combined, so stacking multiple patterns is
// a valid and powerful feature (e.g. "contains `error`" AND "matches regex").
// A few combinations, however, are nonsensical and produce stubs that can
// never match:
//   - More than one literal-body matcher (equalTo / equalToJson / equalToXml
//     / binaryEqualTo) — a body can only equal one thing at a time.
//   - `absent` combined with any other matcher — a body can't simultaneously
//     be missing and contain a value.
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

  return issues
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
  list.push({ key: '', matcherType: 'equalTo', value: '' })
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
// detected format. Returns '' when valid (or when the field is empty,
// or when the user opted into file-reference mode — where the textarea
// holds a path, not a body).
const responseBodyFormatError = computed(() => {
  if (useBodyFileName.value) return ''
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
  if (useBodyFileName.value) return null // file-backed bodies: size is unknown until runtime
  const chunks = chunkedNumberOfChunks.value
  if (typeof chunks !== 'number' || !Number.isFinite(chunks) || chunks < 1) return null
  const body = responseBody.value ?? ''
  if (body.length === 0) return null
  return Math.max(1, Math.round(body.length / chunks))
})

// Warn when chunked dribble is enabled but there's nothing to dribble —
// WireMock will just emit the (empty) body with no throttling effect.
// File-backed bodies are exempt since we can't see their contents.
const chunkedDribbleNoBody = computed(() => {
  if (!chunkedDribbleActive.value) return false
  if (useBodyFileName.value) return false
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

  // Response Definition — body format must match Content-Type. Fault &
  // proxy bypass the canned response, so don't block saves over bodies
  // the server will never send; file-reference mode stores a path, not
  // content, so it isn't format-checked either.
  if (
    !enableFault.value
    && !enableProxy.value
    && !useBodyFileName.value
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
    const missing = rows.filter(isMatcherRowValueMissing)
    if (missing.length > 0) {
      const names = missing.map(r => (r.key ?? '').trim()).filter(Boolean).join(', ')
      issues.push({
        section,
        message: `Each ${noun} with a name also needs a value${names ? ` (missing for ${names})` : ''}.`,
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
  return { [row.matcherType]: row.value }
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
  } else {
    obj[row.matcherType] = row.value
  }
  return obj
}

// ── Build payload ─────────────────────────────────────────────
function buildRequest(): RequestDefinition {
  // Serialize 'url' match type as 'urlPath' in the payload
  const payloadUrlKey = urlMatchType.value === 'url' ? 'urlPath' : urlMatchType.value
  const req: Record<string, unknown> = {
    method: httpMethod.value,
    [payloadUrlKey]: urlValue.value,
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
    if (responseStatusMessage.value.trim()) {
      res.statusMessage = responseStatusMessage.value
    }

    // Headers — build the map, then inject Set-Cookie from the Cookies
    // section so it wins over any Set-Cookie the user typed into the
    // headers grid by hand. When there's more than one cookie we emit the
    // header value as a string[] (WireMock's multi-value form); browsers
    // can't parse a comma-joined Set-Cookie because cookie Expires legally
    // contains commas.
    const filteredHeaders = responseHeaders.value.filter(h => h.key.trim())
    const headerMap: Record<string, string | string[]> = {}
    for (const h of filteredHeaders) {
      // Drop any Set-Cookie the user typed in — the dedicated Cookies
      // section is the single source of truth for this header.
      if (h.key.trim().toLowerCase() === 'set-cookie') continue
      headerMap[h.key] = h.value
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
    // `responseBodyFormat`), not a user toggle. File-reference mode is
    // the only exception; it maps directly to `bodyFileName` regardless
    // of Content-Type.
    if (responseBody.value.trim()) {
      if (useBodyFileName.value) {
        res.bodyFileName = responseBody.value.trim()
      } else if (responseBodyFormat.value === 'json') {
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
  // `responseStatusMessage` is a computed (derived from `responseStatus`) —
  // resetting the status code to 200 naturally resets the message to "OK".
  responseHeaders.value = [{ key: 'Content-Type', value: 'application/json' }]
  responseBody.value = ''
  useBodyFileName.value = false
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
// Side-panel visibility. Users asked for the cURL preview to be visible by
// default and collapsible when it gets in the way — persisting to nothing
// (per-session state is fine).
const curlVisible = ref(true)

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
  const path = url.startsWith('/') ? url : `/${url}`

  // Append query params (if any) to the path with proper encoding.
  const filteredParams = queryParams.value.filter(q => q.key.trim())
  let fullPath = path
  if (filteredParams.length > 0) {
    const qs = filteredParams
      .map(q => `${encodeURIComponent(q.key)}=${encodeURIComponent(q.value)}`)
      .join('&')
    fullPath += (fullPath.includes('?') ? '&' : '?') + qs
  }

  // Only matchers that represent a literal request body belong in -d.
  // Regex/JSONPath/XPath matchers describe a shape, not a concrete payload,
  // so emitting them as -d would produce a misleading cURL.
  const LITERAL_BODY_MATCHERS = new Set(['equalTo', 'equalToJson', 'equalToXml', 'binaryEqualTo'])
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
    const isJson = literalBody.matcherType === 'equalToJson'
    const isXml = literalBody.matcherType === 'equalToXml'
    const hasContentType = filteredHeaders.some(h => h.key.toLowerCase() === 'content-type')
    if (!hasContentType) {
      const ct = isJson ? 'application/json' : isXml ? 'application/xml' : 'text/plain'
      lines.push(`-H ${shEscape(`Content-Type: ${ct}`)}`)
    }
    let bodyStr = literalBody.value.trim()
    if (isJson) {
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

        <!-- Read-only IDs (edit mode) -->
        <div v-if="isEditMode && props.mock" class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-4">
          <div>
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
          <div>
            <label class="block text-sm font-medium mb-1" :class="labelClasses">Project ID</label>
            <div class="flex items-center gap-2">
              <input :value="props.mock.projectId" readonly
                class="flex-1 rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none transition-colors cursor-default"
                :class="isDark ? 'bg-gray-800/50 border-gray-600 text-gray-400' : 'bg-gray-50 border-gray-300 text-gray-500'" />
              <button type="button" @click="copyField(props.mock!.projectId, 'projectId')"
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
          </div>
          <div v-if="!props.projectId && !isEditMode">
            <label for="projectId" class="block text-sm font-medium mb-1" :class="[labelClasses, !internalProjectId.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">Project ID *</label>
            <input id="projectId" v-model="internalProjectId" type="text" placeholder="UUID of the project"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="requiredInputClasses(internalProjectId)" />
          </div>
        </div>

        <div class="mt-4">
          <label for="description" class="block text-sm font-medium mb-1" :class="labelClasses">Description</label>
          <textarea id="description" v-model="description" rows="2" placeholder="Add notes or description for this mock..."
            class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
        </div>

        <div class="mt-4 grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label for="priority" class="block text-sm font-medium mb-1" :class="labelClasses">Priority</label>
            <input id="priority" v-model.number="priority" type="number" min="1" placeholder="5 (lower = higher priority)"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
          </div>
          <div class="flex items-end pb-1">
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
                title="This metadata entry is auto-managed and cannot be removed">locked</span>
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
            <input id="urlValue" v-model="urlValue" type="text" placeholder="/api/v1/resource"
              class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
              :class="urlValidationError
                ? (isDark ? 'bg-gray-800 border-red-500 text-gray-100 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-500/30' : 'bg-white border-red-400 text-gray-800 focus:ring-red-500 focus:border-red-500 ring-1 ring-red-400/30')
                : requiredInputClasses(urlValue.trim() === '/' ? '' : urlValue)" />
            <p v-if="urlValidationError" class="text-xs text-red-500 mt-1">{{ urlValidationError }}</p>
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
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <select v-model="param.matcherType"
                :aria-label="`Query parameter matcher type ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-model="param.value" placeholder="Value" :disabled="param.matcherType === 'absent'"
                :aria-label="`Query parameter value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(param) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(queryParams, i)"
                :aria-label="`Remove query parameter ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg pt-1">&times;</button>
            </div>
            <p v-if="isMatcherRowValueMissing(param)" class="text-xs text-red-500 mt-1 ml-[calc(25%+0.5rem)]">
              Query parameter value is required when a name is provided.
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
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
              <select v-model="header.matcherType"
                :aria-label="`Request header matcher type ${i + 1}`"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-model="header.value" placeholder="Expected value" :disabled="header.matcherType === 'absent'"
                :aria-label="`Request header expected value ${i + 1}`"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(requestHeaders, i)"
                :aria-label="`Remove request header ${i + 1}`"
                class="text-red-400 hover:text-red-600 px-2 text-lg pt-1">&times;</button>
            </div>
            <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1 ml-[calc(25%+0.5rem)]">
              Header value is required when a header name is provided.
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
            <textarea v-if="bp.matcherType !== 'absent'" v-model="bp.value" rows="3"
              :placeholder="bp.matcherType === 'equalToJson' ? '{ &quot;key&quot;: &quot;value&quot; }' : bp.matcherType === 'matchesJsonPath' ? '$.name' : 'Pattern...'"
              class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
              :class="inputClasses" />
            <div v-if="getRegexExamples(bp.matcherType)" class="mt-1 text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
              <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">{{ getRegexExamples(bp.matcherType)!.tip }}</span>
              <span class="ml-1">E.g.</span>
              <code v-for="(ex, idx) in getRegexExamples(bp.matcherType)!.examples" :key="idx"
                class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                :title="'Click to use: ' + ex"
                @click="bp.value = ex">{{ ex }}</code>
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
            <label for="responseStatusMessage" class="block text-sm font-medium mb-1" :class="labelClasses">Status Message</label>
            <input id="responseStatusMessage" :value="responseStatusMessage" type="text" readonly
              title="Automatically derived from the selected Status Code"
              class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none cursor-not-allowed"
              :class="isDark
                ? 'bg-gray-800/60 border-gray-700 text-gray-300'
                : 'bg-gray-100 border-gray-300 text-gray-600'" />
            <p class="text-xs mt-1" :class="isDark ? 'text-gray-400' : 'text-gray-500'">
              Derived from the Status Code.
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
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option value="" disabled>Select header...</option>
                <option v-for="h in responseHeaderOptions(header.key)" :key="h" :value="h">{{ h }}</option>
                <option value="__custom__">Custom...</option>
              </select>
              <div v-else class="flex-1 flex gap-1 items-center">
                <input v-model="header.key"
                  :aria-label="`Response header custom name ${i + 1}`"
                  placeholder="Custom header name"
                  class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
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
          </div>
        </div>

        <!-- Response body — the payload format is derived from the
             Content-Type response header (see `responseBodyFormat`), so
             there's no format dropdown here. A small pill next to the
             label shows the detected format; validation errors appear
             inline below the textarea. The file-reference checkbox is
             the one remaining mode toggle, because "load from __files/"
             can't be inferred from MIME alone. -->
        <div class="mb-6">
          <div class="flex items-center justify-between gap-4 mb-2">
            <div class="flex items-center gap-2">
              <label class="block text-sm font-medium" :class="labelClasses">Response Body</label>
              <span v-if="!useBodyFileName"
                class="inline-flex items-center text-xs font-medium px-2 py-0.5 rounded-full"
                :class="isDark ? 'bg-emerald-900/40 text-emerald-300' : 'bg-emerald-100 text-emerald-800'"
                :title="'Validation and payload shape derived from Content-Type (' + responseContentType + ')'">
                {{ responseBodyFormatLabel }}
              </span>
            </div>
            <label class="inline-flex items-center gap-2 text-xs font-medium cursor-pointer" :class="labelClasses"
              title="Treat the field as a path under WireMock's __files/ directory (emitted as bodyFileName) instead of an inline body.">
              <input type="checkbox" v-model="useBodyFileName"
                class="rounded border-gray-300 text-emerald-600 focus:ring-emerald-500" />
              Use file reference (<code class="font-mono">__files/</code>)
            </label>
          </div>
          <textarea v-model="responseBody" rows="6"
            aria-label="Response body"
            :placeholder="useBodyFileName ? 'path/to/file.json (relative to __files/)' :
              responseBodyFormat === 'json' ? '{ &quot;id&quot;: &quot;123&quot;, &quot;status&quot;: &quot;ok&quot; }' :
              responseBodyFormat === 'xml' ? '&lt;response&gt;&lt;status&gt;ok&lt;/status&gt;&lt;/response&gt;' :
              responseBodyFormat === 'base64' ? 'Base64-encoded binary payload…' :
              'Plain-text response body…'"
            class="w-full rounded-lg border px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 transition-colors"
            :class="[inputClasses, { 'border-red-400': !!responseBodyFormatError }]" />
          <p v-if="responseBodyFormatError" class="text-xs text-red-500 mt-1">{{ responseBodyFormatError }}</p>
          <p v-else-if="useBodyFileName" class="text-xs mt-1" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
            WireMock reads the body from this file path, relative to its <code class="font-mono">__files/</code> directory.
          </p>
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
                :class="[inputClasses, cookie.direction === 'request' ? 'w-1/4' : 'w-1/3']" />
              <select v-if="cookie.direction === 'request'" v-model="cookie.matcherType"
                class="w-1/4 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses">
                <option v-for="mt in MATCHER_TYPES" :key="mt.value" :value="mt.value">{{ mt.label }}</option>
              </select>
              <input v-model="cookie.value" placeholder="Value"
                :disabled="cookie.direction === 'request' && cookie.matcherType === 'absent'"
                class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                :class="isMatcherRowValueMissing(cookie) ? requiredInputClasses('') : inputClasses" />
              <button type="button" @click="removeRow(cookies, i)" class="text-red-400 hover:text-red-600 px-2 text-lg pt-1" title="Remove cookie">&times;</button>
            </div>
            <p v-if="isMatcherRowValueMissing(cookie)" class="text-xs text-red-500 mt-1">
              Cookie value is required when a cookie name is provided.
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
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="requiredInputClasses(basicAuthUsername.trim())" />
              </div>
              <div>
                <label class="block text-sm font-medium mb-1"
                  :class="[labelClasses, !basicAuthPassword.trim() ? (isDark ? 'text-red-400' : 'text-red-500') : '']">
                  Password *
                </label>
                <input v-model="basicAuthPassword" type="text" placeholder="expected password"
                  class="w-full rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                  :class="requiredInputClasses(basicAuthPassword.trim())" />
              </div>
            </div>

            <!-- Live preview of the encoded Authorization header value, only
                 in base64 mode. Helps users sanity-check the b64 against
                 their curl example before saving. -->
            <div v-if="basicAuthMode === 'base64' && basicAuthBase64Preview" class="rounded-lg border p-3"
              :class="isDark ? 'border-gray-700 bg-gray-800/30' : 'border-gray-200 bg-gray-50'">
              <p class="text-xs font-medium mb-1" :class="labelClasses">Authorization header preview</p>
              <code class="text-xs font-mono break-all"
                :class="isDark ? 'text-emerald-300' : 'text-emerald-700'">{{ basicAuthBase64Preview }}</code>
            </div>
          </div>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  CHUNKED DRIBBLE DELAY (collapsible)                    -->
      <!-- ═══════════════════════════════════════════════════════ -->
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
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Trickle response bytes over time by splitting the body into chunks. Emitted as <code>chunkedDribbleDelay: &#123; numberOfChunks, totalDuration &#125;</code> on the response.</p>
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
            Chunked dribble is ignored while Fault Simulation is active — WireMock short-circuits delays on faulted stubs.
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
            Chunked dribble has no effect without a response body — add one above, or WireMock will release the (empty) response immediately.
          </p>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  FAULT SIMULATION (collapsible)                         -->
      <!-- ═══════════════════════════════════════════════════════ -->
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
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Simulate network-level failures instead of returning a normal response.</p>
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
            Fault simulation is unavailable while Proxying is enabled — a stub can either forward or fault, not both.
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

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  PROXYING (collapsible)                                 -->
      <!-- ═══════════════════════════════════════════════════════ -->
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
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Forward matched requests to a real backend server instead of returning a canned response.</p>
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
            Proxying is unavailable while Fault Simulation is enabled — a stub can either forward or fault, not both.
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
            <!-- Additional proxy headers -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-sm font-medium" :class="labelClasses">Additional Proxy Headers</label>
                <button type="button" @click="additionalProxyHeaders.push({ key: '', value: '' })"
                  class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
              </div>
              <div v-for="(header, i) in additionalProxyHeaders" :key="i" class="mb-2">
                <div class="flex gap-2">
                  <input v-model="header.key" placeholder="Header name"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                  <input v-model="header.value" placeholder="Header value"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <button type="button" @click="removeRow(additionalProxyHeaders, i)" class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
                </div>
                <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1">
                  Header value is required when a header name is provided.
                </p>
              </div>
            </div>
            <!-- Remove proxy headers -->
            <div>
              <div class="flex items-center gap-2 mb-1 flex-wrap">
                <label class="block text-sm font-medium" :class="labelClasses">Remove Headers Before Proxying</label>
                <span class="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded font-mono"
                  :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'">array</span>
                <span class="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded"
                  :class="isDark ? 'bg-gray-800 text-gray-400' : 'bg-gray-100 text-gray-500'">optional</span>
                <span class="text-xs" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Headers to strip before proxying</span>
              </div>
              <p class="text-xs mb-2" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                A list of header names to remove from the request before forwarding to the proxy target.
              </p>
              <div class="flex gap-2 mb-2">
                <input v-model="removeProxyHeaderInput" type="text" placeholder="Header name to remove (e.g. Cookie)"
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
              <div class="text-xs" :class="isDark ? 'text-gray-500' : 'text-gray-400'">
                <span class="font-medium" :class="isDark ? 'text-gray-400' : 'text-gray-500'">Example:</span>
                <code v-for="ex in ['Cookie', 'X-Internal-Token']" :key="ex"
                  class="ml-1 px-1.5 py-0.5 rounded text-xs font-mono cursor-pointer hover:opacity-80 transition-opacity"
                  :class="isDark ? 'bg-gray-800 text-emerald-300' : 'bg-gray-100 text-emerald-700'"
                  :title="'Click to add: ' + ex"
                  @click="addRemoveProxyHeaderValue(ex)">{{ ex }}</code>
                <pre class="mt-1.5 px-2 py-1.5 rounded overflow-x-auto text-[11px]"
                  :class="isDark ? 'bg-gray-900/60 text-gray-300' : 'bg-gray-50 text-gray-700'"><code>"removeProxyRequestHeaders": ["Cookie", "X-Internal-Token"]</code></pre>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  WEBHOOKS (collapsible)                                 -->
      <!-- ═══════════════════════════════════════════════════════ -->
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
          <p class="text-xs mb-4" :class="isDark ? 'text-gray-500' : 'text-gray-400'">Fire an HTTP callback after the response is sent to the client.</p>
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
            <!-- Webhook headers -->
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-sm font-medium" :class="labelClasses">Headers</label>
                <button type="button" @click="addRow(webhookHeaders)"
                  class="inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1 rounded-md border transition-colors cursor-pointer" :class="isDark ? 'text-emerald-300 border-emerald-700 bg-emerald-900/30 hover:bg-emerald-800/50' : 'text-emerald-700 border-emerald-200 bg-emerald-50 hover:bg-emerald-100'">+ Add</button>
              </div>
              <div v-for="(header, i) in webhookHeaders" :key="i" class="mb-2">
                <div class="flex gap-2">
                  <input v-model="header.key" placeholder="Header name"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors" :class="inputClasses" />
                  <input v-model="header.value" placeholder="Header value"
                    class="flex-1 rounded-lg border px-3 py-2 text-sm focus:outline-none focus:ring-2 transition-colors"
                    :class="isMatcherRowValueMissing(header) ? requiredInputClasses('') : inputClasses" />
                  <button type="button" @click="removeRow(webhookHeaders, i)" class="text-red-400 hover:text-red-600 px-2 text-lg">&times;</button>
                </div>
                <p v-if="isMatcherRowValueMissing(header)" class="text-xs text-red-500 mt-1">
                  Header value is required when a header name is provided.
                </p>
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium mb-1" :class="labelClasses">Body (supports Handlebars)</label>
              <textarea v-model="webhookBody" rows="3" placeholder='{"event": "stub-matched", "id": "{{request.path}}"}'
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

      <!--
        The validation report, cURL preview, and submit button all live in
        the sticky side panel (outside this form) so they stay visible
        while the user scrolls the long form on the left.
      -->
    </form>
      </div><!-- /main form column -->

      <!-- ═══════════════════════════════════════════════════════ -->
      <!--  STICKY SIDE PANEL — validation + cURL + submit          -->
      <!-- ═══════════════════════════════════════════════════════ -->
      <aside
        class="lg:mt-0 lg:sticky lg:top-4 space-y-4"
      >
        <!-- Validation report (full live list of every outstanding issue) -->
        <div
          v-if="validationIssuesBySection.length > 0"
          class="rounded-xl border transition-colors"
          :class="isDark ? 'bg-amber-950/30 border-amber-800' : 'bg-amber-50 border-amber-200'"
        >
          <div class="flex items-center justify-between gap-2 px-4 py-3 border-b"
            :class="isDark ? 'border-amber-800' : 'border-amber-200'">
            <div class="flex items-center gap-2">
              <svg class="w-4 h-4" :class="isDark ? 'text-amber-400' : 'text-amber-600'"
                fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round"
                  d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
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
              :class="idx > 0 ? 'mt-3 pt-3 border-t' : ''"
              :style="idx > 0 ? (isDark ? 'border-color: rgb(146 64 14 / 0.5)' : 'border-color: rgb(253 230 138)') : ''">
              <p class="text-xs font-semibold uppercase tracking-wide mb-1"
                :class="isDark ? 'text-amber-300' : 'text-amber-700'">{{ section }}</p>
              <ul class="list-disc list-inside space-y-1">
                <li v-for="(msg, i) in messages" :key="i" class="leading-snug">{{ msg }}</li>
              </ul>
            </div>
          </div>
        </div>

        <!-- Test with cURL -->
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
                <svg v-if="!curlCopied" class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1" />
                </svg>
                <svg v-else class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                </svg>
                {{ curlCopied ? 'Copied!' : 'Copy' }}
              </button>
              <button
                type="button"
                @click="curlVisible = !curlVisible"
                :aria-expanded="curlVisible"
                class="inline-flex items-center px-2.5 py-1 rounded-lg text-xs font-medium transition-colors cursor-pointer"
                :class="isDark ? 'text-gray-400 hover:text-gray-200 hover:bg-gray-800' : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100'"
              >
                {{ curlVisible ? 'Hide' : 'Show' }}
              </button>
            </div>
          </div>
          <div v-show="curlVisible" class="p-4">
            <pre
              class="rounded-lg p-3 overflow-x-auto max-h-[40vh] overflow-y-auto text-xs font-mono leading-relaxed whitespace-pre"
              :class="isDark ? 'bg-gray-800 text-gray-300' : 'bg-gray-100 text-gray-700'"
            >{{ curlCommand }}</pre>
          </div>
        </div>

        <!-- Submit button (sits at the bottom of the sticky aside). Lives
             outside the <form> so we wire it via @click. The form's
             @submit.prevent still handles Enter-key submissions inside
             text inputs. -->
        <div class="rounded-xl border p-4 transition-colors" :class="sectionClasses">
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
        </div>

        <!-- WireMock stub status (edit mode only). Mirrors the per-card
             action on ProjectDetailView: probe the admin port for the
             matching stub and, if it's missing, surface a "click to
             create" button that re-pushes the stored mock to WireMock
             via PUT /api/mocks/{id}. See the corresponding script block
             (refreshStubStatus / handleCreateStub) for the state
             machine. Kept below the submit button so the primary
             "Update Mock" action stays top-of-sidebar. -->
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
              title="Re-check the WireMock admin port"
            >
              Refresh
            </button>
          </div>

          <!-- Loading: initial probe in flight -->
          <div
            v-if="stubStatus === 'loading'"
            class="flex items-center gap-2 text-xs"
            :class="isDark ? 'text-gray-400' : 'text-gray-500'"
          >
            <svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
            Checking WireMock…
          </div>

          <!-- Exists: confirmation only, no action -->
          <div
            v-else-if="stubStatus === 'exists'"
            class="flex items-center gap-2 text-xs"
            :class="isDark ? 'text-emerald-400' : 'text-emerald-600'"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
            Stub present on WireMock
          </div>

          <!-- Missing: prominent call-to-action -->
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
              title="Push this mock to WireMock"
            >
              click to create
            </button>
          </template>

          <!-- Creating: disabled button with spinner -->
          <button
            v-else-if="stubStatus === 'creating'"
            type="button"
            disabled
            class="w-full inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium cursor-not-allowed opacity-70 border"
            :class="isDark
              ? 'text-amber-300 border-amber-500/40'
              : 'text-amber-700 border-amber-300'"
          >
            <svg class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
            Creating…
          </button>

          <!-- Error: probe failed (admin port unreachable, etc.). Treated
               as neutral so a down admin isn't mislabelled as 'missing'. -->
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
    </div><!-- /lg:flex wrapper -->
  </div>
</template>     