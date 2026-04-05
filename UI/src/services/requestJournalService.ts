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

function normalizeEntry(item: LoggedRequest | LoggedRequestDetail, index: number): LoggedRequest {
  if ((item as LoggedRequest).request) {
    const wrapped = item as LoggedRequest
    if (!wrapped.id) {
      const alt = (item as unknown as { uuid?: string }).uuid
      if (typeof alt === 'string' && alt) {
        return { ...wrapped, id: alt }
      }
    }
    return wrapped
  }
  const detail = item as LoggedRequestDetail & { id?: string }
  return {
    id: detail.id ?? `find-${index}-${detail.loggedDate ?? ''}`,
    request: detail,
  }
}

export async function getAllRequests(): Promise<RequestJournalResponse> {
  const raw = await request<{
    requests?: Array<LoggedRequest | LoggedRequestDetail>
    meta?: { total: number }
    requestJournalDisabled?: boolean
  }>(BASE_URL)

  const normalized: LoggedRequest[] = (raw.requests ?? []).map(normalizeEntry)
  return {
    requests: normalized,
    meta: raw.meta ?? { total: normalized.length },
    requestJournalDisabled: raw.requestJournalDisabled ?? false,
  }
}

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

  const normalized: LoggedRequest[] = (raw.requests ?? []).map(normalizeEntry)

  return {
    requests: normalized,
    meta: raw.meta ?? { total: normalized.length },
    requestJournalDisabled: raw.requestJournalDisabled ?? false,
  }
}

export async function deleteAllRequests(): Promise<void> {
  const response = await fetch(BASE_URL, { method: 'DELETE' })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to delete all requests (${response.status})`)
  }
}

export async function deleteRequest(id: string): Promise<void> {
  const response = await fetch(`${BASE_URL}/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to delete request (${response.status})`)
  }
}

export async function removeRequestsByPattern(
  pattern: RequestPattern,
): Promise<{ requestsRemoved?: LoggedRequest[]; ids: string[] }> {
  const raw = await request<{
    requestsRemoved?: LoggedRequest[]
    ids?: string[]
  }>(`${BASE_URL}/remove`, {
    method: 'POST',
    body: JSON.stringify(pattern),
  })
  return {
    requestsRemoved: raw.requestsRemoved,
    ids: raw.ids ?? (raw.requestsRemoved ?? []).map(r => r.id).filter(Boolean) as string[],
  }
}
