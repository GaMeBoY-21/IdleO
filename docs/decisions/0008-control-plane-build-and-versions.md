# 0008. Control plane build, versions and database layout

- **Status:** Accepted
- **Date:** 2026-10-04

Sources:
- docs/plan/execution-plan.md line 156; docs/plan/technical-design.md lines 24, 80, 112, 531–545
- ADR 0001, ADR 0003 (open question on Maven or Gradle), ADR 0006
- docs/prompts/phase1-step3-control-plane-foundation.md

## Context

The plan named Java 21 and Spring Boot 3 for the control plane (execution plan line 156). Before the first line of control-plane code, three things were still open:
- which versions to start on
- which build tool to use (ADR 0003 left this open)
- how the database is laid out between modules

A production service built to ADR 0006's bar can't start on a line that's already out of support.

## Decision

1. **Java 25**, the current LTS.
   - The control plane runs on virtual threads (execution plan line 156).
   - From Java 24, a virtual thread that blocks inside `synchronized` no longer pins its carrier thread: [JEP 491](https://openjdk.org/jeps/491), "Synchronize Virtual Threads without Pinning", was delivered in release 24. On Java 21, every library that synchronizes around I/O would quietly remove the benefit.
   - Java 25 also has the longer support window.
2. **Spring Boot 4.1.1 and Spring Modulith 2.1.1**, the newest GA releases.
   - Spring Boot 3.5.x reached the end of open-source support on 2026-06-30. 4.1.x is supported in open source until 2027-07-31 ([spring.io support dates](https://spring.io/projects/spring-boot#support)).
   - Spring Initializr pairs Modulith 2.1.1 with Spring Boot ≥ 4.1.0-M1 and < 4.2.0-M1 ([spring.io/projects/spring-modulith](https://spring.io/projects/spring-modulith)).
   - Flyway, Testcontainers, the PostgreSQL driver, Hibernate and Jackson come from the Spring Boot 4.1.1 dependency management, not from our own pins.
3. **Maven, with the Maven wrapper** pinning Maven 3.9.16. This answers ADR 0003's open question.
   - Maven is declarative, and Nikki already works with it.
   - The wrapper pins the Maven version for every machine and CI.
   - The protobuf plugin used in the next step downloads a pinned `protoc` itself, so the Anaconda `protoc` on Nikki's PATH can never leak into a build.
4. **PostgreSQL 18**, everywhere.
   - It's the newest major that DigitalOcean Managed PostgreSQL offers today: majors 14–18 are supported, 18 is the newest, and it's supported until 2030-11-08 ([DigitalOcean docs](https://docs.digitalocean.com/products/databases/postgresql/how-to/create/)).
   - Tests and local runs use the same major, pinned by tag and digest: `postgres:18.6@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722`.
5. **One database schema per module, created by Flyway.**
   - Each of the eleven modules (technical design lines 535–545) gets its own schema, created in `V1`. The rule "none reads another module's tables" (line 531) then shows up in every query, and a module can later be extracted without untangling its tables.
   - Flyway keeps its history table in its own `flyway` schema.
   - Flyway owns all DDL; nothing else creates or changes tables. JPA isn't on the classpath yet, because no module has an entity. When it arrives, `spring.jpa.hibernate.ddl-auto` is `validate`: Hibernate never changes the schema, and the app refuses to start if an entity and its migration disagree. `none` was rejected, because it would let that drift reach production silently.
6. **Tables arrive with the first code that uses them.**
   - The outbox (technical design line 112) comes with the first module that publishes to agents or the payment gateway.
   - Spring Modulith's event publication table comes with the first module that publishes an application event.
   - Valkey comes with the agent gateway's presence (line 537).

   None of them is added early.
7. **"IdleO" is the internal codename**, the repository name. The base package is `com.idleo.controlplane`. The product brand isn't decided and can still be anything; nothing user-facing uses the codename.

## Alternatives considered

- **Stay on Java 21:** rejected, because of JEP 491 and the shorter support window.
- **Spring Boot 3.5:** rejected, because its open-source support has ended.
- **Gradle:** rejected, for the reasons in item 3.
- **One shared `public` schema with a table-name prefix per module:** rejected. Nothing would stop a query from reading another module's tables.
- **Hibernate `ddl-auto: update` for speed:** rejected. Flyway owns DDL, with expand, then migrate, then contract.

## Consequences

- Every machine that builds the control plane needs Java 25. On the Mac it comes from `brew install openjdk@25`, and CI uses Temurin 25.
- The PostgreSQL major version lives in three places (`compose.yaml`, the Testcontainers configuration and production). A test keeps the first two identical, and production uses DigitalOcean's PostgreSQL 18.
- A module that needs a table adds a migration in its own schema and never touches another's.
