# core-service

Core Service of Relay: owns application user profiles and core messaging logic.
Java 21, Spring Boot, PostgreSQL 16, Liquibase.

Authentication is delegated to an external identity provider (ZITADEL, planned); this service stores no credentials.

## Build and test

Requires JDK 21 and Docker (integration tests start disposable PostgreSQL containers via Testcontainers).

```
./mvnw clean test
```

## Run locally

PostgreSQL comes from the `infrastructure` repository. Datasource settings are read from `DB_URL`, `DB_USER` and `DB_PASSWORD` (defaults target `localhost:5432/relay`).

## Database migrations

Liquibase changelogs live under `src/main/resources/db/changelog`. Rollback SQL files are kept next to the forward SQL files.

## Branches

`TASK_<feature>_<n>` -> `test` -> `main`, both through pull requests, enforced by the `branch-policy` workflow. Feature branches are created from `test`.
