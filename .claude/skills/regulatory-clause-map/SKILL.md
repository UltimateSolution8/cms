---
name: regulatory-clause-map
description: The clause → system-behaviour map for this platform — DPDP Act and Rules, TRAI TCCCPR, GDPR/UK GDPR and PECR, Korea PIPA, Singapore and Malaysia PDPA, US states. Load it when a plan needs its mapping table, when deciding where an obligation belongs in the code, or when checking whether an obligation is already discharged. A map with pointers, not a copy of the statutes.
---

# Clause → system behaviour

Use this to build the mapping table a plan must carry **before** implementation, and to check whether
an obligation is already discharged. Each row points at the code that implements it and the document
section that records the reasoning — go there for the argument; this is the index.

**Where the modules live:** `platform/consent-policy/src/main/java/com/uds/consent/policy/jurisdiction/`
— one class per regime (`DpdpModule`, `TccprModule`, `GdprModule`, `PipaModule`, `CcpaModule`,
`PdpaSingaporeModule`, `PdpaMalaysiaModule`), composed by `PolicyEngine`. Response periods are
constants in `rights/StatutoryClock.java`; breach clocks in `rights/BreachClock.java`. Each class
carries its own citations in its Javadoc, and those are the authority — **if this file and the code
disagree, the code has been checked more recently, and one of them is a defect worth reporting.**

## India — DPDP Act 2023 / DPDP Rules 2025 (substantive Rules bind 13 May 2027)

| Clause | Obligation | Behaviour | Where |
|---|---|---|---|
| s.5(1), Rule 3 | Notice accompanying or preceding a consent request: data and purpose, the manner of exercising s.6(4)/s.13 rights, the manner of complaining to the Board | Notice served with integrity hash; three Rule 3(c) links | `NoticeService`, `ReceiptService` |
| s.5(3), s.6(3) | The notice **and** the consent request available in English or an Eighth Schedule language. **This is in the Act, not Rule 3** — Rule 3's own text has no language clause, and three documents said it did until Phase 17 | `notice_translation` per language; coverage reported, placeholders refused | `GET /v1/notices/reports/coverage` |
| s.6(3), Rule 9 | DPO or authorised-person contact **on the consent request** (s.6(3)) and **published standingly** on site/app and in every rights response (Rule 9). **Not Rule 3** | Receipt carries `dpo_contact` and `grievance_uri`, resolved up the entity hierarchy | `ReceiptService`, `EntityContactCheck` |
| s.6(4) | Withdrawal with ease comparable to giving. **Not s.6(6)** — that is the cease-and-cause-processors duty, and the two were conflated in six javadocs and two matrix rows until Phase 17 | Withdrawal is a same-day route in the same family, not a rights request with a period | `DpdpModule` ~l.56, `ConsentController` |
| s.6(6) | *"Cease and cause its Data Processors to cease processing"* — a duty about the systems **downstream** of this one | The outbox → `WebhookPublisher` → `webhook_delivery` chain, and the propagation register that names who must be told | `PropagationTargetStore`, handoff §8.7 |
| Rule 4 | Consent Manager framework, operational 13 November 2026 | UDS does not register; it must be able to *transact* with one — `/v1/consent-manager/**`, **dark behind `uds.consent.features.consent-manager-relay`** until the Board publishes the standard | `ConsentManagerController`, handoff §4 |
| Rule 6 | Reasonable security safeguards, incl. a one-year log-retention floor | Already modelled — checked, not missing. `PartitionMaintenanceSweeper` adds the ceiling above the floor | handoff §6 |
| Rule 8 | Dark patterns prohibited outright — pre-selection, disguised refusal | Capture refused at validation, not warned about | `DpdpModule` ~l.85, `CaptureValidator` |
| Rule 10 | Verifiable parental consent; due diligence on the guardian | Guardian verification is an evidenced fact, not a flag; capture refused without it | `DpdpModule` ~l.137–163 |
| Rule 13(4) | A notified category may not leave India for a Significant Data Fiduciary | Hook built and deliberately empty (`data_category.transfer_restricted`); residency recorded per entity — **including the backup destination**, which is the limb an infrastructure default decides silently | handoff §7.1, §9 W2; `RUNBOOK_DR.md` |
| Rule 14(1) | Publish the *means* of exercising rights, and the identifier list required | Means: `/v1/portal/**` is real. **The identifier list is UDS's** and the portal accepts any single identifier because no list exists to check against | handoff §4; `PrincipalPortalIT` |
| Rule 14(3) | Publish the response period; ceiling is *"a reasonable period not exceeding ninety days"* — **the Rules set a ceiling, not a figure** | Group undertaking is **30 days**, well inside it, and the constant names Rule 14(3) as its basis. A published period that contradicts the platform's makes the group's own records the evidence against it | `StatutoryClock.IN`, `IN_STATUTORY_CEILING`; handoff §3, §7.3 |
| — | The `FULFILLED` claim | Gated on evidence: every mandatory `fulfilment_target` needs a terminal `rights_fulfilment_action`. Scope statement for UDS to sign: handoff §8.5 | `RightsService` |

## India — TRAI TCCCPR 2018 (as amended February 2025) — **enforced today**

Applies *on top of* DPDP, not instead of it, and this is the group's nearest-term exposure: financial
penalties and disconnection of telecom resources, against Denave's and Athena's outbound activity now.
Consent mechanics a boolean cannot express: **explicit consent for a transactional communication
lapses after seven days**; consent inferred from a contractual relationship lasts only as long as the
relationship. Expiry is enforced by the core engine through the purpose's `ExpiryPolicy`; `TccprModule`
adds the obligations valid consent does not remove — **registry scrubbing and DLT registration**.

## EU / UK — GDPR, UK GDPR, ePrivacy and PECR

`GdprModule` is instantiated **once per jurisdiction**, because the two regimes have diverged and will
diverge further. The point most often missed and worth restating in any plan: **the lawful basis under
GDPR and the consent requirement under ePrivacy are separate questions** — legitimate interest can
support B2B outreach and does nothing at all for setting a cookie. Cookie rules are therefore
*configuration in the purpose registry*, not logic in the class, which is also why the contested
Digital Omnibus proposal needs no code change. Withdrawal ease (Art. 7(3)) is the withdrawal path plus
propagation evidence; Art. 30 records are the RoPA export. Response period: one month (Art. 12(3)).

## Korea — PIPA (amendment commenced 11 September 2026) and the Network Act

Tightest response period in the group: **10 days**. Breach clock starts on a *reasonable likelihood*,
not on confirmation (`BreachClock`). Fines to 10% of total turnover in the severe tier, with the
business owner named as ultimately responsible. **Network Act Enforcement Decree Art. 62-3** —
two-year re-confirmation — is implemented and **dark behind
`uds.consent.features.korea-reconfirmation`**, because the silence rule is not in primary text;
handoff §2 records the re-activating event. Do not turn it on to be safe: carrying speculative surface
live is itself the defect.

## Singapore, Malaysia, US states

Singapore PDPA: 30 days, or say when you will respond. Malaysia PDPA 2024: 21 days for access or
correction. CCPA/CPRA: 45 days, extendable once by 45 on notice; `CcpaModule` also honours GPC.

---

## Standards, not statutes — ISO/IEC TS 27560 and 29184

Consent *records and receipts* are Kantara → **ISO/IEC TS 27560:2023**; the notice layer is
**ISO/IEC 29184:2020**; the vocabulary underneath both is the **W3C DPV**. IAB TCF is an adtech
signalling framework and is an **adapter, never the core model** — `UDS_Consent_Control_Plane_v2_FINAL.md:47`
corrects an earlier document that conflated the two.

**The ISO text is paywalled and this project does not hold it.** The field structure comes from the free
W3C DPV rendering and from Pandit et al. (arXiv 2405.04528), and any conformance statement must name the
rendering rather than the standard. `docs/standards/` holds what was read, with access dates — go there
first.

## Where a row lands

A finished row belongs in **`docs/TRACEABILITY.md`**, whose columns are this table's plus two: the test
that proves the behaviour, and a status of *satisfied / partial / configuration / UDS decision*. A row
with no test is the finding, not the row.

## Using this in a plan

1. Name the clause and its **commencement date**. A date is part of the mapping.
2. State the obligation in one sentence, then the behaviour that discharges it.
3. Say which of three kinds it is: **code**, **configuration** (purpose registry, seed data), or **a
   decision for UDS** — handoff §8 lists the ones already known to be theirs, and proposing to build
   one of those is a scope error, not helpfulness.
4. If the obligation is not yet in primary text: **flag, dark, and record the re-activating event.**
   Two precedents, both deliberate.
5. Spawn `regulatory-researcher` for anything not in this file. Do not research primary text in the
   main thread.
