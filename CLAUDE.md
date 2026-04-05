# WireMate

## Project Overview

WireMate is a backoffice system for WireMock. It lets users create, persist, and publish WireMock stubs to a running WireMock server through a guided web UI. Users can organise mocks into projects, clone and move them between projects, and monitor activity via an in-app notification system.

WireMock API documentation: `wiremock-api-documentation.html`
WireMock stub properties reference: `stub-properties-documentation.html`

## Core Workflow

1. User opens the backoffice UI
2. User creates or selects a project
3. User constructs a WireMock mapping (request/response JSON) via the UI
4. On save, the mapping is persisted to the database and published to the WireMock server
5. User can clone, move, or re-publish mocks at any time

## Tech Stack

- **Backend** — Java 25, Spring Boot 4.0.5, Spring Data JPA, PostgreSQL (JSONB), Maven
- **Frontend** — Vue 3.5, TypeScript 5.9, Vite 8, Tailwind CSS 4, mgv-backoffice component library
- **Infrastructure** — Docker & Docker Compose, Nginx (production UI), WireMock 3.6.0

## Infrastructure

- PostgreSQL 17 (Alpine) — port 5432, user `wiremate`, db `wiremate`
- WireMock 3.6.0 — port 8080
- Backend API — port 8081 (code in `/service`)
- Frontend (dev) — port 5173 (code in `/UI`)
- Frontend (Docker/production) — port 3000 via Nginx

## WireMock Mapping Example

The UI builds JSON payloads like the following and POSTs them to WireMock's admin endpoint:

```json
{
  "request": {
    "method": "POST",
    "url": "/interaction/v1/events"
  },
  "response": {
    "status": 201,
    "jsonBody": {},
    "headers": {
      "Content-Type": "application/json"
    },
    "delayDistribution": {
      "type": "uniform",
      "lower": 60,
      "upper": 80
    }
  },
  "persistent": true
}
```

## API Endpoints

### Projects — `/api/projects`

- `GET /` — list all projects
- `GET /:projectId` — get a project with its mocks
- `POST /` — create a project
- `POST /:projectId/clone` — clone a project (with its mocks)
- `DELETE /:projectId` — delete a project

### Mocks — `/api/mocks`

- `POST /` — create a mock (auto-publishes to WireMock)
- `GET /:mockId` — get mock details
- `PUT /:mockId` — update a mock
- `GET /project/:projectId` — list mocks for a project
- `POST /:mockId/clone` — clone a mock
- `PUT /:mockId/move` — move a mock to another project
- `DELETE /:mockId` — delete a mock
- `POST /:mockId/publish` — manually publish a mock to WireMock

### Backoffice — `/api/backoffice`

- `GET /notifications` — retrieve system notifications

## Project Structure

```
wiremate/
├── service/                    # Spring Boot backend
│   └── src/main/java/com/wire/mate/service/
│       ├── config/             # REST client configuration
│       ├── controller/         # REST controllers (Project, Mock, Backoffice)
│       ├── dto/                # Request/response records
│       ├── entity/             # JPA entities (Project, Mock, Notification)
│       ├── exception/          # Global error handling & custom exceptions
│       ├── gateway/            # WireMock REST client
│       ├── repository/         # Spring Data repositories
│       ├── service/            # Business logic
│       └── task/               # Scheduled tasks
├── UI/                         # Vue 3 frontend
│   └── src/
│       ├── components/         # Reusable components (sidebar, modals, etc.)
│       ├── composables/        # Vue composables (theme)
│       ├── services/           # API clients (mock, project, notification, request journal, state control, stub)
│       ├── types/              # TypeScript type definitions
│       ├── views/              # Page components
│       └── router/             # Vue Router config
├── docker-compose.yml
├── wiremock-api-documentation.html
└── stub-properties-documentation.html
```

## Configuration

Environment variables (with defaults from `application.properties`):

| Variable            | Default                 | Description         |
|---------------------|-------------------------|---------------------|
| `DB_HOST`           | `localhost`             | PostgreSQL host     |
| `DB_PORT`           | `5432`                  | PostgreSQL port     |
| `DB_NAME`           | `wiremate`              | Database name       |
| `DB_USER`           | `wiremate`              | Database user       |
| `DB_PASSWORD`       | `wiremate`              | Database password   |
| `SERVER_PORT`       | `8081`                  | Backend API port    |
| `WIREMOCK_BASE_URL` | `http://localhost:8080` | WireMock server URL |
