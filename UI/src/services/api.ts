import type { CreateMockPayload, MockResponse } from '../types/mock'
import { API_BASE_URL } from '../config'

const API_BASE = API_BASE_URL

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`, {
    headers: {
      'Content-Type': 'application/json',
    },
    ...options,
  })

  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Request failed with status ${response.status}`)
  }

  return response.json()
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
}

