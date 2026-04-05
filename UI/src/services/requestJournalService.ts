import type {
  RequestJournalResponse,
  RequestPattern,
  LoggedRequest,
  LoggedRequestDetail,
} from '../types/requestJournal'

import { WIREMOCK_ADMIN_URL } from '../config'

const WIREMOCK_ADMIN = WIREMOCK_ADMIN_URL
const BASE_URL = `${WIREMOCK_ADMIN}/requests`

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Request failed with status ${response.status}`)
  }
  return response.json()
}

/**
 * POST /__admin/requests/find — Find requests matching a pattern.
 *
 * This is the single journal-read endpoint the UI uses: passing an empty
 * pattern (`{}`) matches every request, so list and filter flows both go
 * through here.
 *
 * WireMock's /find can return results in two shapes depending on version:
 *   1. Wrapped ServeEvent-style: { id, request: {...}, response?, stubMapping?, ... }
 *   2. Flat LoggedRequestDetail: { method, url, loggedDate, headers, ... }
 *
 * We normalize to the wrapped shape so the rest of the UI can treat results
 * uniformly regardless of which variant the backend returned.
 */
export async function findRequests(
  pattern: RequestPattern
): Promise<RequestJournalResponse> {
  const raw = await request<{
    requests: Array<LoggedRequest | LoggedRequestDetail>
    meta?: { total: number }
    requestJournalDisabled?: boolean
  }>(`${BASE_URL}/find`, {
    method: 'POST',
    body: JSON.stringify(pattern),
  })

  const normalized: LoggedRequest[] = (raw.requests ?? []).map((item, index) => {
    // Already in wrapped form.
    if ((item as LoggedRequest).request) return item as LoggedRequest
    // Flat LoggedRequestDetail — wrap it so downstream code can rely on
    // `request.request.url`, `request.request.method`, etc.
    const detail = item as LoggedRequestDetail & { id?: string }
    return {
      id: detail.id ?? `find-${index}-${detail.loggedDate ?? ''}`,
      request: detail,
    }
  })

  return {
    requests: normalized,
    meta: raw.meta ?? { total: normalized.length },
    requestJournalDisabled: raw.requestJournalDisabled ?? false,
  }
}

/**
 * DELETE /__admin/requests — Delete all requests.
 *
 * NOTE: The Logs page no longer uses this — it's kept here only because
 * NearMissesView.vue still depends on it. If that usage is removed the
 * function can be deleted.
 */
export async function deleteAllRequests(): Promise<void> {
  const response = await fetch(BASE_URL, { method: 'DELETE' })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to delete all requests (${response.status})`)
  }
}
