import type { Project, CreateProjectRequest, CloneProjectRequest } from '../types/project'
import { API_URL } from '../config'
import { throwApiError } from './errors'

const BASE_URL = `${API_URL}/projects`

export async function fetchProjects(): Promise<Project[]> {
  const response = await fetch(BASE_URL)
  if (!response.ok) {
    await throwApiError(response, 'Failed to fetch projects')
  }
  return response.json()
}

export async function fetchProject(id: string): Promise<Project> {
  const response = await fetch(`${BASE_URL}/${id}`)
  if (!response.ok) {
    await throwApiError(response, 'Failed to fetch project')
  }
  return response.json()
}

export async function deleteProject(id: string): Promise<void> {
  const response = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    await throwApiError(response, 'Failed to delete project')
  }
}

// Rename an existing project. Hits PUT /api/projects/{id} with the standard
// `{ name }` payload - same shape the create endpoint takes - so the backend
// can reuse its name-uniqueness check. On any non-OK status we surface the
// backend's ProblemDetail `detail` text so duplicate-name and validation
// errors show the actual reason in the inline message instead of a generic
// "Failed to rename project" toast.
export async function renameProject(id: string, name: string): Promise<Project> {
  const response = await fetch(`${BASE_URL}/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })

  if (!response.ok) {
    await throwApiError(response, 'Failed to rename project')
  }

  return response.json()
}

export async function createProject(request: CreateProjectRequest): Promise<Project> {
  const response = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    await throwApiError(response, 'Failed to create project')
  }

  return response.json()
}

// Returns the cloned Project when the backend includes it in the response,
// or `null` when the endpoint replies with an empty body (204 No Content
// or 200 with Content-Length: 0). The clone itself is still considered
// successful in the empty-body case - callers should re-fetch the project
// list to pick up the new row instead of trying to append a result that
// isn't there. Calling response.json() unconditionally on an empty body
// throws "Unexpected end of JSON input", which the UI was surfacing as a
// misleading "Failed to clone project" toast even though the clone
// succeeded server-side.
export async function cloneProject(
  projectId: string,
  request: CloneProjectRequest,
): Promise<Project | null> {
  const response = await fetch(`${BASE_URL}/${projectId}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    // e.g. 400 with { detail: "Project with name: X (copy) already exists" }
    // - bubble that detail straight up so the user sees the real reason.
    await throwApiError(response, 'Failed to clone project')
  }

  if (response.status === 204) {
    return null
  }

  const text = await response.text()
  if (!text) {
    return null
  }

  try {
    return JSON.parse(text) as Project
  } catch (e) {
    throw new Error(
      `Invalid JSON response from clone: ${e instanceof Error ? e.message : String(e)}`,
    )
  }
}
