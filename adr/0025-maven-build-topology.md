# 0025 — Maven Build Topology, BOM Pinning, Static Analysis

**Status:** Accepted
**Date:** 2026-04-17

---

## Context

Four Spring Boot services need: one source of truth for third-party versions (so one
service can't silently diverge on Micrometer / OTel), uniform static analysis and
formatting, a fast local inner loop, and a consistent unit-vs-integration test split.
The decision is how the root `pom.xml` is organised and what it enforces.

---

## Decision

**Multi-module aggregator.** Root `pom.xml` is `<packaging>pom</packaging>`, aggregates
the four modules, and inherits `spring-boot-starter-parent:3.5.6`. Service modules
inherit `gt-parent:1.0.0` (the root). `eureka-server` (and `api-gateway`) inherit
`spring-boot-starter-parent` directly — known, intentional gap (see Why/known-gap).

**BOM pinning, no inline versions.** `<dependencyManagement>` imports three BOMs:
`spring-cloud-dependencies:2025.0.0`, `micrometer-bom:1.16.0`,
`opentelemetry-bom:1.46.0`. Modules declare dependencies without `<version>`. A fleet
upgrade is one line in the root.

**Java 21**, no preview features (driven by ADR-0012, virtual threads).

**Compile-time static analysis** via `maven-compiler-plugin` in root pluginManagement:

```xml
<compilerArgs>
  <arg>-XDcompilePolicy=simple</arg>
  <arg>-Xplugin:ErrorProne -XepOpt:NullAway:AnnotatedPackages=com.galactic</arg>
</compilerArgs>
```

Error Prone catches common bug patterns; NullAway fails the build on potentially-null
dereferences. Both run inside `javac` — zero runtime cost.

**`fast` profile** skips Spotless + Error Prone for the inner loop
(`./mvnw test -T 1C -Pfast`). CI never uses `-Pfast`; `mvn verify` is authoritative.

**Test-type split via suffix + two plugins** (uniform across modules):

| Suffix                                  | Runner           | Phase  |
|---|---|---|
| `*Test.java`, `*Properties.java`        | surefire         | `test` |
| `*ContractTest`, `*RepositoryTest`, `*E2ETest` | failsafe   | `verify` |

Surefire explicitly excludes the integration suffixes so a misplaced `FooRepositoryTest`
doesn't run without Testcontainers.

Per-module opt-in for heavy plugins (PIT in starport-registry only; Spring Cloud Contract
in trade-route-planner only; Spotless still per-module — candidate to consolidate).

---

## Why

- **Single upgrade path.** BOM bump is one line; no copy-paste across four modules.
- **Compile-time NPE guard** with zero runtime overhead.
- **Predictable test runs.** `mvn test` = guaranteed unit; `mvn verify` = full suite.
  No "did I accidentally pull in a Testcontainers test?" confusion.
- **Fast inner loop.** `./mvnw test -T 1C -Pfast` runs all services' unit tests under a
  minute on a laptop.
- **Known gap:** `eureka-server` / `api-gateway` skip `gt-parent`, so Error Prone +
  NullAway don't run on them. Acceptable today (thin framework-config apps); revisit if
  real logic lands there.

---

## Alternatives

- **Separate repos per service** — cross-service refactor cost outweighs the isolation
  win at this scale.
- **Gradle** — team fluency is Maven; no measurable build-time win here.
- **Inline versions in each module** — guaranteed drift; the problem this ADR exists to
  solve.
- **Always-on Error Prone (no `fast` escape)** — 20–30 % compile overhead discourages
  TDD; CI catches anything `fast` skips.
- **`-Pci` enabling slow checks instead of `-Pfast` disabling them** — current inverse
  is simpler: CI is plain `mvn verify`, no profile juggling.

---

## References

- ADR-0006 — Testing Strategy (the suffix convention)
- ADR-0011 — Architecture Rules (ArchUnit, Spotless, PIT)
- ADR-0012 — Virtual Threads (why Java 21)
- ADR-0026 — Container Build Strategy
- Error Prone — https://errorprone.info/
- NullAway — https://github.com/uber/NullAway
