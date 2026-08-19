# UDS Consent & Privacy Control Plane

Group-wide consent, notice and enforcement platform for UDS Group entities acting as Data
Fiduciaries under the **DPDP Act 2023 / DPDP Rules 2025**, **TRAI TCCCPR 2018 (as amended
February 2025)**, **UK/EU GDPR and PECR/ePrivacy**, **Korea PIPA (as amended 10 March 2026)**,
**Singapore PDPA** and **Malaysia PDPA 2024**.

The binding date is **13 May 2027**, when the substantive DPDP Rules become enforceable. What
arrives before it, and none of it hypothetical:

- **TRAI is enforced today**, which is why its expiry semantics are first-class here rather than an
  extension.
- **The Data Protection Board has been constituted since 6 June 2026** and its grievance portal is
  live, so a complaint can arrive now. That is what
  `GET /v1/admin/evidence/subject/{entityId}/{subjectId}` exists to answer.
- **11 September 2026** — Korea's PIPA amendment commences: administrative fines to 10% of total
  turnover for the severe tier, the business owner named as ultimately responsible, and a breach
  clock that now starts on a *reasonable likelihood* rather than on confirmation.
- **13 November 2026** — the DPDP Consent Manager framework under Rule 4 becomes operational. UDS
  does not register and cannot; it must be able to transact with one, which is what
  `/v1/consent-manager/**` is for.

The plan this implements is [`docs/UDS_Consent_Control_Plane_v2_FINAL.md`](../docs/UDS_Consent_Control_Plane_v2_FINAL.md).
Operational procedures — provisioning, key management, integrity sweeps, the breach runbook, restore
verification — are in [`docs/OPERATIONS.md`](../docs/OPERATIONS.md). Items that need a person rather
than a commit — Korean counsel, Malaysian DPO registration, the Board's signing standard — are in
[`docs/REGULATORY_HANDOFF.md`](../docs/REGULATORY_HANDOFF.md). And
[`docs/WALKTHROUGH.md`](../docs/WALKTHROUGH.md) follows one data principal from notice to evidence
bundle with the real commands and the real responses — the fastest way to see what this does
without reading any of the below.

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

`test` runs the unit suites — **151 cases** (33 in `consent-core`, 113 in `consent-policy`, 5 in
`consent-service`), no Docker, about two seconds. `verify` adds the integration suites, which need
Docker for a real PostgreSQL because the properties they check are database properties: 38 in
`consent-ledger` and 366 in `consent-service`. **555 tests in total.**

Those are the figures the build prints, not an estimate. The README claimed 188 for several months
after it stopped being true, and then 414 for one release after it stopped being true, which is a
small lie that teaches the reader to discount the large statements beside it. Re-read them off
`mvn verify` whenever this paragraph is touched.

**The build needs a network on its first run.** Earlier revisions described it as offline, and that
was a habit mistaken for a constraint: `-o` worked because everything happened to be cached, CI has
always run `mvn -B verify` online, and there is no `settings.xml` forcing it. Once `~/.m2` is warm,
`mvn -o verify` works as before.

`mvn verify -pl consent-service` **without `-am`** does not work: it resolves stale sibling jars
from the local repository and fails with dozens of spurious "cannot find symbol" errors. Run the
full build from `platform/`.

| Suite | What it protects |
|---|---|
| `GoldenDecisionSuiteTest` | Every decision rule, per regime, each traceable to a statute. Also runs the same cases through the server engine and the offline evaluator and compares outcome *and* reason |
| `CaptureValidatorTest` | What makes consent valid at the door: pre-ticked boxes, bundling under PIPA, affirmative action under s.6, parental consent under s.9, whether the submitting surface is one the group owns, and that a Consent Manager relay earns no exemption from any of it |
| `StatutoryClockTest` | The rights-response period per jurisdiction, and that a withdrawal is same-day everywhere rather than sitting on the access-request clock |
| `BreachClockTest` | The two-stage DPDP Rule 7 clock, GDPR Art.33/34, and Korea re-derived against the PIPA amendment in force 11 September 2026 |
| `LedgerStoresIT` | The SQL itself — suppression scope precedence and effective-date windows, quarantine transitions, outbox claim semantics, identifier resolution that does not create subjects as a side effect, and the blast-radius calculation broken down per entity |
| `LedgerAppendOnlyIT` | UPDATE, DELETE and TRUNCATE rejected by the database; the chain verified end to end; tampering detected even when a superuser disables the triggers |
| `ConsentLifecycleIT` | Capture → decide → withdraw → decide; idempotent replay; out-of-order offline sync; TRAI expiry from when the subject acted |
| `ProvenanceIT` | Imports land quarantined and cannot self-certify; a re-run does not inflate the count; substantiation carries a named reviewer |
| `NoticeIT` | A notice version reproduced byte-identically years later; a missing translation reported as missing rather than silently answered in English |
| `NoticeCacheIT` | That the notice cache never remembers a negative — a version or a language published two minutes ago must not be reported missing, because the consequence is real consent rejected and nothing recording that it was |
| `RightsRequestIT` | The statutory clock per jurisdiction, breach detection, the refusal to close a request without a resolution, and the status **state machine** — a request cannot bounce backwards into `RECEIVED` while the clock runs, and `RECEIVED → FULFILLED` stays legal on purpose. Also carries the tree's only HTTP `PATCH` test, against the one route that had none |
| `RopaIT` | The Record of Processing Activities, including that the export ships its own gaps |
| `ConsentApiIT` | The API over HTTP with real credentials, including that a dialer's role cannot write consent |
| `AdminApiIT` | The role boundary swept over **every** route: 403 for each role an annotation does not name and non-403 for each it does. The failure mode of this control is a route added later without an annotation, so the assertion is the list rather than a sample |
| `EntityIsolationIT` | That a Denave credential cannot reach a Matrix row through any endpoint, in both directions, and that a group-level credential still can. Covers the three route families that were missing from the guard's prefix list — the SDF register, provenance and the signed snapshot — and pins the refusal's *shape*: RFC 7807, like every other refusal, and not echoing the entity id back to a caller who could otherwise enumerate them |
| `PublishingIT` | The writable control plane: a publish is refused without its blast radius, and a legitimate-interest purpose without its assessment |
| `EnforcementEvidenceIT` | That the platform can prove it asked before it acted — and that a failing evidence write never fails the decision |
| `BreachIT` | The two-stage Rule 7 clock end to end, and the affected population computed as at the breach instant |
| `RetentionIT` | That retention proposes rather than deletes, and that a proposal carries the rule that produced it |
| `ReceiptIT` | A receipt number worth quoting: durable, reproducible byte-for-byte, verifiable against the chain, and carrying the ISO/IEC TS 27560 field set — recipients, transfers, retention and sensitivity per purpose. Asserts the receipt describes **the purpose version the subject agreed to**, not the current one, which is the difference between evidence and a document that grows new data categories every time a purpose is re-published; and that a version whose row has gone degrades to the purpose code rather than to today's metadata. Includes the regression the 27560 change could have caused: a receipt issued before those fields existed still reproduces exactly as issued |
| `ObservabilityIT` | The correlation id on every response and the metric names an alert is wired to |
| `TraiCoolingOffIT` | The ninety-day cooling-off from the February 2025 amendment, as a prohibition rather than an obligation |
| `SweeperAndRelayIT` | The background machinery: expiry under a controlled clock, the outbox actually draining, and the advisory lock releasing even when a sweep throws |
| `ConsentManagerIT` | A Consent Manager relay is indistinguishable in effect from a first-party consent and distinguishable in evidence; an unregistered, deregistered or credential-mismatched one is refused, the refusal recorded, and all three refusals are indistinguishable from outside so the endpoint cannot be used to enumerate the register. Also the register's administration: a suspension made through the admin endpoint stops the next relay and lands in the audit trail |
| `RowLevelSecurityIT` | The second isolation layer, which had never executed under test because the application connects as the table owner and `V13` deliberately does not `FORCE`. Proves the policies refuse a cross-entity read *and write* under a claim, that the claim is re-applied on every pool checkout, and — as a build failure rather than a checklist line — that the deployed environment has RLS enabled and the app role is not the owner. Its sharpest assertion derives the entity-scoped tables from `information_schema` rather than from a list: any table carrying an `entity_id` without a policy fails the build, and the three that are open on purpose are carried here with their reasons written out |
| `EvidenceBundleIT` | The one call that answers a complaint: the notice as served, the chain and its verification, suppressions swept across every channel, the age assertions and Korean re-confirmations a specific complaint turns on, and the read itself audited. Plus the guard that keeps it complete — it enumerates the subject-scoped tables from `information_schema` and fails when one is neither carried nor explained, because a completeness checklist in a runbook is one that gets done once |
| `DecisionLatencyIT` | The published objective, measured. A loose p95 floor, and — the assertion that earns its place — a **round-trip count capped at the measured six** — a ceiling rather than an equality, so a seventh query fails the build and a drop to five does not — which means a query added to the hot path fails a build instead of arriving in a dialer campaign. The batch path pinned separately against super-linear growth |
| `GuardianVerificationIT` | DPDP s.9 with Rule 10's diligence attached. A capture on a child's behalf is refused unless it records *how* the guardian was verified; the verification travels inside `canonical_payload` and so inside the hash chain; the raw reference the caller sent lands in no column anywhere; and minority becomes an append-only dated assertion, so "was this subject a minor on the day we tracked them" survives their eighteenth birthday — asserted end to end, including that a subject with **no** assertion at all is still protected by the flag |
| `ReconfirmationIT` | Korea's two-yearly re-confirmation under Network Act Enforcement Decree Art. 62-3 — the interval as calendar years rather than 730 days (asserted across a leap day, and on 29 February), the three disclosures Art. 62-3(2) requires, and the judgement that an overdue confirmation raises an obligation and **still allows**, because the Decree does not say what silence means |
| `SdfObligationIT` | DPDP Rule 13, and the flag that was read by nothing. A designated entity owes a rolling annual DPIA and audit plus one diligence check per registered algorithmic system; an undesignated one owes nothing and says so rather than erroring; a completion with no hashed artefact is refused. Also Rule 13(4)'s restriction hook, asserted empty because no category is notified |

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

**Per-entity isolation is enforced twice, and the second layer deliberately does not FORCE.** A
Matrix credential must not reach a Denave row. `EntityAccessGuard` refuses a request naming an
entity the credential is not scoped to, before any query runs — that is the layer that produces a
comprehensible 403 and the one an integrator meets. Row-level security in PostgreSQL
(`V13__row_level_security.sql`) is the second, keyed on a session variable set from the same claim
on **every** connection checkout, because the pool hands one physical connection to different
requests and a variable set once per session would answer a Matrix request with a Denave claim
within minutes of start-up.

Two layers rather than one because the first is code, and code acquires a new endpoint somebody
forgets to add to the list — which happened during this work: the evidence-bundle route was missing
from the guard's prefix list and was caught by `EvidenceBundleIT` asserting the scope rather than by
anyone remembering. The database policy applies to every statement regardless.

The migration deliberately does **not** `FORCE ROW LEVEL SECURITY`. Policies do not apply to a
table's owner, and the owner is the migration role: Flyway keeps working, the store suites keep
working, and the policies bind exactly the role that serves traffic. The consequence to verify in
every environment is that the application does **not** connect as the table owner — if it does,
every policy is bypassed in silence. See `docs/OPERATIONS.md` §8.

**Authorisation is decided in the filter chain as well as on the method.** Method security is a
proxy around the handler, so `@PreAuthorize` runs *after* Spring has deserialised and validated the
request body — a dialer credential POSTing to a publishing endpoint was getting a 400 describing the
fields it got wrong, having already enumerated the shape of a write it may not make. The request
matchers in `SecurityConfiguration` mirror the annotations and move the refusal in front of all of
it. The duplication is covered: `AdminApiIT` sweeps every route in both directions, so a rule that
disagrees with an annotation fails the build.

**Denials are recorded in full and allowances in aggregate.** A dialer at a hundred thousand calls a
day would otherwise write a hundred thousand rows proving nothing happened, and evidence that gets
switched off under load leaves its gap on exactly the busy days. Screening coverage is proved by the
scrub run instead.

**Retention proposes; it does not delete.** The sweeper writes a proposal carrying the rule that
produced it. Erasure under s.8(7) is consequential and irreversible, and a platform that performed it
on a schedule would eventually perform it on a bug.

**The affected population of a breach is computed as at the breach instant**, not as at the moment
somebody asks. Consent withdrawn after the breach does not remove a person from the set of people
whose data was exposed.

**A Consent Manager relay uses the same write path as everything else.** UDS does not register as a
Consent Manager and structurally cannot — the First Schedule requires independence from the
fiduciaries it intermediates for. What it must do is transact with one, so
`/v1/consent-manager/{registrationId}/grant` and `/withdraw` flow into the same
`ConsentCaptureService` a web form uses. A relayed withdrawal *is* a withdrawal; a second write path
would drift from the first within two releases and the drift would be invisible until somebody was
contacted after asking not to be. What differs is evidence: `ActorType.CONSENT_MANAGER`,
`CaptureMethod.RELAYED_BY_CONSENT_MANAGER`, and the Board registration number on the actor id.

**The registration number on a relay is proved, not asserted.** It arrives as a path variable, so
the platform resolves the registration and refuses unless it is the one the authenticating
credential holds. Without that check any `CONSENT_MANAGER` credential could write consent into the
ledger under any other registration, and the ledger would faithfully record the *other* Consent
Manager as the actor — an actor id the caller can choose is not evidence of anything. `ADMIN`
bypasses the binding, explicitly and audited, because rehearsing the relay path and reproducing a
disputed relay both require acting as a Consent Manager one is not. The bypass does not extend to
status: a deregistered or suspended registration is refused for everybody.

**The Consent Manager register is a copy, and says so.** The Board publishes no feed, so
`/v1/admin/consent-managers` carries `last_reconciled_at` — when a *person* last compared UDS's copy
with the published list — and `/actuator/health` reports the oldest value plus every entry nobody
has ever checked. A weaker control than a sync, and the true one; a column called `last_synced_at`
would imply a mechanism that does not exist. Suspension and deregistration are endpoints rather than
a psql session, because Rule 4 lets the Board suspend a registration after a hearing and UDS has to
stop honouring that CM's relays the day the notice arrives.

**Parental consent is refused unless the diligence behind it is recorded.** DPDP Rule 10 does not
ask a fiduciary to record that it verified a guardian; it asks it to *observe due diligence* that
the person identifying as a parent is an identifiable adult. Before this the platform accepted
`PARENTAL_VERIFIED` and `PARENT_GUARDIAN` on the capture surface's own word, which meant the group
could produce a consent and could not produce the obligation the consent depends on. A child
capture now carries which of Rule 10's two routes was taken, against what reference, when, and by
whom — or it is refused as `GUARDIAN_VERIFICATION_NOT_EVIDENCED`. The reference is peppered and
hashed at the HTTP boundary like a phone number, and for a stronger reason: the guardian is a third
party who appears in the record only because the law required a check on them.

**Minority is an assertion with a date, not a flag.** `subject.is_child` answers "is this subject a
minor now". The question anyone actually asks is "was this subject a minor on the day we tracked
them", and a mutable boolean cannot answer it — a subject who turned eighteen last year makes every
behavioural decision taken about them at fifteen look lawful. `subject_age_assertion` is
append-only, dated and sourced; the column survives as the read model the hot path consults.

**And the decision path now asks the dated question.** For one release the assertion history existed
and the child gate still read the mutable flag — the query was built and wired to nothing, which is
the third consecutive planning pass to find a control in that state. `SubjectAttributeLookup` is now
`isChildAt(entityId, subjectId, at)`, with no unscoped or undated variant left to call, and it reads
the assertion as at `DecisionRequest.at()`. **The fallback is the part that matters**: an absent
assertion is not "adult", so where nobody had asserted anything by that instant the lookup falls back
to the flag. A bare `.orElse(false)` would have silently un-protected every subject captured before
the assertion table existed — a change that passes review, passes every test written after it, and
surfaces only as under-eighteens being profiled.

**The evidence bundle checks its own completeness.** It promises "everything the platform holds
about one person", and that promise decays: two subject-scoped stores were added in one release and
neither reached it. `EvidenceBundleIT` now reads the subject-scoped tables out of
`information_schema` and fails when one is neither carried nor accompanied by a written reason for
leaving it out — which is the whole difference between a considered omission and an oversight, and a
difference that is invisible in the finished export. It found a third gap on the day it was written.

**An overdue Korean re-confirmation raises an obligation and still allows.** Network Act
Enforcement Decree Art. 62-3 requires consent to receive advertising to be re-confirmed every two
years, and specifies what the confirmation must disclose. It says nothing about a recipient who
never answers. Industry practice treats silence as maintaining consent; practice is not text, so
the platform surfaces `reconfirmation-overdue` and leaves the decision alone. Denying would enforce
a rule nobody can cite, against the group's own interest, on the platform's own authority. The
counsel question is one sentence in `REGULATORY_HANDOFF.md` §2, and `ReconfirmationIT` names the
test that pins it.

**The Significant Data Fiduciary register is built and empty, and empty is an answer.** Rule 13's
annual DPIA, annual independent audit and per-system algorithmic diligence are modelled and gated
on `fiduciary_entity.significant_fiduciary` — a column that had existed since the first migration
and was read by nothing. An entity the Government has not notified gets an empty register rather
than a 404: manufacturing obligations for entities that do not have them would produce an overdue
count nobody could act on, which is how a real overdue count stops being read. **Rule 13(4)**'s
restricted-category hook is the same discipline in miniature — `data_category.transfer_restricted`
is false on every row and the RoPA cross-border report already consults it, so a Government
notification is an update against one column rather than a release. The *general* cross-border
position is **Rule 15**, which binds every Data Fiduciary rather than only Significant ones, and is
the `crossBorderTransfers` half of the same report.

**A citation this platform had wrong, corrected in the open.** The restricted-category power was
cited as "Rule 14" in `V20` and in seven other files. Rule 14 is *rights of data principals* —
publication, identification particulars, grievance redressal, nomination. The localisation power is
Rule 13(4), on the recommendation of the Rule 13(5) committee. `V21__correct_rule_citations.sql`
reissues the affected column comments and records the correction rather than making it silently,
because `V20` is already applied in environments and Flyway checksums an applied migration — an
applied migration is a record of what was believed at the time, and quietly rewriting one is the
habit the append-only ledger exists to refuse. Rule 13(4) also reaches "the traffic data pertaining
to [the] flow"; this platform holds consent evidence and not message logs, so that limb is recorded
as out of scope rather than left looking covered.

## What is not built yet

Named rather than implied, so nobody assumes otherwise:

- **Consent Manager *registration*.** Permanently deferred, not pending: it would require UDS to
  stop being a Data Fiduciary for the same principals. Interoperability is built (above)
- **Signature verification on a relayed request.** `consent_manager.public_key` exists and is
  unused, because the Board has published no signing standard. Verifying against a scheme nobody
  else implements would be worse than not verifying — it would look like proof
- **An identity provider to point OIDC at.** The resource server is built and tested: bearer tokens
  are accepted alongside HTTP Basic, scopes map onto the same four roles so no route rule changed,
  and the human comes from a signed claim rather than a header. What does not exist is a configured
  issuer — and its client registrations must set an `entity_id` claim on every scoped credential,
  because an absent claim means group level
- **A collector to send traces to.** `micrometer-tracing-bridge-otel` and the OTLP exporter are on
  the classpath and tracing is off by default; an exporter retrying into a void on every span is CPU
  spent on the decision path to produce nothing
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
- **A sender for the rights-portal verification code.** `/v1/portal/**` lets a data principal file
  a request without a credential — the Rule 14(1) obligation the platform previously modelled and
  could not serve. The platform mints a single-use code and enqueues it on
  `rights.verification.requested` with the identifier *hash*; something has to consume that,
  resolve the hash to a contact and send the message. Until it does, every submission expires
  unverified. The platform has never been able to reach a person and this does not change that
- **A preference centre.** Not deferred — declined. Letting somebody change a consent from the open
  internet needs a session model the group does not have, and it would open a write path into the
  append-only ledger from an unauthenticated surface. Withdrawal has a route, and the capture
  surface that knows who the person is calls it
- **An authenticated decision path that is not dominated by authentication.** The load run on
  17 August 2026 (`docs/CAPACITY.md` §7) measured the decision engine at 2.6 ms p95 — eleven times
  inside the published objective — and the client at 115 ms, of which ~110 ms is BCrypt re-hashing
  the Basic credential on every request. One instance therefore serves ~50 rps, and the ceiling is
  CPU rather than the database. JWT does not re-hash, so the seam already exists; what is missing is
  an IdP and the migration. Related and still open: `RateLimitFilter` runs *after* authentication, so
  invalid credentials cost the platform ~110 ms each and never reach a bucket (`OPERATIONS.md` §12.2)
