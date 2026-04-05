# WireMate UI

WireMate is a visual management tool for WireMock. It provides a web-based interface for creating, organizing, and publishing WireMock stub mappings without writing JSON by hand.

## Tech Stack

- Vue 3 (Composition API + `<script setup>`)
- TypeScript
- Vite
- Tailwind CSS v4
- Vue Router
- Heroicons
- mgv-backoffice component library (BaseButton, BaseSpinner, BaseToast, BaseAlert)

## Features

- **Visual Mock Builder** - Create WireMock request/response mappings through an intuitive form with support for methods, URLs, headers, JSON bodies, delays, scenarios, and more.
- **Project Management** - Organize mocks into projects with search and filtering.
- **CRUD Operations** - Add, edit, and delete mocks or projects.
- **Clone** - Duplicate any mock or project in one click.
- **Move Mocks** - Transfer mocks between projects.
- **One-Click Publish** - Push stubs directly to WireMock with instant feedback. All stubs are persisted in the database.
- **Sync Delta Notifications** - A background task detects differences between the database and WireMock. View deltas on the notifications page.
- **WireMock Stubs Viewer** - Browse all active stub mappings on the WireMock server with filtering.
- **Request Journal** - Inspect every request WireMock receives. Filter by method, URL, or status.
- **State Control** - Manage WireMock scenarios, set global response delays, monitor server health, and perform admin operations.
- **Dark & Light Theme** - Switch between themes with a single click.
- **Docker-Ready** - Spin up the entire stack with Docker Compose.

## Project Structure

```
src/
  components/       # Reusable components (AppSidebar, CreateMock, modals, etc.)
  composables/      # Vue composables (useTheme)
  config.ts         # App configuration (WireMock admin URL)
  router/           # Vue Router setup
  services/         # API service layers (project, mock, notification, stub, etc.)
  types/            # TypeScript interfaces
  views/            # Page-level components
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
```

### Development

```bash
npm run dev
```

### Build

```bash
npm run build
```

### Preview Production Build

```bash
npm run preview
```

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `VITE_WIREMOCK_ADMIN_URL` | WireMock admin API base URL | `http://localhost:8080/__admin` |

## API Endpoints

The UI communicates with two backends:

**WireMate Backend** (proxied via Vite to `/api`):
- `GET/POST /api/projects` - List and create projects
- `GET/DELETE /api/projects/:id` - Get or delete a project
- `POST /api/projects/:id/clone` - Clone a project
- `GET /api/mocks/project/:projectId` - List mocks for a project
- `DELETE /api/mocks/:id` - Delete a mock
- `POST /api/mocks/:id/clone` - Clone a mock
- `PUT /api/mocks/:id/move` - Move a mock to another project
- `GET /api/backoffice/notifications` - Get sync delta notifications

**WireMock Admin API** (configurable via `VITE_WIREMOCK_ADMIN_URL`):
- `/__admin/mappings` - Stub mappings
- `/__admin/requests` - Request journal
- `/__admin/scenarios` - Scenarios
- `/__admin/settings` - Global settings
- `/__admin/health` - Health check
