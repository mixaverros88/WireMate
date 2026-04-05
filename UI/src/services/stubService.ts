/**
 * WireMock Stub Service
 * Handles communication with WireMock admin API
 */

import { WIREMOCK_ADMIN_URL } from '../config'

export interface RequestConfig {
  method?: string
  url?: string
  urlPath?: string
  urlPattern?: string
  urlPathPattern?: string
  headers?: Record<string, string>
  queryParameters?: Record<string, unknown>
  body?: string | Record<string, unknown>
}

export interface ResponseConfig {
  status?: number
  headers?: Record<string, string>
  body?: string
  jsonBody?: Record<string, unknown>
  fixedDelayMilliseconds?: number
}

export interface StubMapping {
  id: string
  name?: string
  request: RequestConfig
  response: ResponseConfig
  persistent?: boolean
  priority?: number
  createdDate?: string
  metadata?: Record<string, unknown>
}

export interface StubMappingsResponse {
  mappings: StubMapping[]
  meta?: {
    total: number
  }
}

export interface DeleteStubResponse {
  success: boolean
  message?: string
}

/**
 * Fetch stub mappings from WireMock admin API with optional pagination
 * Supports: limit (page size) and offset (skip N items)
 */
export async function fetchAllStubs(params?: {
  limit?: number
  offset?: number
}): Promise<StubMappingsResponse> {
  try {
    const query = new URLSearchParams()
    if (params?.limit) query.set('limit', String(params.limit))
    if (params?.offset !== undefined) query.set('offset', String(params.offset))
    const qs = query.toString()

    const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings${qs ? `?${qs}` : ''}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    if (!response.ok) {
      throw new Error(`Failed to fetch stubs: ${response.statusText}`)
    }

    const data: StubMappingsResponse = await response.json()
    return data
  } catch (error) {
    console.error('Error fetching stubs:', error)
    throw error
  }
}

/**
 * Delete a stub mapping by ID
 */
export async function deleteStub(id: string): Promise<DeleteStubResponse> {
  try {
    const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/${id}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    if (!response.ok) {
      throw new Error(`Failed to delete stub: ${response.statusText}`)
    }

    return {
      success: true,
      message: `Stub ${id} deleted successfully`,
    }
  } catch (error) {
    console.error('Error deleting stub:', error)
    throw error
  }
}

/**
 * Get a single stub mapping by ID
 */
export async function getStub(id: string): Promise<StubMapping> {
  try {
    const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/${id}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    if (!response.ok) {
      throw new Error(`Failed to fetch stub: ${response.statusText}`)
    }

    const data: StubMapping = await response.json()
    return data
  } catch (error) {
    console.error('Error fetching stub:', error)
    throw error
  }
}

/**
 * Check whether a stub mapping with the given ID currently exists in
 * WireMock. Returns `true` for 200, `false` for 404, and throws for any
 * other status so callers can distinguish "definitely missing" from
 * "network/admin-port problem" (which should not be rendered as missing).
 *
 * We retry on transient 5xx / network errors because WireMock's embedded
 * Jetty on the admin port returns 503 (Service Unavailable) under
 * concurrent load — when this page fires several probes at once, a
 * subset routinely comes back 503 even though the mapping resolves
 * fine on a follow-up request. Without retrying we would mislabel
 * existing stubs as missing the moment the user opens a busy project.
 */
export async function checkStubExists(id: string): Promise<boolean> {
  const maxAttempts = 3
  let lastError: unknown = null
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/${id}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      })
      if (response.status === 200) return true
      if (response.status === 404) return false
      // 5xx is typically transient under admin-port contention — retry
      // with a short linear backoff before giving up. 4xx other than
      // 404 is a hard error (bad UUID, etc.) and should not be retried.
      if (response.status >= 500 && response.status < 600 && attempt < maxAttempts) {
        await new Promise(r => setTimeout(r, 150 * attempt))
        continue
      }
      throw new Error(`Unexpected status ${response.status} when checking stub existence`)
    } catch (e) {
      lastError = e
      if (attempt < maxAttempts) {
        await new Promise(r => setTimeout(r, 150 * attempt))
        continue
      }
      throw e
    }
  }
  // Unreachable — the loop either returns, retries, or throws.
  throw lastError ?? new Error('checkStubExists: exhausted retries')
}

/**
 * Save all in-memory stubs to disk (persist to __files/ directory)
 */
export async function saveAllStubs(): Promise<void> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/save`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to save stubs: ${response.statusText}`)
  }
}

/**
 * Reset stubs to startup defaults (reload from disk)
 */
export async function resetStubsToDefaults(): Promise<void> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/reset`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to reset stubs: ${response.statusText}`)
  }
}

/**
 * Find stubs by metadata matcher
 */
export async function findStubsByMetadata(
  metadataMatcher: Record<string, unknown>,
): Promise<StubMappingsResponse> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/find-by-metadata`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(metadataMatcher),
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to find stubs by metadata: ${response.statusText}`)
  }
  return response.json()
}

/**
 * Remove stubs by metadata matcher
 */
export async function removeStubsByMetadata(
  metadataMatcher: Record<string, unknown>,
): Promise<StubMappingsResponse> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/remove-by-metadata`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(metadataMatcher),
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to remove stubs by metadata: ${response.statusText}`)
  }
  return response.json()
}

/**
 * Get unmatched stubs (stubs that have never been triggered)
 */
export async function fetchUnmatchedStubs(): Promise<StubMappingsResponse> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/unmatched`, {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to fetch unmatched stubs: ${response.statusText}`)
  }
  return response.json()
}

/**
 * Delete all unmatched stubs (stubs that have never been triggered)
 */
export async function deleteUnmatchedStubs(): Promise<DeleteStubResponse> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/unmatched`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to delete unmatched stubs: ${response.statusText}`)
  }
  return { success: true, message: 'Unmatched stubs deleted successfully' }
}

/**
 * Update a stub mapping by ID (full replacement)
 */
export async function updateStub(id: string, stub: Partial<StubMapping>): Promise<StubMapping> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(stub),
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to update stub: ${response.statusText}`)
  }
  return response.json()
}

/**
 * Create a new stub mapping
 */
export async function createStub(stub: Partial<StubMapping>): Promise<StubMapping> {
  const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(stub),
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Failed to create stub: ${response.statusText}`)
  }
  return response.json()
}

/**
 * Reset all stubs (clear all mappings)
 */
export async function resetAllStubs(): Promise<DeleteStubResponse> {
  try {
    const response = await fetch(`${WIREMOCK_ADMIN_URL}/mappings`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    if (!response.ok) {
      throw new Error(`Failed to reset stubs: ${response.statusText}`)
    }

    return {
      success: true,
      message: 'All stubs reset successfully',
    }
  } catch (error) {
    console.error('Error resetting stubs:', error)
    throw error
  }
}
