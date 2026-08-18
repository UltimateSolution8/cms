# Capacity model — UDS Consent & Privacy Control Plane

*17 August 2026. Written because `OPERATIONS.md` §6 publishes **p95 under 30 ms** on the decision API
and **99.99% availability**, and neither had ever been measured.*

---

## 1. What the existing evidence actually is

`DecisionLatencyIT` is the only latency assertion in the tree, and it is worth being exact about
what it proves: **one round trip, on a developer's laptop, against a warm registry cache and an
almost empty table, inside the same JVM as the server.** It is a ceiling — it catches a change that
makes a decision ten times slower — and it is not a measurement of the published SLO.

It has never been run at concurrency, at volume, against a cold cache, across a network, or with a
connection pool under contention. Every one of those is where a p95 goes.

**So the honest statement of the position is this: 30 ms p95 is a target, not a commitment, until
§4 has been run.** Quoting it in a client contract before then is quoting a number nobody has
observed. That sentence is the reason this document exists.

---

## 2. The model

Derived from what the platform does per request, not from a throughput figure somebody liked.

### Decision path — `POST /v1/evaluate`

| Work | Cost |
|---|---|
| Purpose registry lookup | In-memory, cached, refreshed every 5 min |
| Application registry lookup | In-memory, cached |
| Consent artefact read | One indexed row by `(entity_id, subject_id, purpose_code)` |
| Suppression check | One indexed lookup per channel |
| Jurisdiction rules | Pure computation |
| Enforcement evidence write | **Only on a denial.** One insert |

The read path is one or two indexed lookups. The 30 ms target is not ambitious for that shape —
which is precisely why failing it would mean something is wrong rather than that the number was
optimistic.

**The asymmetry that decides everything.** An allowance writes nothing; a denial writes an
`enforcement_decision` row. A campaign against a heavily-suppressed list is therefore a write
workload wearing a read workload's clothing, and it is the profile most likely to move p95 — which
is why the load profile in §4 includes a high-denial case rather than only a happy path.

### Capture path — `POST /v1/consent`

Heavier and correctly so: validation, an identifier hash, a chain-head row lock, the event insert,
the artefact upsert, the outbox row. The chain-head lock serialises writes **per subject**, not
globally — two events for the same person contend, two events for different people do not. Capture
concurrency is bounded by distinct subjects, which at any real volume is not a bound.

### Batch scrub — `POST /v1/evaluate/batch`

Up to 1,000 identifiers per call. One decision each, so one call is up to a thousand times the work
of a single evaluation. This is why it has its own rate-limit class (10/s, burst 20) rather than
inheriting the decision path's 200/s: those two numbers describe the same amount of work.

---

## 3. Pool sizing — the derivation `DB_POOL_SIZE: 20` never had

The value has been 20 since the first commit, with a comment saying "sized for a single instance"
and no arithmetic behind it. Here is the arithmetic.

Little's Law: `pool = throughput × time-holding-a-connection`.

- A decision holds a connection for the artefact and suppression reads — call it **2 ms** on an
  indexed lookup with a warm buffer cache.
- At **1,000 decisions/second/instance**: `1000 × 0.002 = 2` connections.
- A capture holds one for the transaction — lock, insert, upsert, outbox — call it **10 ms**.
- At **50 captures/second/instance**: `50 × 0.010 = 0.5` connections.

So steady state needs about **3**. Twenty is roughly a 6× margin, which is the right order for
absorbing a slow query, a lock wait, or a batch scrub arriving mid-burst — and it is not so large
that it becomes the problem.

**The ceiling that actually binds is the database, not the application.** At 12 replicas (the HPA
maximum) × 20 = **240 connections**, against a default PostgreSQL `max_connections` of 100.

> **This is the first thing to check before scaling out.** Raising `maxReplicas` without raising
> `max_connections` converts a latency problem into `FATAL: sorry, too many clients already` — an
> outage, on the path every outbound campaign depends on, caused by an attempt to make it faster.

Either raise `max_connections` to 300+ with the memory to match, or put PgBouncer in transaction
mode in front. PgBouncer is the better answer past about six replicas, and it has one interaction
worth knowing about: the row-level security claim is a **session** variable set on every connection
checkout, so it must be `SET LOCAL` inside the transaction or transaction pooling will hand one
entity's claim to another entity's query. Check `EntityScopedDataSource` before introducing a pooler.

---

## 4. The load profile

`perf/k6/` — five scenarios and a seed. **`perf/README.md` is the invocation.** Not wired into CI,
because a fifteen-minute ramp is not a pull-request gate.

> **This section used to say "k6 is not installed on the build machine", and that was the stated
> reason the platform's published SLO had never been measured.** It was not true — k6 has been at
> `/opt/homebrew/bin/k6` throughout. It is left recorded here rather than quietly deleted because
> it was the second time in two phases that the largest outstanding item turned out to be blocked
> on nothing, and because the check cost one `which`. §7 is what running it produced.

```bash
k6 run perf/k6/decision.js
```

| Scenario | Shape | What it is for |
|---|---|---|
| `decision.js` | Ramp to 1,000 rps, 10 min steady | The published SLO. p95 and p99, not mean |
| `decision-deny.js` | Same, against suppressed subjects | The write-per-denial asymmetry |
| `capture.js` | 100 rps, distinct subjects | Chain-head cost with no contention |
| `capture-hot.js` | 100 rps, **20** subjects | Deliberately pathological. Whether the lock is really per subject |
| `batch.js` | 10 rps × 1,000 identifiers | 10,000 decisions/second through one route |

**Seed first.** `perf/seed.sql` loads a realistic artefact population; a decision against an empty
table measures an index that fits in cache and answers a question nobody asked.

**Run against a deployed instance, over a network.** In-JVM numbers omit serialisation, the socket,
and the connection pool — which between them are most of what a p95 is made of.

**What to record.** p50/p95/p99 per scenario, connection-pool wait time, CPU at the plateau, and the
rate at which `enforcement_decision` grows. That last one feeds the partition retention decision in
`OPERATIONS.md`, and it is the one nobody thinks to capture.

**Scrape it rather than reading the k6 summary alone.** `/actuator/prometheus` on the management
port now publishes `uds_consent_decision_seconds` with percentiles, plus the connection-pool and
JVM series Micrometer registers for free. k6 measures what the client saw; the scrape says where the
time went, and the difference between the two is usually the whole finding.

---

## 5. Cache behaviour under multiple replicas

`CachingPurposeCatalog` and `CachingApplicationRegistry` refresh every five minutes, so **five
minutes is the window in which two replicas can disagree about policy after a publish.** The
publishing instance refreshes itself immediately; the others lag.

At three replicas this is a small window and a known one. It grows with replica count, and the
failure it produces is specific: a capture validated against a new purpose registry by one instance
and rejected by another, resolving itself minutes later. That is the hardest kind of failure to get
anyone to believe, and it is the reason `POST /v1/admin/purposes/refresh` exists — call it after a
publish rather than waiting.

If replica count goes past about six, shorten `registry-refresh-interval` or push refresh through
the outbox instead of polling.

---

## 6. What §2 and §3 do not claim

They are a model, not a measurement. Every number in them is derived from the shape of the work and
from ordinary indexed-lookup costs.

**The point of writing them down is that §7 had something to disagree with.** A load test with no
prior model produces a number and no opinion about whether it is the right one — and as it turns
out, the model was right about the engine and wrong about two other things.

The objective is also **watched**: `DecisionLatencyOverObjective` in `deploy/observability/alerts.yaml`
fires on a p95 over 30 ms held for ten minutes. An alert tells you when the number is wrong; a load
test tells you what the number is. Both now exist.

---

## 7. Measured — 17 August 2026

**Run at last.** Everything below was observed, not derived.

### What it was measured on, and what that licenses

| | |
|---|---|
| Hardware | Apple silicon laptop, 8 cores, 7.65 GiB available to Docker |
| Topology | k6, the service container and the database container **all on the same machine** |
| Population | 1,000,000 subjects, 1,000,000 artefacts, 150,000 suppressions (`perf/seed.sql`), `ANALYZE`d |
| Transport | Loopback through Docker Desktop's port forwarding — a real socket, not in-JVM |
| Auth | HTTP Basic, which is what every current integrator uses |

**This does not license quoting any absolute latency to a client.** The load generator competes with
the thing it is measuring for the same eight cores. What it *does* license is every statement below
about **where the time goes**, because those are ratios between components measured under identical
conditions, and a shared bottleneck moves all of them together.

A control experiment bounds the transport: 200 rps against `/actuator/health/liveness` — same
socket, same port forwarding, no database and no policy engine — sustained **170 rps at p95 4.75 ms**.
So Docker's networking is not the bottleneck in anything that follows.

### The headline

**The consent decision meets its objective by a factor of eleven. The product misses it by a factor
of four. None of the gap is in the platform's own logic.**

| | p50 | p95 | p99 |
|---|---|---|---|
| **Decision engine, server-side** (`uds_consent_decision_seconds`) | **1.3 ms** | **2.6 ms** | 4.4 ms |
| **What the client saw**, same requests | 112 ms | 115 ms | 124 ms |
| Published objective | — | 30 ms | 100 ms |

The 110 ms between those two rows is **one BCrypt verification per request**.

`SecurityConfiguration.java:322` uses `PasswordEncoderFactories.createDelegatingPasswordEncoder()`,
whose default is BCrypt at strength 10, and `InMemoryUserDetailsManager` re-runs it on **every
request** — there is no session and no credential cache, by design. BCrypt is deliberately slow;
that is its entire purpose. It costs about 110 ms of pure CPU on this hardware.

Isolated, and this is the measurement that settles it:

```
authenticated decision, warm, uncontended   115 ms
401 — known user, wrong password            113 ms      ← same cost, no work done
401 — user that does not exist              113 ms
unauthenticated route (liveness)              2 ms
```

**A 401 costs the same as a successful decision.** It touches no database, evaluates no policy and
returns an error body. Everything the platform exists to do — deserialisation, the entity guard, the
RLS claim, eight policy gates, the enforcement write, serialisation — is the ~2 ms difference
between the first and second lines.

### Throughput, and where it stops

| Offered | Achieved | p95 | Note |
|---|---|---|---|
| 20 rps | 15 rps | 114 ms | Already saturated |
| 40 rps | 30 rps | 122 ms | |
| 60 rps | 46 rps | 145 ms | |
| 200 rps | 53 rps | **4.55 s** | 20,488 iterations dropped; the queue, not the platform |

**The single-request ceiling is ~50 rps per instance**, and it is a CPU ceiling imposed by password
hashing. It is not the database: HikariCP reported **zero pending connections and zero timeouts**
across 94,719 acquisitions, with a maximum acquire time of 0.24 s and a mean of 0.14 ms. The pool
sized at 20 in §3 was never approached. **§3's derivation is untested by this run** — the workload
never got far enough to exercise it.

### The batch route is the answer, and by a wider margin than anyone claimed

| | Achieved | Per decision |
|---|---|---|
| `POST /v1/evaluate` | ~50 decisions/s | one BCrypt each |
| `POST /v1/evaluate/batch` | **~5,400 decisions/s** | one BCrypt per 1,000 |

A hundred-fold difference, and it is not the query plan — it is that a batch amortises the
authentication over a thousand decisions. Per-batch latency was p95 20.6 s at ten concurrent
batches, over the 5 s ceiling `batch.js` asserts; the *marginal* decision inside an uncontended
batch was **0.88 ms**, and 12.2 ms averaged under full concurrency.

**Operational consequence for Denave: scrub the campaign list, do not pre-flight per call.** The
route already exists and `MAX_BATCH` is 1,000. Pre-flighting individually is the pattern that meets
the ceiling above.

### Two predictions this document made, and how they came out

**§2 predicted the denial path would be the slower one. It is not — it is the faster one.**
Server-side p95 on 100% suppressed subjects was **1.5 ms**, against 2.6 ms for the mixed population.
Suppression is gate 8 and short-circuits: a denied subject never reaches artefact resolution or
purpose-version lookup. The `enforcement_decision` write is real and was measured — 119,699 rows
accumulated over the run at roughly **800 rows/second** during the batch scenario, at **378 bytes
per row including indexes** — but it does not dominate the refusal.

**The per-subject chain lock claim holds.** `capture-hot.js` put 2,279 appends across **20** subject
chains (98 events on the busiest single chain) and produced **zero conflicts, zero 5xx, and latency
statistically identical to `capture.js`** against distinct subjects — p95 115.4 ms versus 115.5 ms,
both of which are the same BCrypt floor. Server-side capture time was ~60 µs. This is the first
evidence the architecture's central serialisation claim has ever had.

### `enforcement_decision` growth, for the retention decision

**378 bytes per row.** At Denave's projected scrub volume the partition-retention ceiling in
`OPERATIONS.md` is now derivable rather than guessed: one million denials is ~360 MB, one partition
month at a sustained 800 rows/second would be ~700 GB. **The retention ceiling is not optional at
volume**, and this is the number that decides where it goes.

### What this run did not do

- **Never reached the database's limits.** The pool, the indexes and §3's sizing are all still
  untested, because authentication saturated the CPU first. Re-run after the finding below is
  addressed and §3 will finally have something to say.
- **One instance.** Nothing here says anything about replica scaling or the five-minute cache
  divergence window in §5.
- **Basic auth only.** The JWT path was not measured, and it is the one that matters — see below.

### The one change that would move all of this

**Authentication is the platform's capacity limit and its cheapest fix.** Three options, in the order
they should be considered:

1. **Move integrators onto the OIDC resource server** delivered in Phase 11. JWT validation is an
   RSA signature verification — tens of microseconds against BCrypt's 110 ms. This is already built,
   already tested, and needs an IdP, which is on the outstanding list anyway. It would leave the
   client-side p95 at roughly the server-side one, i.e. **inside the 30 ms objective**.
2. **Cache authenticated credentials** for the Basic path, with a short TTL. Standard, and it means
   BCrypt runs once per client per window instead of once per request.
3. **Do nothing and batch everything.** Already viable at 5,400 decisions/second, and it is the
   right advice for a dialer regardless.

Do **not** lower the BCrypt strength. It is the correct cost for a password check; the defect is
that a password is being checked on every machine-to-machine request in a hot path.

### And one security finding that came out of the same measurement

> **Closed in Phase 16 (18 August 2026), and this section is kept rather than deleted because two
> other documents cite §7 as the authority on it.** What follows describes the state on 17 August.
> `PreAuthRateLimitFilter` now runs at `SecurityProperties.DEFAULT_FILTER_ORDER - 10` (−110), ahead
> of Spring Security's chain, keyed by client address alone with one loose ceiling; the ordering is
> asserted by `PreAuthRateLimitIT.theRefusalPrecedesAuthentication`, which requires **429 and not
> 401** and so cannot pass with the filter behind authentication.
>
> **The ~110 ms figure below has not been re-measured since.** The test proves *ordering*, which is
> the property that matters, and does not prove *cost*. Saying so is the difference between this
> correction and a second unverified claim. The paragraph after it — every configured ceiling
> sitting above measured capacity — is untouched by Phase 16 and is still true.

**The rate limiter sat behind authentication, so it could not protect the most expensive thing in
the request path.** `RateLimitFilter` is `@Order(Ordered.LOWEST_PRECEDENCE - 120)`, which places it
after Spring Security's chain (order -100). Five hundred concurrent requests bearing deliberately
invalid credentials returned **500 × 401 and not one 429** — every one of them paying the full
~110 ms of BCrypt before reaching the filter that existed to refuse them.

That was an unauthenticated CPU-exhaustion path: roughly **fifty junk requests per second saturate
an eight-core instance**, and no credential was required to send them. The per-credential limiter
itself works correctly — 3,541 of 4,000 refused at 200 rps on the public route, counted in
`uds_consent_ratelimit_refused_total{route="PUBLIC"}` — it was purely a matter of where it sat, and
it still sits there deliberately: it is the fairness limit, and it needs the credential.

Worth adding: **every configured rate-limit ceiling is above the measured single-request capacity.**
`decision` is 200/s against a measured ~50/s. A limiter whose threshold is four times what the
service can serve will never fire before saturation, which means it currently protects nothing on
that route. These numbers were set before anyone had a capacity figure; §7 is that figure.
