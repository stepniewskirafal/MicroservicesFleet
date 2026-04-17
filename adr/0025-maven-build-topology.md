# 0025 — Maven Build Topology, BOM Pinning, and Static Analysis

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

A fleet of four Spring Boot services (starport-registry, trade-route-planner,
telemetry-pipeline, eureka-server) must:

1. Share a **single source of truth** for third-party versions — otherwise one service
   upgrades Micrometer and silently diverges from another, and an OTel breaking change
   lands in half the fleet.
2. Enforce the same **static analysis** and **formatting rules** everywhere so a PR does
   not turn into a style debate per-service.
3. Offer a **fast local-iteration path** — a developer running `./mvnw test` on a single
   module should not wait for a full suite of heavyweight checks.
4. Separate **unit tests** (surefire) from **integration / contract / E2E** tests
   (failsafe) consistently — a one-off convention in one module creates blind spots
   across the fleet.

The decision is how the root `pom.xml` is organised and what it enforces on its modules.

---

## Decision

### Multi-module Maven layout, one aggregator POM

Root `pom.xml` is `<packaging>pom</packaging>` and aggregates four modules. Each module's
`pom.xml` declares `<parent>` pointing at the root. The root is the **single place** where
BOMs, common plugins, and static-analysis configuration live.

```xml
<!-- pom.xml:9,20-25 -->
<packaging>pom</packaging>
<modules>
    <module>eureka-server</module>
    <module>starport-registry</module>
    <module>trade-route-planner</module>
    <module>telemetry-pipeline</module>
</modules>
```

The root itself inherits from `spring-boot-starter-parent:3.5.6` — a standard Spring Boot
pattern. Service POMs inherit from the root (`gt-parent:1.0.0`). The one **deliberate
exception** is `eureka-server`, which inherits `spring-boot-starter-parent` directly and
skips the root. That is documented in the Consequences section; it is a known
inconsistency, not an accident.

### Pin third-party versions through BOMs, not direct versions

`<dependencyManagement>` imports three BOMs so modules never write a `<version>` for
managed artifacts:

```xml
<!-- pom.xml:36-57 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2025.0.0</version>
            <type>pom</type><scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-bom</artifactId>
            <version>1.16.0</version>
            <type>pom</type><scope>import</scope>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-bom</artifactId>
            <version>1.46.0</version>
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Module POMs declare dependencies **without** versions. Upgrading Micrometer across the
fleet is a one-line bump in the root; rollback is a git revert.

### Java 21, one language level

```xml
<!-- pom.xml:28 -->
<java.version>21</java.version>
```

Driven by ADR-0012 (virtual threads). No `--enable-preview` — we rely on GA features only.

### Compile-time static analysis: Error Prone + NullAway

`maven-compiler-plugin` is configured in root `pluginManagement` to attach:

- **Error Prone** — catches common bug patterns at compile time.
- **NullAway** — fails the build on dereferences of potentially-null values, seeded from
  `@Nullable` annotations.

```xml
<!-- pom.xml:75-112 -->
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <compilerArgs>
      <arg>-XDcompilePolicy=simple</arg>
      <arg>-Xplugin:ErrorProne -XepOpt:NullAway:AnnotatedPackages=com.galactic</arg>
    </compilerArgs>
    <annotationProcessorPaths>
      <path>... errorprone_core ...</path>
      <path>... nullaway ...</path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

This runs as part of `javac` — **zero runtime overhead**, fails PRs that introduce
NPE-prone code paths.

### `fast` profile — skip slow checks during inner loop

```xml
<!-- pom.xml:121-148 -->
<profile>
  <id>fast</id>
  <build>
    <plugins>
      <plugin>
        <artifactId>spotless-maven-plugin</artifactId>
        <configuration><skip>true</skip></configuration>
      </plugin>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <compilerArgs combine.self="override"/>  <!-- disable Error Prone / NullAway -->
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

Usage: `./mvnw test -T 1C -Pfast` — run all unit tests in parallel across cores, skip
Spotless and Error Prone. **CI never uses `-Pfast`**; the full suite is authoritative.

### Test-type separation via naming convention + two plugins

Enforced per-module, with one convention across the fleet:

| Suffix                | Runner          | Included in  |
|---|---|---|
| `*Test.java`, `*Properties.java` | `maven-surefire-plugin`  | `mvn test`  |
| `*ContractTest.java`             | `maven-failsafe-plugin`  | `mvn verify` |
| `*RepositoryTest.java`           | `maven-failsafe-plugin`  | `mvn verify` |
| `*E2ETest.java`                  | `maven-failsafe-plugin`  | `mvn verify` |

Surefire is configured to **exclude** integration-suite suffixes explicitly so a
misplaced `SomeRepositoryTest` in a unit-style package does not run under surefire with
wrong assumptions (no Testcontainers, no Flyway).

```xml
<!-- starport-registry/pom.xml:252-260 -->
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <includes>
      <include>**/*Test.java</include>
      <include>**/*Properties.java</include>
    </includes>
    <excludes>
      <exclude>**/*E2ETest.java</exclude>
      <exclude>**/*RepositoryTest.java</exclude>
      <exclude>**/*ContractTest.java</exclude>
    </excludes>
    <parallel>classes</parallel>
    <threadCount>2</threadCount>
    <perCoreThreadCount>true</perCoreThreadCount>
  </configuration>
</plugin>
```

### Per-module opt-in for heavier plugins

Three plugins are **not** in root `pluginManagement` — each module opts in:

- **PIT mutation testing** — heavy, explicit invocation (`mvn pitest:mutationCoverage`).
  Configured only in starport-registry (ADR-0011 §PIT).
- **Spotless** — configured in each service POM (not in root). Format rules are identical;
  the split is historical and a candidate for future consolidation.
- **Spring Cloud Contract** — only in trade-route-planner (`testMode=MOCKMVC`,
  ADR-0006 contract tests).

---

## How the codebase enforces this

1. **Root aggregator** — `pom.xml` owns `<modules>`, Java version, BOM pins, static
   analysis, `fast` profile.
2. **Service POMs** never declare `<version>` for: Spring Cloud Stream, Resilience4j,
   Micrometer, OpenTelemetry, Spring Cloud LoadBalancer — all resolved from BOMs.
3. **Wrapper** — `./mvnw` (Maven Wrapper) at the root commits a pinned Maven version
   (`.mvn/`) — developers do not need a local Maven install.
4. **CI parity** — CI runs `./mvnw verify` (no profile); developers run the same command
   locally when they want the authoritative answer. `-Pfast` is a convenience, not a
   shortcut.

---

## Consequences

### Benefits

- **Single upgrade path.** Bumping Spring Cloud from 2025.0.0 to 2025.0.1 is a one-line
  change to the root POM. No copy-paste across four modules.
- **Compile-time safety.** Error Prone + NullAway catch NPE paths and common Java
  pitfalls before a PR is even reviewed. Runtime overhead: zero.
- **Predictable test runs.** The suffix convention means `mvn test` is a guaranteed unit
  run, `mvn verify` is the authoritative full suite. No "did I accidentally include a
  Testcontainers test in the fast cycle" confusion.
- **Fast local inner loop.** `./mvnw test -T 1C -Pfast` typically runs all services'
  unit tests in under a minute on a modern laptop.
- **Inheriting from Spring Boot parent.** Gets free dependency management for hundreds
  of Spring-ecosystem artifacts and plugin versions without extra ceremony.

### Trade-offs and known inconsistencies

- **`eureka-server` and `api-gateway` break the pattern.** Their `<parent>` is
  `spring-boot-starter-parent` directly, not `gt-parent`. Consequence: Error Prone +
  NullAway are *not* enforced on either module. Rationale when made: both are thin
  framework-configuration apps (`@EnableEurekaServer` / `@EnableDiscoveryClient` +
  YAML routes) with no business logic worth static-analysing; the pragmatic choice
  was to keep their POMs minimal. Cost: if someone adds real business logic to
  either module, they bypass the guardrails. Flagged as a known gap — consolidation
  into `gt-parent` is a simple follow-up.
- **Spotless duplicated across modules.** Not in root `pluginManagement` today. Four
  POMs can drift silently. Candidate for consolidation.
- **`-Pfast` tempts developers to skip quality locally.** If a dev never runs the full
  pipeline locally, PR failures become the first feedback — slow cycle. Mitigation: CI
  runs full pipeline; the pre-push hook is a future improvement.
- **BOM pins are a release bottleneck.** Upgrading a BOM touches every service that
  transitively depends on the BOM's artifacts. Regressions in a BOM affect the whole
  fleet. Mitigation: staged rollout (bump BOM in one service branch → run full suite →
  merge → bump fleet-wide).
- **NullAway seed annotations not yet comprehensive.** Without `@Nullable` markers in
  third-party code, NullAway sees a lot of "unknown" types. This reduces false positives
  but also false negatives. A proper `jspecify`-based annotation pass would improve
  coverage.
- **Per-module surefire/failsafe configuration duplication.** Same plugin blocks appear
  in three service POMs. Extracting to root `pluginManagement` is a cleanup candidate.

---

## Alternatives Considered

1. **Separate repos per service.** Rejected — would require separate BOM-pin PRs in each
   repo, harder cross-service refactors, higher CI cost. For a three-service fleet
   developed by one team, a monorepo pays off.
2. **Gradle instead of Maven.** Rejected — team fluency is Maven; Spring Boot's Maven
   integration is first-class; no measurable win in build time for this size of project.
3. **Direct version numbers in each module POM (no BOMs).** Rejected — the whole point
   of this ADR; drift is inevitable without central pinning.
4. **Error Prone / NullAway on by default without a `fast` escape hatch.** Considered.
   Rejected because the 20-30 % compile-time overhead of Error Prone is noticeable on
   every `mvn test` iteration and discourages TDD.
5. **Separate CI-only profile** (`-Pci`) to enable the slow checks. Considered; current
   approach (default = full, `-Pfast` = skip) is the inverse. Current approach is
   simpler: CI is just `mvn verify`, no profile juggling.

---

## References

- ADR-0006 — Testing Strategy (the `*Test` / `*ContractTest` / `*E2ETest` convention)
- ADR-0011 — Architecture Rules & Guardrails (ArchUnit + Spotless + PIT)
- ADR-0012 — Virtual Threads (Java 21 rationale)
- ADR-0026 — Container Build Strategy (how Maven builds become Docker images)
- Error Prone — https://errorprone.info/
- NullAway — https://github.com/uber/NullAway
- Maven BOM pattern —
  https://docs.spring.io/spring-boot/docs/current/reference/html/using.html#using.build-systems.maven
