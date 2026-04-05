import type { Project, CreateProjectRequest, CloneProjectRequest } from '../types/project'
import { API_BASE_URL } from '../config'

const BASE_URL = `${API_BASE_URL}/projects`

export async function fetchProjects(): Promise<Project[]> {
  const response = await fetch(BASE_URL)
  if (!response.ok) {
    throw new Error('Failed to fetch projects')
  }
  return response.json()
}

export async function fetchProject(id: string): Promise<Project> {
  const response = await fetch(`${BASE_URL}/${id}`)
  if (!response.ok) {
    throw new Error('Failed to fetch project')
  }
  return response.json()
}

export async function deleteProject(id: string): Promise<void> {
  const response = await fetch(`${BASE_URL}/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    throw new Error('Failed to delete project')
  }
}

export async function createProject(request: CreateProjectRequest): Promise<Project> {
  const response = await fetch(BASE_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (response.status === 409) {
    throw new Error('A project with this name already exists')
  }

  if (!response.ok) {
    throw new Error('Failed to create project')
  }

  return response.json()
}

export async function cloneProject(projectId: string, request: CloneProjectRequest): Promise<Project> {
  const response = await fetch(`${BASE_URL}/${projectId}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })

  if (response.status === 409) {
    throw new Error('A project with this name already exists')
  }

  if (!response.ok) {
    throw new Error('Failed to clone project')
  }

  return response.json()
}
