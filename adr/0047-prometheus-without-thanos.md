# ADR-0047: Prometheus bez Thanosa i MinIO

## Status

Zaakceptowany (2026-11-23). Uzupełnia ADR-0005.

## Kontekst

`docker-compose.observability.yml` uruchamiał sześć usług długoterminowej retencji metryk: `minio`,
`minio-init`, `thanos-sidecar`, `thanos-store`, `thanos-query`, `thanos-compactor`. Żaden ADR nie
opisywał tej decyzji (README ADR wymieniał to jako lukę).

Stos ma jednego Prometheusa i jedną aplikację. Thanos rozwiązuje dwa problemy, których tu nie ma:
globalny widok wielu Prometheusów i retencję liczoną w miesiącach. Kosztował za to sześć kontenerów,
cztery wolumeny i znany kłopot: po `docker compose down -v` store i compactor wpadały w pętlę
restartów.

## Decyzja

- Usuwamy wszystkie sześć usług i ich wolumeny oraz `infra/docker/thanos/`.
- Prometheus wraca do zwykłej konfiguracji: bez flag wymaganych przez sidecar (`min-block-duration`
  = `max-block-duration`), bez `external_labels`, z lokalną retencją 15 dni.
- Datasource Grafany wskazuje wprost na `prometheus:9090`. `uid` zostaje, więc dashboardy działają
  bez zmian. Exemplary (ADR-0033) nadal działają — obsługuje je sam Prometheus.

## Konsekwencje

- Sześć kontenerów mniej; znika pętla restartów po `down -v`.
- Metryki starsze niż 15 dni przepadają. Dla stosu deweloperskiego to wystarcza.
- Liczba instancji aplikacji nie ma tu znaczenia: Thanos dotyczył replik Prometheusa, nie aplikacji.
- Gdy pojawi się drugi Prometheus albo potrzeba dłuższej retencji, wraca temat Thanosa lub
  `remote_write` — jako nowy ADR.
