# CLAUDE.md — WireMate Service

## Project Overview
This is a backoffice service that expose APIs for the WireMate UI
- A `Project` is a logical container (bucket) that groups related mocks together. It allows users to organize and quickly access similar mocks belonging to the same context or service.
- A `Mock` is the actual stub registered in WireMock. Each mock defines a request/response mapping and always belongs to a parent `Project`.

## Tech Stack
- JAVA 25
- Spring Boot 4.0.5
- Postgres

## Architecture

- Layered: Controller → Service → Repository, plus Gateway for WireMock HTTP calls
- DTOs are Java records with Jakarta validation annotations
- Entities extend `BaseEntity` (UUID id, timestamps) except `Notification` which uses `BIGSERIAL` (Long id)
- Entities have `toDto()` methods for conversion — no separate mapper classes
- Constructor injection everywhere, no `@Autowired`
- `@Transactional` on all service write methods; `@Transactional(readOnly = true)` on reads
- Exceptions are custom RuntimeExceptions handled by `GlobalExceptionHandler` using RFC 7807 ProblemDetail

## Folder Structure

Source root: `src/main/java/com/wire/mate/service/`

- `config/` — Spring `@Configuration` classes and infrastructure beans. Holds `MainConfig` (registers the WireMock `RestClient`, enables scheduling, enables `@ConfigurationProperties`), `WireMateProperties` (typed binding for `app.*` properties such as the WireMock base URL and timeouts), `CorsConfig` (CORS rules for the WireMate UI), and `JsonHelper` (thin Jackson wrapper used across the codebase).

- `controller/` — REST entry points under the `/api/` prefix. `ProjectController` and `MockController` expose CRUD/clone/move endpoints for the two core aggregates; `BackofficeController` exposes paginated reads of `Notification`s produced by the cross-check task. Controllers stay thin: they delegate to services and rely on `@ResponseStatus` (no `ResponseEntity`).

- `dto/` — Inbound and outbound payloads as Java `record`s with Jakarta validation annotations. Organized by domain:
  - `dto/project/` — `CreateProjectRequest`, `UpdateProjectRequest`, `CloneProjectRequest`, `ProjectResponse`, `ProjectWithMocksResponse`.
  - `dto/mock/` — `CreateMockRequest`, `UpdateMockRequest`, `CloneMockRequest`, `MoveMockRequest`, `MockResponse`.
  - `dto/notification/` — `NotificationResponse` for the backoffice feed.

- `entity/` — JPA entities. `BaseEntity` defines the shared UUID id and timestamp columns; `Project` and `Mock` extend it. `Notification` is the exception — it uses a `BIGSERIAL` (`Long`) primary key and does not extend `BaseEntity`. Entities own their `toDto()` conversion methods (no separate mapper layer).

- `exception/` — Domain exceptions and the centralized error handler. Custom `RuntimeException`s (`NotFoundExc`, `BadRequestExc`, `InternalServerErrorExc`, `WireMockNotFound`, `WireMockPublishExc`) are thrown by services/gateways and translated by `GlobalExceptionHandler` into RFC 7807 `ProblemDetail` responses.

- `gateway/` — Outbound HTTP integration with WireMock. `WireMockGateway` wraps the `RestClient` and exposes typed methods for creating, updating, deleting, fetching, and bulk-removing stubs.
  - `gateway/dto/` — DTOs that mirror the WireMock admin API wire format: `WiremockRequest`, `MappingDto`, `RequestDto`, `ResponseDto`, `MatchDto`, `MetaDto`, `WireMockResponseDto`. Kept separate from `dto/` so the WireMock format never leaks into the public API.

- `logic/` — Orchestration helpers that sit between services and the gateway. `WireMockService` translates domain operations (publish/update/delete a stub, delete all stubs of a project by metadata) into `WireMockGateway` calls and is invoked by the domain services in `service/`.

- `repository/` — Spring Data JPA repositories (`ProjectRepository`, `MockRepository`, `NotificationRepository`). Extend `JpaRepository` and use derived query methods.

- `service/` — Transactional domain services. `ProjectService` and `MockService` enforce uniqueness (check-then-save), persist via repositories, and trigger WireMock publication through `logic/WireMockService` on create/clone/move. `NotificationService` exposes paginated reads for the backoffice. Write methods are `@Transactional`; reads are `@Transactional(readOnly = true)`.

- `task/` — Scheduled background jobs. `CrossCheckTask` runs on a fixed interval, diffs the mocks in Postgres against the stubs registered in WireMock, and persists drift findings as `Notification` rows. Gated by the `app.cross-check.enabled` property (default on).

- `util/` — Stateless helpers with no Spring dependencies. Currently `Util` (build WireMock metadata maps, rewrite the first path segment of a URL).

- `ServiceApplication.java` — Spring Boot entry point (`@SpringBootApplication`).

Other top-level locations:

- `src/main/resources/` — `application.properties` and `db/migration/` (Flyway SQL migrations, e.g. `V1__create_initial_schema.sql`).
- `src/test/` — Spring Boot test sources (currently `ServiceApplicationTests`) and `application-test.properties`.
- `claude/` — Project-specific Claude assets: coding `rules/`, helper `scripts/`, custom `agents/`, and `settings.json`.
- `.run/` — IntelliJ run configurations (Build Image, Run Local, Set Up - Mac/Windows).
- `target/` — Maven build output (ignored).
- `Dockerfile`, `pom.xml`, `README.md` — build, dependency, and project documentation at the repository root.

## Key Conventions

- REST endpoints live under `/api/` prefix
- Request DTOs: `Create*Request`, `Update*Request`, `Clone*Request`, `Move*Request`
- Response DTOs: `*Response` records
- Repositories: extend `JpaRepository`, use derived query methods
- Unique constraints enforced at both DB and service level (check-then-save pattern)
- Mocks are auto-published to WireMock on create, clone, and move
- Scheduled task in `task/CrossCheckTask.java` syncs DB↔WireMock drift every 10s, saves results as `Notification` entities
- `Notification` entity uses `BIGSERIAL` primary key (not UUID) — does not extend `BaseEntity`

## Testing and Quality
Before considering a task complete:
- mvn clean package

Testing rules:
- add unit tests for reusable logic

## Safety Rules
- Do not rename public API routes unless explicitly requested
- Do not change database schema without calling it out clearly
- Do not modify auth flows unless the task requires it
- Flag major architectural changes before implementing them

## Commands

- Build: `mvn clean package`
- Run: `mvn spring-boot:run`
- Test: `mvn test`
- Docker: `docker build -t wiremate-service .`
- App runs on port 8081 by default, WireMock expected at port 8080
