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
- **There is no git remote, deliberately** — the schema and its seed data are regulated personal data by
  design intent, so a push destination is a decision for UDS. Nothing is committed without an explicit
  instruction. *`CLAUDE.md`.*
