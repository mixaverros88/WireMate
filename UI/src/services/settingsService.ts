import type {
  ScenariosResponse,
  GlobalSettings,
  GlobalSettingsResponse,
  HealthResponse,
  VersionResponse,
  RecordingStatusResponse,
} from '../types/settings'

import { WIREMOCK_ADMIN_URL } from '../config'

const API_BASE = WIREMOCK_ADMIN_URL

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Request failed with status ${response.status}`)
  }
  return response.json()
}

async function requestNoBody(url: string, options?: RequestInit): Promise<void> {
  const response = await fetch(`${API_BASE}${url}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  })
  if (!response.ok) {
    const errorBody = await response.text()
    throw new Error(errorBody || `Request failed with status ${response.status}`)
  }
}

/** GET /__admin/scenarios — Get all scenarios */
export async function fetchScenarios(): Promise<ScenariosResponse> {
  return request<ScenariosResponse>('/scenarios')
}

/** POST /__admin/scenarios/reset — Reset all scenarios */
export async function resetScenarios(): Promise<void> {
  return requestNoBody('/scenarios/reset', { method: 'POST' })
}

/** PUT /__admin/scenarios/{name}/state — Set scenario state */
export async function setScenarioState(name: string, state: string): Promise<void> {
  return requestNoBody(`/scenarios/${encodeURIComponent(name)}/state`, {
    method: 'PUT',
    body: JSON.stringify({ state }),
  })
}

/** POST /__admin/recordings/start — Start recording */
export async function startRecording(targetBaseUrl: string): Promise<void> {
  return requestNoBody('/recordings/start', {
    method: 'POST',
    body: JSON.stringify({ targetBaseUrl }),
  })
}

/** POST /__admin/recordings/stop — Stop recording */
export async function stopRecording(): Promise<unknown> {
  return request<unknown>('/recordings/stop', { method: 'POST' })
}

/** GET /__admin/recordings/status — Get recording status */
export async function getRecordingStatus(): Promise<RecordingStatusResponse> {
  return request<RecordingStatusResponse>('/recordings/status')
}

/** POST /__admin/recordings/snapshot — Snapshot recorded stubs */
export async function snapshotRecording(): Promise<unknown> {
  return request<unknown>('/recordings/snapshot', { method: 'POST' })
}

/** GET /__admin/requests/unmatched/near-misses — Get near-miss analysis */
export async function fetchNearMisses(): Promise<unknown> {
  return request<unknown>('/requests/unmatched/near-misses')
}

/** GET /__admin/settings — Get current global settings */
export async function fetchSettings(): Promise<GlobalSettingsResponse> {
  return request<GlobalSettingsResponse>('/settings')
}

/** POST /__admin/settings — Update global settings */
export async function updateSettings(settings: GlobalSettings): Promise<void> {
  return requestNoBody('/settings', {
    method: 'POST',
    body: JSON.stringify(settings),
  })
}

/** POST /__admin/reset — Reset mappings and journal */
export async function resetAll(): Promise<void> {
  return requestNoBody('/reset', { method: 'POST' })
}

/** POST /__admin/shutdown — Shutdown the server */
export async function shutdownServer(): Promise<void> {
  return requestNoBody('/shutdown', { method: 'POST' })
}

/** GET /__admin/health — Health check */
export async function fetchHealth(): Promise<HealthResponse> {
  return request<HealthResponse>('/health')
}

/** GET /__admin/version — Get version */
export async function fetchVersion(): Promise<VersionResponse> {
  return request<VersionResponse>('/version')
}
