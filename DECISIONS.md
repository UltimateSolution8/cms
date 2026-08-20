# Decisions

One line per decision, with the reason in a clause and a pointer to where it is argued at length. **This
is an index, not the argument** — the reasoning lives in the code comment, the migration header or the
plan-file section named, and duplicating it here would create a second copy to keep true.

`PLAN` below means `~/.claude/plans/we-want-to-build-staged-widget.md`, which holds the delivery record
for every phase. Read it for the argument; read this to find out which argument you want.

---

## Evidence and schema

- **`consent_event` is not partitioned, `enforcement_decision` is** — a partitioned table's unique
  constraints must include the partition key, and admitting `recorded_at` to `(entity_id, subject_id,
  sequence_number)` and `(entity_id, idempotency_key)` would let the chain fork across a month boundary
  and let a retry crossing midnight be accepted twice. *V28 header; PLAN Phase 10 §1b, deviation 1.*
- **Append-only is enforced by database grants and triggers, not convention** — a guarantee that depends
  on application code is a guarantee until somebody adds a route. *V2; `LedgerAppendOnlyIT`.*
- **A merge aliases, never rewrites** — `subject_alias` plus a `SUBJECT_MERGED` event, because
  `consent_event.subject_id` can never be edited. *V24; PLAN Phase 10, W1.*
- **A receipt pins the consented purpose version**, not the current one, so a taxonomy change cannot
  retroactively alter what a person agreed to. *PLAN Phase 8, H2.*
- **Speculative surface is flagged dark, and its migration stays** — removing a table is worse than not
  running code. *PLAN Phase 10, W8.*
- **Entity isolation is two layers with one resolver** — `EntityAccessGuard.currentEntityClaim` feeds
  both the filter and the connection preparer, because two layers are only two layers if they agree
  about who the caller is. *`EntityAccessGuard` Javadoc; PLAN Phase 11, defect 1.*

- **Propagation state is split from propagation evidence**, in two places rather than one table.
  A single table answering both could never return to zero: a message published before a
  subscription existed is never re-published, so a "still open?" count would have been permanently
  non-zero, the critical alert would have fired for the life of the database, and it would have been
  muted inside a week. Current state is derived from the register — bounded, and it clears when
  configuration is fixed; `propagation_gap` is append-only history that **nothing alerts on**.
  *Plan §R D1.*
- **A propagation gap records a reason, never a conclusion.** `webhook_delivery` is written only by
  the webhook publisher, so under `log` (the default) and `kafka` the platform cannot observe
  delivery at all. Writing `NOT_DELIVERED` there would assert a system was not told when the truth
  is that nobody here can know — the same false statement as answering "no recipients" on a receipt
  where nobody recorded them. Hence `NO_DELIVERY_CHANNEL`. *Plan §R D2; handoff §8.7.*
- **One gap row per system per day, not per event.** `propagation_gap` is partitionable where
  `consent_event` is not — but the unique key that makes it idempotent under three concurrent relays
  would have to include the partition key, at which point a retry crossing a month boundary is
  accepted twice. That is `V28`'s trap exactly, so the rows are bounded instead of partitioned.
  *Plan §R D12.*
- **The reconciler runs after `markPublished`.** Between `publish` and `markPublished` a throwing
  evidence write would land in the relay's catch block, mark the message failed, and cause **a second
  POST downstream because recording a gap failed**. It also swallows its own failures and counts
  them, for the same reason. *Plan §R D10.*
- **The outbox relay stays outside `SweepLock`.** Putting it under the sweep lock would serialise
  fan-out onto one instance — a throughput change Phase 17 had no business making. Idempotency comes
  from the daily unique key; `for update skip locked` on `fetchUnpublished` is the real fix and is
  scheduled as its own change with its own test. *`ROADMAP.md`; plan §P2a.*
- **Propagation targets do not inherit down the entity hierarchy**, stated in the migration header
  and the store javadoc rather than left to be inferred — because rules §3 is the most misread
  section in that file, and Phase 16's C6 found a false inheritance claim had propagated into seven
  documents over three phases, including the reviewer agent's own checklist. *`V31` header.*

## Regulatory posture

- **Korea Art. 62-3 re-confirmation is dark** behind `uds.consent.features.korea-reconfirmation` — the
  silence rule is not in primary text. Re-activating event recorded. *`REGULATORY_HANDOFF.md` §2.*
- **The Rule 4 Consent Manager relay is dark** behind `uds.consent.features.consent-manager-relay` —
  UDS does not register and cannot; it must be able to *transact* with a Consent Manager once the Board
  publishes the standard. *`REGULATORY_HANDOFF.md` §4.*
- **The group's rights-response undertaking is 30 days**, a choice inside DPDP Rule 14(3)'s ninety-day
  ceiling — the Rules set a ceiling, not a figure, and publishing the period is itself the obligation.
  *`StatutoryClock`; `REGULATORY_HANDOFF.md` §3, §7.3.*
- **The statutory clock starts at verification, not submission** — an unverified request is not yet a
  request from the principal, and starting the clock on a stranger's assertion would let anyone burn the
  group's response window for somebody else. *PLAN Phase 11, T4.*
- **`FULFILLED` is gated on evidence** — every mandatory `fulfilment_target` needs a terminal
  `rights_fulfilment_action`; the platform is intake, clock, gate and evidence, and the *acts* happen in
  named systems under a named SOP. *V26; `REGULATORY_HANDOFF.md` §8.5.*
- **Entity contacts inherit up the hierarchy, nearest ancestor winning; purposes do not** — one
  statement of `dpo_contact` and `grievance_uri` on the `UDS` row fixes all fifteen entities' receipts,
  by an iterative walk in `EntityStore`. This line said "contacts *and purposes*" for three phases and
  the purposes half was never true: there is no per-entity purpose configuration to inherit, and
  `REGULATORY_HANDOFF.md` §8.2 records why building one to match a comment was refused.
  *PLAN Phase 10 W8; corrected Phase 16 closure, C6.*
- **The receipt's standards claim is stated against what was actually read** — the ISO/IEC TS 27560 text
  is paywalled and not held, so conformance is claimed against the free W3C DPV rendering and named as
  such. *`docs/standards/README.md`; PLAN Phase 14 G2.*
- **`schemaVersion` says `-receipt-subset`, because the platform chose honesty over conformance and was
  claiming both** — `recipients` and `retentionPeriod` are `1..*` in the record structure and nullable
  here, since answering "none" where the truth is "unrecorded" would be a false statement to a principal.
  Two words made the claim true; the receipt did not change. *`ConsentReceipt.SCHEMA_VERSION` javadoc;
  `docs/standards/iso-27560-consent-records.md` §4; PLAN Phase 15 R2.*
- **The receipt carries `withdrawnAt`** — a document reading `"status": "WITHDRAWN"` beside a grant date
  and no withdrawal date withheld the one date a grievance turns on. The value was already on the
  artefact projection. *PLAN Phase 15 R2; `ReceiptIT`.*
- **TCF is an adapter and its consent string is never an input** — a TC String has no subject identifier,
  is a state rather than a history, overwrites its own `Created` on every update, and its policy-change
  rule mandates discarding the prior answer. Emit one as a disposable projection if adtech ever needs it;
  never store one as the record. *`docs/standards/tcf-and-consent-mode.md` §2, §5.*

## Front door

- **JWT sits alongside Basic auth, and is off unless configured** — replacing Basic would be a flag day
  for the dialer, DenCRM and every capture surface at once, and an unconfigured decoder rejects
  everything as a 500 on the auth path. *PLAN Phase 11, T1 and its deviations.*
- **Scopes map onto the four existing roles**, so not one of the forty route matchers changed — the
  authorisation model was already right; only the authentication was weak. *`JwtRoleConverter`.*
- **`X-UDS-Actor` is required under Basic, ignored under a token** — a claim an IdP signed beats a header
  a client asserted, and preferring the header would leave a spoofable path open under the scheme adopted
  to close it. *PLAN Phase 11, T1.*
- **Attribution is deliberately ignored on machine routes** — a dialer and a website are systems, not
  people; honouring the header there would let any capture surface write a name into evidence about
  something no person did. *PLAN Phase 10 §1b, deviation 2.*
- **No `actor-claim` property** — a fixed `preferred_username` → `email` → `sub` precedence reaches the
  same answer with no knob to misconfigure, and `sub` is last because an opaque uuid in an append-only
  audit trail identifies a person only to whoever still has the provider's directory. *PLAN Phase 11.*
- **The metrics endpoint is on management port 9090** — `/actuator/prometheus` publishes denial reasons,
  capture volumes and rights-queue depth, which is operational intelligence about a regulated system.
  *PLAN Phase 11, T2.*
- **BCrypt strength is not lowered.** It costs ~110 ms per request and caps one instance near 50 rps, and
  the fix is the authentication scheme rather than the work factor. *`CAPACITY.md` §7; `OPERATIONS.md`
  §12.2.*

## Principal-facing surface

- **`/v1/portal/**` is intake and status only — not a preference centre.** Letting a principal flip
  consents from the open internet needs a session model the group does not have, and it opens a write
  path into the ledger from an unauthenticated surface, which is the one thing two layers of isolation
  exist to refuse. *PLAN Phase 11, T4.*
- **The portal answers identically for a known and an unknown identifier** — anything else is a
  subject-enumeration oracle against regulated data, reachable without a credential. *`PrincipalPortalIT`.*
- **The platform sends nothing.** It mints a verification token, stores only its hash, and emits an
  outbox event for whatever system sends messages. That boundary has held since V1. *PLAN Phase 11, T4.*

## Operations and deliberate absences

- **No Helm chart** — `deploy/k8s/` is six manifests and a Kustomize overlay is the cheaper answer if
  per-environment values are ever actually needed. No document promises one. *PLAN Phase 12, non-goals.*
- **No Redis-backed rate limiter** — the limiter is per instance and the limitation is documented; a
  fleet-wide limiter needs a Redis that does not exist. *PLAN Phase 12, non-goals.*
- **Two rate limiters, not one** — `RateLimitFilter` stays behind authentication because a
  per-credential ceiling needs the credential; `PreAuthRateLimitFilter` runs in front of the security
  chain keyed on the client address alone. Merging them would mean either giving up per-credential
  fairness or leaving the BCrypt amplification open. *PLAN Phase 16, N3; `OPERATIONS.md` §12.2.*
- **The pre-authentication ceiling is deliberately loose (400/s)** — it cannot tell callers apart, so
  behind a NAT it is one bucket for a building. It is a flood ceiling, not a fairness limit, and
  tightening it to feel more protective is how a legitimate integrator gets refused. *PLAN Phase 16, N3.*
- **`UNVERIFIED` rights intake is labelled, never refused** — parking requests outside the statutory
  clock until somebody fills in a field produces the outcome Rule 14(3) penalises. Record the silence;
  do not let it read as diligence. The V30 backfill defaults to `UNVERIFIED` for the same reason: any
  other default would write a claim of verification that never happened. *PLAN Phase 16, N1.*
- **`receivedAt` is bounded in both directions, and the two bounds do different jobs** — forward
  beyond `ClockTolerance.SKEW` is refused because it moves the deadline outward, which is a real
  compliance hole; backward beyond `uds.consent.rights.max-backdate` is a **sanity window only**, and
  the claim that it prevents "a request already in breach" was false for three phases — every computed
  period is shorter than the bound, so a late filing inside it is accepted and *is* overdue. That is
  the right outcome, and the fact is recorded as `bornOverdue` on the audit event instead. The forward
  window is a shared constant rather than a setting: two definitions of "now" inside one evidence plane
  is not something to make configurable. *PLAN Phase 16, N1 and C1.*
- **No group-level evidence bundle route** — assembling one person across fifteen entities would have
  to bypass `EntityAccessGuard` and the RLS session claim simultaneously, which is the hole Phase 11
  spent a defect learning to close. The fifteen-call assembly is an SOP instead, and an SOP is weaker
  than a route and is the right trade. *PLAN Phase 16, N2; `OPERATIONS.md` §12.2a.*
- **The bundle declares truncation rather than counting the remainder** — detected by asking each store
  for `cap + 1`. Counting the total would mean a scan over an entity-scoped, month-partitioned table
  under an RLS predicate on every bundle, to turn "there are more" into "there are 1,347 more" when the
  reader's next action is the same either way. *PLAN Phase 16, N2.*
- **No pgcrypto columns for encryption at rest** — it belongs at the infrastructure layer and is recorded
  as a requirement rather than built. *`RUNBOOK_DR.md` §6.*
- **Private-key custody is an SPI, not a key accessor** — `SigningKeyProvider` exposes `sign`, never
  `getPrivateKey`, because in a KMS the private key never leaves the appliance and an SPI shaped around
  handing back a key cannot be implemented by the thing it exists to allow. *PLAN Phase 11, T5.*
- **RPO 5 min / RTO 60 min remain proposed.** The 17 August 2026 rehearsal measured the *procedure* on a
  laptop — seventeen seconds of machine time with no instance to provision and no incident to diagnose —
  which is not ratification. *`RUNBOOK_DR.md` §2, §5.1.*
- **k6 is not in CI** because a fifteen-minute ramp against a million-subject database is a scheduled
  exercise, not a pull-request gate. That is a different sentence from "k6 is not installed", and only
  one of them was ever true. *`.github/workflows/build.yml`.*
- **The verification gate is on the *claim*, not the act, and never at intake.** `FULFILLED` is refused
  409 on a disclosing or destructive right while `verification_method` reads `UNVERIFIED`. It does not
  refuse intake — GDPR Art. 12(2) forbids refusing to act on a request — and it does not gate disclosure
  itself, because `GET /v1/admin/evidence/subject/**` is unlinked from any `rights_request`. What it buys
  is that the moment somebody wants a request marked answered, which is the cheapest moment there is,
  they must first say who they checked. *PLAN Phase 18, Q2; `ROADMAP.md` for the unlinked route.*
- **Six types are gated and three deliberately are not.** `ACCESS`, `PORTABILITY`, `ERASURE`,
  `CORRECTION`, `COMPLETION`, `NOMINATION` disclose or irreversibly change a person's data.
  `CONSENT_WITHDRAWAL` and `OPT_OUT_OF_SALE` are never gated — DPDP s.6(4) and GDPR Art. 7(3) require
  withdrawing to be as easy as consenting was, and consent is a checkbox with no identity check at all.
  `GRIEVANCE` is ungated on a **risk argument rather than a clause**: it is a complaint coming in, not a
  file going out. *PLAN Phase 18, Q2.*
- **DPDP does not require identity verification before answering a rights request, and no document here
  may say it does.** Checked against ss.11, 13, 15 and Rules 13, 14: Rule 14(1)(b) is a
  publish-your-own-requirements duty and s.15 places the duty on the *principal* not to impersonate. The
  support is GDPR Art. 12(6) ("may request", on reasonable doubt) and Recital 64 ("should", access only).
  Same posture as Art. 19 — the platform exceeds DPDP, which is a position to state rather than a gap
  being filled. *PLAN Phase 18, clause table; `docs/TRACEABILITY.md` §1.*
- **Verification is written once.** The `where` clause requires the row still to be `UNVERIFIED`, so two
  operators cannot race into a silent overwrite. It is evidence about what a named person did; if it is
  wrong, that is a correction to make deliberately, and the superseding-record shape is a design question
  on `ROADMAP.md` rather than an edit path shipped by default. *PLAN Phase 18, Q1.*
- **`PORTAL_TOKEN` cannot be asserted by an operator.** It is the platform's own label for a principal
  who redeemed a token it minted and checked; letting a human type it would put the strongest claim the
  platform makes behind a say-so. `PrincipalPortalService` stays its only writer. *PLAN Phase 18, Q1.*
- **A notice served never asserts a consent status — in the projection *and* on the wire.** `NOTICE_SERVED` resolving to `NOT_ASKED` was
  allowed to overwrite a live grant in `consent_artefact` — which is what the decision engine reads —
  destroying the status, the expiry, the capture method and the channel, with the grant still in the
  ledger and every hash valid. The projection now carries the artefact forward and updates the notice
  fields alone. Where no artefact exists it still creates `NOT_ASKED`, which is the s.7(i) workforce path.
  **The fan-out was fixed in the same phase, after `qa-verifier` found it:** `ConsentLedger` published
  the event's own `resultingStatus`, so downstream systems were told `NOT_ASKED` with `restrictive: true`
  while the projection said `GRANTED`. The payload now carries the **projected** status — the state
  after the event, which is the projector's answer — and `NOTICE_SERVED` is not flagged restrictive.
  A projection and a wire that disagree is worse than the original defect, because neither looks wrong
  alone. *PLAN Phase 18, Q3.*
- **There is no git remote, deliberately** — the schema and its seed data are regulated personal data by
  design intent, so a push destination is a decision for UDS. Nothing is committed without an explicit
  instruction. *`CLAUDE.md`.*

## Phase 19 — the controls that could not be trusted yet

- **The projection reconciliation sweep reports and does not repair.** A projector defect and a
  direct `UPDATE` of `consent_artefact` produce an identical divergence, and only one of them is a
  security incident. Repairing automatically would erase the distinction before anyone saw it.
  Argued in `ProjectionReconciler`'s javadoc and in `OPERATIONS.md` §3.
- **The reconciler reuses `ArtefactProjector`'s fold rather than writing its own.** A second fold
  would drift and then either report divergence where there is none or — far worse — agree with
  itself exactly where the real projector is wrong, which is the case it exists to catch.
- **No table for divergences.** A divergence is current state that returns to zero when it is fixed;
  an append-only count can only grow, so an alert on it fires forever and is muted within a week.
  Same argument as `propagation_gap` versus the register (Phase 17, D1). The chain is the evidence.
- **`sweep_run` is current state, in the database, and not evidence.** `SweepLock` runs a sweep on
  one instance at a time and the deployment runs three replicas, so an in-memory last-run timestamp
  reads "never ran" on the two that skipped — permanently — and an alert over it can never clear.
  Mutable and one row per sweep: the append-only alternative is ~43,000 rows a day from the relay
  alone to answer what one row answers. Deliberately absent from `LedgerAppendOnlyIT`'s list.
- **A sweep with no record reports no age, never zero.** Zero renders as "finished just now", which
  is the healthiest possible value for the exact condition being watched. That is why `alerts.yaml`
  needs `absent()` beside the threshold rule.
- **The relay claims with `for update skip locked`, not a `SweepLock`.** The advisory lock would
  serialise fan-out onto one instance, which is a throughput change; `skip locked` lets all three
  replicas work on disjoint rows. Historical duplicate `webhook_delivery` rows are **not**
  de-duplicated — three replicas really did make three attempts, and that is append-only evidence.
- **`system_code` is keyed to a declared vocabulary the database enforces — not validated against
  *subscriptions*.** (Both `PUT` routes also refuse an undeclared code, which is where an operator
  meets it; the foreign key is what makes the vocabulary authoritative rather than advisory.)
  "Refuse a code no subscription carries" is circular: a target must be registrable *before* its
  subscription exists, which is the register's whole point. V26's argument against a foreign key on
  `fulfilment_target.system_code` is re-argued rather than inherited, and the asymmetry decides it —
  there a mismatch fails loud and closed, here it failed quiet and wrote to an append-only table.
- **A subscription's `system_code` is settable.** It was derived from the subscription id and never
  updated, so correcting a mismatch meant deleting and recreating the subscription, discarding the
  `webhook_delivery` history that proves withdrawals reached that system. Fixing a configuration
  mistake must not cost the evidence that the system was working.
- **The children's gate is scoped to consent-based decisions, and that is a position rather than
  what the clause compels.** DPDP s.9(1) reads *"before processing any personal data of a child"*,
  arguably wider than consent-based processing. Placing the gate after gate 10 means a legitimate
  use or legal obligation — s.7(i) employment — never reaches it. Taken under the instruction not to
  over-engineer the legal-policy side; s.9(4)'s exemption power has not been exercised.
- **`CHILD_GUARDIAN_NOT_EVIDENCED` is a separate reason from `CHILD_SUBJECT_RESTRICTED`.** One says
  the purpose is closed to children however consent was given; the other says the purpose is open to
  them and the consent relied upon records no verified guardian. An operator told the wrong one
  fixes the wrong thing.
- **No regime but DPDP requires a decision-time guardian check.** GDPR Art. 8 is a consent-validity
  rule at collection. Same posture as Phase 17's Art. 19 row and Phase 18's verification gate: the
  platform doing more than a regime asks is a position to state, not a gap being filled.

## Phase 20 — making the last three phases' controls provable

- **The projection report is counts group-wide and identifiers per entity.** The sweep must be
  group-wide — it re-derives every chain in the database — but the answer must not be. A route
  returning `entityId`, `subjectId` and `purposeCode` for every fiduciary sits outside both
  isolation layers at once: `EntityAccessGuard` has no entity to read, and RLS is not in the path
  because the sweep, not the request, gathered the rows. The only shipped ADMIN credential is
  group-wide by design, so there was no exploit — but the configuration supports a per-entity one,
  and rules §9 already refuses this exact shape for the evidence bundle. Identifiers moved to
  `GET /v1/admin/projection/divergences?entityId=`, which the guard covers through the query
  parameter it reads on any path.
- **The cap is on what the sweep retains, never on what it counts.** A systemic projector defect
  produces one divergence per artefact, which is the case the control exists for — so capping the
  count would understate precisely the finding that matters most, while an unbounded list sits in
  memory on the instance that ran the sweep. The count stays exact and the retained sample says
  what it left out, the evidence bundle's discipline applied one route over.
- **Gate 11a's current-state reading is pinned rather than changed.** It reads the artefact's
  `captureMethod` as it stands now, where gate 7 asks minority as at the decision instant. Making
  it chain-aware means walking the chain on the decision path, which `CAPACITY.md` §7 rules out
  against a 2.6 ms p95, and it would need the architect review that Phase 19 could not obtain. A
  test now names the behaviour so that changing it is a decision rather than an accident.
- **`sweep_run` deliberately has no work counter.** It records that a job ran, not that it did
  anything, and `last_outcome = OK` is written whenever the body returned. Adding a per-sweep item
  count would need a migration and would still not answer the real question, because what a sweep
  *should* have found differs for every one of them. The answer is to watch each sweep's own output
  where it has one; the limit is stated in `OPERATIONS.md` §4.0b and rules §1 rather than papered
  over.
- **One `Truncation` record, not two.** The projection route reuses
  `EvidenceBundleService.Truncation` rather than declaring its own. Two identically-named records
  collide in the OpenAPI schema namespace, and two identically-shaped ones drift.
- **Every sweeper's `Report` carries an explicit OpenAPI schema name.** Six classes were named
  `Report`, springdoc collapsed them onto one `Report` schema, and whichever won described all six
  routes — so `docs/openapi.json` stated the wrong shape for five of them and `OpenApiContractIT`
  could not detect drift in any. The pin existed and did not cover what everyone believed it did.
  Distinct `@Schema(name = …)` per class; a simple-name collision is now a documentation defect
  somebody has to make deliberately.

## Phase 21 — the browser front door

- **CORS is an allowlist of exact origins, empty by default, and its filter runs ahead of the
  pre-authentication rate limiter.** Empty means every existing machine caller is unaffected.
  `.cors(withDefaults())` was rejected: it places the filter behind `PreAuthRateLimitFilter`, whose
  429 carries no `Access-Control-Allow-Origin`, so a rate-limited browser is told its origin is
  wrong — a diagnosis pointing at the wrong subsystem. Argued in `CorsConfiguration`'s javadoc;
  proven by `CorsIT.preflightsOutrunTheFloodCeiling`, which fails when the order is changed.
- **`allowCredentials` is false and a wildcard origin will not be added.** The flag governs *ambient*
  credentials — cookies, TLS client certs — and this platform is stateless and uses none. A bearer
  token is an ordinary header the client sets deliberately.
- **A token's entity may come from an app role `entity.<ID>` as well as from an `entity_id` claim.**
  Entra cannot mint a custom claim for a custom API without a claims-mapping policy and a custom
  signing key; app roles need neither and are assigned to people rather than to applications.
  **Keying the entity on `azp` was considered and rejected** — in a browser flow that is one value
  for every human who signs into the console, so the isolation boundary would become which
  application you authenticated to.
- **Two `entity.*` roles on one token are refused, not resolved.** First-wins would let a set's
  iteration order decide which fiduciary a caller reads, and both isolation layers would agree on
  the wrong answer.
- **Granted values are the union of `scope`, `scp` and `roles`, not the first non-empty one.** An
  Entra delegated token carries `scp: "openid profile"` — none of it mapped — beside
  `roles: ["consent.admin"]`, which is the actual grant. First-wins refuses a valid token and names
  the wrong claim in the diagnostic. A union cannot over-grant: the lookup is an allowlist.
- **A token that authenticates and maps to no role now logs at WARN**, naming both the claims
  inspected and the claims the token carries. The previous guard required a non-empty scope set, so
  the one case worth catching — a claim name the provider does not use — logged nothing at all.
- **The OpenAPI document declares its security schemes, its refusals and a `ProblemDetail` schema,
  globally rather than per route.** Annotating 120 handlers would go stale the first time one was
  added without the annotation. It deliberately does **not** state which role a route needs —
  OpenAPI cannot express "ADMIN or CAPTURE", and `docs/UI_CONTRACT.md` carries that table instead.
- **Colliding operation ids are named by handler, not by the id springdoc would have generated.**
  Keying on `forSubject_2` would stop applying the moment that numbering shifted, which is the
  fragility it exists to remove.

- **422 is deliberately not among the documented refusals.** A4's text listed
  `400/403/404/409/422/429`; the published set is `400/401/403/404/409/429/500`. `POST /v1/consent`
  and the Consent Manager grant return 422 with a normal response body carrying `violations[]` —
  a domain answer, not an error — so describing it with the `ProblemDetail` schema would tell a
  generated client to parse it as one. Recorded here as well as in `docs/UI_CONTRACT.md` §8,
  because a deviation explained in one place and not the index is how it gets re-litigated.
- **The WARN on `Actor`'s fallback to `sub` was not built in Phase 21.** It is three lines and it is
  the item that survives somebody re-creating the Entra app registration, so it is a real deferral
  rather than a scope call. On `ROADMAP.md` with the optional-claims configuration it belongs with.

## Phase 22 — the hosted environment

- **The schema stays in `public` on Supabase and is locked down there, rather than moved to a
  schema the Data API does not expose.** The review's first-choice fix was the move, and it is the
  better shape in the abstract. `V13__row_level_security.sql`'s policy loop is
  `where table_schema = 'public'`: moving the schema without editing that migration would have
  created **zero RLS policies and logged nothing**, trading a reachable-but-revoked schema for an
  unreachable one with no isolation inside it, on a database where `service_role` holds `BYPASSRLS`.
  So `provision.sql` revokes `anon`, `authenticated` and `service_role` from schema `public` **and**
  their default privileges, and `verify.sql` check 4 asserts the resulting state rather than the
  intent. Revisit if the Data API is ever re-enabled or a second provider is adopted; `V13` is the
  file that changes first, and it changes in the same commit as the move or not at all.
- **Partition creation moved into a `SECURITY DEFINER` function (`V34`) rather than into a second
  data source.** An owner-credentialled `DataSource` inside the application would bypass every V13
  policy for whatever else picked the bean up — a larger hole than the one being closed. The
  function hardcodes its parent table: a parameterised `create table … partition of $1` running as
  the owner is a privilege-escalation primitive with a friendly signature, so a second partitioned
  table gets a second function and `PartitionStore` refuses any other name loudly.
- **`V34`'s function must never read an entity-scoped table**, and that is a property of the
  environment rather than of the code. On a managed database the definer is the provider's role,
  which holds `BYPASSRLS`; a `select` inside the function would cross every fiduciary boundary with
  nothing able to observe it. Stated in the migration header because the next person to extend the
  body will not derive it.
- **The partition-level append-only revoke was added, and its severity was measured rather than
  assumed.** `V28` revoked `UPDATE`/`DELETE` on the parent and the provisioning scripts' default
  privileges granted them on every child; PostgreSQL checks DML on the relation named in the query,
  so `update enforcement_decision_2026_08` reached the permission check while `update
  enforcement_decision` did not. **The row trigger then refused the write** — verified against a
  real database, not reasoned from documentation — so this was one of two layers missing, not an
  open hole. Recorded at that severity. It is still closed, because the reason the platform keeps
  two layers is that either one can be got wrong.
- **The `hosted` profile pins the session-mode pooler and cannot be pointed at transaction mode by
  configuration alone.** `EntityScopedDataSource`'s entity claim and `SweepLock`'s advisory lock are
  both session state. Transaction pooling was tested and appeared to preserve the claim; the profile
  records that the test proves nothing, because one idle connection is never reassigned. A comment
  that only says "do not use 6543" invites somebody to check for themselves and get the reassuring
  answer.
- **A migration that creates a function revokes the provider roles from it, in the migration.**
  Found by applying `V34` to the real hosted project: it came into existence with `anon=X` on its
  ACL — a `SECURITY DEFINER` function that creates tables, executable with the key that ships inside
  a browser bundle — because the provider declares `alter default privileges … grant execute on
  functions to anon, authenticated, service_role`, and `revoke … from public` does not remove a
  direct grant. The durable lesson is the general one: **default privileges are applied at object
  creation time, so a provisioning script that ran before the first migration can never protect an
  object a later migration creates.** `deploy/hosted/provision.sql` was missing the `on functions`
  clause entirely and now carries it, and says to run the file again after `flyway migrate` — but
  the migration defends itself rather than relying on either.
- **`verify.sql` check 4 covered tables while its heading claimed the evidence plane.** It read
  `information_schema.table_privileges` and returned empty, and that empty result was read as the
  whole answer for a phase. It now also checks function ACLs and prints the schema's `USAGE` grant,
  because revoking a schema from `anon` leaves `GRANT USAGE … TO PUBLIC` standing and `anon` is a
  member of PUBLIC like everything else. A check that covers a subset of what its heading claims is
  worse than no check.

## Phase 23 — two issuers

- **One issuer per environment, and deliberately no trusted-issuer list.** Keycloak in development
  and CI, Entra in production, each configured through `OIDC_ISSUER_URI`. `JwtIssuerValidator`
  compares `iss` verbatim against exactly one configured value. Building multi-issuer machinery to
  avoid two lines of per-environment configuration is over-engineering, and the cost of the simple
  answer is one real failure mode that is now written down: Entra's default
  `accessTokenAcceptedVersion` issues v1.0 tokens whose `iss` is `https://sts.windows.net/<tid>/`,
  which will be refused. `OPERATIONS.md` §2.3a item 1.
- **The realm export is the artefact, not a page of instructions.**
  `platform/docker/keycloak/uds-realm.json` is committed and imported by
  `docker compose --profile auth up`. A realm somebody clicked together in a UI is a realm nobody
  can reproduce, and this programme has already priced what an unrehearsed procedure is worth
  (`RUNBOOK_DR.md` §5). The realm exercises **both** entity paths deliberately — a hardcoded
  `entity_id` on the machine client and an `entity.<ID>` app role on a user — because Phase 21 built
  two and only one had ever met a real issuer.
- **`KeycloakIssuerIT` is tagged and excluded from the default build**, so an ordinary
  `mvn -B verify` does not pull a 450 MB image. **It therefore contributes nothing to the baseline
  count**, and that is stated rather than left for a reader to infer coverage from a number. The
  exclusion is on the failsafe plugin rather than on the tag alone, because JUnit inherits
  class-level tags from `PostgresIntegrationTest` and the tag by itself would have excluded far more
  than intended.
- **`preferred_username` absent is a WARN, not a refusal.** `Actor` falls back to `email` and then
  to `sub`, and on Entra `sub` is a pairwise identifier — meaningless outside the directory,
  different per application — written permanently into an append-only audit table. Refusing the
  request would be worse: it would make an administrative act impossible because of a claim
  configuration, which is a fault the operator cannot fix at the console. So the platform records
  what it has and says loudly that it had to. Same shape as `JwtRoleConverter`'s zero-authorities
  WARN and for the same reason — a control that fails silently is defect class 4.
- **The IdP is a start-up dependency, and that is the right failure.**
  `JwtDecoders.fromIssuerLocation` fetches the discovery document when the bean is created, so with
  an issuer configured and the provider unreachable the application does not start. Starting with
  authentication silently disabled would be worse. The consequence is real and belongs in a
  deployment ordering rather than in a workaround: a rollout that brings the platform up before its
  issuer will crash-loop. `OPERATIONS.md` §2.3a.
- **The capacity comparison changed the load, never the bar.** `perf/k6/common.js` emits `Bearer`
  when `DECISION_TOKEN` is set and the Basic default is byte-identical to what it was, because
  `CAPACITY.md` §7's numbers were measured under Basic and a profile whose baseline moves silently
  measures nothing. §9 records the result, including the part that was **not** expected: the engine
  was faster at 153 rps than at 34 rps, because the 34 rps run was the first traffic after a
  restart. Those cold figures are quoted only as half of a matched pair and are labelled so.
