# CLAUDE.md — WireMate Service

# Project

A `Project` is a logical container (bucket) that groups related mocks together. It allows users to organize and quickly access similar mocks belonging to the same context or service.

# Mock

A `Mock` is the actual stub registered in WireMock. Each mock defines a request/response mapping and always belongs to a parent `Project`.

## Build & Run

- Build: `mvn clean package`
- Run: `mvn spring-boot:run`
- Test: `mvn test`
- Docker: `docker build -t wiremate-service .`
- App runs on port 8081 by default, WireMock expected at port 8080

## Architecture

- Spring Boot 4.0.5 with Java 25, PostgreSQL, WireMock
- Layered: Controller → Service → Repository, plus Gateway for WireMock HTTP calls
- DTOs are Java records with Jakarta validation annotations
- Entities extend `BaseEntity` (UUID id, timestamps) except `Notification` which uses `BIGSERIAL` (Long id)
- Entities have `toDto()` methods for conversion — no separate mapper classes
- Constructor injection everywhere, no `@Autowired`
- `@Transactional` on all service write methods; `@Transactional(readOnly = true)` on reads
- Exceptions are custom RuntimeExceptions handled by `GlobalExceptionHandler` using RFC 7807 ProblemDetail

## Key Conventions

- REST endpoints live under `/api/` prefix
- Request DTOs: `Create*Request`, `Update*Request`, `Clone*Request`, `Move*Request`
- Response DTOs: `*Response` records
- Repositories: extend `JpaRepository`, use derived query methods
- Unique constraints enforced at both DB and service level (check-then-save pattern)
- Mocks are auto-published to WireMock on create, clone, and move
- Scheduled task in `task/Task.java` syncs DB↔WireMock drift every 10s, saves results as `Notification` entities
- `Notification` entity uses `BIGSERIAL` primary key (not UUID) — does not extend `BaseEntity`

## Database

- PostgreSQL with