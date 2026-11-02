# ADR-0004: Flyway with plain SQL owns the database schema

- Status: Accepted
- Date: 2026-11-02

## Context

The schema relies on PostgreSQL-specific features: exclusion constraints with `btree_gist`, `tstzrange`, partial and expression indexes, composite foreign keys. We target exactly one database engine.

## Decision

- Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`) with plain SQL migrations in `src/main/resources/db/migration`.
- Hibernate never changes the schema: `spring.jpa.hibernate.ddl-auto=validate` everywhere.
- Versions are timestamps to avoid collisions between branches: `V2026_11_02_1200__short_description.sql`. Module-specific migrations may go in sub-folders (`db/migration/<module>/`).
- A migration is never edited after it is merged to `main`. Mistakes are fixed forward with a new migration.
- Zero-downtime changes use expand/contract (add nullable → backfill → switch code → constrain or drop).
- Reference data goes in versioned migrations. Local sample data, if any, goes in a separate location enabled only locally. Tests create their own data.
- Liquibase is not used (rejected: database-agnostic changelogs and rollback features bring no value with a single engine and a forward-fix policy).

## Consequences

- Migrations are readable SQL that maps 1:1 to what PostgreSQL runs.
- Every integration test applies all migrations to a fresh PostgreSQL 18 container, so a broken migration fails the build.
- Infrastructure tables (e.g. Modulith's `event_publication`) are also created by migrations, so `validate` passes.
