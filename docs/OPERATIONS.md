# Operations — UDS Consent & Privacy Control Plane

Everything in this document exists because something in the platform's design depends on an
operator doing it. Where a step is skipped, the consequence is stated rather than implied — a
runbook that only says what to do produces deployments that are subtly wrong and confidently
signed off.

Referenced from `V2__append_only_guards.sql`.

[`WALKTHROUGH.md`](WALKTHROUGH.md) runs the platform end to end with real commands and real
responses, including the operator half of this document — the authenticated health endpoint, the
verification key, and a cross-entity refusal. Read it once before working through this.

---

## 1. Database provisioning

### 1.1 Two roles, not one

| Role | Used by | Must be able to |
|---|---|---|
| `uds_consent_owner` | Flyway migrations | Create and alter schema objects |
| `uds_consent_app` | The running service | Insert into the ledger and read everything; **not** update or delete evidence |

Create the application role **before the first migration**:

```sql
create role uds_consent_app with login password :'app_password';
grant connect on database uds_consent to uds_consent_app;
grant usage on schema public to uds_consent_app;
alter default privileges in schema public grant select, insert, update, delete on tables to uds_consent_app;
alter default privileges in schema public grant usage, select on sequences to uds_consent_app;
```

`V2__append_only_guards.sql` then revokes `UPDATE`, `DELETE` and `TRUNCATE` from that role on
`consent_event`, `admin_audit_event`, `notice_version`, `notice_translation` and `purpose_version`.

**If the role does not exist when V2 runs, V2 logs a notice and skips the revocation.** The
migration succeeds. The database still has the triggers and the hash chain, so it is not broken —
but one of the three layers is silently absent, and no error will ever tell you. Verify explicitly
after every environment build:

```sql
select grantee, privilege_type from information_schema.table_privileges
 where table_name = 'consent_event' and grantee = 'uds_consent_app';
```

Expect `SELECT`, `INSERT`, `REFERENCES`, `TRIGGER` — and **no** `UPDATE` or `DELETE`. If UPDATE
appears, the environment is not production-ready: create the role and re-run V2.

Configure the two roles separately. The service reads `DB_URL` / `DB_USER` / `DB_PASSWORD`; Flyway
reads `DB_MIGRATION_URL` / `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD`. Pointing both at the
owner would hand the running application the ability to edit history that the whole design exists
to remove.

### 1.2 Residency

Host the DPDP-scoped ledger in India (`ap-south-1` / Central India). DPDP Rule 15 is a blacklist
model — transfer is permitted except where the government restricts it, and no restricted list has
been published — but Rule 13 lets the government bar offshore transfer of specified categories for
Significant Data Fiduciaries. Residency is configurable per entity so that a designation does not
become a migration project.

UK, Malaysia, Singapore and Korea flows are documented as explicit cross-border transfers in the
RoPA, not treated as an implementation detail.

---

## 2. Secrets

### 2.1 Identifier pepper — `uds.consent.identifier-pepper`

Mixed into every identifier hash before it reaches the ledger. **The service refuses to start
without it, and now also refuses to start with fewer than 32 characters.** The space of Indian
mobile numbers is small enough to enumerate exhaustively — about 10¹⁰ candidates, minutes of GPU
time — so a bare SHA-256 of a phone number is reversible in practice; the pepper is what stops an
attacker holding a database copy from recovering the numbers in it, and only if it is itself
unguessable.

The length used to be a `WARN`. That was the wrong way round: an absent pepper is noticed on the
first start-up, while a short one starts the platform, looks entirely normal, and quietly produces
a database of recoverable phone numbers. Both failures have the same consequence, so both now stop
the process.

Source it from the KMS. Treat it as **versioned alongside the ledger**: rotating it invalidates
every stored hash and requires a planned re-hash of `subject_identifier` and `suppression_entry`.
Do not rotate it casually or on a calendar.

### 2.2 Snapshot signing keys

Ed25519, configured as a pair:

- `uds.consent.snapshot.signing-key-base64` — PKCS#8 private key
- `uds.consent.snapshot.verification-key-base64` — X.509 public key
- `uds.consent.snapshot.signing-key-id` — published with each snapshot

Both are required together. The JDK's Ed25519 private key encoding does not carry the public point,
and deriving it would mean hand-rolling scalar multiplication in the component the entire offline
enforcement story depends on.

With neither configured, the service generates an ephemeral pair and warns. Every snapshot signed
with it stops verifying at the next restart, and other instances reject it. That is fine on a
laptop and unacceptable anywhere shared.

**Rotation now has a mechanism behind it.** `signing_key` holds the public half of every key with
its lifecycle, `/v1/keys` publishes all of them that are still trusted, and every instance registers
the key it is holding at start-up — so a device can verify a snapshot signed by any replica, not
only by the one it happened to talk to.

Three states, and the difference between two of them is the whole point:

| State | Signs | Verifies | Meaning |
|---|---|---|---|
| `ACTIVE` | yes | yes | In service |
| `RETIRED` | no | **yes** | Rotated out. Signatures it already produced remain good evidence |
| `COMPROMISED` | no | no | Private half may be in someone else's hands. Nothing it signed proves anything |

**The rotation procedure.**

1. Generate a new pair. Set the three properties and a **new** `signing-key-id`.
2. Roll the fleet. Each instance registers its key on the way up; both keys are published
   throughout, so nothing in the field notices.
3. Once **every** instance is on the new key, retire the old one:
   ```
   POST /v1/admin/signing-keys/{oldKeyId}/retire   {"state":"RETIRED","reason":"scheduled rotation"}
   ```
   Not before — an instance still signing with a retired key produces snapshots no device accepts.
4. The retired key keeps verifying. Leave it retired for at least one `snapshot.validity`; there is
   no cost to leaving it longer, and a retired key is how an old snapshot is still explicable during
   an investigation.

**If a key is exposed**, use `{"state":"COMPROMISED"}` instead. This is *not* a tidier retirement:
it withdraws the key from `/v1/keys`, so every device rejects every snapshot it ever signed,
immediately. That is correct and it is also an enforcement outage for anyone working offline —
expect it and tell the field force, rather than discovering it.

Both transitions are refused if the key is not currently `ACTIVE`, and both are written to
`admin_audit_event` with the reason and the person. "We rotated on schedule" and "it was in a leaked
backup" lead to different incident responses, and six months later the state column alone cannot
tell them apart.

`/actuator/health` carries `signingKeyAgeDays` — the age of the **oldest** key still signing, so a
fleet where one instance was restarted onto a fresh key and the rest were not shows up rather than
reporting the one instance that is fine.

**Still absent, and honestly:** the private half lives in the process environment, not a KMS or an
HSM. Nothing here changes that — it makes the *public* side of rotation survivable, which is what
the runbook promised and could not deliver. Moving the private half touches `SigningKeys` and
nothing in this schema, which is why the split was made now.


**Custody: where the private half lives, and how to move it.**

Today it is an environment variable read into the process at start-up — which means the group's
signing key exists in plaintext in a pod's memory, in whatever injected it, and in the shell history
of whoever set it. `V25` made the *public* side of rotation survivable; this is the other half, and
it is still open.

`SigningKeyProvider` (in `consent-core`) is the seam. Declare a `@Bean SigningKeyProvider` backed by
the KMS and `EnvironmentSigningKeyProvider` steps aside — no other change anywhere, because
`SnapshotSigner` asks for a signature over bytes and never sees key material.

The interface has **no** `privateKey()` accessor and must never grow one. In a KMS the private key
does not leave the appliance; an SPI shaped around handing back a key object cannot be implemented
by the very thing it was extracted to allow. Four requirements the platform cannot check for you are
in the interface's Javadoc: treat `sign` as a remote call on the decision path's shoulder, keep
`keyId` stable for the key's life and changed when the key changes, make sure `publicKey` is the
public half of the key `sign` uses (two KMS calls, easily wired to different keys), and stay on
Ed25519 because the header says `EdDSA`.

### 2.3 API credentials

HTTP Basic per client under `uds.consent.security.clients.*`. Roles map to capability:

- `CAPTURE` — write consent
- `DECISION` — ask the decision API and scrub lists; **cannot** write consent, because a dialer
  that can record consent is a dialer that can manufacture it
- `ADMIN` — the compliance console and the evidence trail
- `CONSENT_MANAGER` — a Consent Manager registered with the Board, relaying a grant or a withdrawal
  on a principal's behalf under DPDP Rule 4. It reaches `/v1/consent-manager/**` and nothing else.
  The credential is bound to one registration id in `consent_manager.api_client_id`, so a relay
  cannot speak for a registration other than its own, and a suspended or deregistered CM's relays
  are refused without the credential having to be revoked

A client with a blank password fails startup rather than being skipped. A credential nobody can
use, that everybody assumes works, is worse than a missing one.

**Bearer tokens now sit alongside Basic, not instead of it.** Set
`uds.consent.security.jwt.issuer-uri` (or `.public-key` where the issuer is unreachable from the
platform's network) and the resource server registers; leave both unset and it does not, because a
decoder with nothing to validate against rejects every token and surfaces as a 500 on the
authentication path.

Two schemes at once is the migration strategy, not indecision. A replacement would be a flag day
across the Athena dialer, DenCRM and every capture surface in the same window, coordinated against
a provider none of them has been pointed at. Each integrator moves when it is ready; set
`uds.consent.security.basic-enabled: false` per environment when the last one has, which makes
closing the old door a decision somebody takes rather than a side effect.

The role model above survives unaltered — scopes map onto the same four roles through
`uds.consent.security.jwt.scope-roles`, and not one of the forty route rules changed. Two claims
carry weight beyond authentication:

| Claim | Effect | Failure mode if the issuer omits it |
|---|---|---|
| `entity_id` | The fiduciary this token may act for, enforced by both isolation layers | **Absent means group level.** A token intended to be scoped to Denave silently reads every entity in the group — the same grant an unscoped client credential has, and the reason this row is in a table rather than a footnote |
| `preferred_username` | The human, written to `admin_audit_event.actor_id`; falls back to `email`, then `sub` | An opaque `sub` in the audit trail identifies a person only to whoever still has the directory, which years into an inquiry is not a safe assumption |

Under a token, `X-UDS-Actor` is **ignored entirely** — see §12.5.

### 2.4 Configuration that changes behaviour silently

Four settings whose defaults are reasonable and whose consequences are not obvious. None of them
fails loudly when set wrongly, which is the reason for the section.

**`uds.consent.security.clients.*.entity-id` — blank means every entity.**

This is the operator-facing half of the isolation story, and it was documented nowhere. A client
with an `entity-id` is refused any request naming a different entity, at the filter and again at
the database. A client with the field **blank or absent is group-level**: it reaches every
fiduciary entity in the group, and the database sees no claim and applies no policy.

That is a grant rather than an oversight — group compliance genuinely has to see everything, and
the alternative is a shared credential nobody can attribute. But it means the difference between a
scoped and an unscoped credential is one line that is easy to omit, and omitting it fails **open**.
The service logs the full list at startup — `group-level API client(s) with access to every entity:
[...]` at WARN — and that line is the thing to read after any credential change.

**`uds.consent.default-calling-code` — changing it invalidates every stored identifier hash.**

Phone numbers are normalised to E.164 *before* they are peppered and hashed, and a number entered
without a country code is normalised using this value. Change it and every subsequently-hashed
number lands in a different place from the same number hashed yesterday: a principal who withdraws
consent is no longer the same subject as the one who granted it, and neither the suppression check
nor the evidence bundle will say anything is wrong. This is the same hazard as rotating the pepper,
which has a whole section (§2.1) — treat it the same way, and change it only as a migration.

**`uds.consent.registry-refresh-interval` (default `PT5M`) — the width of the divergence window.**

§7 describes the behaviour without naming the property. Purposes and the application registry are
cached in memory per instance and reloaded on this interval, so after a publish, instances disagree
about current policy for up to this long. Shortening it narrows the window and costs a query per
instance per interval; lengthening it is a decision to let a retirement or a scope change take
longer to bind everywhere. `POST /v1/admin/purposes/refresh` forces one instance to reload and does
not reach the others.

**`management.endpoint.health.show-details: when-authorized` — the counters are invisible to curl.**

The four health counters §8's runbook is built around are only rendered for an authenticated
caller. An operator following that section will reach for an unauthenticated `curl /actuator/health`
first, get `{"status":"UP"}`, and conclude the runbook is wrong. Use a credential:

```bash
curl -su compliance-console:admin-secret http://localhost:9090/actuator/health
```

Port **9090**, not 8080. The actuator moved to its own port so that `/actuator/prometheus` — which
needs no credential — is not served where the ingress terminates; see §11.3. An operator who reaches
for 8080 out of habit gets a 404 with a body saying this platform serves no such route, which is
accurate and, on this particular path, misleading about why.

---

## 3. Ledger integrity

### 3.1 The nightly sweep

`IntegritySweeper` runs on `uds.consent.sweeper.integrity-cron` (default 02:15) and walks every
chain, verifying that each event's `previous_hash` matches its predecessor and that each
`event_hash` recomputes from the stored `canonical_payload`.

**Alert on any finding.** Distinguish two kinds:

| Finding | Meaning | Response |
|---|---|---|
| `CHAIN_BREAK`, `HASH_MISMATCH`, `SEQUENCE_GAP` | History has been altered or an event removed | Wake somebody. This is a security incident until proven otherwise |
| `PAYLOAD_DIVERGENCE` alone | Structured columns no longer re-serialise to the stored payload | Raise a ticket. A schema change that added a field after the event was written produces this benignly; so does column-level tampering, so it still needs a human |

The chain is the layer that survives everything else. Triggers and revoked grants stop the
application and an ordinary operator; a superuser can disable a trigger or set
`session_replication_role = replica`. What they cannot do is rewrite one row without invalidating
every hash after it in that subject's chain — provided the sweep actually runs and someone actually
reads its output.

### 3.2 After every restore

Restoring from backup and *not* re-verifying leaves the group asserting the integrity of data it
has not checked. Run a full sweep as part of the restore procedure, before the service takes
traffic:

```bash
curl -u compliance-console:… -X POST http://localhost:8080/v1/admin/integrity/sweep
```

Record the result in the restore log. A sweep that was run and passed is evidence; a sweep that
was assumed is nothing.

### 3.3 Never repair by editing

There is no supported procedure for correcting a consent event, and that is the point. Consent is
corrected by **appending a compensating event** — a withdrawal, an invalidation with a reason and
an actor. An administrator who edits history leaves the ledger technically intact and practically
worthless.

---

## 4. Sweepers and the outbox

| Job | Property | Default | Notes |
|---|---|---|---|
| Expiry | `sweeper.expiry-interval` | 5 min | Writes durable `EXPIRED` events for lapsed artefacts. Decisions do **not** wait for it — `effectiveStatus` already treats a lapsed consent as expired — so a delay here costs evidence tidiness, not correctness |
| Integrity | `sweeper.integrity-cron` | 02:15 daily | §3 |
| Outbox relay | `events.relay-interval` | 2 s | Drains `event_outbox` to the broker |
| Rights SLA | `sweeper.rights-sla-interval` | 15 min | §4.1 |
| Breach SLA | `sweeper.breach-sla-interval` | 5 min | §9. Raises the two-stage Rule 7 obligations as they fall due. Five minutes rather than fifteen because the first leg is "without delay" and a quarter-hour of quiet is a quarter-hour of it |
| Retention | `sweeper.retention-interval` | 6 h | Proposes retention actions; **never deletes**. `sweeper.retention-notice-lead-time` (default 7 days) is the Rule 8 pre-erasure notice window |
| Re-confirmation | `sweeper.reconfirmation-interval` | 12 h | Korea only. Raises the Network Act Enforcement Decree Art. 62-3 two-year re-confirmation queue for KR entities. Disable with `sweeper.reconfirmation-enabled`; `sweeper.reconfirmation-batch-size` (default 500) bounds one pass |
| Partition maintenance | `sweeper.partition-cron` | 03:40 daily | Provisions `enforcement_decision` partitions three months ahead and detaches past the retention ceiling. **Absent from this table until Phase 17**, which is why the sentence below counted six sweeps against seven sweepers — the job nobody listed is the one whose silent failure has no symptom for a quarter, and then every denial fails to record |

Every job in this table has an `-enabled` flag of the same shape (`sweeper.expiry-enabled` and so
on) and all of them are now carried explicitly in `application.yml`. They were not: the three
re-confirmation properties were absent from the file entirely, so an operator disabling sweeps by
editing the block they could see would have left the Korean one running.

### 4.0 Leader election, and its silent failure mode

**The outbox relay is not one of these, and the arithmetic here was wrong twice over.** This
sentence said "six" against seven sweepers, and the table above listed seven *jobs* only because
partition maintenance was missing from it — so two errors happened to look consistent. Both are
fixed above. The substantive point: the relay takes **no** lock, so all three replicas
drain the same batch every two seconds — every subscriber receives each event up to three times and
`webhook_delivery` carries up to three rows per attempt. The class javadoc concedes duplicate
delivery for the crash window; systematic triplication as the steady state was described nowhere.
`propagation_gap` is idempotent under this by its daily unique key rather than by a lock, and
`for update skip locked` on `fetchUnpublished` is the real fix — scheduled on `ROADMAP.md` as its own
change, because putting the relay under `SweepLock` would serialise fan-out onto one instance.

All **seven sweeps** take a PostgreSQL **session advisory lock** (`pg_try_advisory_lock`) before doing
anything. Two replicas otherwise page on-call twice for the same statutory breach, and send a
principal two pre-erasure notices for the same record. A sweep that does not get the lock does
nothing and says so at DEBUG — that is the normal case on every instance but one.

⚠️ **The failure mode is silence.** An advisory lock is held by a *connection*, so a lock acquired
on one pooled connection and released on another is held forever by a connection nobody can
identify — after which every future sweep on every replica quietly does nothing, and the symptom is
indistinguishable from a sweeper with no work to do. `SweepLock` borrows a dedicated connection per
call and releases on it, which is why it does not use the shared `JdbcClient`; `SweeperAndRelayIT`
pins both the exclusivity and the release-after-a-throwing-sweep.

**What to monitor:** not the lock, but the work. If `uds.consent.rights.overdue` climbs while no
`RightsSlaSweeper` output appears in the log for an hour, or `uds.consent.outbox.pending` rises
steadily, assume nothing is running rather than that there is nothing to run. `select * from
pg_locks where locktype = 'advisory'` names the holding backend.

### 4.0a Propagation reconciliation — and the two artefacts that answer different questions

After each message is drained, `PropagationReconciler` compares it against `propagation_target` — the
register of systems that must be told — and records, once per system per day, any obligation the
platform could not show was met. It runs **after** `markPublished`, never between it and `publish`:
in between, a throwing evidence write would mark a delivered message failed and re-POST it to a
downstream system. It swallows its own failures for the same reason and counts them on
`uds.consent.propagation.failed_writes`.

**Two questions, two places to look, and confusing them is the trap.**

| Question | Where | Behaviour |
|---|---|---|
| *What is broken right now?* | `GET /v1/admin/propagation/targets`, `propagationUncovered` on `/actuator/health`, `uds_consent_propagation_uncovered` | Derived from the register. **Returns to zero** when an operator registers the missing subscription |
| *What did we fail to show, and when?* | `GET /v1/admin/propagation/gaps` | Append-only, one row per system per day. **Never returns to zero, and nothing alerts on it** |

**A persistently failing endpoint produces no gap rows, and this is the asymmetry to know about.**
A message that fails to deliver stays unpublished and the relay breaks on it, so the reconciler never
runs for that message at all. A downstream system that is *down* therefore shows up as `FAILED` rows
in `webhook_delivery` and as the relay's escalation after ten attempts — while a *configuration* error
(nobody registered, or a `system_code` typo) shows up in `propagation_gap`. Neither artefact alone
answers *"is DenCRM current?"*; read both.

**One row per system per day, and it names an exemplar rather than a census.** The gap's unique key
is `(entity, topic, system_code, detected_on)` — it does **not** include the subject — so the first
uncovered message of a day writes the row and later ones are discarded. The `subject_id` and
`event_type` on it therefore name *one* principal whose message went untold, not all of them. That is
a deliberate bound: admitting the subject would make growth targets × subjects × days, unbounded by
population. **The consequence to hold on to is that `propagation_gap` is register-level evidence, not
per-principal evidence**, and the evidence bundle's propagation section says so on the record rather
than implying otherwise.

**`NOT_DELIVERED` is rare in the shipped topology, and that is not a bug.** A delivery failure throws,
which leaves the message unpublished and means the reconciler never runs for it; a success writes the
`DELIVERED` row the coverage check looks for. So the three reasons are not three equally likely
outcomes — in practice you will see `NO_SUBSCRIPTION` (a configuration error) and
`NO_DELIVERY_CHANNEL` (the publisher cannot evidence anything), with `NOT_DELIVERED` reserved for the
narrow case where a subscription matches and no successful delivery was recorded for that message.

**Under the default `log` publisher the reason is always `NO_DELIVERY_CHANNEL`.** That is a fact about
this deployment, not about any downstream system: only the webhook publisher writes delivery
evidence, so the platform cannot observe arrival and does not pretend to. Switching
`uds.consent.events.publisher` to `webhook` is what turns the register from a configuration check
into evidence.

### 4.1 The rights-request SLA sweep

Every open rights request carries a deadline fixed at intake from its type and jurisdiction —
ten days under Korea's PIPA, thirty under GDPR, forty-five under CPRA, one day for a withdrawal.
The sweep compares them against the clock and logs:

| Level | Meaning | Response |
|---|---|---|
| `WARN` | Falls due inside `sweeper.rights-sla-warning-window` (default 3 days) | There is still time. Make sure it is assigned |
| `ERROR` | **Already past its deadline.** | Wake somebody. It repeats every pass until the request is closed, deliberately |

**`ERROR` does not always mean the group was late.** A request may be *born* overdue: a filing that
genuinely arrived late — a letter found in a postbag, a backlog imported after an acquisition — is
accepted with its real `receivedAt`, because refusing it is how an operator learns to file with
today's date and destroy the provenance. Its deadline can already have passed on the first sweep,
and the group had no opportunity to meet it.

The two are different facts and a response that treats them alike is wrong in both directions.
`RightsService` records `bornOverdue` on the intake's `admin_audit_event` detail; it is not yet on a
route or a metric (`ROADMAP.md`), so today the check is a query against that event. **Confirm which
one you have before escalating it as a breach.**

**Wire `ERROR` from `RightsSlaSweeper` to the on-call channel.** The failure mode this exists for
is not somebody deciding to miss a deadline — it is a request sitting in a queue nobody opened for
six weeks. That failure is silent by nature, so the countermeasure has to be noisy.

The deadline is **stored on the row**, not recomputed on read. Changing a period in
`StatutoryClock` therefore affects new requests only, and cannot retroactively turn a request that
was answered in time into a breach, or the reverse. That is the property that makes the record
usable as evidence.

⚠️ **The Indian periods in `StatutoryClock` are the group's own undertaking, not a statutory
figure** — the DPDP Act leaves the response period to be prescribed. They must be reconciled
against the published privacy notice and signed off by legal before go-live. A deadline the
platform believes in and the notice contradicts makes the group's own records the evidence against
it.

Set `uds.consent.events.publisher` to `kafka` **before any downstream system depends on hearing
about withdrawals.** The default is `log`, which needs no broker and is right for a developer
machine and wrong the moment a dialer subscribes.

Monitor `event_outbox` pending depth. A rising backlog means downstream systems are working from
stale consent state — the exact condition in which a dialer keeps calling someone who has opted
out.

---

## 5. Suppression registries

Registry scrubbing is **not** satisfied by holding a valid consent record, and is not a one-time
list-build activity. Scrub before every send.

| Registry | Jurisdiction | Channel |
|---|---|---|
| NCPR / DND | India (TRAI) | Voice, SMS |
| DNC Registry | Singapore | Voice, SMS, WhatsApp |
| TPS / CTPS | United Kingdom | Voice |

Load exports through `SuppressionService.loadStatutoryRegistry`, which hashes the identifiers on
the way in so no plaintext number reaches the store. Statutory entries are GLOBAL in scope and
cannot be overridden by a consent record.

Separately, **DLT registration** (headers and templates) is mandatory for A2P SMS in India
regardless of consent. Since the February 2025 amendment work the decision response does not merely
say "use a registered header" — it **names the registered header and template** from
`dlt_header` / `dlt_template` for the entity and purpose, so the sending system has no reason to
guess and no room to substitute. Honouring them is still the sender's job: nothing in this platform
can put a header on somebody else's SMS.

Register them through `PUT /v1/admin/dlt/headers/{headerId}` and
`PUT /v1/admin/dlt/templates/{templateId}`; read them with `GET /v1/admin/dlt/registrations`.
A purpose with no registered header for its entity produces a decision that allows the processing
and carries no header to use, which is a configuration gap the sender will discover at send time —
review `GET /v1/admin/dlt/headers?entityId=…` per entity before go-live.

---

## 6. Service level objectives

> ⚠️ **Measured on 17 August 2026, and the result splits in two.** See `docs/CAPACITY.md` §7 for the
> full run; the operational summary is here because it changes what you should tell a client.
>
> **The decision engine meets the objective with room to spare: p50 1.3 ms, p95 2.6 ms against a
> published 30 ms.** That is the platform's own work — eight policy gates, the entity guard, the
> row-level-security claim and the enforcement write — and it is eleven times faster than the number
> it commits to.
>
> **What a caller experiences is p95 115 ms, and about 110 ms of that is one BCrypt password
> verification per request.** Basic auth re-hashes on every call by design; a **401 with a wrong
> password costs the same 113 ms as a successful decision**, which is what proves where the time is.
> The single-request ceiling is therefore about **50 requests/second per instance**, and it is a CPU
> limit in the authentication filter, not a database limit — HikariCP saw zero pending connections
> and zero timeouts throughout.
>
> **So: the SLO is achievable and is not currently achieved, and the fix is authentication, not
> tuning.** Moving integrators onto the OIDC resource server already built in Phase 11 replaces a
> 110 ms BCrypt with a sub-millisecond signature check. Until then, quote the 30 ms as an
> **objective**, and tell Denave to use `POST /v1/evaluate/batch` — which amortises the one
> authentication over a thousand decisions and measured **~5,400 decisions/second**, a hundred times
> the single-request route.
>
> The objective is also **watched**: `DecisionLatencyOverObjective` in
> `deploy/observability/alerts.yaml` fires on p95 over 30 ms held for ten minutes, against the
> `uds_consent_decision_seconds` histogram — which is the *server-side* number, so note that it
> watches the 2.6 ms figure and will not fire on the 115 ms a client sees. That gap is deliberate
> and is called out in `CAPACITY.md` §7; closing it means an alert on the ingress, which UDS does not
> yet have.
>
> **Not measured:** replica scaling, the JWT path, and anything at production hardware scale. The
> run was on one laptop with the load generator, the service and the database sharing eight cores,
> so the absolute numbers are not quotable — the *ratios* between them are, and they are what the
> paragraphs above rest on.


| Path | Target | Why it is set there |
|---|---|---|
| Local snapshot evaluation | p95 < 1 ms | It is on the field app's critical path; anything slower gets routed around |
| Decision API | p95 < 30 ms | Athena's dialer pre-flights every call |
| Control plane reads | p99 < 100 ms | Admin console responsiveness |
| Availability | 99.99% | Below this, teams build local caches, and local caches of consent state are how a group ends up with five answers |

**One of the four is enforced by the build. The other three are monitoring-only, and the difference
matters.** "We have SLOs" and "we would notice" are different claims, and until `DecisionLatencyIT`
was written the platform was making the first while only being entitled to the second —
`ObservabilityIT` asserted that a decision was *timed*, and nothing anywhere asserted the time.

- **Decision API — enforced.** `DecisionLatencyIT` runs a few hundred decisions against a real
  Postgres and asserts two things. A **p95 floor of 150 ms**, deliberately five times the published
  objective: the objective is for production hardware, and a test asserting 30 ms on a
  Testcontainers database under a cold JVM would fail on CI for reasons unrelated to the code, get
  marked flaky, and then get deleted. What the floor catches is the order-of-magnitude regression.
  And a **round-trip count capped at the measured six per decision** — a ceiling
  (`isLessThanOrEqualTo`), not an equality, so a decision that drops to five passes silently and
  only a seventh fails the build. That is the direction with the consequence, and it is worth
  stating precisely because "pinned at exactly six" is what this paragraph used to say and it was
  not true. Measured rather than allowed for,
  which is the assertion that earns its place — it is identical on every machine, it is what
  regressed when the Korean re-confirmation lookup was added to the hot path, and it says *why* a
  latency failure happened rather than only that it did. The batch path is pinned separately against
  super-linear growth. **If the two disagree, trust the count.**
- **Local snapshot evaluation, control-plane reads, availability — monitoring only.** No test
  asserts these. The first is a pure in-memory evaluation with no I/O and is not where a regression
  would hide; the other two are properties of a deployment rather than of this repository.

Raising the pinned count is a legitimate change. Raising it without noticing is what the pin exists
to prevent: Athena's dialer pre-flights every outbound call against this endpoint, so a query added
to the decision path is paid per contact, not per deploy.

Signed snapshots keep the decision API off the hot path for field apps. If the decision API becomes
a bottleneck for server-side callers, split it out — it already talks to the rest of the system
through `PolicyPorts`, so the split is a deployment change rather than a rewrite.

`uds.consent.snapshot.validity` (default 15 minutes) is a genuine trade-off, not a tuning knob:
long-lived snapshots keep a field force working through a day with no connectivity; short-lived
ones bound how long a withdrawal can go unseen by a device that has been offline. Denave's field
apps override it upward and accept that in exchange for working at all in places with no signal.
Record the chosen value and the reasoning, per application, in the application registry.

---

## 7. Policy and taxonomy changes

`uds.consent.policy-version` is stamped on every decision. **Bump it whenever the purpose registry
or a jurisdiction rule changes**, so a decision taken last March can be identified as having been
taken under the rules in force then. Failing to bump it does not break anything today; it destroys
the ability to reconstruct an answer during an audit years from now, which is the one thing the
evidence plane exists for.

Purpose and notice versions are immutable once published — the database enforces it. A material
change means a **new version**, and `BlastRadiusService` then classifies the consequence:

| Classification | Meaning |
|---|---|
| `NO_ACTION` | Wording or formatting; standing consent still covers the processing |
| `NOTICE_UPDATE_ONLY` | Subjects must be shown the new notice; consent stands |
| `RE_CONSENT_REQUIRED` | Standing consent no longer covers what will be done |

Run the blast-radius calculation **before** publishing, not after. Publishing first and asking
afterwards means the window between the two is a window of processing on consent that no longer
covers it.

The in-process purpose cache refreshes on a timer and immediately on the instance that served an
admin publish. Other instances converge within the refresh interval. That is the accepted cost of
not putting Redis on the decision path; it is bounded and visible, unlike a network hop and a new
failure mode on the platform's most latency-sensitive call.

---

## 8. Environment checklist

Before a deployment is considered production-ready:

- [ ] `uds_consent_app` role exists and V2's revocations verified present (§1.1)
- [ ] Flyway configured to run as the owner, service configured to run as the app role
- [ ] Identifier pepper set from the KMS. **Length is now a gate, not a warning** — the service
      refuses to start below 32 characters as well as on an absent one (`PolicyConfiguration`).
      The box still wants a person to look at the *value*: 32 characters of a memorable sentence
      passes the check and defeats the purpose
- [ ] `X-UDS-Actor` sent by the compliance console on every administrative change. The platform
      refuses the mutation without it (400) — so a console that does not send it is not a
      degraded console, it is a broken one. Confirm the console populates it from the signed-in
      user and not from a constant. **Not required once the console holds a bearer token**, where
      the human comes from a signed claim and this header is ignored (§12.5)
- [ ] If OIDC is configured: the issuer's client registrations set **`entity_id`** on every scoped
      credential. **Absent means group level** — a token intended for Denave reads every entity in
      the group, through both isolation layers, silently (§2.3)
- [ ] Rate limits reviewed against expected traffic (§12). They are **per instance**: N replicas
      allow N times the configured numbers in aggregate
- [ ] Liveness and readiness pointed at `/actuator/health/liveness` and
      `/actuator/health/readiness` respectively, **on port 9090**, and **not** both at
      `/actuator/health` (§12.1)
- [ ] The scrape target is `:9090/actuator/prometheus`, the `NetworkPolicy` admits only the
      monitoring namespace on that port, and `deploy/observability/alerts.yaml` is applied. The
      scrape path needs no credential, so that policy **is** the access control (§11.3)
- [ ] Something consumes `rights.verification.requested` and sends the code. Without it every
      data-principal portal submission expires unverified and the Rule 14(1) page is a dead end
      (§12.6)
- [ ] `json-logging` profile active wherever there is a log aggregator
- [ ] Deployment from `deploy/k8s/`, secrets injected from the group's secret manager and **not**
      from `secrets.example.yaml` — every value in it is a placeholder that will refuse to start
- [x] WAL archiving on, PITR configured, and the restore procedure in `docs/RUNBOOK_DR.md`
      **rehearsed at least once**, ending in a full chain verification. Rehearsed 17 August 2026
      (`RUNBOOK_DR.md` §5.1): point-in-time recovery exact to the transaction, 17,302 chains verified,
      three defects found in the DR machinery and fixed (§5.2). **Ticked for the laptop stack only** —
      the rehearsal on the group's own infrastructure is a separate exercise and this box goes back to
      unticked for it
- [ ] RPO and RTO in `RUNBOOK_DR.md` §2 ratified by UDS or replaced. They are currently proposals,
      and the rehearsal deliberately did not ratify them: it measured a laptop restoring from local
      disk with no instance to provision and no incident to diagnose
- [ ] `max_connections` on the database checked against `DB_POOL_SIZE` × `maxReplicas`
      (`docs/CAPACITY.md` §3). At the HPA ceiling that is 240 against a default of 100 — scaling out
      without raising it turns a latency problem into a connection-refused outage
- [x] `perf/k6/` run against a seeded, deployed instance and the results recorded in
      `docs/CAPACITY.md` **§7** (not §6, which is what this line used to point at). Run 17 August
      2026 against 1,000,000 subjects. **Re-run on the group's hardware before quoting any number in
      a client contract** — §7 says what the laptop run is and is not evidence for
- [ ] Authentication cost on the decision path addressed before Denave's projected volume.
      `CAPACITY.md` §7 measured the engine at 2.6 ms p95 and the client at 115 ms, of which ~110 ms is
      BCrypt re-hashing the credential on every request: a ~50 rps/instance ceiling that is CPU, not
      database. The fix is the authentication scheme, not tuning — JWT (§12.5) does not re-hash
- [ ] `fulfilment_target` register populated per entity and per request type, and the scope
      statement in `REGULATORY_HANDOFF.md` §8.5 signed. Until then `FULFILLED` can still be asserted
      without evidence
- [ ] `uds.consent.events.publisher` set to `kafka` or `webhook`. On `log`, a withdrawal reaches
      nobody and every downstream system is silently dependent on remembering to ask
- [ ] Snapshot signing key pair set from the KMS; key id recorded; rotation overlap procedure agreed
- [ ] API client credentials sourced from the secret store, none blank
- [ ] `events.publisher` set to `kafka` if any downstream system consumes consent events
- [ ] Integrity sweep scheduled, alerting wired to §3.1, output routed to a human
- [ ] Outbox pending-depth alert configured
- [ ] Backup restore procedure includes a full chain verification (§3.2)
- [ ] Statutory registry loads scheduled for every jurisdiction the entity operates in (§5)
- [ ] Data residency confirmed for each entity's scope (§1.2)
- [ ] Rights-SLA sweep enabled and its `ERROR` level routed to on-call (§4.1)
- [ ] Indian statutory periods in `StatutoryClock` reconciled with the published notice and signed
      off by legal (§4.1). **DPDP Rule 14(3) requires the grievance response period to be
      prominently published and caps it at ninety days.** The platform's 30 sits inside the cap, so
      this is not a code change — but publishing one figure and operating another is now a breach
      rather than an inconsistency
- [ ] **Rule 14(1): the identifiers a principal must supply to exercise a right are decided and
      published**, alongside the means (which the notice already carries as `rightsUri`,
      `grievanceUri` and `withdrawalUri`). Not modelled deliberately — how hard it is to exercise a
      right is a policy decision, not a default for code to pick. See `REGULATORY_HANDOFF.md` §4
- [ ] Every capture surface registered in `application_registry` for the right entity and
      environment — an unregistered id is refused, and a staging build pointed at production is
      exactly what this catches
- [ ] Notice language coverage reviewed per entity (`GET /v1/notices/reports/coverage`); the
      shortfall is a translation procurement with an owner and a date, not a backlog item
- [ ] Vendors with no signed DPA reviewed (`GET /v1/admin/vendors`, and the gap list on every RoPA
      export). Registration is permitted without one so the relationship stays tracked; leaving it
      unpapered is not
- [ ] **Row-level security verified, not assumed.** It shipped in `V13__row_level_security.sql`
      and applies to every entity-scoped table. Verify two things in each environment, because the
      way this control fails is silent:
      `select relname, relrowsecurity from pg_class where relname = 'consent_event';` must be true,
      and `select tableowner from pg_tables where tablename = 'consent_event';` must **not** be the
      role the application connects as. Policies do not apply to a table's owner, so an environment
      where the app connects as the owner has every policy bypassed and looks completely normal.
      **Both checks are now enforced by `RowLevelSecurityIT` as well**, over every table `V13` and
      `V16` name — a check that lives only in a runbook is a check that gets done once, and this one
      had never been done at all until the suite was written. Keep the manual step for production,
      where the build does not run
- [ ] Application connects as `uds_consent_app` (or an equivalent non-owner role) and Flyway
      connects as the owner — two roles, two URLs, `spring.datasource` vs `spring.flyway`
- [ ] Consent Manager register (`GET /v1/admin/consent-managers`) reviewed before 13 November
      2026: the three `CM-TEST-…` rows seeded by `V14` and `V15` are fixtures, not Board
      registrations, and must be set to `DEREGISTERED` in production —
      `PUT /v1/admin/consent-managers/{registrationId}/status` with a reason, which is audited.
      Deregistration rather than deletion: a registration that has ever relayed is referenced by
      live consents, and removing the row would orphan their provenance
- [ ] Register reconciliation has an owner and a cadence. The register is a **copy** of the Board's,
      with no feed to poll, so `POST /v1/admin/consent-managers/{registrationId}/reconciled` records
      that a named person compared the two. `/actuator/health` reports
      `consentManagerRegisterLastReconciled` (the oldest across the register) and
      `consentManagersNeverReconciled` (listed by name, because a count of three tells nobody which
      three). The failure this prevents is honouring a relay from a Consent Manager the Board
      suspended last month, which looks exactly like normal operation until somebody asks. The
      `CM-TEST-…` fixtures deliberately stay unreconciled, so they keep appearing here until the
      line above is done
- [ ] The **Korean re-confirmation queue has a named owner**. `/actuator/health` reports
      `koreanReconfirmationsOverdue`; `GET /v1/admin/reconfirmation/due?entityId=…` lists them.
      The platform raises the obligation under Network Act Enforcement Decree Art. 62-3 and
      **cannot send the message** — sending is a marketing-systems job, and recording what was
      sent is `POST /v1/admin/reconfirmation/{id}/sent`, which requires all three of Art. 62-3(2)'s
      disclosures because the obligation is not "we sent something" but "we sent something
      containing these three things". Live from **1 October 2026**
- [ ] **Read the note below before treating that counter as an outage.** An overdue Korean
      re-confirmation does **not** expire the consent and does **not** deny a decision. The Decree
      fixes the interval and the disclosure and is silent on what a recipient's silence means, so
      the platform declines to decide it — see `REGULATORY_HANDOFF.md` §2. A non-zero counter is
      an obligation somebody owes, not a consent being relied on unlawfully, and the next person to
      see it will otherwise assume it is a bug and "fix" it into a denial
- [ ] **If any UDS entity is notified a Significant Data Fiduciary**: set
      `fiduciary_entity.significant_fiduciary`, register the entity's algorithmic systems
      (`PUT /v1/admin/sdf/{entityId}/systems`), and raise the cycle with the designation date
      (`POST /v1/admin/sdf/{entityId}/raise?designatedFrom=YYYY-MM-DD`). Name who conducts the
      annual DPIA and who conducts the independent audit — they must be different people, and the
      audit's independence is the point of it. `/actuator/health` reports `sdfObligationsOverdue`;
      it reads zero today because no entity is designated, not because nothing is checked
- [ ] `uds.consent.enforcement.failed_writes` alerted on **anything above zero**. It means the
      platform is currently unable to prove decisions it is taking — a compliance incident on a
      delay, and the kind that goes unnoticed without a number attached to it

---

## 9. Breach runbook

The one runbook that gets read under time pressure with a clock already running. Read §4.0 first if
nothing has alerted — a silent breach sweeper looks exactly like no breaches.

### 9.1 The clock starts on awareness, not on occurrence

Every regime here keys its deadline to when the group **became aware**, so a breach found in a log
review three weeks later starts its countdown at the review. Record the awareness instant honestly
and record who formed it; it is the single most consequential field on the record and the one an
investigation will test hardest.

⚠️ **Korea is different and the difference is easy to miss.** Since the PIPA amendment in force
**11 September 2026**, the obligation is triggered by a *reasonable likelihood* of a breach, not by a
confirmed one — and the definition now covers forgery, alteration and damage as well as loss, theft
and disclosure, so a ransomware event that encrypts without exfiltrating is reportable. An incident
team behaving reasonably by its own lights, waiting to confirm before starting the clock, is three
days late by the time it does.

### 9.2 Steps

1. **Report it into the platform immediately**, before it is understood:
   `POST /v1/admin/breaches` with the entity, the awareness instant, the affected data categories
   and purpose codes. An incomplete record that exists beats a complete one filed on day three.
2. **Read the obligations back.** `GET /v1/admin/breaches/{breachId}` returns them per party with
   their deadlines, derived by `BreachClock` from the jurisdiction. Two-stage regimes show two rows,
   and the first has no hours on it.
3. **Assess** — `POST /v1/admin/breaches/{breachId}/assess`. This is the judgement about likely
   harm, and it is a human's.
4. **Get the affected population** — `GET /v1/admin/breaches/{breachId}/affected`. Computed **as at
   the breach instant**, deliberately: consent withdrawn after the breach does not remove somebody
   from the set of people whose data was exposed.
5. **Notify**, and record each notification as it goes out:
   `POST /v1/admin/breaches/{breachId}/notifications/{notificationId}`. The record of *when* you
   notified is the evidence; an email nobody logged did not happen as far as an investigation is
   concerned.
6. **Close** — `POST /v1/admin/breaches/{breachId}/close`, with the resolution.

### 9.3 Who has to be told, and by when

| Jurisdiction | Regulator | Data principals |
|---|---|---|
| India (DPDP Rule 7) | **Without delay** on becoming aware, then a **detailed report within 72 hours** | **Without delay** — the same first leg, and the one a single 72-hour countdown hides for three days |
| EU / UK (GDPR Art.33/34) | 72 hours, unless unlikely to risk rights and freedoms | Without undue delay where the risk is high |
| Korea (PIPA Art.34, as amended) | **72 hours** to the PIPC or KISA where the breach touches ≥1,000 principals, sensitive or unique identifying information, or follows unlawful external access | **72 hours**, unless there is a justifiable reason for delay |
| Malaysia (PDPA s.12B) | 72 hours to the Commissioner | Where significant harm is caused or likely |
| Singapore (PDPA) | Assess within 30 days; notify within **3 calendar days** if notifiable | With the regulator |

An unmapped jurisdiction takes the shortest period on the list. If the deadline is going to be
wrong, it should be wrong early.

### 9.4 What the platform will not do for you

It does not detect breaches, decide whether harm is likely, or send a notification. It holds the
clock, computes who was affected, and records what was done — which is exactly the part that is
hardest to reconstruct afterwards and the part nobody has time for on the day.

---

## 10. What the platform deliberately does not decide

Positions that look like gaps and are not. Each was a live choice, each has a reason, and each is
the kind of thing a well-meaning change would quietly reverse.

**An unanswered Korean re-confirmation does not expire a consent.** Network Act Enforcement Decree
Art. 62-3 fixes the two-year interval and the three disclosures and says nothing about the effect
of silence. Industry practice treats silence as maintaining consent; practice is not statute.
Denying would suppress lawful contact on the platform's own authority, and treating silence as
consent without saying so would hide the question. So the decision allows and carries the
obligation `reconfirmation-overdue`, the counter sits on `/actuator/health`, and the one-sentence
question sits with counsel in `REGULATORY_HANDOFF.md` §2. `ReconfirmationIT
.anOverdueConfirmationDoesNotDenyTheDecision` exists to make reversing this deliberate.

**No data category is restricted from leaving India, because none has been notified.** DPDP **Rule
13(4)** lets the Government, on the recommendation of the Rule 13(5) committee, name categories a
Significant Data Fiduciary may not transfer abroad at all. `data_category.transfer_restricted` is
false on every row and the RoPA cross-border report already reads it. The empty list is a checked
absence, not an unchecked one, and it is asserted as such by a test — so the day a notification
arrives, honouring it is an `UPDATE` and not a release. The general restriction on transfer outside
India is **Rule 15** and binds every Data Fiduciary, not only Significant ones; that is the
`crossBorderTransfers` list rather than `prohibitedTransfers`. Cited as "Rule 14" until `V21`, which
records the correction — Rule 14 is rights, publication and grievance redressal.

**Rule 13(4)'s traffic-data limb is out of scope, deliberately.** The rule restricts "the personal
data and the traffic data pertaining to its flow". This platform models the personal data half, by
category. Traffic data — which carrier a message travelled through, when, to which destination — is
not a data category and is not held here at all: this is a consent evidence plane, not a message
log, and the DLT registry knows header and template registrations rather than deliveries. Whoever
operates the CPaaS and dialer logs owns that limb. Stated so nobody reads the hook as covering it.

**A subject with no age assertion is not treated as an adult.** The decision path asks
`isChildAt(entityId, subjectId, at)` — the age as at the decision instant, so an audit replay
answers the question the engine actually faced rather than today's. Where nothing had been asserted
by that instant the lookup returns *empty*, which is a different fact from `false`, and the wiring
falls back to `subject.is_child` rather than defaulting to "adult". Every subject captured before
the assertion table existed is in that state, and a bare `.orElse(false)` there would have silently
un-protected the whole existing population. `GuardianVerificationIT
.aSubjectPredatingTheAssertionTableIsStillProtected` builds its subject directly rather than through
a capture, because every capture written since the table landed leaves an assertion behind and a
capture-built fixture would pass the test without testing anything. The fallback is a migration
artefact and should shrink on its own; **it is not to be removed until the pre-assertion population
is gone.**

**The evidence bundle's completeness is enforced, not remembered.** It claims to hold everything the
platform has about one person, and that claim decays every time a subject-scoped store is added by
someone thinking about the obligation in front of them. `EvidenceBundleIT
.theBundleIsCompleteAndStaysComplete` enumerates tables carrying both `entity_id` and `subject_id`
out of `information_schema` and fails when one is neither carried by the bundle nor listed with a
written reason for its absence. Three tables are deliberately absent and each says why in the test:
the chain head (represented by the integrity verification), `subject` (the mutable read model, whose
evidence is the assertion table), and `subject_identifier` (correlatable hashes that tell the holder
of the file nothing they do not already know). **Adding a subject-scoped table means answering this
test one way or the other.**

**The platform never infers that a subject is an adult.** A capture that says nothing about age is
silence, not a declaration, and no age assertion is written for it. Only a surface that positively
declares a child produces a row — with a source, a date and the guardian diligence behind it.
Filling the table with assertions nobody made would destroy the only thing it is for, which is
being able to say who told the group what, and when.

---

## 11. Correlation ids and metrics

`ApiExceptionHandler` tells callers to quote a trace id. This is where it points.

### 11.1 The correlation id

Every response carries **`X-Correlation-Id`**. If the caller sends one it is echoed; otherwise the
platform mints a UUID. The same value is in the MDC for every log line the request produced, so
`correlationId=<value>` in the log aggregator returns the request end to end.

It is sanitised before use — a header is caller-controlled, and an unsanitised one is a log-injection
vector and a way to put a newline into a response header.

Ask integrators to log the value they received. A support conversation that starts with a
correlation id ends in minutes; one that starts with "it failed this afternoon" does not.

### 11.2 Metrics worth an alert

| Metric | Type | What it means | Alert |
|---|---|---|---|
| `uds.consent.enforcement.failed_writes` | gauge | Evidence writes that failed since start-up | **> 0.** The platform is taking decisions it cannot prove |
| `uds.consent.outbox.pending` | gauge | Undelivered consent events | Rising steadily — downstream systems are working from stale consent state, which is the exact condition in which a dialer keeps calling someone who opted out |
| `uds.consent.rights.overdue` | gauge | Rights requests past their statutory deadline | **> 0.** Each one is a breach that has already happened |
| `uds.consent.decision` | timer | Decision latency | p95 against the §6 objective |
| `uds.consent.decision.outcome` | counter | Tagged by outcome and reason | A sudden shift in the reason mix is usually a configuration change, not a change in what subjects want |
| `uds.consent.capture` / `.capture.outcome` | timer, counter | Capture latency and accept/reject | A rejection spike means a capture surface is sending something invalid, and every one of those is consent not recorded |
| `uds.consent.scrub` / `.scrub.identifiers` | timer, counter | Scrub latency and submitted/excluded counts | Coverage: a campaign that never scrubbed produces no counter movement at all |

Three for propagation and the rights clock, added in Phase 17:

| Metric | Type | What it means | Alert |
|---|---|---|---|
| `uds.consent.propagation.uncovered` | gauge | Mandatory `propagation_target` rows that no active subscription can reach | **> 0 for 30m, critical.** A system the group declared must be told about a withdrawal, that nothing can tell. Read over the *register*, so it returns to zero when the configuration is fixed — which is what makes it alertable at all |
| `uds.consent.propagation.failed_writes` | gauge | Gap records the reconciler could not write | **> 0.** Consent changes are going out and what could not be shown to have arrived is not being recorded. The reconciler swallows these deliberately so an evidence write can never re-POST a delivered message; this is where they surface |
| `uds.consent.rights.unverified_open` | gauge | Open rights requests whose clock started on an instant nobody verified | Threshold is **UDS's to set** (§8.6). `UNVERIFIED` refuses nothing by design; the *share* is what was undertaken to be watched. `V30` built the index for this question in Phase 16 and nothing asked it until now |

**Nothing alerts on `propagation_gap`.** It is append-only history and its count only grows, so a
rule over it would fire forever and be muted within a week — which is precisely how the first design
of this phase would have failed. Read it through `GET /v1/admin/propagation/gaps` when investigating,
not from a pager.

Two more, added because both are silent until they are catastrophic:

| Metric | Type | What it means | Alert |
|---|---|---|---|
| `uds.consent.partition.months_ahead` | gauge | Months of `enforcement_decision` partitions provisioned beyond the current one | **< 1.** The sweeper provisions three ahead and never drops, so this only falls because the job stopped. At zero, every denial fails to record |
| `uds.consent.signing_key.age_days` | gauge | Age of the oldest ACTIVE snapshot signing key | **> 90**, per §2.2. Nothing breaks on day 91 — which is exactly why it needs watching, since a control whose violation has no symptom is one that quietly stops being followed |

### 11.3 Scraping, and the port it is on

`/actuator/prometheus` is served on the **management port, 9090** — not on the traffic port.

That is a security boundary rather than tidiness. The series above name denial reasons, capture
volumes and rights-queue depth: an accurate operational picture of a regulated system, served
without a credential because that is what a scraper expects. On 8080 it would be one ingress rule
away from the public internet.

Everything moves with it or the deployment crash-loops — `deploy/k8s/deployment.yaml`'s probes and
`containerPort`, `deploy/k8s/service.yaml`, the `Dockerfile` HEALTHCHECK, and
`docker/docker-compose.yml`. Health is **no longer served on 8080**, so a probe left pointing there
fails on every pod, forever, and the deployment never becomes ready.
`MetricsEndpointIT.prometheusIsNotOnTheTrafficPort` asks as an authenticated administrator and
asserts 404, which proves the endpoint is not mapped there rather than merely blocked.

The `NetworkPolicy` in `service.yaml` admits port 9090 from the `monitoring` namespace and nothing
else. Since the scrape path needs no credential, **that policy is the access control** — check it
before assuming the port split alone is sufficient. Endpoints that are genuinely dangerous — heap
dumps, environment, loggers — require ADMIN even there, because "not routable" is a property of a
deployment and the security configuration deliberately does not assume every deployment gets it
right.

### 11.4 Alert rules

`deploy/observability/alerts.yaml` — a `PrometheusRule` covering §11.2 and §11.3.

The table above has listed the metrics worth an alert since the instrumentation work, and until now
that was a description of an intention: nothing scraped them and nothing alerted on them. The rules
fire on conditions where a named thing is wrong, never on traffic being unusual — a rule nobody
trusts is a rule everybody silences, and then a real one is silenced with it.

Thirteen rules as of Phase 17, which added `PropagationTargetUnreachable` (critical),
`PropagationEvidenceWriteFailing` and `UnverifiedRightsRequestsOpen`. The first is the only critical
rule in the file besides the ledger and evidence ones, and deliberately so: a processor the group
cannot notify is a DPDP s.6(6) duty that cannot be discharged for as long as it holds.

The last rule in the file is `ConsentPlatformScrapeFailing`, and it guards every other: none of them
can fire while the target is not being scraped, and an alerting stack that has gone quiet looks
exactly like a healthy one.

---

## 12. The front door

### 12.1 Liveness and readiness are different questions

They used to be answered by one endpoint, which is a deployment hazard rather than an untidiness.
`PlatformHealthIndicator` reports `DOWN` when the integrity sweep finds a broken hash chain —
correct, and exactly the wrong thing to hang a *liveness* probe on. An orchestrator would restart
the pod, the sweep would find the same broken chain, and the platform would crash-loop over a data
condition no restart can fix, taking the decision API down with it.

All three are on the **management port, 9090** — not the traffic port. A probe left pointing at 8080
fails on every pod, forever, and the deployment never becomes ready.

| Probe | Path | Includes | Meaning |
|---|---|---|---|
| Liveness | `:9090/actuator/health/liveness` | process state only | The JVM is alive. Restart if this fails |
| Readiness | `:9090/actuator/health/readiness` | database, ledger integrity | This instance should receive traffic. **Drain, do not restart** |
| Aggregate | `:9090/actuator/health` | everything | For a human, not for an orchestrator |

A broken chain therefore drains the instance and pages somebody. It does not restart it. The
container `HEALTHCHECK` points at readiness on 9090 for the same reason.

### 12.2 Rate limits

There were none, anywhere — including on `GET /v1/notices/*` and `GET /v1/keys`, which need no
credential at all. Both are public deliberately and correctly, which is exactly what made them the
two routes anyone could hold open. The authenticated case was sharper: `POST /v1/evaluate/batch`
caps at a thousand identifiers per call and had no cap per second, so a dialer in a retry loop was
an outage.

Configured under `uds.consent.rate-limit`, per route class, as a token bucket:

| Class | Routes | Default | Keyed by |
|---|---|---|---|
| `public-routes` | `GET /v1/notices/*`, `GET /v1/keys` | 20/s, burst 60 | client IP |
| `decision` | `POST /v1/evaluate` | 200/s, burst 400 | credential |
| `batch` | `POST /v1/evaluate/batch` | 10/s, burst 20 | credential |
| `capture` | consent, provenance, suppression, rights | 100/s, burst 200 | credential |
| `admin` | `/v1/admin/**` | 50/s, burst 100 | credential |

Refusals are RFC 7807 with `Retry-After: 1`, counted at `uds.consent.ratelimit.refused` (tagged by
route class only) and logged at `WARN` with the caller. `/actuator` is exempt — a limiter that
refused a readiness probe would drain a healthy instance, turning a defence against overload into
a cause of one.

**There is a second limiter, in front of authentication, and the two are not interchangeable.**

The table above is enforced *behind* Spring Security, because a per-credential ceiling needs the
credential. The 17 August 2026 load run (`CAPACITY.md` §7) measured what that costs: 500 requests
carrying an invalid credential produced 500 × 401 and **zero** 429s. Spring Security's chain is
ordered −100 and `RateLimitFilter` is `LOWEST_PRECEDENCE - 120`, so authentication ran first — and
authentication is a BCrypt hash costing ~113 ms of CPU per attempt, on every request, with no session
and no credential cache. An unauthenticated caller could saturate an instance without ever reaching a
bucket that would have refused them, and every refusal cost the platform more than the attempt cost
the attacker.

`PreAuthRateLimitFilter` now runs at `SecurityProperties.DEFAULT_FILTER_ORDER - 10`, ahead of the
security chain:

| Class | Routes | Default | Keyed by |
|---|---|---|---|
| `pre-auth` | everything except `/actuator` | 400/s, burst 800 | client address |

**It is a flood ceiling, not a fairness limit, and the default is loose on purpose.** Running before
authentication means it cannot know who is calling, so it keys on the client address alone — behind a
corporate NAT, or an ingress without `server.forward-headers-strategy` configured, that is one bucket
for an entire building or for the whole fleet's traffic. 400/s comfortably exceeds Denave's dialer at
its ordinary 200/s while staying far below what a flood attempts. **Tightening it to feel more
protective is how a legitimate integrator gets refused at 09:00 on a Monday.** Fairness between
callers stays in the table above.

The property to check after any change to filter ordering is not that a 429 appears — that would be
satisfied by the old filter. It is that **a request with a deliberately invalid credential, over the
ceiling, is answered 429 and not 401**: reaching the credential check at all produces the 401.
`PreAuthRateLimitIT.theRefusalPrecedesAuthentication` asserts exactly that.

**The ingress or WAF bucket is still worth having**, and is now defence in depth rather than the only
defence: an instance that refuses a flood cheaply is still an instance receiving it. What has changed
is that the platform no longer depends on infrastructure it cannot prove is configured. **Do not lower
the BCrypt strength to make authentication cheaper** — that weakens every stored credential to buy
throughput on a path that should not be doing the work at all; the durable fix is JWT (§12.5), which
does not re-hash.

**Three things to know before tuning them.**

1. **Per instance, not fleet-wide.** Four replicas allow four times these numbers in aggregate, and
   a caller pinned to one instance is limited harder than one spread evenly. It still bounds what
   one runaway client does to one instance, which is the failure being defended against. Fleet-wide
   limiting needs shared state; when there is a Redis, the counter moves there.
2. **`X-Forwarded-For` is deliberately ignored.** It is caller-supplied, so trusting it would let
   anyone reset their own bucket by inventing a header. Behind a trusted proxy, set Boot's
   `server.forward-headers-strategy` — that fixes the remote address for everything at once,
   rather than this one filter believing something the rest of the platform does not.
3. **Set `enabled: false` where a gateway already does this.** Two limiters in series produce a
   refusal nobody can attribute to either. The switch turns off both filters together.

### 12.2a Assembling one person across the group

`GET /v1/admin/evidence/subject/{entityId}/{subjectId}` answers for **one entity**. A Board complaint is
about a *person*, and a person may appear under more than one of the fifteen entities.

**There is deliberately no group-level route**, and this is not a gap waiting to be filled. A route
returning one person's records across every entity would have to bypass `EntityAccessGuard` and the
row-level-security session claim at the same time — the exact hole Phase 11 spent a defect learning
to close, rebuilt on purpose and reachable over HTTP. The isolation model is worth more than the
convenience.

So the assembly is an SOP, performed by the compliance team:

1. Take the entity list from `GET /v1/admin/entities`. Do not work from memory; the group has
   fifteen and acquisitions change that.
2. For each entity, call the bundle **under that entity's own scope** — a credential or token scoped
   to it. A call that succeeds for an entity the caller is not scoped to is a defect to report, not
   a shortcut to use.
3. **Read each bundle's `truncation` array before filing it, and run *every* entry — not the first
   one.** Each entry names the section, the cap, and a ready-to-run request that returns that
   remainder; run each and attach the results. Filing a capped bundle without its remainder is how
   a complete-looking answer omits the row the complaint turns on.

   **A merged principal produces more than one entry per section, and this is the step where that
   matters.** The cap is applied *per subject id*, because a person merged from several ids has
   their history written under each and the receipt route reads one id at a time — so one pointer
   over a concatenated list could not be run at all. A subject merged from three ids can therefore
   produce three `receipts` entries, each with a different `subjectId` in its request. Read
   `mergedFrom` at the top of the bundle: if it is non-empty, expect several, and check the
   `subjectId` in each request rather than assuming they are the same call twice.

   The person most likely to hit a cap is the long-lived, merged one — which is the same person
   this step is most likely to be got wrong for.
4. The assembled document must **name the fifteen calls it came from**, including the ones that
   returned nothing. "We hold nothing about this person at Matrix" is part of the answer, and a
   silence is indistinguishable from a call nobody made.

Each bundle carries its own chain verification, and those do not merge — they are separate ledgers
under separate entities and combining them would assert a chain that does not exist. Attach them
separately.

### 12.3 Attribution — `X-UDS-Actor`

`admin_audit_event` recorded one identifier and it was the API client. `compliance-console` is a
single credential held by a compliance team, so a row saying it retired a purpose, invalidated a
consent, or assembled an evidence bundle for a named data principal identified a service account
and nobody else — permanently, because the table is append-only.

Two columns now. `client_id` is the credential, which the platform verifies. `actor_id` is the
human, which the caller asserts in `X-UDS-Actor` and **without which an administrative mutation is
refused with 400**.

Machine routes are deliberately exempt: a dialer scrubbing a list and a website recording an
opt-out are systems, not people, and `scrub_run.actor_id` still records the credential. Honouring
the header there would let any capture surface write an arbitrary name into evidence about
something no person did.

**What this is worth, plainly.** A header the console sets is weaker than a signed OIDC claim, and
it is not pretending otherwise — it is trustworthy exactly as far as the console is. It is sanitised
(control characters stripped, so it cannot forge a log line) and bounded to 128 characters (so it
cannot bulk-load an append-only table). It remains the path every current integrator uses.

### 12.5 Attribution under a bearer token

When the caller authenticated with a JWT, the human comes from the token and **`X-UDS-Actor` is
ignored — present or not**.

That is deliberate and it is the one place this behaviour could have gone wrong. Preferring the
header when it happens to be set would leave the spoofable attribution path open under the very
scheme adopted to close it: a token proves the provider authenticated somebody, and letting the
same request overwrite the name would make the proof decorative. `JwtAuthenticationIT
.theHeaderIsIgnoredUnderAToken` sends both and asserts the claim wins.

So the header is required for Basic administrators and is dead weight under a token, which is the
cheapest available incentive to migrate. **The schema did not change for either**: `actor_id` and
`client_id` have been separate columns since `V23`, and both schemes fill the same two.


### 12.6 The data principal's routes

`/v1/portal/**` is the only unauthenticated **write** surface on the platform, and the only way a
person can reach it without going through somebody at UDS who holds a credential. DPDP **Rule
14(1)** requires the means of exercising a right to be published, and `NoticeStore.rightsUri` —
reproduced on every consent receipt ever issued — pointed at a page that did not exist.

Three routes: `POST /v1/portal/requests` submits, `POST /v1/portal/requests/{ref}/verify` confirms,
`GET /v1/portal/requests/{ref}?code=…` reports status.

**Two properties an operator should know about, because both look like bugs from outside.**

*Submission tells the caller nothing about the identifier.* The response is identical whether the
group holds a file on that person or has never heard of them — and it is identical because the path
never looks, not because two branches were matched. A route that answered differently would let
anyone with a list of phone numbers learn which of them UDS holds data about. If a support ticket
says "the portal accepted an address we have no record of", that is the design.

*The statutory clock starts at verification, not at submission.* `StatutoryClock` produces a
deadline Rule 14(3) caps at ninety days. An anonymous submission that started it would let anyone
burn the group's whole response window on somebody else's behalf, repeatedly, without proving
anything. A submission is not a request until the code comes back.

**What must exist outside this platform.** The platform sends nothing — it never has. It mints a
single-use code, stores only a peppered hash of it, and enqueues `rights.verification.requested` on
the outbox carrying the code and the identifier *hash*. **Something has to consume that topic and
resolve the hash to a contact, or no principal ever receives a code and every submission expires
unverified.** That consumer does not exist yet; wiring it is the one operational prerequisite before
publishing the URL under Rule 14(1).

Codes are ten characters, valid 24 hours, single-use, and capped at five wrong attempts per
reference. Unverified submissions are discarded by the retention sweep — they are identifier hashes
for people who may never have asked for anything.

**Still UDS's, and it blocks publication as much as the consumer does.** Rule 14(1) also requires
publishing "the particulars of such information as may be required to identify" the principal. Ask
too little and a request cannot be safely authenticated; ask too much and the identification
requirement becomes the obstruction a regulator reads it as. `IdentifierType` is the vocabulary the
answer goes in. See `REGULATORY_HANDOFF.md` §4.
### 12.4 Structured logging

Activate the `json-logging` profile wherever there is an aggregator. Spring Boot emits ECS JSON
natively, so this costs no dependency — which matters, because it is the one part of the
observability gap closeable without one. Every line carries `correlationId`, `clientId` and
`actor`, so "everything this person did last Tuesday" is a query rather than a grep.

Traces join them when tracing is on: `traceId` and `spanId` sit beside `correlationId` rather than
replacing it. The correlation id is the **caller's** — it survives outside the trace system and it
is the one a Denave engineer quotes in a support thread — so a deployment running without a
collector loses nothing it had.
