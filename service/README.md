# WireMate Service

A Spring Boot REST API for managing WireMock stub mappings, organized by projects. WireMate lets you create, clone, and move mock API definitions and automatically publishes them to a running WireMock instance.

## Tech Stack

- Java 25, Spring Boot 4.0.5
- PostgreSQL (data store)
- WireMock (mock server target)
- Maven (build tool)
- Docker (containerization)

## Prerequisites

- Java 25+
- PostgreSQL database
- WireMock instance running

## Configuration

Configured via environment variables (defaults in parentheses):

| Variable            | Default                   | Description                  |
|---------------------|---------------------------|------------------------------|
| `DB_HOST`           | `localhost`               | PostgreSQL host              |
| `DB_PORT`           | `5432`                    | PostgreSQL port              |
| `DB_NAME`           | `wiremate`                | Database name                |
| `DB_USER`           | `wiremate`                | Database username            |
| `DB_PASSWORD`       | `wiremate`                | Database password            |
| `SERVER_PORT`       | `8081`                    | Application port             |
| `WIREMOCK_BASE_URL` | `http://localhost:8080`   | WireMock admin API base URL  |

## Running

### Local

```bash
mvn spring-boot:run
```

### Docker

```bash
docker build -t wiremate-service .
docker run -p 8081:8181 wiremate-service
```

## API Endpoints

### Projects — `/api/projects`

| Method | Path                        | Description                                      |
|--------|-----------------------------|--------------------------------------------------|
| GET    | `/api/projects`             | List all projects                                |
| GET    | `/api/projects/{projectId}` | Get a project with its mocks                     |
| POST   | `/api/projects`             | Create a new project                             |
| POST   | `/api/projects/{projectId}/clone` | Clone a project and all its mocks          |
| DELETE | `/api/projects/{projectId}` | Delete a project and all its mocks               |

### Mocks — `/api/mocks`

| Method | Path                           | Description                                   |
|--------|--------------------------------|-----------------------------------------------|
| POST   | `/api/mocks`                   | Create a mock (auto-publishes to WireMock)    |
| GET    | `/api/mocks/{mockId}`          | Get a mock by ID                              |
| GET    | `/api/mocks/project/{projectId}` | Get all mocks in a project                 |
| PUT    | `/api/mocks/{mockId}`          | Update a mock                                 |
| POST   | `/api/mocks/{mockId}/clone`    | Clone a mock within the same project          |
| PUT    | `/api/mocks/{mockId}/move`     | Move a mock to a different project            |
| DELETE | `/api/mocks/{mockId}`          | Delete a mock                                 |
| POST   | `/api/mocks/{mockId}/publish`  | Publish a mock to WireMock                    |

### Backoffice — `/api/backoffice`

| Method | Path                            | Description              |
|--------|---------------------------------|--------------------------|
| GET    | `/api/backoffice/notifications` | List all notifications   |

## Background Tasks

A scheduled task runs every 10 seconds to detect sync drift between the database and WireMock. Any mismatches (mocks in the DB but missing from WireMock, or stubs in WireMock with no matching DB record) are saved as notifications, queryable via the backoffice endpoint.

## Project Structure

```
src/main/java/com/wire/mate/service/
├── config/          # Spring configuration (RestClient, scheduling)
├── controller/      # REST controllers (Project, Mock, Backoffice)
├── dto/             # Request/response records
├── entity/          # JPA entities (Project, Mock, Notification)
├── exception/       # Custom exceptions + global handler
├── gateway/         # WireMock HTTP client
├── repository/      # Spring Data JPA repositories
├── service/         # Business logic layer
└── task/            # Scheduled background tasks
```
