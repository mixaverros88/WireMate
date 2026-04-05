# WireMate UI

WireMate is a visual management tool for WireMock. It provides a web-based interface for creating, organizing, and publishing WireMock stub mappings without writing JSON by hand.

## Tech Stack

- Vue 3 (Composition API + `<script setup>`)
- TypeScript
- Vite
- Tailwind CSS v4
- Vue Router
- Heroicons
- Chart.js + vue-chartjs (analytics & charts)
- mgv-backoffice component library (BaseButton, BaseSpinner, BaseToast, BaseAlert)

## Features

- **Visual Mock Builder** - Create WireMock request/response mappings through an intuitive form with support for methods, URLs, headers, JSON bodies, delays, scenarios, and more.
- **Project Management** - Organize mocks into projects with search, filtering, and inline rename.
- **CRUD Operations** - Add, edit, and delete mocks or projects.
- **Clone** - Duplicate any mock or project in one click.
- **Move Mocks** - Transfer mocks between projects.
- **Postman Import** - Upload a Postman v2.x collection and turn each request into a mock in the selected project.
- **Related Mocks Panel** - While creating or editing a mock, the form surfaces other mocks in the same project that share the URL you are typing, so you can spot collisions before saving.
- **Deep-Link Shortcut** - The `/mock/:mockId` route resolves a bare mock id to its owning project and forwards to the canonical edit URL — useful for request-journal links and external tools.
- **One-Click Publish** - Push stubs directly to WireMock with instant feedback. All stubs are persisted in the database.
- **Sync Delta Notifications** - A background task detects differences between the database and WireMock. View deltas on the notifications page.
- **WireMock Stubs Viewer** - Browse all active stub mappings on the WireMock server with filtering.
- **Request Journal** - Inspect every request WireMock receives. Filter by method, URL, or status.
- **Near Misses** - Diagnose unmatched requests by viewing the closest matching stubs.
- **State Control** - Manage WireMock scenarios, set global response delays, monitor server health, and perform admin operations.
- **Dark & Light Theme** - Switch between themes with a single click.
- **Docker-Ready** - Spin up the entire stack with Docker Compose.

## Project Structure

```
src/
  App.vue            # Root - sidebar + router-view
  config.ts          # Centralized env config (API + WireMock admin URLs)
  components/        # Reusable components (AppSidebar, CreateMock, modals, etc.)
  composables/       # Vue composables (useTheme)
  router/            # Vue Router setup
  services/          # API service layers (project, mock, notification, stub, etc.)
  types/             # TypeScript interfaces matching backend DTOs
  views/             # Page-level components (one per route)
```

## Getting Started

### Prerequisites

- Node.js 18+
- npm

### Installation

```bash
npm install
```

### Configuration

Create a `.env` file in the project root (one is provided by default):

```
VITE_WIREMOCK_ADMIN_URL=http://localhost:8080/__admin
VITE_API_BASE_URL=http://localhost:8081/api
```

### Development

```bash
npm run dev
```

### Build

```bash
npm run build
```

This runs `vue-tsc -b` for type-checking, then produces a production bundle with Vite. To type-check only, run `npx vue-tsc -b`.

### Preview Production Build

```bash
npm run preview
```

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `VITE_WIREMOCK_ADMIN_URL` | WireMock admin API base URL | `http://localhost:8080/__admin` |
| `VITE_API_BASE_URL` | WireMate Spring Boot backend base URL | `http://localhost:8081/api` |

`VITE_` variables are read at build time, not runtime.

## Routes

| Path | Name | Component |
|---|---|---|
| `/` | - | Redirects to `/projects` |
| `/presentation` | presentation | LandingPage (no sidebar) |
| `/projects` | projects | ProjectsView |
| `/projects/:id` | project | ProjectDetailView |
| `/create-project` | create-project | CreateProjectView |
| `/mocks/create` | create-mock | CreateMockView |
| `/projects/:id/mocks/:mockId/edit` | edit-mock | EditMockView |
| `/mock/:mockId` | mock-redirect | MockRedirectView (deep-link shortcut) |
| `/logs` | logs | RequestJournalView |
| `/settings` | settings | SettingsView |
| `/stubs` | stubs | StubsView |
| `/stubs/:id` | stub-detail | StubDetailView |
| `/notifications` | notifications | NotificationsView |
| `/near-misses` | near-misses | NearMissesView |
| `/:pathMatch(.*)*` | not-found | NotFoundView |

## API Endpoints

The UI communicates with two backends:

**WireMate Backend** (configurable via `VITE_API_BASE_URL`):
- `GET/POST /api/projects` - List and create projects
- `GET/PUT/DELETE /api/projects/:id` - Get, rename, or delete a project
- `POST /api/projects/:id/clone` - Clone a project
- `GET /api/projects/:projectId/mocks` - List all mocks in a project (used for the related-mocks panel)
- `GET /api/mocks/project/:projectId` - List mocks for a project
- `GET /api/mocks/:id` - Fetch a single mock by id
- `DELETE /api/mocks/:id` - Delete a mock
- `POST /api/mocks/:id/clone` - Clone a mock
- `PUT /api/mocks/:id/move` - Move a mock to another project
- `GET /api/backoffice/notifications` - Get sync delta notifications

**WireMock Admin API** (configurable via `VITE_WIREMOCK_ADMIN_URL`):
- `/__admin/mappings` - Stub mappings
- `/__admin/requests` - Request journal
- `/__admin/requests/unmatched/near-misses` - Near misses for unmatched requests
- `/__admin/scenarios` - Scenarios
- `/__admin/settings` - Global settings
- `/__admin/health` - Health check

## Mock Form Section Order

General > Metadata > Request Matching > Response Definition > Cookies > Basic Authentication > Custom Matcher > Chunked Dribble Delay > Fault Simulation > Proxying > Webhooks / Post-Serve Actions
