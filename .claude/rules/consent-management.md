# Invariants of the consent control plane

The things a change can violate **without the compiler noticing**. Not a summary of `docs/` — those
are 4,000 lines and duplicating them would produce a second document to keep true. Everything here is
either an invariant or a pointer.

---

## 1. The evidence plane is append-only, and that is load-bearing

- `consent_event` is hash-chained per `(entity_id, subject_id, sequence_number)`. **The chain has no
  forks**, and `(entity_id, idempotency_key)` is unique so a retried offline capture is not recorded
  twice.
- `UPDATE` and `DELETE` are refused to `uds_consent_app` by triggers in `V2__append_only_guards.sql`.
  `LedgerAppendOnlyIT` proves it as the application role, which is the only role whose refusal means
  anything.
- **`consent_event` is deliberately not partitioned.** A partitioned table's unique constraints must
  include the partition key; admitting `recorded_at` to those two constraints would let the chain fork
  across a month boundary and let a retry crossing midnight be accepted twice. That trades the two
  guarantees the evidence plane rests on for scan pruning nobody has measured a need for. The
  argument is in `V28`'s header — do not re-open it because the table looks unbounded.
  `enforcement_decision`, whose growth is bounded by traffic rather than population, is partitioned
  monthly and `PartitionMaintenanceSweeper` provisions three months ahead.
- **A partition is a table, and neither a grant nor a revoke on the parent reaches it.** PostgreSQL
  checks DML privileges on the relation *named in the query*, and the provisioning scripts'
  `alter default privileges` grant `update`/`delete` on everything the owner creates — so `V28`'s
  revoke held for `update enforcement_decision` and not for
  `update enforcement_decision_2026_08`. Measured against a real database in Phase 22, where the
  **row triggers refused the write anyway**: one of two layers missing, not an open hole, and
  recorded at that severity rather than the first-suspected one. Closed in `V34`, which revokes on
  every existing partition and inside the function that creates the next. **Creation itself is
  `uds_ensure_enforcement_partition`, `SECURITY DEFINER` with the parent table hardcoded** —
  partition DDL needs ownership of the parent, which the application role must never hold, and a
  parameterised form of that function would be a privilege-escalation primitive. It must never read
  an entity-scoped table: on a managed database the definer holds `BYPASSRLS`.
- A **receipt pins the consented purpose version**, not the current one. A taxonomy change must never
  retroactively alter what a principal is recorded as having agreed to.
- **The receipt's standards claim is `iso-27560:2023-receipt-subset`, and the suffix is load-bearing.**
  The ISO text is paywalled and not held; conformance is claimed against the free W3C DPV rendering, by
  section, and the subset named is the receipt-metadata reading of its §9 — *not* the full-record
  reading, under which `recipients` and `retentionPeriod` could not be nullable. They are nullable
  deliberately: null means nobody recorded the fact, and answering "none" would be a false statement
  issued to a principal. **Do not "improve" the claim to bare `iso-27560:2023` and do not default those
  fields to empty** — either change makes the platform say something untrue. `docs/standards/` holds the
  field-by-field position; `docs/TRACEABILITY.md` §6 holds the rows.
- Integrity is `POST /v1/admin/integrity/sweep` (plus `…/last` and `…/{entityId}/{subjectId}`).
  **There has never been an `…/integrity/verify`** — it was written into a runbook and believed for
  months. Check a route exists before instructing anyone to call it.
- **A verified chain says nothing about the projection, and until Phase 19 nothing checked the
  projection at all.** `last_event_hash` is *copied* onto `consent_artefact` rather than derived from
  its `status`, so an artefact whose status is wrong stays perfectly self-consistent and the
  integrity sweep verifies it happily. Two of this programme's headline defects were projection
  defects that every control passed — C8's purpose version, Phase 18's `NOTICE_SERVED`. The control
  is `POST /v1/admin/projection/sweep` (plus `…/last`), nightly at 03:15, **after** the integrity
  sweep. It **reports and does not repair**: a projector defect and a direct `UPDATE` of
  `consent_artefact` produce an identical divergence and only one of them is a security incident, so
  re-projecting automatically would erase the distinction before anybody saw it. Its fold is
  `ArtefactProjector.replay` — **the projector's own**, never a second implementation, because a
  second fold agrees with itself in precisely the places where the real projector is wrong.
- **`purpose_version = 0` is `ConsentEvent.NO_PURPOSE_VERSION_ASSERTED`, a sentinel and not a
  version.** Versions start at 1. `ExpirySweeper` writes it because an expiry ends an agreement
  without restating its terms, and the projector carries the prior version forward rather than
  projecting the zero. Anything comparing projection against chain must read it as *no assertion*, or
  every expired artefact in the database reports as divergent.
- **A sweep that stopped is now visible.** `SweepLock` writes `sweep_run` (V32) for all eight jobs,
  in the database rather than in memory — it runs a sweep on one instance at a time, so an in-memory
  timestamp reads "never ran" on every replica that skipped, permanently, and an alert over that can
  never clear. A sweep with no row reports **no age at all**, never zero: zero renders as "just
  finished", which is the healthiest possible value for the exact condition being watched.
  **It records that a job ran, never that it worked** — `last_outcome` is `OK` whenever the body
  returned, so a sweep whose query has silently stopped matching writes a clean run indefinitely.
  There is no generic fix: the expectation differs per sweep, so the answer is to watch each one's
  *output* where it has one (`outbox.pending`, `projection.divergent`, `propagation.uncovered`).
  Do not read an `OK` as evidence that a control is working.
- **The projection report is counts group-wide, identifiers per entity.**
  `POST /v1/admin/projection/sweep` and `…/last` name nobody: the sweep re-derives every chain in
  the database, so a group-wide route carrying subject ids would sit outside both layers of §2 at
  once — no entity for the guard to read, and no RLS in the path because the sweep gathered the
  rows. `GET /v1/admin/projection/divergences?entityId=` is the scoped read. **The count is exact
  and only the retained sample is capped**, because a capped count would understate the one case
  the control exists for: a systemic projector defect, which produces a divergence per artefact.
  **The general rule, and it is what to check when adding a route: a background job's output is not
  covered by layer two.** RLS scopes the *session that queries*, so a route returning rows the
  request itself selected is protected even with no entity in the path — `GET
  /v1/admin/subscriptions/deliveries/{outboxId}` carries `subjectId` and is safe for exactly that
  reason. A route returning rows a **sweeper** gathered is not, because the sweeper ran group-level
  on a different session. Audited across the admin surface in Phase 20: the projection report was
  the only one carrying **subject** identifiers, and it is **not** the only one on the wrong side of
  the rule — `POST /v1/admin/retention/sweep`, `…/reconfirmation/sweep` and the breach SLA sweep all
  return unbounded group-wide lists of *row* ids a background job gathered. Lower severity, same
  shape, on `ROADMAP.md`.

## 2. Isolation is two layers, and they must agree

- Layer one: `EntityAccessGuard` (`consent-service/.../config/EntityAccessGuard.java`) refuses a
  request naming an entity the caller is not scoped to, from the **query string and path only** —
  bodies are not parsed, because reading the stream in a filter consumes it.
- Layer two: PostgreSQL RLS, `V13__row_level_security.sql`, pushed into the session by
  `EntityScopedDataSource`. It applies to every statement whatever route the value took.
- **One resolver feeds both:** `EntityAccessGuard.currentEntityClaim(...)`. **It now has two sources for a
  token and that changed nothing about the rule.** A JWT's entity comes from the configured claim
  (`entity_id`) or, where the issuer cannot mint one, from an app role `entity.<ID>` — read through
  `JwtRoleConverter.grantedValues`, the *same* parser the authorities go through, so a claim shape
  one understood and the other did not is unrepresentable. **Two `entity.*` roles on one token are
  refused, never first-wins**: a set's iteration order would otherwise decide which fiduciary a
  caller reads, and both layers would agree on the wrong answer. Keying the entity on the token's
  *application* (`azp`) was the first design and is wrong — in a browser flow that is one value for
  every human who signs in, so the boundary becomes which app you authenticated to. Phase 11's worst defect was
  the two layers disagreeing about who the caller was — a bearer token's subject is not in the client
  map, resolved to "no claim", and no claim reads as *group level*, so a token scoped to Denave read
  every entity in the group through both layers at once with nothing logged. Never resolve a caller's
  scope anywhere else.
- **CORS is not part of either layer, and must not be read as if it were.** It decides which origins
  a *browser* will let read a response; it decides nothing about who may call. The allowlist
  (`uds.consent.security.cors.allowed-origins`, empty by default) exists so a console and the
  principal portal can be reached at all. Its filter is registered at
  `DEFAULT_FILTER_ORDER - 20` — **ahead of `PreAuthRateLimitFilter`** — because a refusal written by
  that filter carries no `Access-Control-Allow-Origin`, so a rate-limited browser client would be
  told its origin was wrong. `CorsIT.preflightsOutrunTheFloodCeiling` is the test that fails if
  somebody moves it into the security chain.
- Layer one is a **list of path prefixes** and lists get forgotten; layer two is derived. A new
  entity-scoped table therefore needs its RLS policy in its own migration, and `RowLevelSecurityIT`
  reads the protected set out of `information_schema`, so it starts failing on its own when one is
  missed. That failure is the test working — cover the table, do not edit the test.

## 3. Subsidiary overrides: one walk exists, and it is narrower than this section used to claim

The group is a hierarchy (`fiduciary_entity` parent link). **Exactly one thing inherits along it:
entity contacts.** `EntityStore.inheritanceChain` walks the parent link as an ordinary Java loop
bounded at ten hops, and its only caller is `resolveContacts` — which is why one statement of
`dpo_contact` and `grievance_uri` on the `UDS` row fixes all fifteen entities' receipts.

**Purposes do not inherit, deliberately.** This section asserted for three phases that they did,
"walked by recursive CTE in `PurposeRegistryStore` and exposed through `CachingPurposeCatalog`,
nearest ancestor winning on `purpose_code`". Every clause of that was false: there is no recursive
CTE anywhere in the platform, `PurposeRegistryStore` has no `entity_id` column and no ancestor
logic, `CachingPurposeCatalog` has no entity dimension, and **there is no per-entity purpose
configuration to inherit in the first place** — `docs/REGULATORY_HANDOFF.md` §8.2 records that
resolution explicitly, and notes that building what the comment described would have meant
entity-scoping the purpose registry to satisfy a comment. The claim had already been re-cited in
five other documents, including a standards note that named *this section* as its authority.
Corrected in Phase 16's closure, C6.

So: if something new genuinely needs to vary by subsidiary, `EntityStore`'s walk is the shape to
follow and there is no second one to avoid inventing — but **check first whether the thing being
overridden is per-entity at all.** That was the actual mistake here, and it is the more expensive
one.

## 4. Withdrawal is not finished at the ledger

- Identity first: `subject_alias` canonicalisation (`SubjectStore`) means a person known by phone and
  email is one subject, so **a withdrawal by email suppresses the phone**. Canonicalise on read *and*
  write. A merge is never a rewrite — it writes a `SUBJECT_MERGED` event and is itself chained
  evidence.
- Then outward: the outbox carries it, `WebhookPublisher` delivers it HMAC-signed, and
  `webhook_delivery` is the row that proves it arrived. **A propagation path with no delivery evidence
  is a violation nothing records** — that is the defining CMP function, not a nicety.
- **That sentence only became true in Phase 17, and it needs the register to stay true.**
  `webhook_delivery` proves arrival *at subscribers that exist*; it is structurally impossible for a
  system nobody registered, so before `V31` an unregistered downstream system left no trace of not
  having been told, and `event_outbox.published_at` meant "the publisher did not throw". The register
  is `propagation_target` (who must hear, per entity and topic) against `webhook_subscription`
  (how they are reached, joined on `system_code`, **upper case on both sides**).
  **Current state and evidence are deliberately separate and must stay so.** `uncovered` is derived
  from the register — bounded, and it returns to zero when configuration is fixed, which is what
  makes it alertable. `propagation_gap` is append-only history, one row per system per day, and
  **nothing alerts on it**: a count that can only grow fires forever and is muted within a week.
- **`system_code` is not free text any more.** `propagation_system` (V33) is the vocabulary an
  entity declares, and `propagation_target` and `webhook_subscription` both carry a foreign key to
  it — so a target for `DENCRM` against a subscription named `DENCRM_PROD` is refused at the `PUT`
  rather than producing a daily gap row, permanently, in an **append-only** table, for a system that
  is in fact reachable. `fulfilment_target` already failed loud and closed on this class of error;
  propagation failed quiet and wrote. A code is retired with `active = false` and **never deleted** —
  the `propagation_gap` rows naming it have to stay readable.
- **The relay claims its batch with `for update skip locked`** (`OutboxStore.claimUnpublished`),
  inside the transaction that publishes. It deliberately takes no `SweepLock`: that would serialise
  fan-out onto one instance. Before it, three replicas drained the same rows every two seconds and
  `webhook_delivery` carried up to three rows per attempt — an evidence table that over-counts.
- **The gap's `reason` records an observation, never a conclusion.** `NO_DELIVERY_CHANNEL` means the
  configured publisher writes no delivery evidence at all — `log` (the default) and `kafka` — and
  writing `NOT_DELIVERED` there would assert that a system was not told when the platform has no way
  to know. Same class of false statement as answering "no recipients" on a receipt where nobody
  recorded them. And `NOT_DELIVERED` requires the absence of a **`DELIVERED`** row: a `FAILED` row is
  an attempt, not an arrival.
- Suppression lookups go through `SuppressionStore`; a scope value outside `SuppressionScope` is
  silently ignored rather than rejected, which is how the load seed measured the allow path under the
  name of the deny path for a whole phase.

## 5. Attribution: a credential is not a person

`admin_audit_event.actor_id` records `client=<clientId>;actor=<human>` (`api/Actor.java`).
`X-UDS-Actor` is **required** on mutating admin routes under Basic auth, **ignored** under a JWT — a
claim an IdP signed beats a header a client asserted, and preferring the header would leave a
spoofable path open under the scheme adopted to close it. It is **deliberately ignored on machine
routes**: a dialer scrubbing a list and a website recording an opt-out are systems, not people, and
honouring it there would let any capture surface write a name into evidence about something no person
did.

## 6. Rights: gates, not assertions

- `FULFILLED` is refused (409, naming the outstanding systems) unless every **mandatory**
  `fulfilment_target` for that entity and request type has a terminal `rights_fulfilment_action`. The
  platform is intake, clock, gate and evidence; the *acts* of erasure and export happen in named
  systems under a named SOP. `REGULATORY_HANDOFF.md` §8.5 is the scope statement UDS signs.
- **The statutory clock starts at verification, not submission — on `/v1/portal/**`, which is the
  only path that establishes identity for itself.** Say which path. That sentence was carried
  unqualified for three phases and was never true of `POST /v1/rights`, where `receivedAt` is
  supplied by the caller. What is true everywhere is weaker and is the invariant now: **the start
  instant is bounded and its provenance is recorded.** A `receivedAt` in the future is refused
  (`ClockTolerance.SKEW`) because it moves the deadline outward — that one is a real compliance
  hole. One older than `uds.consent.rights.max-backdate` is refused as a **sanity bound only**, and
  the reason written here for three phases ("it files a request already in breach") was false: every
  period the platform computes is shorter than the bound, so a late filing inside it is accepted and
  *is* overdue on arrival. **That is the correct outcome** — a letter found in a postbag is a real
  filing — and the fact is recorded as `bornOverdue` on the audit event instead of being refused.
  `rights_request.verification_method` says whether the start was `PORTAL_TOKEN`-verified,
  `OPERATOR_ASSERTED`, or `UNVERIFIED`; **`OPERATOR_ASSERTED` requires `X-UDS-Actor`**, because it
  claims a person checked and §5 applies.
- **`UNVERIFIED` refuses nothing *at intake*, and that is deliberate. Since Phase 18 it refuses the
  *claim of fulfilment* on a disclosing or destructive right, and the two halves must not be
  confused.** At intake the label is not a gate: parking requests outside the clock until somebody
  fills in a field produces the outcome Rule 14(3) actually penalises, and GDPR **Art. 12(2)**
  forbids refusing to act on a request at all except where the controller cannot identify the
  principal. Same posture as the empty `fulfilment_target` register — record the silence, never let
  it read as diligence. The migration's default is `UNVERIFIED` for the same reason: labelling
  pre-V30 rows otherwise would write a false statement into evidence.
  **At closure it is a gate, for six types and deliberately not for three.** `FULFILLED` is refused
  409 while `verification_method` is `UNVERIFIED` for `ACCESS`, `PORTABILITY`, `ERASURE`,
  `CORRECTION`, `COMPLETION` and `NOMINATION` — fulfilling those discloses the person's file,
  destroys it, rewrites it, or hands a third party the standing to do all three, and recording that
  as discharge of a statutory right with nothing about who was asking is the platform asserting
  compliance on an identity nobody established. `CONSENT_WITHDRAWAL` and `OPT_OUT_OF_SALE` are
  **never** gated: a withdrawal by an impostor *stops* processing, and DPDP **s.6(4)** and GDPR
  **Art. 7(3)** require withdrawing to be as easy as consenting was — consent is a checkbox with no
  identity check, so a gate fails "comparable ease" on its face. `GRIEVANCE` is ungated on a **risk
  argument, not a clause**: it is the intake of a complaint, not the disclosure of the complainant's
  file back to them. **The instinct to apply the gate uniformly is the wrong turn here**, and
  `RightsRequestIT.anUnverifiedWithdrawalStillCloses` is the test that fails when somebody takes it.
- **DPDP requires none of the above, and no document may say it does.** Checked against ss.11, 13,
  15 and Rules 13 and 14: there is no verification obligation. Rule 14(1)(b) is a
  publish-your-own-requirements duty; s.15 places the duty on the *principal* not to impersonate.
  The support is GDPR **Art. 12(6)** (*"may request"*, on reasonable doubt — a permission, not a
  mandate) and **Recital 64** (*"should"*, and specific to access). Same shape as Art. 19: the
  platform does more than DPDP asks, which is a position to state, not a gap being filled.
- **Verification is recordable after intake, and written once.** `POST /v1/rights/{id}/verification`,
  ADMIN, `X-UDS-Actor` required. Write-once is enforced in the `where` clause, not by reading first,
  so two operators cannot race into a silent overwrite; a second attempt is 409 and the first record
  stands. `PORTAL_TOKEN` cannot be asserted there — it is the platform's own label for a redeemed
  token and `PrincipalPortalService` stays its only writer. **The gate is on the claim, not the
  act**: `GET /v1/admin/evidence/subject/**` is unlinked from any request, so a file can still be
  disclosed without one ever being opened. `ROADMAP.md` carries that.
- `/v1/portal/**` is the only unauthenticated write surface. It must answer **byte-identically for a
  known and an unknown identifier** or it is a subject-enumeration oracle against regulated data,
  reachable without a credential. Status reads return status and dates — **never** the evidence
  bundle; a token mailed to an address is not the standard on which to hand over a person's file.

### The other gate: a child's guardian, at the decision

`PolicyEngine` gate 7 refuses a purpose closed to children (**s.9(3)**). **Gate 11a refuses a
purpose that IS open to them when the consent being relied on records no verified guardian** —
**s.9(1)** with **Rule 10**, where the diligence is the obligation and the consent is only its
output. s.9(1) says *"before processing any personal data of a child"*, not before capturing it, and
the live hole was a subject whose minority is established **after** capture: `CaptureValidator`
refuses an unevidenced parental capture, and nothing asked again.

The evidence is `artefact.captureMethod() == PARENTAL_VERIFIED`, and that proxy was checked rather
than assumed: `ConsentCaptureService.capture` is the only path that puts a submission's capture
method on an event, it validates first and returns rejected before recording, and every other write
path stamps `NOT_APPLICABLE`.

**It is placed after gate 10, and that placement is the scoping.** A basis needing no consent record
— s.7(i) employment, legal obligation — has already returned, so the gate never refuses processing
that consent was not carrying. The wider reading of s.9(1) would reach those too; **the narrower one
is taken deliberately** under the instruction not to over-engineer the legal-policy side, and is a
position rather than what the clause compels. `GuardianVerificationIT.aLegitimateUseIsNotGated` is the test that fails if somebody widens it past
gate 10, and `anAdultIsUnaffected` / `anEvidencedGuardianStillAllows` cover the rest. This section
named only the latter two for one build — neither of which covers widening, which is the wrong turn
it is written to prevent.

**Gate 11a reads the artefact's `captureMethod` as it stands now, where gate 7 asks minority *as at*
`request.at()`.** The whole engine reads current-state artefacts, so this is not new — it became
load-bearing when a child-protection gate started reading one. A decision replayed at an instant
before a later guardian-verified re-capture therefore *allows*.
`GuardianVerificationIT.aReplayedDecisionReadsTodaysCaptureMethod` pins that, deliberately, so
changing it is a decision somebody takes rather than one they discover: making the gate chain-aware
means walking the chain on the decision path, which `CAPACITY.md` §7 rules out against a 2.6 ms p95.

**A child with no consent record at all is denied too**, and that had to be fixed rather than
assumed: the `FAIL_OPEN` branch returns an allowance *before* this gate, so for one build a child
whose consent was recorded but unverified was refused while the same child with nothing on record
was allowed. `aFailOpenPurposeDoesNotLetAChildThrough` pins it.

**No regime but DPDP requires this** — GDPR Art. 8 is a consent-validity rule at collection. Do not
let a document describe it as a GDPR control.

## 7. Migrations

**A migration runs against a database somebody else configured, and it may not assume the
configuration protects it.** On a managed provider the schema owner holds `BYPASSRLS` and the
provider declares default privileges granting its own HTTP-exposed roles — `anon` is the key inside
a browser bundle — `arwdDxtm` on every table and `EXECUTE` on every function the owner creates.
Those are applied **at creation time**, so `deploy/hosted/provision.sql` running before the first
migration cannot protect anything a later migration adds. `V34` therefore revokes `anon`,
`authenticated` and `service_role` from its own function, conditionally, the same shape every
migration here uses for `uds_consent_app`. Any migration adding a function must do the same, and
`deploy/hosted/verify.sql` is what proves it rather than the intent.

`V1`–`V34` exist in `platform/consent-ledger/src/main/resources/db/migration/`; next is **`V35`**.
Removing a table is worse than not running code — speculative surface is flagged dark and the
migration stays. New entity-scoped table ⇒ `entity_id`, an RLS policy on `uds_entity_claim()`
following V13's shape, an index, and a prefix in `EntityAccessGuard` if it has an entity-bearing
route.

## 8. The five recurring defect classes

Each has bitten this programme at least once. Look for them first.

1. **A premise recorded in prose and never re-checked.** `-o` (three planning passes), *"k6 is not
   installed"* (two phases), *"archiving is configured"* (until the first rehearsal). Each cost one
   command to disprove. Check the premise before reasoning from it.
2. **A test asserting the mechanism instead of the property.** The portal's attempt counter
   incremented and was rolled back by the refusal thrown after it — a test asserting the increment
   passed; one asserting *the cap* failed. Assert the property.
3. **A document naming a route that does not exist.** `/v1/admin/integrity/verify`, and a "fictional
   front door" before it. If a document instructs a call, make the call.
4. **A config key the framework never reads.** `otel.exporter.otlp.endpoint` is the OTel SDK's name;
   Boot reads `management.otlp.tracing.endpoint`. Assert the **bound value**, not the property's
   presence.
5. **A Spring conditional that does not mean what it reads like.** `@ConditionalOnMissingBean` on a
   scanned `@Component` is order-undefined; `ConditionalOnEnabledTracing` gates *exporters*, not
   instrumentation; `EndpointRequest.toAnyEndpoint()` matches nothing once the management port is
   separate.

## 9. Operational facts worth knowing before optimising anything

- **Authentication, not the platform, is the throughput ceiling.** Server-side decision p95 is
  **2.6 ms**; client-observed is **115 ms**; a 401 costs the same 113 ms. BCrypt re-hashes the
  credential on every request with no session and no cache, so one instance serves **~50 rps** and the
  ceiling is CPU. A bigger pool, a faster query and more replicas each address the wrong thing.
  **Do not lower the BCrypt strength** — the fix is the authentication scheme, and JWT does not
  re-hash. `CAPACITY.md` §7.
- **There are two rate limiters and they answer different questions. Do not merge them.**
  `RateLimitFilter` (`LOWEST_PRECEDENCE - 120`) is *behind* authentication, keyed by credential and
  route class: that is fairness between callers, and it needs the credential, so it cannot move.
  `PreAuthRateLimitFilter` (`SecurityProperties.DEFAULT_FILTER_ORDER - 10`) is *in front* of the
  security chain, keyed by client address alone, one loose ceiling — that is the flood ceiling, and
  it exists because until it did, invalid credentials produced 401s and **zero** 429s at ~113 ms of
  BCrypt each, an unauthenticated CPU-exhaustion path where refusing cost the defender more than the
  attacker. **Its default is deliberately loose (400/s)**: it cannot tell callers apart, and behind a
  NAT or an ingress without `server.forward-headers-strategy` it is one bucket for a building.
  Tightening it to feel more protective is how a legitimate integrator gets refused.
  **The assertion that proves it works is about order, not about 429**: a request with an invalid
  credential, over the ceiling, must answer **429 and not 401** — reaching the credential check at
  all produces the 401. `PreAuthRateLimitIT`, `OPERATIONS.md` §12.2, `CAPACITY.md` §7.
- **The evidence bundle caps two sections and must say when it did.** Receipts at 100, enforcement
  denials at 200, detected by asking for `cap + 1`; a `truncation` entry names the section, the cap
  and the *ready-to-run* request that returns the remainder. The class javadoc promises "everything
  the platform holds about one person", and for a long-lived principal that was silently false. An
  incomplete copy that states what it omits is a lawful answer under DPDP s.11 and GDPR Art. 15(1);
  one that does not is a claim about the extent of processing that happens to be wrong. **A pointer
  is only honest if the route can deliver it** — that is why `GET /v1/receipts` gained an `offset`.
  **There is deliberately no group-level bundle route**: assembling one person across fifteen
  entities would have to bypass `EntityAccessGuard` and the RLS claim at once, which is precisely the
  hole §2 exists to refuse. The SOP is in `OPERATIONS.md`.
