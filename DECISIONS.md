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
- **Contacts and purposes inherit up the entity hierarchy, nearest ancestor winning** — one statement on
  the `UDS` row fixes all fifteen entities' receipts. *PLAN Phase 10, W8.*
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
- **`receivedAt` is bounded in both directions, and only the past bound is configurable** — forward
  beyond `ClockTolerance.SKEW` is refused because it moves the deadline outward; backward beyond
  `uds.consent.rights.max-backdate` because it files a request already in breach. The forward window is
  a shared constant rather than a setting: two definitions of "now" inside one evidence plane is not
  something to make configurable. *PLAN Phase 16, N1.*
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
