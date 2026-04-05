import type { MockResponse, CloneMockRequest, MoveMockRequest } from '../types/mock'
import { API_BASE_URL } from '../config'

const BASE_URL = `${API_BASE_URL}/mocks`

export async function fetchMocksByProject(projectId: string): Promise<MockResponse[]> {
  const response = await fetch(`${BASE_URL}/project/${projectId}`)
  if (!response.ok) {
    throw new Error('Failed to fetch mocks for project')
  }
  return response.json()
}

export async function deleteMock(id: string): Promise<void> {
  const response = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    throw new Error('Failed to delete mock')
  }
}

export async function cloneMock(mockId: string, request: CloneMockRequest): Promise<MockResponse> {
  const response = await fetch(`${BASE_URL}/${mockId}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (response.status === 409) {
    throw new Error('A mock with this name already exists')
  }

  if (!response.ok) {
    throw new Error('Failed to clone mock')
  }

  return response.json()
}

export async function moveMock(mockId: string, request: MoveMockRequest): Promise<MockResponse> {
  const response = await fetch(`${BASE_URL}/${mockId}/move`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    throw new Error('Failed to move mock')
  }

  return response.json()
}

/**
 * GET /api/mocks/{mockId} — fetch a single mock by id.
 *
 * Used by the `/mock/:mockId` shortcut route to resolve a bare mockId into
 * its owning projectId so the user can be deep-linked to the canonical
 * edit URL `/projects/{projectId}/mocks/{mockId}/edit`.
 */
export async function fetchMockById(mockId: string): Promise<MockResponse> {
  const response = await fetch(`${BASE_URL}/${mockId}`)

  if (response.status === 404) {
    throw new Error(`Mock ${mockId} not found`)
  }

  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(body || `Failed to fetch mock (HTTP ${response.status})`)
  }

  return response.json()
}
