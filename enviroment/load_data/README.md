# WireMate — Sample Data Loader

Shell script that populates WireMate with sample data and exercises **every API endpoint** (14/14). The amount of data is controlled by a single parameter `N`.

## Prerequisites

- WireMate backend running (default `http://localhost:8081`)
- PostgreSQL and WireMock containers up (via `docker-compose`)
- `curl` installed

## Usage

```bash
# Default: 3 projects × 3 mocks each (9 mocks total)
bash load_data.sh

# 5 projects × 5 mocks each (25 mocks total)
bash load_data.sh 5

# 10 projects × 10 mocks, custom backend URL
bash load_data.sh 10 http://localhost:8081
```

### Arguments

| # | Argument | Default | Description |
|---|----------|---------|-------------|
| 1 | `N` | `3` | Number of projects to create AND number of mocks per project |
| 2 | `BASE_URL` | `http://localhost:8081` | WireMate backend URL |

### Data scale

| N | Projects | Mocks | Total API calls |
|---|----------|-------|-----------------|
| 1 | 1 | 1 | ~14 |
| 3 | 3 | 9 | ~24 |
| 5 | 5 | 25 | ~42 |
| 10 | 10 | 100 | ~127 |

## What the Script Does

The script runs through 10 phases, each exercising specific API endpoints.

### Phase 1 — Create projects
`POST /api/projects` — Creates N projects with names from a built-in pool (User Service, Order Service, Payment Gateway, etc.).

### Phase 2 — Create mocks
`POST /api/mocks` — Creates N mocks inside each project. Each mock has a unique WireMock request/response definition with varied HTTP methods and URL paths.

### Phase 3 — Read operations
Exercises all GET endpoints to verify data was created correctly:
- `GET /api/projects` — list all projects
- `GET /api/projects/{id}` — get single project with its mocks
- `GET /api/mocks/{id}` — get single mock details
- `GET /api/mocks/project/{projectId}` — list mocks for a project
- `GET /api/backoffice/notifications` — retrieve system notifications

### Phase 4 — Update mocks
`PUT /api/mocks/{id}` — Updates the first mock in each project with new request/response data.

### Phase 5 — Publish to WireMock
`POST /api/wiremock/mocks/{id}/publish` — Publishes the first mock of each project to the running WireMock server.

### Phase 6 — Clone a project
`POST /api/projects/{id}/clone` — Clones the first project (including all its mocks).

### Phase 7 — Clone a mock
`POST /api/mocks/{id}/clone` — Clones the first mock of the second project.

### Phase 8 — Move a mock
`PUT /api/mocks/{id}/move` — Moves the cloned mock to the last project.

### Phase 9 — Delete a mock
`DELETE /api/mocks/{id}` — Deletes the moved mock.

### Phase 10 — Delete a project
`DELETE /api/projects/{id}` — Deletes the cloned project.

## API Endpoints Covered

All 14 endpoints from all 4 controllers are exercised:

| Controller | Method | Endpoint | Phase |
|------------|--------|----------|-------|
| ProjectController | `POST` | `/api/projects` | 1 |
| ProjectController | `GET` | `/api/projects` | 3 |
| ProjectController | `GET` | `/api/projects/{id}` | 3 |
| ProjectController | `POST` | `/api/projects/{id}/clone` | 6 |
| ProjectController | `DELETE` | `/api/projects/{id}` | 10 |
| MockController | `POST` | `/api/mocks` | 2 |
| MockController | `GET` | `/api/mocks/{id}` | 3 |
| MockController | `PUT` | `/api/mocks/{id}` | 4 |
| MockController | `GET` | `/api/mocks/project/{id}` | 3 |
| MockController | `POST` | `/api/mocks/{id}/clone` | 7 |
| MockController | `PUT` | `/api/mocks/{id}/move` | 8 |
| MockController | `DELETE` | `/api/mocks/{id}` | 9 |
| WireMockController | `POST` | `/api/wiremock/mocks/{id}/publish` | 5 |
| BackofficeController | `GET` | `/api/backoffice/notifications` | 3 |

## Notes

- All mocks are created with `persistent: true`.
- Mocks are auto-published to WireMock on creation; Phase 5 demonstrates manual re-publish.
- The cloned project and moved mock are deleted at the end (Phases 9-10) to demonstrate delete endpoints while keeping the core data intact.
- Running the script multiple times creates duplicate data — delete existing projects first if you need a clean slate.
