import type { CreateMockPayload, MockResponse, CloneMockRequest, MoveMockRequest } from '../types/mock'
import { API_URL } from '../config'
import { request } from './http'

// Single client for everything under `/api/mocks`. Previously the
// per-mock CRUD was split between this object (create/update/republish)
// and a sibling `mockService.ts` (delete/clone/move/fetch/existence
// checks) — same resource, two clients, slightly different error
// surfaces. They've now been collapsed into one named export so the
// rest of the app has exactly one place to look for mock HTTP calls.
//
// Existence checks (`exists/by-url`, `exists/by-name`) and the
// project-scoped URL collision lookup also live here because they
// share the same `/mocks` URL space.

const BASE = `${API_URL}/mocks`

export const mockApi = {
  create(payload: CreateMockPayload): Promise<MockResponse> {
    return request<MockResponse>(BASE, {
      method: 'POST',
      body: JSON.stringify(payload),
      errorFallback: 'Failed to create mock',
    })
  },

  fetchById(id: string): Promise<MockResponse> {
    return request<MockResponse>(`${BASE}/${id}`, {
      errorFallback: 'Failed to fetch mock',
    })
  },

  update(id: string, payload: CreateMockPayload): Promise<MockResponse> {
    return request<MockResponse>(`${BASE}/${id}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
      errorFallback: 'Failed to update mock',
    })
  },

  delete(id: string): Promise<void> {
    return request<void>(`${BASE}/${id}`, {
      method: 'DELETE',
      errorFallback: 'Failed to delete mock',
    })
  },

  // Republish may return either the updated MockResponse or an empty
  // body (204) depending on backend version — callers should not depend
  // on the returned value. The shared `request<T>` helper resolves to
  // `undefined` for empty/204 bodies so we don't trip over JSON parsing.
  republish(id: string): Promise<MockResponse | undefined> {
    return request<MockResponse | undefined>(`${BASE}/${id}/republish`, {
      method: 'POST',
      errorFallback: 'Failed to republish mock',
    })
  },

  clone(id: string, payload: CloneMockRequest): Promise<MockResponse> {
    return request<MockResponse>(`${BASE}/${id}/clone`, {
      method: 'POST',
      body: JSON.stringify(payload),
      errorFallback: 'Failed to clone mock',
    })
  },

  move(id: string, payload: MoveMockRequest): Promise<MockResponse> {
    return request<MockResponse>(`${BASE}/${id}/move`, {
      method: 'PUT',
      body: JSON.stringify(payload),
      errorFallback: 'Failed to move mock',
    })
  },

  /**
   * Return all mocks in the given project whose request URL exactly
   * matches `url` (compared against url / urlPath / urlPattern /
   * urlPathPattern). Used by the create/edit form to show *which*
   * mocks collide with the URL the user typed.
   *
   * TODO: this fetches every mock under the project and filters on the
   * client. Move into a dedicated backend endpoint
   * (`/api/projects/{id}/mocks?url=...`) when the API supports it.
   */
  async findByUrlInProject(projectId: string, url: string): Promise<MockResponse[]> {
    const all = await request<MockResponse[]>(
      `${API_URL}/projects/${encodeURIComponent(projectId)}/mocks`,
      { errorFallback: 'Failed to fetch mocks for collision check' },
    )
    return all.filter(m => {
      const candidates = [
        m.request.url,
        m.request.urlPath,
        m.request.urlPattern,
        m.request.urlPathPattern,
      ]
      return candidates.some(c => typeof c === 'string' && c === url)
    })
  },

  async existsByUrl(projectId: string, url: string): Promise<boolean> {
    const result = await request<boolean>(
      `${BASE}/projects/${encodeURIComponent(projectId)}/exists/by-url`,
      {
        method: 'POST',
        body: JSON.stringify({ url }),
        errorFallback: 'Failed to check whether the mock URL exists',
      },
    )
    return result === true
  },

  async existsByName(projectId: string, name: string): Promise<boolean> {
    const result = await request<boolean>(
      `${BASE}/projects/${encodeURIComponent(projectId)}/exists/by-name`,
      {
        method: 'POST',
        body: JSON.stringify({ name }),
        errorFallback: 'Failed to check whether the mock name exists',
      },
    )
    return result === true
  },
}
