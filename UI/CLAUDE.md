# CLAUDE.md - WireMate UI

## Project Overview

WireMate UI is a Vue 3 + TypeScript frontend for managing WireMock stub mappings. It connects to a Spring Boot backend (`/api`) and directly to the WireMock admin API.

## Build & Run

```bash
npm install          # Install dependencies
npm run dev          # Start dev server (Vite)
npm run build        # Type-check + production build
npx vue-tsc -b       # Type-check only (no build)
```

## Architecture

- **Framework:** Vue 3 Composition API with `<script setup>` and TypeScript
- **Build:** Vite with `VITE_` environment variables (build-time only)
- **Styling:** Tailwind CSS v4, dark mode via `useTheme()` composable (`isDark` ref)
- **Components:** mgv-backoffice library (BaseButton, BaseSpinner, BaseToast, BaseAlert)
- **Icons:** @heroicons/vue (v24/outline)
- **Routing:** Vue Router, all routes in `src/router/index.ts`
- **HTTP:** Fetch API (no axios)

## Key Patterns

- **Services** are in `src/services/`, each with a `BASE_URL` and async fetch functions
- **Types** are in `src/types/`, matching backend DTOs
- **Dark mode** uses `useTheme()` composable, classes applied conditionally via `:class="isDark ? ... : ..."`
- **Modals** follow a reusable pattern: ConfirmModal, CloneModal, MoveModal with `defineExpose({ submitting })` for parent-controlled loading state
- **Config** is centralized in `src/config.ts`, reading from `VITE_WIREMOCK_ADMIN_URL` env var with a fallback default
- **Sidebar** component (`AppSidebar.vue`) is responsive: fixed sidebar on desktop, hamburger menu on mobile

## File Layout

```
src/
  App.vue                          # Root - sidebar + router-view
  config.ts                        # Centralized env config
  components/
    AppSidebar.vue                 # Main navigation sidebar
    CreateMock.vue                 # Large mock creation form
    CloneModal.vue                 # Reusable clone modal
    MoveModal.vue                  # Move mock between projects modal
    ConfirmModal.vue               # Generic confirmation modal
    LandingPage.vue                # Presentation / landing page
    WireMateLogo.vue               # SVG logo component
    RequestDetailModal.vue         # Request journal detail modal
  views/
    ProjectsView.vue               # Projects list
    ProjectDetailView.vue          # Single project + mocks list
    CreateProjectView.vue          # Create project form
    CreateMockView.vue             # Standalone mock creation page
    EditMockView.vue               # Edit existing mock
    NotificationsView.vue          # Sync delta notifications list
    RequestJournalView.vue         # WireMock request journal (logs)
    SettingsView.vue               # WireMock settings & scenarios
    NearMissesView.vue             # Near miss request diagnostics
    StubsView.vue                  # WireMock stubs viewer
    StubDetailView.vue             # Single stub detail
    NotFoundView.vue               # 404 page
  services/
    projectService.ts              # CRUD + clone for projects
    mockService.ts                 # CRUD + clone + move for mocks
    notificationService.ts         # Fetch notifications
    stubService.ts                 # WireMock stubs API
    requestJournalService.ts       # WireMock request journal API
    settingsService.ts             # WireMock settings & admin operations
    api.ts                         # Generic request helper
  types/
    project.ts, mock.ts, notification.ts, requestJournal.ts, settings.ts
  composables/
    useTheme.ts                    # Dark/light theme toggle
```

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
| `/logs` | logs | RequestJournalView |
| `/settings` | settings | SettingsView |
| `/stubs` | stubs | StubsView |
| `/stubs/:id` | stub-detail | StubDetailView |
| `/notifications` | notifications | NotificationsView |
| `/near-misses` | near-misses | NearMissesView |
| `/:pathMatch(.*)*` | not-found | NotFoundView |

## Mock Form Section Order

General > Metadata > Request Matching > Response Definition > Scenario > Response Transformers > Cookies > Basic Authentication > Custom Matcher > Chunked Dribble Delay > Fault Simulation > Proxying > Webhooks / Post-Serve Actions
