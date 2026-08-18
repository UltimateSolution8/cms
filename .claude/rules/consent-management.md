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

## 2. Isolation is two layers, and they must agree

- Layer one: `EntityAccessGuard` (`consent-service/.../config/EntityAccessGuard.java`) refuses a
  request naming an entity the caller is not scoped to, from the **query string and path only** —
  bodies are not parsed, because reading the stream in a filter consumes it.
- Layer two: PostgreSQL RLS, `V13__row_level_security.sql`, pushed into the session by
  `EntityScopedDataSource`. It applies to every statement whatever route the value took.
- **One resolver feeds both:** `EntityAccessGuard.currentEntityClaim(...)`. Phase 11's worst defect was
  the two layers disagreeing about who the caller was — a bearer token's subject is not in the client
  map, resolved to "no claim", and no claim reads as *group level*, so a token scoped to Denave read
  every entity in the group through both layers at once with nothing logged. Never resolve a caller's
  scope anywhere else.
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
- **`UNVERIFIED` refuses nothing, and that is deliberate.** The label is not a gate: parking requests
  outside the clock until somebody fills in a field produces the outcome Rule 14(3) actually
  penalises. Same posture as the empty `fulfilment_target` register — record the silence, never let
  it read as diligence. The migration's default is `UNVERIFIED` for the same reason: labelling
  pre-V30 rows otherwise would write a false statement into evidence.
- `/v1/portal/**` is the only unauthenticated write surface. It must answer **byte-identically for a
  known and an unknown identifier** or it is a subject-enumeration oracle against regulated data,
  reachable without a credential. Status reads return status and dates — **never** the evidence
  bundle; a token mailed to an address is not the standard on which to hand over a person's file.

## 7. Migrations

`V1`–`V31` exist in `platform/consent-ledger/src/main/resources/db/migration/`; next is **`V32`**.
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
