# Operations — UDS Consent & Privacy Control Plane

Everything in this document exists because something in the platform's design depends on an
operator doing it. Where a step is skipped, the consequence is stated rather than implied — a
runbook that only says what to do produces deployments that are subtly wrong and confidently
signed off.

Referenced from `V2__append_only_guards.sql`.

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
without it.** The space of Indian mobile numbers is small enough to enumerate exhaustively, so a
bare SHA-256 of a phone number is reversible in practice; the pepper is what stops an attacker
holding a database copy from recovering the numbers in it.

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

**Rotation.** Publish the retired key alongside the new one at `/v1/keys` for at least one snapshot
lifetime, so devices holding a snapshot signed moments before the rotation keep working until it
expires. Rotating without the overlap turns a routine key change into a fleet-wide enforcement
outage.

### 2.3 API credentials

HTTP Basic per client under `uds.consent.security.clients.*`. Roles map to capability:

- `CAPTURE` — write consent
- `DECISION` — ask the decision API and scrub lists; **cannot** write consent, because a dialer
  that can record consent is a dialer that can manufacture it
- `ADMIN` — the compliance console and the evidence trail

A client with a blank password fails startup rather than being skipped. A credential nobody can
use, that everybody assumes works, is worse than a missing one.

Destination is the group's OIDC provider as an OAuth2 resource server. The role model above
survives that change unaltered.

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

### 4.1 The rights-request SLA sweep

Every open rights request carries a deadline fixed at intake from its type and jurisdiction —
ten days under Korea's PIPA, thirty under GDPR, forty-five under CPRA, one day for a withdrawal.
The sweep compares them against the clock and logs:

| Level | Meaning | Response |
|---|---|---|
| `WARN` | Falls due inside `sweeper.rights-sla-warning-window` (default 3 days) | There is still time. Make sure it is assigned |
| `ERROR` | **Already past its deadline.** A statutory breach that has happened | Wake somebody. It repeats every pass until the request is closed, deliberately |

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
regardless of consent. The decision API returns `use-dlt-registered-header` and
`use-dlt-registered-template` as obligations; honouring them is the sending system's job, and
nothing in this platform can enforce it on the sender's behalf.

---

## 6. Service level objectives

| Path | Target | Why it is set there |
|---|---|---|
| Local snapshot evaluation | p95 < 1 ms | It is on the field app's critical path; anything slower gets routed around |
| Decision API | p95 < 30 ms | Athena's dialer pre-flights every call |
| Control plane reads | p99 < 100 ms | Admin console responsiveness |
| Availability | 99.99% | Below this, teams build local caches, and local caches of consent state are how a group ends up with five answers |

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
- [ ] Identifier pepper set from the KMS, ≥ 32 characters
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
      off by legal (§4.1)
- [ ] Every capture surface registered in `application_registry` for the right entity and
      environment — an unregistered id is refused, and a staging build pointed at production is
      exactly what this catches
- [ ] Notice language coverage reviewed per entity (`GET /v1/notices/reports/coverage`); the
      shortfall is a translation procurement with an owner and a date, not a backlog item
- [ ] Vendors with no signed DPA reviewed (`GET /v1/admin/vendors`, and the gap list on every RoPA
      export). Registration is permitted without one so the relationship stays tracked; leaving it
      unpapered is not
- [ ] Row-level security for per-entity isolation — **not yet implemented**; until it is, restrict
      admin credentials per entity at the identity provider and record the gap on the risk
      register. Note that `APPLICATION_ENTITY_MISMATCH` at capture is currently the only thing
      preventing one entity's credential writing into another's ledger
