# UDS Consent & Privacy Control Plane

Group-wide consent, notice and enforcement platform for UDS Group entities acting as Data
Fiduciaries under the **DPDP Act 2023 / DPDP Rules 2025**, **TRAI TCCCPR 2018 (as amended
February 2025)**, **UK/EU GDPR and PECR/ePrivacy**, **Korea PIPA**, **Singapore PDPA** and
**Malaysia PDPA 2024**.

The binding date is **13 May 2027**, when the substantive DPDP Rules become enforceable. TRAI is
enforced *today*, which is why its expiry semantics are first-class here rather than an extension.

The plan this implements is [`docs/UDS_Consent_Control_Plane_v2_FINAL.md`](../docs/UDS_Consent_Control_Plane_v2_FINAL.md).
Operational procedures — provisioning, key management, integrity sweeps, restore verification — are
in [`docs/OPERATIONS.md`](../docs/OPERATIONS.md).

---

## What this is

Three planes in one deployable, separated by package and by database guarantee rather than by
process.

| Plane | What it holds | Changes |
|---|---|---|
| **Control** | Purpose registry, notices, vendors, applications, jurisdiction policy | When a human publishes |
| **Enforcement** | Decision API, signed offline snapshots, suppression | On every request |
| **Evidence** | Append-only hash-chained ledger, provenance, admin audit | Only by appending |

The organising idea is that **consent is a platform, not a banner**. The banner is the thinnest
layer; the ingestion contract and the decision point are the product. Everything below the
ingestion API is ours and vendor-independent, which is what keeps the build-versus-buy decision
free to defer to the Phase 1 gate.

## Modules

| Module | Depends on | Why it is separate |
|---|---|---|
| `consent-core` | Jackson only | The model, canonical JSON, hashing, snapshot signing and the offline evaluator. Framework-free so an Android or iOS SDK can embed the same evaluation logic the server runs |
| `consent-ledger` | core, Spring JDBC, Flyway | Schema, stores, projector, integrity verifier. Plain `JdbcClient`, not JPA — an ORM's dirty checking is a path by which a mapped entity could be flushed as an UPDATE against tables whose entire design forbids one |
| `consent-policy` | core, SLF4J | The decision engine, capture validation and one module per jurisdiction. No Spring, no database: that is what lets the golden suite run every case across seven jurisdictions in milliseconds, and a suite that runs in a second gets run on every commit |
| `consent-service` | all of the above | The deployable: REST API, snapshot issuance, outbox relay, sweepers |

## Running it

```bash
docker compose -f docker/docker-compose.yml up -d
```

```bash
DB_URL=jdbc:postgresql://localhost:5433/uds_consent DB_MIGRATION_URL=jdbc:postgresql://localhost:5433/uds_consent mvn spring-boot:run -pl consent-service -Dspring-boot.run.profiles=local
```

The container publishes PostgreSQL on **5433**, not the default 5432. Developer machines very
often already have a PostgreSQL on 5432 — a Homebrew install, another project's container — and
the failure when they do is a port-bind error if you are lucky and the application quietly
migrating someone else's database if you are not. Override with `POSTGRES_PORT` if 5433 is taken
too. To run the service in a container as well:

```bash
docker compose -f docker/docker-compose.yml --profile app up -d
```

The API is then at `http://localhost:8080`, with OpenAPI at `/swagger-ui.html` (ADMIN role).
Development credentials under the `local` profile are `denave-web` / `athena-dialer` /
`compliance-console`, password `dev`.

The service **refuses to start without an identifier pepper**. That is deliberate: the space of
Indian mobile numbers is small enough to enumerate exhaustively, so unpeppered hashes are
reversible in practice, and a silent fallback would produce a database of reversible hashes that
nobody discovers until it has been accumulating for a year.

## Tests

```bash
mvn test
```

```bash
mvn verify
```

`test` runs the unit suites — 90 cases, no Docker, about two seconds. `verify` adds the
integration suites, which need Docker for a real PostgreSQL because the properties they check are
database properties. **188 tests in total.**

| Suite | What it protects |
|---|---|
| `GoldenDecisionSuiteTest` | Every decision rule, per regime, each traceable to a statute. Also runs the same cases through the server engine and the offline evaluator and compares outcome *and* reason |
| `CaptureValidatorTest` | What makes consent valid at the door: pre-ticked boxes, bundling under PIPA, affirmative action under s.6, parental consent under s.9, and whether the submitting surface is one the group owns |
| `LedgerStoresIT` | The SQL itself — suppression scope precedence and effective-date windows, quarantine transitions, outbox claim semantics, identifier resolution that does not create subjects as a side effect |
| `LedgerAppendOnlyIT` | UPDATE, DELETE and TRUNCATE rejected by the database; the chain verified end to end; tampering detected even when a superuser disables the triggers |
| `ConsentLifecycleIT` | Capture → decide → withdraw → decide; idempotent replay; out-of-order offline sync; TRAI expiry from when the subject acted |
| `ProvenanceIT` | Imports land quarantined and cannot self-certify; a re-run does not inflate the count; substantiation carries a named reviewer |
| `NoticeIT` | A notice version reproduced byte-identically years later; a missing translation reported as missing rather than silently answered in English |
| `RightsRequestIT` | The statutory clock per jurisdiction, breach detection, and the refusal to close a request without a resolution |
| `RopaIT` | The Record of Processing Activities, including that the export ships its own gaps |
| `ConsentApiIT` | The API over HTTP with real credentials, including that a dialer's role cannot write consent |

## Design decisions worth knowing before changing anything

**Consent is a time series with expiry semantics, not a boolean.** TRAI's transactional consent
lapses after seven days and inferred consent lasts only as long as the contract behind it. A schema
storing `marketing = true` cannot express either, and is wrong on day one.

**Purpose is separate from data category.** "GPS location, for field-attendance verification" and
"GPS location, for marketing personalisation" are two purposes over one category, and a subject may
reasonably accept the first and refuse the second. Collapsing them into a `location` toggle is the
most common way a consent system becomes indefensible.

**Ordering uses `occurredAt` with the server sequence number as tiebreak.** Several thousand
Android devices across five countries will have clock skew. Where two events disagree about the
outcome inside a five-minute window, the projection goes to `CONFLICTED` and denies — the platform
does not quietly pick the permissive reading.

**Withdrawal does not create a channel suppression.** Withdrawing consent to promotional email is
not the same as asking never to be emailed, and treating it that way would silently stop
transactional messages the subject still expects. Channel-level opt-out is a separate explicit act.

**Provenance records default to quarantined.** Unsubstantiable records are quarantined, never
grandfathered. Encoding that as the column default rather than as a convention means nobody has to
remember to choose the safe state.

**Legitimate uses are not a consent problem.** DPDP s.7(i) covers employment processing, so roughly
76,000 workforce records need notice, retention and rights machinery — not consent records. The
capture validator *rejects* an attempt to record consent for such a purpose, because implying the
subject can withdraw and stop the processing invites exactly the grievance that follows from the
misunderstanding.

**The decision engine never throws.** A policy engine that throws is a policy engine that gets
wrapped in a try/catch whose catch block allows the operation, because the campaign has to go out
tonight. Internal failures deny, with `POLICY_ERROR`, and the decision stays here.

## What is not built yet

Named rather than implied, so nobody assumes otherwise:

- **Row-level security for per-entity isolation.** A Matrix administrator must not see Denave's
  records. The schema carries `entity_id` everywhere and the API enforces roles, but database-level
  RLS driven by the caller's entity claim is Phase 1 work
- **OIDC.** HTTP Basic with per-client credentials is honest about being a pilot starting point.
  The capability-based role model survives the move to the group's OIDC provider unchanged
- **Rights request *fulfilment*.** Intake, the per-jurisdiction statutory clock and breach alerting
  are built and tested. What is not built is federated retrieval across DenCRM, the HRMS and the
  BGV workflow, and the grievance routing over it — Phase 3. The clock landed first deliberately:
  a request that arrives before anyone can fulfil it becomes a manual job, and manual jobs get
  done; one that arrives before anyone is counting the days becomes a breach nobody notices
- **Notice translation.** The serving API, versioning and per-notice coverage reporting are built;
  nineteen of the twenty-three required languages have no text. That is a procurement task, and
  the platform's job is to keep the gap visible — `GET /v1/notices/reports/coverage` names it.
  Deliberately no placeholder rows: `notice_translation` is immutable once written, and a
  placeholder is indistinguishable from a real notice to whoever reads it
- **Partitioning `consent_event`.** Deliberately deferred: partitioning would weaken the
  `(entity_id, subject_id, sequence_number)` uniqueness constraint the chain depends on. Revisit
  with real volume data, not before
