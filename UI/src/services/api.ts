import type { CreateMockPayload, MockResponse } from '../types/mock'
import { API_URL } from '../config'
import { throwApiError } from './errors'

const API_BASE = API_URL

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`, {
    headers: {
      'Content-Type': 'application/json',
    },
    ...options,
  })

  if (!response.ok) {
    // Surface the backend's ProblemDetail `detail` text (e.g. "Mock with
    // name X already exists") so the UI's toast shows the real reason
    // instead of a generic status code.
    await throwApiError(response, `Request failed with status ${response.status}`)
  }

  // Some endpoints (e.g. POST /mocks/{id}/republish) legitimately return an
  // empty body - either 204 No Content or 200 with Content-Length: 0. Calling
  // response.json() on those throws "Unexpected end of JSON input", which the
  // UI then surfaces as a misleading "Failed to create stub" toast even
  // though the operation succeeded. Read the body as text first and only
  // parse when there's actually something to parse.
  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  if (!text) {
    return undefined as T
  }

  try {
    return JSON.parse(text) as T
  } catch (e) {
    throw new Error(
      `Invalid JSON response from ${url}: ${e instanceof Error ? e.message : String(e)}`,
    )
  }
}

export const mockApi = {
  create(payload: CreateMockPayload): Promise<MockResponse> {
    return request<MockResponse>('/mocks', {
      method: 'POST',
      body: JSON.stringify(payload),
    })
  },

  fetchById(id: string): Promise<MockResponse> {
    return request<MockResponse>(`/mocks/${id}`)
  },

  update(id: string, payload: CreateMockPayload): Promise<MockResponse> {
    return request<MockResponse>(`/mocks/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    })
  },
  // Republish may return either the updated MockResponse or an empty body
  // (204) depending on backend version - callers should not depend on the
  // returned value. ProjectDetailView.createStubForMock re-fetches via
  // loadMocks() right after, so a void return is fine.
  republish(id: string): Promise<MockResponse | undefined> {
    return request<MockResponse | undefined>(`/mocks/${id}/republish`, {
      method: 'POST',
    })
  },
}
