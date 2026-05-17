# 0032 — Log Collector: Grafana Alloy (replaces Promtail)

**Status:** Accepted
**Date:** 2026-05-17

---

## Context

ADR-0005 fixed the observability stack as **PLG + Tempo** (Prometheus, Loki, Grafana,
Tempo) but did not pin the *log-collector* component. The initial implementation used
**Promtail** (`grafana/promtail:2.9.4`) as a sidecar container that discovered Docker
containers via the host socket, tailed their stdout/stderr, and shipped lines to Loki at
`http://loki:3100/loki/api/v1/push`.

Two things changed since that choice was made:

1. **Grafana Labs has consolidated its agents.** In 2024 they released **Grafana Alloy**
   — a single OpenTelemetry-Collector-compatible distribution that supersedes Promtail,
   the Grafana Agent (Static and Flow modes), and several smaller agents. Promtail has
   been moved to a long-term-support / maintenance branch; new feature work (including
   first-class OTLP semantics and the new River-based "Alloy syntax") happens only in
   Alloy. New Grafana documentation, Helm charts, and tutorials default to Alloy.

2. **The fleet will likely need more than logs from the same agent.** This repo already
   sends traces directly from each Spring service to Tempo via OTLP (ADR-0017) and
   metrics are pulled by Prometheus (ADR-0005, ADR-0030). The next plausible step —
   continuous profiling via Pyroscope, or OTel-collector-style metric processing — is
   trivial in Alloy (add a component) but would require a *second* sidecar alongside
   Promtail. Picking Alloy now keeps the door open for one collector per host instead of
   one per signal.

The migration is small in surface area (one container, one config file) and the existing
Loki + Grafana setup is unchanged, so this is the right time to make the switch — before
more signals or more Promtail-specific config accrete.

---

## Decision

### 1. Replace the `promtail` service with `alloy` in `infra/docker/docker-compose.yml`

```yaml
alloy:
  image: grafana/alloy:latest
  container_name: alloy
  ports:
    - "12345:12345"   # Alloy debug UI (http://localhost:12345)
  volumes:
    - /var/lib/docker/containers:/var/lib/docker/containers:ro
    - /var/run/docker.sock:/var/run/docker.sock:ro
    - ./alloy:/etc/alloy:ro
  command:
    - run
    - --server.http.listen-addr=0.0.0.0:12345
    - --storage.path=/var/lib/alloy/data
    - /etc/alloy/config.alloy
  depends_on:
    loki:
      condition: service_healthy
```

The image tag is **`latest`** intentionally — it matches the convention already used for
`prom/prometheus:latest` in the same file. The dev stack is rebuilt frequently and there
is no production target that would require a pinned digest.

### 2. Translate the Promtail YAML pipeline into the Alloy syntax 1:1

The new `infra/docker/alloy/config.alloy` reproduces the previous pipeline using three
Alloy components and preserves the **exact label set** (`container`, `stream`, `service`)
so that existing Grafana dashboards, LogQL queries, and the "Logs for this span" button
in the trace view all keep working without modification:

```hcl
discovery.docker "containers" {
  host             = "unix:///var/run/docker.sock"
  refresh_interval = "5s"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }
  rule {
    source_labels = ["__meta_docker_container_log_stream"]
    target_label  = "stream"
  }
  rule {
    source_labels = ["__meta_docker_compose_service"]
    target_label  = "service"
  }
}

loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.containers.output
  forward_to = [loki.write.default.receiver]
}

loki.write "default" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```

### 3. Expose the Alloy debug UI on `:12345`

Unlike application replicas (kept off the host per ADR-0031), Alloy is an infrastructure
service in the same tier as Grafana, Prometheus, and the Loki/Tempo APIs — all of which
*are* host-bound for the local-dev experience. Exposing `:12345` keeps consistency with
that tier and gives a one-click view of the component graph, target health, and queue
depth that is much faster than `docker logs alloy | grep`.

### 4. Delete the old `infra/docker/promtail/` directory

No alias, no fallback container. The promtail config is regenerated from `git log` if
anyone ever needs to read it.

---

## How the codebase enforces this

- `infra/docker/alloy/config.alloy` — the entire log pipeline (discovery → relabel →
  source → write). Adding a new label or filter is a one-component change.
- `infra/docker/docker-compose.yml` — `alloy` service definition; the only log
  collector in the compose file. ADR drift would show up as a second collector here.
- `README.md` § "Observability" — names Alloy as the collector; mismatch with this ADR
  means the docs need updating, not the stack.
- `infra/docker/wyjasnienie.md` — observability matrix lists `Loki / Alloy` instead of
  `Loki / Promtail`.

---

## Consequences

### Positive

* **Aligned with Grafana's roadmap.** New collector docs, integrations, and bundled
  components target Alloy. Promtail-only material is becoming the long tail.
* **One collector for future signals.** Adding Pyroscope profiles, OTel-Collector-style
  metric processing, or remote-writing a slice of Prometheus output is a `+N components`
  change in the same config — no second sidecar.
* **Declarative component graph.** Alloy syntax is graph-shaped and statically
  type-checked at startup; bad references fail fast instead of silently dropping logs.
* **Debug UI.** `http://localhost:12345` shows the live component graph, target counts,
  scrape errors, and queue back-pressure without `docker exec`.
* **Same Loki, same labels, same dashboards.** Zero changes to Loki, Grafana
  provisioning, or any LogQL query — the cut is collector-only.

### Negative / trade-offs

* **New syntax to learn.** Alloy syntax (HCL-like) is closer to Terraform than to
  Prometheus YAML. Cheaper than OTel-Collector YAML, but unfamiliar at first.
* **`latest` tag = non-deterministic builds.** Matches the rest of the dev stack but
  would need to be pinned before any production-shaped use.
* **One more host-bound port (`:12345`).** Increases the surface area of the local
  stack. The port is firewalled by `localhost` binding in practice, but in a hardened
  deployment the debug UI should be auth-gated or unbound.
* **Memory footprint slightly larger** than Promtail (Alloy bundles OTel collector
  primitives even when unused). Negligible at this scale (~30–60 MB extra RSS).

---

## Alternatives Considered

### Stay on Promtail

* Good, because zero-change inertia.
* Bad, because Promtail is on a maintenance branch — new features (notably OTLP-native
  log handling) are not back-ported, and the eventual migration cost grows with each
  custom rule added.

### OpenTelemetry Collector (`otel/opentelemetry-collector-contrib`)

* Good, because vendor-neutral and the same daemon could replace the existing direct
  OTLP-to-Tempo path from each service.
* Bad, because it has no native Loki receiver/writer story as polished as Alloy's
  (`loki.source.docker` + `loki.write`); shipping logs to Loki would require either the
  `loki` exporter from `contrib` or running both. Heavier dependency footprint for the
  same outcome.

### Fluent Bit

* Good, because mature, fast (C), widely deployed.
* Bad, because no first-class Grafana integration; LogQL label conventions and dynamic
  pipeline reloads would need custom plumbing. Pulls us away from the rest of the
  Grafana-aligned stack (ADR-0005).

---

## References

* ADR-0005 — Observability Stack (PLG + Tempo). This ADR refines that choice for the
  collector component; ADR-0005 itself remains accurate.
* ADR-0017 — Distributed Tracing Propagation. Traces still go directly from each service
  to Tempo via OTLP; Alloy is *not* on the trace path today.
* ADR-0031 — API Gateway / no host-bound instance ports. Alloy is an *infrastructure*
  service, not an application replica, and is therefore exempt from that rule (same
  exemption used by Grafana, Prometheus, Loki, and Tempo).
* Grafana Alloy documentation — https://grafana.com/docs/alloy/latest/
* Promtail deprecation context — https://grafana.com/blog/2024/04/09/grafana-alloy-opentelemetry-collector-with-prometheus-pipelines/
