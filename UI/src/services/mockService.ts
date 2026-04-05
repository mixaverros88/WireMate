import type { MockResponse, CloneMockRequest, MoveMockRequest } from '../types/mock'
import { API_URL } from '../config'
import { throwApiError } from './errors'

const BASE_URL = `${API_URL}/mocks`

export async function fetchMocksByProject(projectId: string): Promise<MockResponse[]> {
  const response = await fetch(`${BASE_URL}/project/${projectId}`)
  if (!response.ok) {
    await throwApiError(response, 'Failed to fetch mocks for project')
  }
  return response.json()
}

export async function searchMocksByProjectAndUrl(
  projectId: string,
  url: string,
): Promise<MockResponse[]> {
  const response = await fetch(`${API_URL}/projects/${projectId}/mocks`)
  if (!response.ok) {
    await throwApiError(response, 'Failed to search mocks')
  }
  const all = (await response.json()) as MockResponse[]
  const needle = url.toLowerCase()
  return all.filter(m => {
    const candidates = [
      m.request.url,
      m.request.urlPath,
      m.request.urlPattern,
      m.request.urlPathPattern,
    ]
    return candidates.some(c => typeof c === 'string' && c.toLowerCase().includes(needle))
  })
}

export async function checkMockUrlExists(
  projectId: string,
  url: string,
): Promise<boolean> {
  const response = await fetch(
    `${BASE_URL}/projects/${encodeURIComponent(projectId)}/exists/by-url`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    },
  )
  if (!response.ok) {
    await throwApiError(response, 'Failed to check whether the mock URL exists')
  }
  return (await response.json()) === true
}

export async function checkMockNameExists(
  projectId: string,
  name: string,
): Promise<boolean> {
  const response = await fetch(
    `${BASE_URL}/projects/${encodeURIComponent(projectId)}/exists/by-name`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
    },
  )
  if (!response.ok) {
    await throwApiError(response, 'Failed to check whether the mock name exists')
  }
  return (await response.json()) === true
}

export async function deleteMock(id: string): Promise<void> {
  const response = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    await throwApiError(response, 'Failed to delete mock')
  }
}

export async function cloneMock(mockId: string, request: CloneMockRequest): Promise<MockResponse> {
  const response = await fetch(`${BASE_URL}/${mockId}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    await throwApiError(response, 'Failed to clone mock')
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
    await throwApiError(response, 'Failed to move mock')
  }

  return response.json()
}

export async function fetchMockById(mockId: string): Promise<MockResponse> {
  const response = await fetch(`${BASE_URL}/${mockId}`)

  if (response.status === 404) {
    throw new Error(`Mock ${mockId} not found`)
  }

  if (!response.ok) {
    await throwApiError(response, `Failed to fetch mock (HTTP ${response.status})`)
  }
  return response.json()
}
