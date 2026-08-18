# Roadmap

What has shipped, and what is still open with the check that closes it. The binding date is
**13 May 2027**, when the substantive DPDP Rules become enforceable; Denave is the pilot entity.

`PLAN` means `~/.claude/plans/we-want-to-build-staged-widget.md`, which carries each phase's delivery
record, defects found, and deviations with reasons. `DECISIONS.md` is the index of decisions.

---

## Delivered

| Phase | What it closed | Tests |
|---|---|---|
| 1–3 | The three planes: control, enforcement, evidence. Append-only hash-chained ledger, purpose registry, decision engine, notice serving, rights intake with the statutory clock, vendor registry and RoPA | — |
| 4–5 | Writable control plane, application and vendor authorisation on the decision path, enforcement evidence log, breach notification and clock, retention sweeper, capture-time notice integrity, ISO-shaped receipts | — |
| 6 | TRAI's February 2025 amendment, US states beyond California, GPC | — |
| 7 | Per-entity isolation: `EntityAccessGuard` plus PostgreSQL RLS, derived and proven | — |
| 8 | Consent Manager interoperability, Korea re-derived against the September 2026 PIPA amendment, guardian verification as an evidenced fact, SDF obligation register, subject evidence bundle | 425 |
| 9–10 | Production hardening, W1–W9: actor attribution, rate limiting, probe split, JSON logging, identity resolution, key registry and rotation, rights-fulfilment evidence gate, webhook propagation, partitioning, `deploy/k8s/`, DR runbook, capacity model | 471 |
| 11 | OIDC resource server alongside Basic, Prometheus on port 9090 with alert rules, tracing wired and off, the data-principal portal (Rule 14(1)'s means half), `SigningKeyProvider` | 491 |
| 12 | The claims measured: load profile made runnable and run, restore rehearsed end to end, `TracingIT`, the OpenAPI contract pinned | 495 |
| 13 | Working practice as repository infrastructure: `CLAUDE.md`, the invariants, four subagent roles, `/phase-gate`, the mechanical verify gate | 495 |
| 14 | Primary-source grounding: `docs/standards/` fetched with access dates, `/next-phase`, `DECISIONS.md`, `ROADMAP.md`. Found the receipt's conformance claim overstated | 495 |
| 15 | Phase 14's remainder: `docs/TRACEABILITY.md`, `docs/competitive-analysis.md`, the receipt claim restated, and the withdrawal date put on the receipt. `qa-verifier` then found five matrix grades too generous and two receipt defects | 498 |
| 16 | The three findings the platform's own audits made against itself: the rights-intake clock bounded and its provenance recorded (`V30`), the evidence bundle made to declare its own truncation, and the unauthenticated flood refused before BCrypt | 508 |

---

## Open — the platform's side

Each item's acceptance criterion is a check somebody can run, not an aspiration.

| Item | Done when |
|---|---|
| **An IdP to point at** | A token from the group's provider reaches `/v1/evaluate`, its `entity_id` claim scopes it to one entity (proven by a second entity's record returning 403), and `admin_audit_event.actor_id` carries a human from the token with no `X-UDS-Actor` sent. This is also the throughput ceiling: JWT does not re-hash a credential per request |
| **An OTLP collector** | A span from a portal submission appears in the collector, and its `traceId` matches the one in the log line beside the caller's `correlationId`. The exporter key is now correct; nothing consumes it |
| **Encryption at rest** | The database volume and the WAL archive are both encrypted, and the archive lands in the residency region recorded on the entity — Rule 13(4) reaches the backup, not only the primary. Infrastructure layer; `RUNBOOK_DR.md` §6 |
| **A rehearsal on the group's hardware** | `RUNBOOK_DR.md` §4 walked on real infrastructure, timed, ending in a clean integrity sweep — then §2's RPO and RTO ratified or replaced. The laptop rehearsal measured the procedure, not the target |
| **A preference centre** | Deliberately not scheduled. It needs an authenticated session model for data principals; until then it would be an unauthenticated write path into the ledger. `DECISIONS.md`. **Phase 15 note: the field considers this table stakes** — three of five products torn down ship one, and the decision is worth re-litigating at the Phase 1 gate rather than treated as settled (`docs/competitive-analysis.md` §C.3) |
| **A Google Consent Mode v2 projection** | A GTM property's tags read GCM and cannot see `/v1/evaluate`, so a UDS website's consent decisions are invisible to Google's tags. Done when a decision projects to the seven GCM parameters — computed by the same `consent-core` engine, mapping held as registry data with nearest-ancestor override, never a second policy. `docs/standards/tcf-and-consent-mode.md` §3–4 |
| **Notice translations, 19 of 23 languages** | `GET /v1/notices/reports/coverage` names the gap and correctly refuses placeholder rows. Done when it reports full coverage for the languages Rule 3 requires. Procurement, not code — and the one place a competitor's published claim beats the platform on a statutory obligation (§C.5) |
| **The 27560 Parties and Events remainder** | Recorded rather than half-built: typed party roles, postal addresses, enumerated rights, Consent Type, Expression by Entity. Each is a `partial` row in `docs/TRACEABILITY.md` §6 with the section it fails. **Consent Type is the cheapest** — `CaptureMethod` already encodes implied/expressed/explicit, so a derived accessor fills a mandatory field with no new data. None is an obligation under any clause the group is subject to |
| **`dpv-pd` against the 17 `data_category` codes** | Personal Data Type is mandatory in the record structure and the platform's codes are locally invented, so that axis is unmapped rather than mapped-and-clean. Done when each code carries a `broader` pointer to its nearest DPV term. `docs/standards/dpv-v2-vocabulary.md` §6 |

## Open — UDS's side, which no commit can close

| Decision | Blocks | Done when |
|---|---|---|
| **Sign the rights-fulfilment scope statement and populate `fulfilment_target`** | `FULFILLED` can still be asserted with nothing behind it | The statement is signed and at least one mandatory target exists per entity and request type. *`REGULATORY_HANDOFF.md` §8.5* |
| **Set `dpo_contact` and `grievance_uri` on the `UDS` row** | Every receipt names a null DPO contact, against DPDP Rule 3 | `EntityContactCheck` is silent at start-up. One statement fixes all fifteen entities |
| **Decide how Denave keys a contact** across DenCRM, the HRMS and BGV | Identity resolution ships the mechanism with a safe default; the keying policy is theirs | `uds.consent.identity.strategy` set deliberately, and the rule written down. *§8.1* |
| **Build a consumer for `rights.verification.requested`** | Every portal submission expires unverified, so Rule 14(1)'s published URL would be a dead end | A verification message reaches a real contact and the token verifies. Small integration, hard prerequisite |
| **Decide the identifier list Rule 14(1) requires published** | The portal accepts any single identifier because no list exists to check against | The list is published and enforced at intake |
| **Register the systems that must receive a withdrawal** | `webhook_subscription` is seeded by nothing, so propagation reaches whoever happens to be registered and a system nobody added leaves no trace of not having received it — the same shape as `fulfilment_target` | At least one active subscription exists per entity for the consent topics, and a withdrawal produces a `webhook_delivery` row against each. *`docs/TRACEABILITY.md` §3* |
| **Say what `OPERATOR_ASSERTED` must mean before a console offers it** | The field is free text and the platform cannot check it | A one-paragraph standard — a call-back to a number already on file, an employee ID checked at a desk, a document reference — published to the compliance team, and the share of open requests reading `UNVERIFIED` watched rather than merely queryable. The platform records the claim; what makes a claim adequate is UDS's to state. *`docs/TRACEABILITY.md` §1, Rule 14(3) admin-intake row* |
| **Decide private-key custody** | The public side of rotation is survivable; the private half is still an environment variable | A `SigningKeyProvider` backed by the group's KMS is the configured bean. Now a swap, not a refactor |

---

## Working the roadmap

`/next-phase` is the entry point: re-read `DECISIONS.md` and `docs/standards/` directly, confirm the
previous phase against the criteria above and **flag gaps rather than carrying them**, then plan with
clause citations and wait for approval. `/phase-gate` closes a phase; nothing is committed without an
explicit instruction.
