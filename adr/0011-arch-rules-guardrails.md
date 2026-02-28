# 0011 — Architecture Rules & Guardrails

**Status:** Accepted — 2026-02-28
**Deciders:** Team
**Technical Story:** ADR-0001 assigns a distinct architecture style to each service. Without automated enforcement, architectural drift is a matter of time: controllers call repositories directly, domain objects import Spring annotations, or filter components accumulate state. Guardrails must make violations visible before they reach main.

---

## Context and Problem Statement

Three services use three different architecture styles (Layered, Hexagonal, Pipes & Filters). Each style defines a set of allowed and forbidden dependency directions and structural constraints. Code reviews catch some violations, but they are inconsistent and slow. How can the architecture decisions in ADR-0001 be translated into automated, always-on quality gates?

---

## Decision Drivers

* Architectural violations (e.g., a REST controller calling a JPA repository directly) must fail the build, not just trigger a code review comment.
* Code formatting must be consistent across all contributors without manual enforcement.
* Unit test quality must be measurable: line coverage alone does not detect tests that run but do not assert anything meaningful.
* Guardrails must run in the same Maven lifecycle that developers use locally (`mvn test`, `mvn verify`) — no separate CI-only toolchain.
* The tools must integrate with the existing Spring Boot / JUnit 5 / Maven stack without additional runtime agents or external services.

---

## Considered Options

1. **ArchUnit (architecture rules) + Spotless (code style) + PIT (mutation testing)** — all Maven plugins, all compile-time or test-time, zero runtime overhead.
2. **SonarQube with architecture plugins** — centralised quality gate with dashboards; requires a running SonarQube server.
3. **Custom annotation processors or PMD/Checkstyle rules** — lower-level static analysis; architecture-awareness requires significant custom rule authoring.
4. **Manual code review only** — no automation; relies entirely on reviewer diligence and shared mental models.

---

## Decision Outcome

**Chosen option: ArchUnit + Spotless + PIT.**

This combination covers all three dimensions of guardrail quality: structural correctness (ArchUnit), stylistic consistency (Spotless), and test effectiveness (PIT). All three tools run as Maven plugins with no external infrastructure.

### Positive Consequences

* **Architecture violations fail the build.** ArchUnit tests run as JUnit 5 tests in Surefire; a forbidden dependency (e.g., `controller → repository` bypass in Layered, or `adapter → adapter` in Hexagonal) causes a red build immediately.
* **Consistent code style without discussion.** Spotless with `palantirJavaFormat` reformats code on `mvn spotless:apply`; `mvn spotless:check` (run in CI) fails on unformatted files. Unused imports are removed automatically.
* **Mutation quality gate.** PIT with `STRONGER` mutators and `mutationThreshold=80` ensures that at least 80 % of injected code mutations are detected by unit tests. Tests that only run code without asserting behaviour fail this gate.
* **Local parity with CI.** All three tools run with the same Maven commands locally and in CI; no "it passes locally but fails in CI" surprises due to different tooling.
* **Fast feedback.** ArchUnit and Spotless check run as part of `mvn test`; PIT is invoked explicitly (`mvn pitest:mutationCoverage`) to avoid slowing down the default cycle.
* **Style skip for fast dev.** The `fast` Maven profile (`-Pfast`) disables Spotless for rapid iteration: `./mvnw test -T 1C -Pfast`.

### Negative Consequences

* **ArchUnit rule maintenance.** As the codebase grows, rules must be updated when new packages are introduced or refactoring changes the layer structure.
* **Spotless formatting lock-in.** `palantirJavaFormat` is an opinionated formatter; some developers may dislike its style decisions, but consistency is more valuable than preference.
* **PIT run time.** Mutation testing is CPU-intensive; running `mvn pitest:mutationCoverage` on the full codebase can take several minutes. It is not included in the default `mvn verify` cycle.
* **ArchUnit tests require knowledge of rules.** Developers must know which ArchUnit test class encodes which rule before they can understand a violation message.
* **No ArchUnit tests written yet.** The dependency (`archunit-junit5`) is declared in `pom.xml` but the rule classes have not been authored. This is a known gap: rules must be written as part of each service's initial implementation.

---

## Pros and Cons of the Options

### Option 1 — ArchUnit + Spotless + PIT ✅

* Good, because all tools run as Maven plugins — no external services, no extra CI steps.
* Good, because ArchUnit violations fail the build immediately with a descriptive message.
* Good, because Spotless enforces format consistently without PR debates.
* Good, because PIT catches "coverage theatre" — tests that run but do not kill mutations.
* Bad, because ArchUnit rules must be written and maintained by the team.
* Bad, because PIT is slow; must be run explicitly rather than on every commit.

### Option 2 — SonarQube

* Good, because rich dashboards, tech-debt tracking, architecture graph visualisation.
* Good, because supports architectural dependency rules via SonarQube Architecture plugin.
* Bad, because requires a running SonarQube server (local or hosted).
* Bad, because the free Community edition has limited branch analysis.
* Bad, because adds a network dependency to the build pipeline.

### Option 3 — Custom PMD / Checkstyle rules

* Good, because lightweight; Checkstyle is already available in many Java projects.
* Good, because PMD rules are XML-configurable without Java test code.
* Bad, because PMD and Checkstyle are not architecture-aware — they cannot express "layer A must not depend on layer C".
* Bad, because custom rule authoring is time-consuming and error-prone.
* Bad, because formatting enforcement with Checkstyle is far less powerful than Spotless.

### Option 4 — Manual code review only

* Good, because zero tooling overhead.
* Bad, because inconsistent: reviewers miss violations under time pressure.
* Bad, because violations accumulate gradually and become expensive to fix retrospectively.
* Bad, because no machine-readable record of which rules are in force.

---

## Implementation

### ArchUnit — planned rules per service

**Starport Registry (Layered)**

```java
// Allowed: controller → service → repository; NOT controller → repository
layeredArchitecture()
    .consideringAllDependencies()
    .layer("Controller").definedBy("..controller..")
    .layer("Service").definedBy("..service..")
    .layer("Repository").definedBy("..repository..")
    .whereLayer("Controller").mayNotAccessLayersOtherThan("Service")
    .whereLayer("Repository").mayNotBeAccessedByLayersThan("Service");
```

**Trade Route Planner (Hexagonal)**
```java
// domain must not import Spring; adapters must not depend on each other
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAPackage("org.springframework..");
```

**Telemetry Pipeline (Pipes & Filters)**
```java
// filters must be stateless — no instance fields that are not final or injected
// sinks must not call persistence from filter classes
```

### Spotless configuration

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.44.2</version>
  <configuration>
    <java>
      <palantirJavaFormat/>
      <removeUnusedImports/>
    </java>
  </configuration>
</plugin>
```

Usage:
* `mvn spotless:apply` — auto-format all Java files.
* `mvn spotless:check` — fail if any file is not formatted (used in CI).
* `./mvnw test -Pfast` — skip Spotless for fast local dev.

### PIT configuration

```xml
<plugin>
  <groupId>org.pitest</groupId>
  <artifactId>pitest-maven</artifactId>
  <configuration>
    <mutators><mutator>STRONGER</mutator></mutators>
    <mutationThreshold>80</mutationThreshold>
    <targetClasses><param>com.galactic.starport.*</param></targetClasses>
    <!-- E2E, Repository, and Contract tests excluded from mutation scope -->
    <excludedTestClasses>
      <param>**.*E2ETest</param>
      <param>**.*RepositoryTest</param>
      <param>**.*ContractTest</param>
    </excludedTestClasses>
    <timestampedReports>false</timestampedReports>
  </configuration>
</plugin>
```

Usage: `mvn pitest:mutationCoverage` (run on demand or in nightly CI).

---

## References

* ADR-0001 — Architecture Styles per Service
* ADR-0006 — Testing Strategy
* ArchUnit — https://www.archunit.org/
* Spotless Maven Plugin — https://github.com/diffplug/spotless/tree/main/plugin-maven
* PIT Mutation Testing — https://pitest.org/
* Palantir Java Format — https://github.com/palantir/palantir-java-format
