# Changelog

All notable changes to WireMate will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-05-27

First release of the WireMate backoffice for WireMock.

### Added

- **Backend** (Spring Boot 4.0.6 on Java 25)
  - Project API (`/api/projects`) — list, get, create, clone, and delete projects.
  - Mock API (`/api/mocks`) — create, read, update, delete, clone, move between projects, and manually publish mocks.
  - Backoffice API (`/api/backoffice/notifications`) — paginated retrieval of system notifications.
  - Auto-publish to WireMock on mock create, clone, and move via the `WireMockGateway` REST client.
  - JPA entities for `Project`, `Mock`, and `Notification`, persisted to PostgreSQL with JSONB mapping payloads. `Project` and `Mock` extend `BaseEntity` (UUID id + timestamps); `Notification` uses `BIGSERIAL`.
  - Flyway migrations for the initial schema (`V1__create_initial_schema.sql`).
  - `CrossCheckTask` — scheduled job that diffs Postgres mocks against the live WireMock stubs every 10s and persists drift findings as notifications. Gated by `app.cross-check.enabled`.
  - Global error handling with RFC 7807 `ProblemDetail` responses and custom exceptions (`NotFoundExc`, `BadRequestExc`, `InternalServerErrorExc`, `WireMockNotFound`, `WireMockPublishExc`).
  - CORS configuration for the WireMate UI.
  - Typed configuration via `WireMateProperties` (`app.*`) and environment variables (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `WIREMOCK_BASE_URL`).

- **Frontend** (Vue 3.5 + TypeScript 6.0 + Vite 8 + Tailwind CSS 4)
  - Visual mock builder with sections for General, Metadata, Request Matching, Response Definition, Response Transformers, Cookies, Basic Authentication, Custom Matcher, Chunked Dribble Delay, Fault Simulation, Proxying, and Webhooks / Post-Serve Actions.
  - Project workspace with create, clone, delete, and search.
  - Mock management with clone and move-between-projects flows.
  - WireMock Stubs Viewer — browse live stubs with method/URL filters and a per-stub detail page.
  - Request Journal (Logs) — matched and unmatched requests with charts for method distribution, status codes, and request volume over time.
  - Near Misses diagnostics view.
  - Settings / State Control page — global response delays, server health, and admin operations.
  - Notifications view surfacing cross-check drift findings from the backend.
  - Postman collection import and JSON export.
  - Dark and light theme via the `useTheme()` composable.
  - Landing / presentation page.
  - API client services for projects, mocks, notifications, stubs, request journal, and settings, plus a generic `api.ts` fetch helper.
  - Reusable modal pattern (`ConfirmModal`, `CloneModal`, `MoveModal`) and a responsive `AppSidebar`.

- **Infrastructure**
  - Docker Compose stack: PostgreSQL 17 (Alpine), WireMock 3.6.0, backend, and frontend.
  - Production frontend served via Nginx on port 3000.
  - Development ports: UI 5173, backend 8081, WireMock 8080, PostgreSQL 5432.
  - GitHub Actions workflow for Docker image publishing (`.github/workflows/docker-publish.yml`).

- **Documentation**
  - Project `README.md` with overview, screenshots, getting-started, and pending-features section.
  - `CLAUDE.md` files at the repo, service, and UI roots documenting conventions.
  - Bundled `wiremock-api-documentation.html` and `stub-properties-documentation.html` references.

[0.1.0]: https://github.com/mixaverros88/WireMate/releases/tag/v0.1.0
