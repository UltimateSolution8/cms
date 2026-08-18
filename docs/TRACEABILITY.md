# Traceability matrix — clause to behaviour to test

Every obligation this platform claims to discharge, mapped onto a named field, route or behaviour, and
onto **the test that proves it**. Almost no commercial CMP publishes one; it is simultaneously the proof
of the platform's completeness and the document an audit asks for.

**How to read it, and how to break it.** The *Evidence* column names a real suite. Take any row, run its
suite, and follow the *Behaviour* column to the code. **A row with no test is the finding, and stays in
the table as one** — deleting it would turn this into a brochure. Where a suite has one test that is
precisely the obligation, its `@DisplayName` is quoted, because a suite name proves less than an
assertion does.

**Status** is exactly one of:

| | Meaning |
|---|---|
| **satisfied** | Behaviour exists and a test asserts the obligation |
| **partial** | Some of the obligation is discharged; the remainder is named in the row |
| **configuration** | The platform provides the mechanism; the obligation is discharged by seed data, the purpose registry or an operator setting |
| **UDS decision** | Not a commit. `REGULATORY_HANDOFF.md` §8 is the list; a row here points at it |

The last two are **not failures so long as they are labelled**. Clause citations and commencement dates
come from `/regulatory-clause-map` and the module Javadocs, which are the authority; the standards rows
come from `docs/standards/`, whose sources and access dates are recorded there.

Run everything with `cd platform && mvn -B verify` (**508 tests, 0 failures**). A single suite — and
**`-am` is not optional**, because `-pl` alone resolves `consent-core` from the local repository rather
than the reactor and every fixture dies on `NoSuchMethodError`:

```bash
cd platform && mvn -B verify -pl consent-service -am -Dit.test=ReceiptIT -Dfailsafe.failIfNoSpecifiedTests=false
```

For a unit suite in `consent-policy`, the same shape with `-Dtest=` and
`-Dsurefire.failIfNoSpecifiedTests=false`. Both invocations were run while writing this file, which is
how the missing `-am` was found.

---

## 1. India — DPDP Act 2023 and DPDP Rules 2025 *(substantive Rules bind 13 May 2027)*

| Clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| s.6(1) | Consent is free, specific, informed, unconditional and unambiguous, by clear affirmative action | Capture refused at validation where any limb fails; consent is **per purpose and per purpose-version**, never a subject-level boolean | `CaptureValidatorTest` — *"a clean, itemised submission is accepted"*; `GoldenDecisionSuiteTest` | satisfied |
| s.6(1) | Consent is limited to the personal data necessary for the specified purpose | Purpose registry binds `dataCategories` per purpose; the receipt states them per entry | `ReceiptIT` — *"every purpose gets its own entry; nothing is collapsed into one line"* | satisfied |
| s.6(4) | Withdrawal as easy as giving | `POST /v1/consent/withdraw` — same route family, no re-authentication, and a withdrawal is same-day rather than a rights request with a period | `ConsentLifecycleIT`, `ConsentApiIT`, `StatutoryClockTest` | **partial** — the platform-side path is symmetric and tested. **"As easy" is a property of the interface a person actually uses, and the platform ships none**, so no test asserts effort-equivalence and none can. Same reasoning as Rule 8 below |
| s.6(5) | Consequences of withdrawal borne by the principal; processing ceases | Withdrawal denies the next decision immediately, before any sweeper runs | `ConsentLifecycleIT`; `GoldenDecisionSuiteTest` | satisfied |
| s.6(6) + **Rule 3** | Notice carries the required elements, in the required languages; the principal is given DPO contact and a grievance route | Notice served with an integrity hash and version; receipt carries `dpoContact` and `grievanceUri`, **resolved up the entity hierarchy**; three specific Rule 3 links, not a contact page | `NoticeIT` — *"the current notice renders with the three URIs Rule 3 requires"*; `ReceiptIT` — *"the receipt carries the Rule 3 links and the notice it was given against"* | **partial** — the mechanism is complete; **19 of 23 languages have no text** (`GET /v1/notices/reports/coverage` reports the gap and refuses placeholder rows), and `dpo_contact` on the `UDS` row is unset |
| s.6(6) | The principal can withdraw as easily as consent was given, via a stated means | `withdrawalUri` on every receipt, read off the notice version served | `ReceiptIT`; `NoticeIT` | satisfied |
| s.7(a) | Legitimate use — voluntarily provided for a specified purpose | `LegalBasis.LEGITIMATE_USE_VOLUNTARY` exists and the engine permits on it without a consent record | — **no test exercises s.7(a).** The only fixture carrying the basis is `WEB_STRICTLY_NECESSARY`, and the sole test evaluating that purpose runs under UK jurisdiction where the fixture's basis is `CONTRACT_PERFORMANCE` | **partial — the finding is the row.** `CaptureValidatorTest`'s refusal-to-record-consent case uses `HR_EMPLOYMENT_ADMIN`, which is s.7(**i**) |
| s.7(i) | Legitimate use — employment purposes | `LegalBasis.LEGITIMATE_USE_EMPLOYMENT`, per-purpose in the registry; consent is refused *against* it, because asking for consent where consent is not the basis is itself a violation | `ConsentLifecycleIT`; `CaptureValidatorTest`; `GoldenDecisionSuiteTest` | satisfied |
| s.8(1)–(2) | Fiduciary is accountable for processor compliance and must have valid consent | Vendor registry authorises per purpose on the decision path; a vendor without a signed DPA is registered **and reported**, not silently refused | `RopaIT` — *"a vendor with no signed DPA is registered and reported, not refused"*, *"vendor authorisation is per purpose, not blanket"* | satisfied |
| s.8(5) | Reasonable security safeguards | Peppered identifier hashing, RLS, database-enforced append-only, role separation | `RowLevelSecurityIT`, `LedgerAppendOnlyIT`, `IdentifierHasherTest` | **partial** — encryption at rest is an infrastructure requirement, recorded in `RUNBOOK_DR.md` §6 and not built |
| s.8(6) + **Rule 7** | Breach notification to the Board and to each affected principal | `BreachClock` files three obligations — two without delay, one at 72 hours; a breach cannot be closed while anybody is still owed one | `BreachIT` — *"Rule 7 files three obligations: two without delay, one at 72 hours"*, *"a breach cannot be closed while anybody is still owed a notification"* | satisfied |
| s.8(7) | Erase on withdrawal or when the purpose is no longer served | `RetentionSweeper` **proposes** erasure and never deletes evidence; the acts happen in named systems | `RetentionIT` — *"a dormant subject is proposed for erasure, and their evidence is untouched"* | **partial** — proposal and evidence are the platform's; the erasure act is a `fulfilment_target` (§8.5) |
| s.9(1) + **Rule 10** | Verifiable parental consent, with due diligence on the guardian | Guardian verification is an **evidenced fact in the hash chain**, not a flag; capture refused without it; the raw reference is deliberately not on the receipt | `GuardianVerificationIT` — *"a child capture with no record of the diligence writes nothing"*, *"the verification lands in the hash chain, and the raw reference lands nowhere"* | satisfied |
| s.9(3) | No tracking or targeted advertising directed at children | Purpose-level refusal for a minor subject, **however consent was given** — and a child declared on the request alone triggers it | `GoldenDecisionSuiteTest` — *"s.9 closes advertising to a subject under eighteen however consent was given"*; `GuardianVerificationIT` | satisfied |
| **Rule 4** | Consent Manager framework *(operational 13 November 2026)* | UDS cannot register (First Schedule independence) but must be able to **transact** with one: `/v1/consent-manager/**`, relayed consent visible **as relayed** in evidence | `ConsentManagerIT` — *"the relay is visible as a relay in the evidence, not folded into first-party consent"*; `FeatureFlagIT` | **configuration** — dark behind `uds.consent.features.consent-manager-relay` until the Board publishes the standard. Handoff §4 |
| Rule 4 | A Consent Manager's requests are authenticated | `consent_manager.public_key` column exists and **is unused** — the Board has published no signing standard, and verifying against a scheme nobody implements would look like proof | — **no test, because there is no behaviour** | **UDS decision / open** — becomes a question on 13 November 2026. `competitive-analysis.md` §C.13 |
| **Rule 6** | Reasonable security safeguards, including a one-year log-retention floor | The floor holds because **nothing drops evidence**: `PartitionMaintenanceSweeper` provisions three months ahead and explicitly does *not* detach — detachment past a retention ceiling is a runbook step with a person's name against it, because a scheduled job that silently deletes evidence is the last thing this platform should own | `PartitionMaintenanceIT` (provisioning, idempotence, append-only on a partition) — **no test asserts the one-year floor**, because no code enforces a ceiling to breach it | **configuration** — `OPERATIONS.md`; the ceiling is an operator decision, not a sweeper's |
| **Rule 6** | The same clause, other limb — safeguards for the **availability** of a system processing personal data | `PreAuthRateLimitFilter` refuses a flood at `SecurityProperties.DEFAULT_FILTER_ORDER - 10`, **before** the BCrypt verification an invalid credential used to cost ~113 ms. Until Phase 16 the only limiter ran behind authentication, so a stranger with no credential could saturate an instance and every refusal cost the defender more than the attempt cost them | `PreAuthRateLimitIT` — *"an invalid credential over the ceiling is answered 429, not 401"* (the assertion is about **order**: reaching the credential check at all produces the 401), *"the health probes are never refused, even under the flood"* | **partial** — the amplification is gone. The ceiling is per instance and keyed on the client address, so it is a flood ceiling and not a fairness limit; an ingress/WAF bucket remains worth having as defence in depth. `OPERATIONS.md` §12.2, `CAPACITY.md` §7 |
| **Rule 8** | Dark patterns prohibited outright — pre-selection, disguised refusal | **Capture refused at validation, not warned about**: a pre-ticked box is rejected outright; consent without an equally available refusal is rejected | `CaptureValidatorTest` — *"a pre-ticked box is rejected outright, not recorded with a caveat"*, *"accepting without an equally available way to refuse is rejected"* | **partial** — binding on what the platform will *record*. **The banner is where a dark pattern lives and the platform ships none**, so each capture surface's UI is outside these guarantees. `competitive-analysis.md` §C.1 |
| Rule 8 | Notice before erasure of dormant data | Sweeper emits the Rule 8 notice to the outbox once, ahead of the erasure date by the configured lead time | `RetentionIT` — *"the notice date precedes the erasure date by the configured lead time"*, *"the Rule 8 notice is emitted to the outbox, once, and the action advances"* | satisfied |
| **Rule 12 / 13** | Significant Data Fiduciary — DPIA, independent audit, algorithmic diligence | Obligation register on designation: yearly DPIA and audit cycle, per-algorithm diligence, completion refused without a hashed artefact, not discharged until the Board has it | `SdfObligationIT` — *"a completion with no hashed artefact is refused"*, *"a completed assessment is not discharged until the Board has it"* | satisfied |
| **Rule 13(4)** | A notified category may not leave India, for an SDF | Hook built and **deliberately empty** (`data_category.transfer_restricted` — the Government has notified nothing); `fiduciary_entity.data_residency_region` records **the primary's** region | `SdfObligationIT` | **partial** — **the backup destination is not recorded anywhere in the schema.** `RUNBOOK_DR.md` §3 asserts backups stay in the entity's residency region; §6 says there is no cross-region replication and no encryption at rest on the bucket. §3 is a requirement written in the indicative, and the two sections must be reconciled. Handoff §7.1, §9 W2; `ROADMAP.md` |
| **Rule 14(1)** | Publish the means of exercising rights, and the identifier list required | Means: `/v1/portal/**` is real, unauthenticated, and answers **byte-identically** for a known and an unknown identifier | `PrincipalPortalIT` — *"a known and an unknown identifier produce byte-identical responses"* | **partial** — the means half is served. Two blockers: **nothing consumes `rights.verification.requested`**, so every submission expires unverified; and **the identifier list is UDS's**. Handoff §4 |
| **Rule 14(3)** | Publish the response period; ceiling is *"a reasonable period not exceeding ninety days"* — **a ceiling, not a figure** | Group undertaking **30 days**, well inside it, and the constant names Rule 14(3) as its basis; `IN_STATUTORY_CEILING` is the 90 | `StatutoryClockTest` — *"India's period is the group's undertaking and says so in the basis"* | satisfied |
| — | The statutory clock starts when the request is *from the principal* — **portal intake** | **Verification, not submission** — an unverified request is not yet a request, so a stranger cannot burn the group's response window | `PrincipalPortalIT` — *"the statutory clock starts at verification, not at submission"* | satisfied |
| — | The same, for **admin intake** (`POST /v1/rights`, roles CAPTURE or ADMIN) | The caller still supplies `receivedAt` — a request arriving by post or over the phone is real, and the clock runs from the principal's act — but it is now **bounded in both directions and its provenance is recorded**. Future beyond `ClockTolerance.SKEW` is refused (it would move the deadline outward); older than `uds.consent.rights.max-backdate` (90d) is refused (it would file a request already in breach); and `rights_request.verification_method` records `PORTAL_TOKEN` / `OPERATOR_ASSERTED` / `UNVERIFIED`, on the row and on the append-only audit event | `RightsRequestIT` — *"a receivedAt in the future is refused, because it would move the deadline outward"*, *"a receivedAt beyond the backdate bound is refused, because it would file a breach"*, *"a request nobody verified says so, rather than looking like every other one"*, *"a row written without the column reads UNVERIFIED, never as though it were checked"* | **partial** — the bound and the label are real; the *verification itself* on this path is still an operator's claim rather than something the platform establishes, and `UNVERIFIED` is deliberately not a gate. What an audit can now answer is which requests rest on what, which it could not before |
| — | A `FULFILLED` claim | Refused with 409 **naming the outstanding systems** unless every mandatory `fulfilment_target` has a terminal `rights_fulfilment_action` | `RightsFulfilmentIT` — *"a mandatory system that has not acted blocks the closure, and is named"*, *"with no register configured, nothing is blocked — and that is the state today"* | **UDS decision** — the gate is built; the scope statement and the targets are theirs. §8.5 |

## 2. India — TRAI TCCCPR 2018 as amended February 2025 — **enforced today**

Applies *on top of* DPDP. The group's nearest-term exposure: financial penalties and disconnection of
telecom resources against Denave's and Athena's outbound activity now.

| Clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| Reg. 17 / Sch. II | Explicit consent for a transactional communication **lapses after seven days** | Enforced by the core engine through the purpose's `ExpiryPolicy` — **auto-denies on day eight with no sweeper having run** | `GoldenDecisionSuiteTest` — *"transactional consent auto-denies on day eight without any sweeper having run"*, *"transactional consent permits on day six"* | satisfied |
| Reg. 17 | Consent inferred from a contractual relationship lasts only as long as the relationship | Denies once the contract it rests on has ended, permits while it subsists, **and says which** | `GoldenDecisionSuiteTest` — *"inferred consent denies once the contract it rests on has ended"* | satisfied |
| Reg. 19 / 23 | Registry scrubbing — an opted-out subscriber may not be re-solicited | Suppression denies as a suppression; a 90-day cooling-off bars re-solicitation even after the opt-out lapses | `TraiCoolingOffIT` — *"a live opt-out still denies as a suppression, not as a cooling-off"*, *"a lapsed opt-out no longer suppresses but still bars re-solicitation"*; `GoldenDecisionSuiteTest` — *"a subscriber who opted out 30 days ago may not be re-solicited"* | satisfied |
| Reg. 19 | A statutory registry entry is not a consent event | *"a statutory registry entry does not start a cooling-off"* | `TraiCoolingOffIT` | satisfied |
| Sch. I | DLT registration of headers and templates — **valid consent does not remove it** | `TccprModule` raises it as an independent obligation | `GoldenDecisionSuiteTest` — *"a valid consent record does not exempt an SMS from DLT registration"*; `TraiCoolingOffIT` — *"the seeded DLT registrations are readable and honestly incomplete"* | **configuration** — the registrations themselves are UDS's to hold |

## 3. EU and UK — GDPR, UK GDPR, ePrivacy and PECR

`GdprModule` is instantiated **once per jurisdiction**, because the two regimes have diverged and will
diverge further. **The lawful basis under GDPR and the consent requirement under ePrivacy are separate
questions** — which is why cookie rules are purpose-registry *configuration*, not logic in the class.

| Clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| Art. 7(1) | The controller must be able to **demonstrate** consent | The whole evidence plane: hash-chained events, a durable receipt reproducible byte-for-byte, and an evidence bundle that verifies the chain | `LedgerAppendOnlyIT` — *"a subject's chain verifies end to end across several events"*; `ReceiptIT` — *"a reproduced receipt is byte-identical to the one issued"*; `EvidenceBundleIT` | satisfied |
| Art. 7(2) | The request is intelligible, clearly distinguishable, per purpose | One entry per purpose, plain-language `purposeName` **as shown to the subject**, pinned to the consented version | `ReceiptIT`; `PublishingIT` | satisfied |
| Art. 7(3) | Withdrawal as easy as giving, **and effective** | Withdrawal path plus **propagation evidence**: outbox → `WebhookPublisher` (HMAC over the exact bytes sent) → a `webhook_delivery` row per attempt, and another entity's subscriber receives nothing | `WebhookDeliveryIT` — *"a consent event is pushed to the subscriber and the delivery is recorded"*, *"the payload is signed over the exact bytes sent"* | **configuration** — the mechanism is complete and is better than anything in the field (`competitive-analysis.md` §B.2), but **`webhook_subscription` is seeded by no migration**. A system nobody registered receives nothing and leaves no trace of not having received it. Structurally the same gap as `fulfilment_target`, and graded the same way |
| Art. 7(4) | Consent is not conditional on service where unnecessary | `requiresSeparateConsent` in the registry; bundling refused at validation | `CaptureValidatorTest` | satisfied |
| Art. 9(1)–(2) | Special-category data needs an Art. 9 condition, not merely Art. 6 | `PurposeDefinition.sensitiveCategories` and `biometricCategories`; the receipt flags `sensitive` per entry | `PublishingIT`; `ReceiptIT` | **partial** — the receipt flattens both sets to one boolean, which 27560 marks as lossy. `docs/standards/iso-27560-consent-records.md` §2.2 |
| Art. 12(3) | Respond within one month | `StatutoryClock` EU/UK = one month | `StatutoryClockTest` — *"GDPR is one month, Malaysia twenty-one days, California forty-five"* | satisfied |
| Art. 32(1)(b) | Ensure the ongoing **availability and resilience** of processing systems | Same behaviour as DPDP Rule 6's availability limb above — the pre-authentication flood ceiling. Recorded separately because it is the limb an EU-facing entity is assessed against, and because the previous answer was an ingress control the platform could not prove was configured | `PreAuthRateLimitIT` — *"an invalid credential over the ceiling is answered 429, not 401"* | **partial** — same caveats: per instance, address-keyed, and resilience beyond this is infrastructure (`RUNBOOK_DR.md`) |
| Art. 15 | Access — a copy of the data and the processing information | `GET /v1/admin/evidence/subject/{entityId}/{subjectId}`; resolves merges (every section reads across the merged id set), enumerates subject-scoped tables from `information_schema` so a new table fails the build, **assembling somebody's whole file is itself recorded**, and the bundle now **declares its own incompleteness** — receipts cap at 100 and denials at 200, and a `truncation` entry names the section, the cap and a ready-to-run request that returns the remainder | `EvidenceBundleIT` — *"a bundle that could not fit everything says so, and says where the rest is"*, *"a bundle that fits carries no truncation notice at all"*, *"assembling somebody's whole file is itself recorded"*, *"the bundle returns the whole person, and names what was merged in"* (`SubjectIdentityIT`) | **partial** — the silent-truncation limb is closed. What remains is **entity scope**: a Board question about a *person* is fifteen calls under fifteen scopes. Deliberately not fixed with a group-level route, which would have to bypass `EntityAccessGuard` and the RLS claim at once; the SOP is `OPERATIONS.md` §12.2a, and an SOP is weaker than a route and is the correct trade here |
| Art. 17 | Erasure | Intake, clock, gate and evidence; the act is a named system's | `RightsRequestIT`, `RightsFulfilmentIT` | **UDS decision** — §8.5 |
| Art. 30 | Records of processing activities | RoPA export joining each activity to the purpose **the engine actually enforces**, and naming enforced purposes nobody has documented | `RopaIT` — *"an activity joins to its purpose in the registry the engine actually enforces"*, *"the export names purposes the platform enforces but nobody has documented"* | satisfied |
| Art. 33 | Breach notification within 72 hours | `BreachClock`; the without-delay obligations are overdue immediately and stay so until sent | `BreachIT` | satisfied |
| ePrivacy Art. 5(3) / PECR 6 | Consent before non-essential storage or access | Purpose-registry configuration (`WEB_STRICTLY_NECESSARY`, `WEB_ANALYTICS`, `WEB_ADVERTISING`), not logic | `GoldenDecisionSuiteTest` | **configuration** — and **the platform emits no Google Consent Mode v2 signal**, so a GTM property cannot see these decisions. `competitive-analysis.md` §C.2 |

## 4. Korea — PIPA *(amendment commenced 11 September 2026)* and the Network Act

| Clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| PIPA Art. 22 | Consent obtained separately per purpose, distinguishably | Bundled acceptance is refused at validation under Korea, and the same purposes actioned individually are accepted | `CaptureValidatorTest` — *"a single 'I agree to the above' across two purposes is rejected in Korea"*, *"the same two purposes, each actioned on its own, are accepted in Korea"* | satisfied |
| PIPA Art. 35–37 | Respond within **10 days** — the tightest period the group operates under | `StatutoryClock.KR` | `StatutoryClockTest` — *"Korea is ten days — the tightest period the group operates under"* | satisfied |
| PIPA Art. 34 | Breach notification, clock starting on a **reasonable likelihood** rather than confirmation | `BreachClock` Korea branch | `BreachClockTest`; `BreachIT` | satisfied |
| Network Act Enforcement Decree **Art. 62-3** | Two-year re-confirmation of consent | Implemented: falls due on the same calendar date two years later (28 February for a 29 February consent), raised once however often the sweeper runs, all three disclosures recorded, partial confirmation refused, and **Art. 50 reaches Korea only** | `ReconfirmationIT` — *"consent given on 29 February falls due on 28 February"*, *"an Indian consent of the same age is not raised"* | **configuration** — **dark** behind `uds.consent.features.korea-reconfirmation`: the silence rule is not in primary text, and carrying speculative surface live is itself the defect. Handoff §2 |

## 5. Singapore, Malaysia, United States

| Clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| SG PDPA s.21 | Respond within 30 days, or state when you will | `StatutoryClock.SG` | `StatutoryClockTest` | satisfied |
| MY PDPA 2024 | 21 days for access or correction | `StatutoryClock.MY` | `StatutoryClockTest` | satisfied |
| CCPA/CPRA §1798.130 | 45 days, extendable once by 45 on notice | `StatutoryClock.US_CA` + extension | `StatutoryClockTest` — *"the US states that followed the CCPA share its period"* | satisfied |
| CCPA §1798.135 | Honour an opt-out preference signal (GPC) | `CcpaModule` honours GPC as an opt-out | `GoldenDecisionSuiteTest` | satisfied |
| — | Withdrawal is same-day in every jurisdiction, not on the access-request clock | Withdrawal is not a rights request with a period | `StatutoryClockTest` — *"a withdrawal is same-day in every jurisdiction, not only India"* | satisfied |

## 6. Standards — ISO/IEC TS 27560:2023 (as rendered) and ISO/IEC 29184:2020

**The ISO texts are paywalled and this project does not hold them.** These rows are stated against the
free W3C DPV rendering and named as such; sources and access dates are in
[`standards/README.md`](standards/README.md), and the field-by-field position with the nine questions the
paid text would settle is in
[`standards/iso-27560-consent-records.md`](standards/iso-27560-consent-records.md). **Nobody can certify
against 27560** — it is a Technical Specification intended to gather feedback — so these rows are honesty
to a downstream reader, not compliance evidence.

| Field or clause | Obligation | Behaviour | Evidence | Status |
|---|---|---|---|---|
| 27560 receipt metadata — Schema Version | `dct:conformsTo` a **named profile** | `schemaVersion` = `uds-consent-receipt/1;iso-27560:2023-receipt-subset`, and the Javadoc says which of §9's two readings it is | `ReceiptIT` — *"the schema version says receipt-subset, and says it in the document"*, asserting **the literal string** rather than the constant, because an assertion against the constant can only fail if the copy breaks, never if the claim becomes untrue | **partial** — the value is honest about the subset but names none of [S1] §2's four profiles (`dpv-27560:receipt-record` and the rest) and resolves to nothing. A resolvable URI needs something about this platform to be published |
| 27560 receipt metadata — Receipt Identifier | Unique, durable | `receiptId`; `GET /v1/receipts/{id}` returns the document byte-for-byte, and a stored receipt cannot be edited | `ReceiptIT` — *"a stored receipt cannot be edited or deleted"* | satisfied |
| 27560 receipt metadata — Consent Record | The record the receipt attests to | `consentRecordId` = `entityId:subjectId`, stable across every receipt to that principal | `ReceiptIT` | satisfied |
| Processing — Purpose, Personal Data, Legal Basis, Notice, Notice Language, Jurisdiction, Controller, Withdrawal | Per-processing detail | All present per entry; **`noticeVersion` is pinned, which is stronger than the structure asks**, and the purpose version is pinned, for which the structure has no field at all | `ReceiptIT`; `NoticeIT` | satisfied |
| Processing — Recipients, Storage Condition | Both `1..*` in the record structure | Present and **deliberately nullable**: null means nobody recorded the fact, and answering "none" would be a false statement issued to a principal | `ReceiptIT`; `RopaIT` — *"an activity with no retention rule is reported as a gap, not omitted"* | **partial**, deliberately — the design is right and the `-receipt-subset` suffix is what makes the claim true |
| Processing — Sensitive category | Categories | One nullable boolean | `ReceiptIT` | **partial** — lossy; §2.2 |
| Processing — Processing Operations, Data Source, Purpose Type, Personal Data Necessity | Optional in the structure | Not emitted; the platform has **no processing-operation vocabulary at all** | — no test; not an obligation under any clause above | **partial** — recorded in `standards/dpv-v2-vocabulary.md` §4 |
| Parties — Postal Address, typed Role, Contact for non-controller parties | Mandatory in the record structure | Not emitted; recipients are display strings (`"Acme (joint controller)"`) although `VendorStore` holds the structure | — no test | **partial** — the largest structural divergence; §2.3 |
| Events — Consent State, Event Time, **withdrawal date** | Per-purpose event facts | `status` evaluated at `at`, `grantedAt`, and `withdrawnAt` — the latter from the withdrawal event's `occurredAt` (the instant the subject acted, which matters for an offline capture syncing late) and **only where the entry is currently withdrawn** | `ReceiptIT` — *"a withdrawn entry carries the date it was withdrawn"* (asserted against a fixed offline instant), *"a consent given again after a withdrawal carries no withdrawal date"* | satisfied |
| Events — Consent Type, Expression by Entity, Expression Method, event *history* | The structure wants a history per purpose | Not emitted. `CaptureMethod` already encodes implied/expressed/explicit in the ledger and a derived accessor would fill Consent Type with no new data | — no test | **partial** — §2.4; the cheapest remaining gap |
| — | Integrity of the receipt | `evidenceHash` ties the document to the chain; `GET /v1/receipts/{id}/verification` returns the hash a holder checks theirs against | `ReceiptIT`; `SnapshotSigningTest` | satisfied — **27560 has no integrity field at all; this exceeds the structure** |
| **29184** §5.2–5.4 | Notice: identity, purposes, categories, retention, third parties, rights, in accessible language, versioned | Notice served with integrity hash and version; a historical version renders byte-identically; a missing translation reports as missing, **never silently as English** | `NoticeIT` — *"a historical version renders byte-identically to what is stored"*, *"a language with no translation reports as missing, never as English"* | **partial** — the mechanism is complete, the translations are procurement. The 29184 text is paywalled and **not held**; this row is the platform's behaviour against the clause structure as summarised in the DPV rendering, not a conformance claim |

## 7. Cross-cutting platform guarantees that no single clause names

These carry more evidentiary weight than most numbered obligations, and none of the five products torn
down in [`competitive-analysis.md`](competitive-analysis.md) publishes an equivalent.

| Guarantee | Behaviour | Evidence | Status |
|---|---|---|---|
| The evidence plane is append-only **by the database**, not by convention | `UPDATE`, `DELETE` and `TRUNCATE` refused to `uds_consent_app` by V2 triggers and grants, proven **as the application role** | `LedgerAppendOnlyIT` — *"UPDATE on a consent event is rejected by the database, not by convention"*, *"the administrative audit trail is immutable too"* | satisfied |
| Multi-entity isolation is two layers that agree | `EntityAccessGuard` at the route, RLS at the connection, **one resolver feeding both**; the protected set is derived from `information_schema`, so a new uncovered table fails the build on its own | `RowLevelSecurityIT` — *"every table that carries an entity_id has a policy, or is open on the record"*, *"the claim is re-applied on every checkout"*; `EntityIsolationIT`, `JwtAuthenticationIT` | satisfied — **nobody in the field publishes an answer to this**; `competitive-analysis.md` §B.6 |
| A credential is not a person | `admin_audit_event.actor_id` records `client=…;actor=…`; `X-UDS-Actor` required under Basic, **ignored under a JWT**, ignored on machine routes | `ActorAttributionIT` — *"the audit row records the human and the credential as separate facts"*; `JwtAuthenticationIT` | satisfied — universal failure mode elsewhere; §B.4 |
| One person is one subject | `subject_alias` canonicalisation on read and write; **a withdrawal by email suppresses the phone**; a merge is a `SUBJECT_MERGED` event and cannot be undone | `SubjectIdentityIT` — *"after a merge, a withdrawal by email suppresses the phone as well"*, *"a merge cannot be deleted, because it joined two people's records"* | satisfied |
| The portal is not an enumeration oracle | Byte-identical responses; an attempt cap that actually caps; single-use codes | `PrincipalPortalIT` — *"a wrong code is refused and burns an attempt, and the cap closes the reference"* | satisfied |
| Offline enforcement is verifiable | Ed25519-signed snapshots, `kid` selected from the key registry so a snapshot signed under a retired key still verifies | `SnapshotSigningTest`; `KeyRotationIT` | **partial** — private-key custody is still an environment variable behind the `SigningKeyProvider` SPI. `ROADMAP.md` |
| Denials are evidence too | Every refusal writes an `enforcement_decision` row with its `DenialReason` | `EnforcementEvidenceIT` | satisfied |
| The published contract cannot drift silently | `OpenApiContractIT` snapshots `docs/openapi.json` and fails the build on drift; regeneration is an explicit flag | `OpenApiContractIT` | satisfied — **this is what pins the routes this matrix cites** |

---

## 8. What this matrix does not claim

Read this before quoting the table.

1. **A row saying *satisfied* means the obligation is discharged by the platform, not by the group.** A
   notice mechanism that works and nineteen missing translations is a platform that works and a group that
   is not yet compliant. Rows marked **configuration** and **UDS decision** are where that distinction
   lives, and `REGULATORY_HANDOFF.md` §8 is the list UDS signs.
2. **The ISO rows are against the free rendering.** Where §6 says *satisfied*, it means satisfied against
   what was actually read, dated, and named in `standards/README.md`.
3. **Rows with no test say so, and stay in.** Rule 4 signature verification, DPDP s.7(a), Rule 6's
   one-year floor, the admin-intake clock start, and 27560's Parties and Events remainders. **A row with
   no test is the finding.**
4. **Three obligations are gated on registers that no commit can populate** — a consumer for
   `rights.verification.requested`, without which the Rule 14(1) portal cannot start a clock; the
   `fulfilment_target` register, without which `FULFILLED` blocks nothing; and `webhook_subscription`,
   without which a withdrawal propagates to whoever happens to be registered. All three are in
   `ROADMAP.md` with the check that closes them.
5. **The grades were audited by `qa-verifier` after the first draft, and five were wrong** — s.6(4),
   s.7(a), Rule 6, Rule 13(4) and Art. 7(3) all read *satisfied* and now do not. Four suite attributions
   were also wrong, including a quoted test name attributed to the wrong file. That review is the reason
   to trust the current grades a little more, and the reason to re-run it on any row added later.
5. **The platform decides nothing a person should decide.** `OPERATIONS.md` §10 is the list of what it
   deliberately does not decide; this matrix is not a licence to read a permission out of it.
