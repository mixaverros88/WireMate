export interface KeyValuePair {
  key: string
  value: string
  _customKey?: boolean
}

// ── String Matcher ─────────────────────────────────────────────
// Used for headers, cookies, query parameters
export interface StringMatcher {
  equalTo?: string
  matches?: string
  doesNotMatch?: string
  contains?: string
  absent?: boolean
  caseInsensitive?: boolean
  // JSON matchers
  equalToJson?: string
  ignoreArrayOrder?: boolean
  ignoreExtraElements?: boolean
  matchesJsonPath?: string | { expression: string; [key: string]: unknown }
  // XML matchers
  equalToXml?: string
  enablePlaceholders?: boolean
  matchesXPath?: string | { expression: string; [key: string]: unknown }
  xPathNamespaces?: Record<string, string>
  // Binary
  binaryEqualTo?: string
  // DateTime matchers
  before?: string
  after?: string
  equalToDateTime?: string
  truncateExpected?: boolean
  truncateActual?: boolean
  expectedOffset?: string
  expectedOffsetUnit?: string
}

// ── Body Pattern ───────────────────────────────────────────────
export interface BodyPattern {
  equalTo?: string
  contains?: string
  matches?: string
  doesNotMatch?: string
  equalToJson?: string | object
  ignoreArrayOrder?: boolean
  ignoreExtraElements?: boolean
  matchesJsonPath?: string | { expression: string; [key: string]: unknown }
  equalToXml?: string
  matchesXPath?: string | { expression: string; [key: string]: unknown }
  xPathNamespaces?: Record<string, string>
  binaryEqualTo?: string
  absent?: boolean
  caseInsensitive?: boolean
  enablePlaceholders?: boolean
}

// ── Basic Auth ─────────────────────────────────────────────────
export interface BasicAuthCredentials {
  username: string
  password: string
}

// ── Custom Matcher ─────────────────────────────────────────────
export interface CustomMatcher {
  name: string
  parameters?: Record<string, unknown>
}

// ── Request Definition ─────────────────────────────────────────
export interface RequestDefinition {
  method: string
  // URL matching (use exactly one)
  url?: string
  urlPath?: string
  urlPattern?: string
  urlPathPattern?: string
  // Headers & Cookies
  headers?: Record<string, StringMatcher>
  cookies?: Record<string, StringMatcher>
  // Query parameters
  queryParameters?: Record<string, StringMatcher>
  // Body
  bodyPatterns?: BodyPattern[]
  // Basic auth
  basicAuthCredentials?: BasicAuthCredentials
  // Custom matcher
  customMatcher?: CustomMatcher
}

// ── Delay Distribution ─────────────────────────────────────────
export interface DelayDistribution {
  type: 'uniform' | 'lognormal' | 'fixed'
  lower?: number
  upper?: number
  median?: number
  sigma?: number
  milliseconds?: number
}

// ── Chunked Dribble Delay ──────────────────────────────────────
export interface ChunkedDribbleDelay {
  numberOfChunks: number
  totalDuration: number
}

// ── Webhook ────────────────────────────────────────────────────
// The shape of the `parameters` object WireMock's webhook listener expects.
// Kept as a standalone type so the UI can still reason about the fields it
// serialises (url/method/headers/body/delay) independently of the generic
// ServeEventListener envelope it's wrapped in on the wire.
export interface WebhookDefinition {
  url: string
  method: string
  headers?: Record<string, string>
  body?: string
  delay?: { type: string; milliseconds: number }
}

// ── Serve Event Listener ───────────────────────────────────────
// WireMock 3.1.0+ replaces the legacy `postServeActions` map with a
// `serveEventListeners` array. Each entry names a listener extension
// (e.g. "webhook") and carries an opaque `parameters` object that the
// listener interprets. Modelled as `unknown` so this envelope stays
// listener-agnostic — per-listener shapes live on their own types.
export interface ServeEventListener {
  name: string
  parameters: Record<string, unknown>
}

// ── Response Definition ────────────────────────────────────────
export interface ResponseDefinition {
  status: number
  statusMessage?: string
  // Headers
  //
  // Values can be a single string or an array of strings. WireMock accepts
  // the array form for headers that can legitimately appear multiple times
  // on a response — notably `Set-Cookie`, where a comma-joined single value
  // would be invalid (cookie Expires dates legally contain commas).
  headers?: Record<string, string | string[]>

  // Body (use exactly one)
  body?: string
  jsonBody?: unknown
  base64Body?: string
  bodyFileName?: string
  // Delays
  fixedDelayMilliseconds?: number
  delayDistribution?: DelayDistribution
  chunkedDribbleDelay?: ChunkedDribbleDelay
  // Fault simulation
  fault?: 'EMPTY_RESPONSE' | 'MALFORMED_RESPONSE_CHUNK' | 'RANDOM_DATA_THEN_CLOSE' | 'CONNECTION_RESET_BY_PEER'
  // Proxying
  proxyBaseUrl?: string
  additionalProxyRequestHeaders?: Record<string, string>
  removeProxyRequestHeaders?: string[]
  // Transformers
  transformers?: string[]
  transformerParameters?: Record<string, unknown>
}

// ── Metadata ───────────────────────────────────────────────────
export type StubMetadata = Record<string, unknown>

// ── Create / Update Payload ────────────────────────────────────
export interface CreateMockPayload {
  name: string
  description?: string
  projectId: string
  request: RequestDefinition
  response: ResponseDefinition
  persistent: boolean
  // Top-level optional properties
  priority?: number
  scenarioName?: string
  requiredScenarioState?: string
  newScenarioState?: string
  metadata?: StubMetadata
  // Webhooks / post-serve actions.
  // Emitted as a WireMock 3.1.0+ `serveEventListeners` array — the
  // legacy `postServeActions` map is no longer produced by the UI.
  serveEventListeners?: ServeEventListener[]
  // A ready-to-run cURL command the UI generates from the current form,
  // sent on create/update so the backend can persist it alongside the mock.
  // Only present when the URL is concrete (not a regex/path pattern) — for
  // pattern-based mocks the UI cannot produce a runnable cURL.
  curl?: string
}

// ── Mock Response (from API) ───────────────────────────────────
export interface MockResponse {
  id: string
  name: string
  description?: string
  projectId: string
  request: RequestDefinition
  response: ResponseDefinition
  persistent: boolean
  priority?: number
  scenarioName?: string
  requiredScenarioState?: string
  newScenarioState?: string
  metadata?: StubMetadata
  // Modern (3.1.0+) listener array. Preferred on read.
  serveEventListeners?: ServeEventListener[]
  // Legacy shape kept only so stubs saved before the migration still
  // hydrate into the edit form. The UI never emits this field — on next
  // save the mock is rewritten to `serveEventListeners`.
  postServeActions?: {
    webhook?: WebhookDefinition
  }
  // Pre-rendered cURL invocation persisted by the backend at
  // create/update time. Round-trips from `CreateMockPayload.curl` so
  // the UI can offer a one-click copy without regenerating from the
  // current state (which would drift if the stub was edited via the
  // raw WireMock API). Absent for pattern-based mocks where the URL
  // isn't concrete.
  curl?: string
  createdAt: string
  updatedAt: string
}

export interface ProjectResponse {
  id: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface CloneMockRequest {
  name: string
}

export interface MoveMockRequest {
  targetProjectId: string
}
