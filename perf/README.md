# Load profile

The invocation, in one place, because the version of it that lived in five file headers had drifted
and the one script that existed could not run.

`docs/CAPACITY.md` is the model — what the numbers *should* be, derived from the shape of the work.
This directory is the measurement. **§7 of that document is where results go**, and a run whose
numbers are not written down there did not happen.

---

## What you need

k6 and a PostgreSQL client. Both are on the build machine already —
`k6 v1.6.1` at `/opt/homebrew/bin/k6`.

> `CAPACITY.md` said for months that "k6 is not installed on the build machine". It was not true,
> and it was the stated reason the platform's published SLO had never been measured. It is recorded
> here because it is the second time in two phases that the largest remaining item turned out to be
> blocked on nothing, and the check cost one `which`.

---

## Running it

**From the repository root.** `\copy` in `seed.sql` writes client-side, relative to psql's working
directory, and the two paths it writes are `perf/k6/*.json`. Run it from anywhere else and those
are the two lines that fail.

```bash
docker compose -f platform/docker/docker-compose.yml --profile app up -d
```

```bash
PGPASSWORD=… psql -h localhost -U uds_consent_owner -d uds_consent -f perf/seed.sql
```

That writes a million subjects, their identifiers, their artefacts, a 15% suppression rate, and the
two sampled id lists the scripts read. It takes a few minutes and ends with `ANALYZE` — which is not
housekeeping: without fresh statistics the planner sequential-scans a million-row table it should be
index-scanning, and the run reports a p95 in the hundreds of milliseconds against a platform that is
working perfectly.

```bash
k6 run perf/k6/decision.js
```

| Script | Shape | What it is for |
|---|---|---|
| `decision.js` | Ramp to 1,000 rps, hold | The published SLO. p95 and p99, never the mean |
| `decision-deny.js` | Same, against suppressed subjects | The write-per-denial asymmetry. Expected to be the slower one |
| `capture.js` | 100 rps, distinct subjects | Chain-head cost with no contention |
| `capture-hot.js` | 100 rps, **20** subjects | Whether the chain lock is really per subject |
| `batch.js` | 10 rps × 1,000 identifiers | 10,000 decisions/second through one route |

Run `capture.js` before `capture-hot.js`. Neither number means anything alone; the pair is the
measurement.

---

## Turning the load down without turning the bar down

```bash
k6 run -e RATE=200 -e DURATION=2m perf/k6/decision.js
```

`RATE`, `DURATION`, `VUS`, `MAX_VUS` and `BASE_URL` come from the environment. **The thresholds do
not, and must not.** They are `OPERATIONS.md` §6 verbatim — `p(95)<30`, `p(99)<100`, failures under
0.01% — and a laptop run is *expected to fail them*.

That failure is the measurement. Read the p95 k6 printed; do not edit the number it was compared
against. A profile whose thresholds are relaxed until a local run goes green will pass forever and
mean nothing, and the numbers it is asserting are the ones quoted to clients.

---

## Three things that will bite

**The rate limiter refuses this before the platform does.** `uds.consent.rate-limit.decision` is
200 permits/second *per caller* and `decision.js` ramps to 1,000, so a single `athena-dialer`
credential is refused by the platform's own limiter well before anything interesting happens. Every
script checks for 429 and counts it as a failure, deliberately.

Do not quietly raise the cap to get a clean run. Record it: **one dialer credential at projected
volume is refused by the platform's configuration**, which is a finding about the configuration and
not about the platform. Then decide — a higher cap, per-region credentials, or the conclusion that
1,000 rps from one caller was never the right profile.

**The limiter is per instance.** With more than one replica the effective fleet limit is
*N* × configured, and a load test against a single container measures the strictest case there is.

**Almost everything k6 reports is the cost of Basic auth, not the cost of a decision.** Measured on
17 August 2026 (`CAPACITY.md` §7): client p95 115 ms, server-side `uds_consent_decision_seconds` p95
2.6 ms. The ~110 ms in between is BCrypt re-hashing the credential on every request, because there is
no session and no credential cache — and a deliberately unauthenticated request costs the same 113 ms,
which is how that was localised. Consequences for whoever runs this next:

- A single instance tops out near **50 rps** on the authenticated path, and that ceiling is CPU. More
  database, a bigger pool and a faster query move none of it.
- Compare like with like: the 30 ms objective in `OPERATIONS.md` §6 is about the decision, so the
  number to compare it against is the scrape, not the summary.
- `batch.js` bypasses the problem by amortising one authentication over a thousand identifiers, which
  is why it reached ~5,400 decisions/second where the single route managed ~50.

---

## Read the scrape, not just the summary

```bash
curl -s localhost:9090/actuator/prometheus | grep uds_consent_decision_seconds
```

k6 measures what the client saw. `/actuator/prometheus` publishes
`uds_consent_decision_seconds{quantile="0.95"}` — what the server spent — plus the HikariCP pool and
JVM series Micrometer registers for free. **The difference between the two is usually the whole
finding**: if the client sees 80 ms and the server reports 4 ms, the time is in the queue, the
socket or the pool, and no amount of query tuning will touch it.

Take the scrape *at the plateau*, not after the run ends.

Worth capturing at the same moment, because nobody thinks to and it feeds the partition-retention
decision in `OPERATIONS.md`:

```sql
select count(*) from enforcement_decision;
```

Run it before and after. The delta over the run length is the growth rate of the fastest-growing
table in the schema.

---

## Not in CI

A fifteen-minute ramp is not a pull-request gate. That is a different sentence from "k6 is not
installed", and only one of them was ever true.

---

## Never against production

`seed.sql` says this at length and means it. Everything it writes is fabricated, and a ledger
containing fabricated consent is a ledger with a fatal problem that no integrity verification will
ever catch — because the hashes will be perfectly valid.

`capture.js` and `capture-hot.js` write to the ledger too, and the ledger is append-only by design:
there is no cleanup for this. Use a database you can drop.
