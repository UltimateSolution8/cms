# ISO/IEC TS 27560:2023 consent records and receipts, as rendered

**Sources and access dates: [`README.md`](README.md).** Every field name below is **DPV's rendering**,
not ISO's spelling — [S2] Figure 1 states plainly that *"the field names have been modified for
alignment with DPV concepts."* Cardinalities are [S2]'s (`1`/`1..*` mandatory, `0..*` optional); where
[S1] disagrees it is flagged.

The platform side is
[`ConsentReceipt.java`](../../platform/consent-core/src/main/java/com/uds/consent/core/model/ConsentReceipt.java)
and
[`ReceiptService.java`](../../platform/consent-service/src/main/java/com/uds/consent/service/ReceiptService.java).

---

## 1. The structure

A **consent record** has four sections — Header, Processing, Parties, Events ([S2] §2). A **consent
receipt** normatively has **two** required fields per ISO ([S2] Table 7, quoted below), to which the DPV
rendering adds a reference to the record — three in the profile this platform states conformance to:

| Receipt field | Card. | Platform | Status |
|---|---|---|---|
| Schema Version (`dct:conformsTo`) | 1 | `schemaVersion` | **present** |
| Receipt Identifier | 1..* | `receiptId` | **present**, durable and reproducible |
| Consent Record | 1..* | `consentRecordId` = `entityId + ":" + subjectId` | **present** |

[S2] is explicit: *"The Consent Receipt in ISO-27560 contains only two required fields… The
information and contents are undefined and left to each implementor to specify."* [S1] §9: a receipt
*"may contain all, some, or no information from the consent record."*

**But there is a second, stricter reading in the same sources**, and the whole verdict in §4 turns on
it. [S2] reports *"ISO-27560 guidance which states that the receipt may contain the same fields as
that of a consent record, and that **the mandatory fields in a consent record are also mandatory in a
consent receipt**."* Whether that is normative or advisory cannot be settled without the paid text
(§5.4).

[S1] §2 names four profiles a Schema Version is meant to reference: `dpv-27560:record`,
`record-eu-gdpr`, `receipt-record`, `receipt-eu-gdpr`.

**Conformance, verbatim [S1] §1:** *"A conformant implementation is one that fulfils all requirements
by either storing information in the form prescribed by [ISO-27560], **or by storing information in a
form that can be converted or transformed to fulfil its requirements**."*

---

## 2. The record's fields against the platform

### 2.1 Header

| Field | Card. | Platform | Status |
|---|---|---|---|
| Record Identifier | 1..* | `consentRecordId` | present |
| Data Subject | 1 | `subjectId` | present |
| `dct:issued` and other metadata | 0..* | `issuedAt` | **named differently** |

### 2.2 Processing — 22 processing fields plus 5 personal-data fields ([S2] Table 4)

| Field | Card. | Platform (`ConsentReceipt.Entry` unless noted) | Status |
|---|---|---|---|
| Process | 1..* | — | **absent as an object.** `Entry` is purpose-keyed, so process ≡ purpose 1:1. Two activities serving one purpose collapse into one entry, which is why `ReceiptService.retentionPeriod()` has to take the **longest** of several |
| Purpose | 1..* | `purposeCode`, `purposeName` | present |
| Purpose Type | 0..* | — | absent; the registry has no parent link, so there is no taxonomy position to emit |
| Personal Data / Personal Data Type | 1..* | `dataCategories` | present |
| Personal Data Identifier | 0..* | — | absent, correctly — a receipt should not carry attribute ids |
| Personal Data Necessity | 0..* | — | absent. `PurposeDefinition.requiresSeparateConsent` exists and is not emitted |
| Sensitive / Special Category | 0..* | `sensitive` | **named differently and lossy** — a nullable boolean where the standard wants categories. `PurposeDefinition` holds `sensitiveCategories` *and* `biometricCategories` as sets; both flatten to one flag |
| Processing Operations | 0..* | — | **absent.** The platform has no `dpv:Processing` vocabulary at all (see [`dpv-v2-vocabulary.md` §4](dpv-v2-vocabulary.md)) |
| Data Source(s) | 0..* | — | absent on the receipt, though `V4__provenance_ingestion.sql` holds it. A real gap: `IMPORTED_WITH_PROVENANCE` capture is precisely the case a Denave prospect asks about |
| **Storage Condition(s)** | **1..*** | `retentionPeriod` | **present but nullable by design**; the location half absent |
| Processing Condition(s) | 0..* | — | absent |
| Geographic Restriction(s) | 0..* | `crossBorderCountries` | **named differently** — transfer *destinations*, not restrictions |
| **Data Controller(s)** | 1..* | `fiduciaryName`, `fiduciaryId` | present |
| Legal Basis | 0..* | `legalBasis` | present — the platform **exceeds** the structure here |
| **Recipients** | **1..*** | `recipients` | **present but nullable by design** |
| Consent Change & Withdrawal | 1..* | `withdrawalUri` | present |
| Jurisdiction(s) | 1..* | `jurisdiction` | present |
| **Rights** | 1..* | `rightsUri` | **named differently, and not the same thing.** The standard wants `dpv:DataSubjectRight` instances — *which* rights exist. The platform emits a URL. `RightsRequestType` enumerates them internally and none reaches the receipt |
| Service / Code of Conduct / Impact Assessment | 0..* | — | absent |
| Notice | 1..* | `noticeId`, `noticeVersion` | present, and **version-pinned**, which is stronger than the standard asks |
| Notice Language | 1..* | `languageTag` | present |

### 2.3 Parties — the weakest section

| Field | Card. | Platform | Status |
|---|---|---|---|
| Name | 1..* | `fiduciaryName` — controller only | partial |
| Identifier | 1..* | `fiduciaryId` — controller only | partial |
| **Role** | 1..* | embedded in a display string: `vendor.name() + " (" + vendor.role() + ")"` (`ReceiptService.recipients()`) | **not machine-readable.** "Acme (joint controller)" is text, not a typed role |
| **Contact** | mandatory | `dpoContact` — controller only | **absent for recipients and third parties** |
| **Postal Address** | mandatory | — | **absent entirely, for every party including the controller** |
| Email / Phone / URL | 0..* | `dpoContact`, one opaque string | partial |
| Authority party | — | `grievanceUri` | **named differently** — a link, not a party with a name and a contact |
| Party Type | 1 in [S1] | — | absent; [S2] does not carry this field ([S1]/[S2] divergence) |

Recipients as bare strings is the **largest structural divergence**. The Parties section exists so a
recipient can be referenced by identifier and described independently. `VendorStore` already holds
name, role and countries — the structure is discarded on the way out.

### 2.4 Events — absent as a section

| Field | Card. | Platform | Status |
|---|---|---|---|
| **Consent Type** | 1..* | — | **absent.** Nothing distinguishes Implied / Expressed / ExplicitlyExpressed. `CaptureMethod`'s 12 values carry it in the ledger; `ReceiptService.build()` never reads it |
| Consent State | 1..* | `Entry.status` | present, evaluated at `at` |
| Event Time | 1..* | `Entry.grantedAt`, `Entry.withdrawnAt` | **present for the two events that matter** — the grant, and (Phase 15) the withdrawal, emitted only where the entry is currently withdrawn |
| Event Duration | 1..* | `Entry.expiresAt` | **named differently** — an instant where the standard wants a duration |
| **Expression by Entity** | 1..* | — | **absent.** `parentalVerification` is the only hint someone other than the subject expressed it, and it names a *method*, not an entity |
| Expression Method | 0..* ([S1]: MUST) | — | **absent** — see `CaptureMethod` above |
| *An event list at all* | — | — | **absent.** The receipt is a snapshot of current state per purpose; the standard's Events section is a **history** |

**The sharpest finding of this review, and it is now closed.** Until Phase 15 an entry read
`"status": "WITHDRAWN"` with a `grantedAt` and **no withdrawal timestamp anywhere on the document** — a
person holding that receipt could not tell when they withdrew, which is the one date a grievance turns
on. `Entry.withdrawnAt` closed it, sourced from `ConsentArtefact.withdrawnAt()`, which `ArtefactProjector`
sets from the withdrawal event's `occurredAt` — the instant the *subject acted*, not the server's clock,
which matters for a field-force capture syncing days late. `ReceiptIT` asserts that equality against a
fixed offline instant, and asserts that a re-granted entry carries **no** withdrawal date: the projection
carries `withdrawnAt` forward across a later grant, which is right for current state and would be a false
statement on a document.

**What remains absent is the rest of the section** — the event *list*. 27560 wants a history per purpose;
the receipt carries current state plus those two instants. A withdrawal that projects to `CONFLICTED`
inside the five-minute skew window is not explained to the principal at all.

### 2.5 What the platform emits with no counterpart in the structure

| Field | Assessment |
|---|---|
| `evidenceHash` | **No counterpart.** 27560 has no integrity field. A legitimate extension and the strongest thing on the document |
| `consentManagerRegistrationId` | No direct counterpart; would be a Parties entry. DPV has `eu-dga:` data-intermediary concepts; DPDP Consent Managers have none |
| `parentalVerification` | No counterpart. The standard handles this through Parties Role + Expression by Entity — which is modelling the platform's flat enum substitutes for lossily |
| `Entry.purposeVersion` | No counterpart. 27560 has no per-purpose versioning; the platform is **stricter** than the standard, deliberately (rules §1) |

---

## 3. Where the rendering was applied, and where it was not

`REGULATORY_HANDOFF.md` §6 records the DPV rendering as *"used as the conformance checklist for the
receipt work."* That is accurate but incomplete, and the distinction belongs in a document whose job is
recording what was checked versus assumed:

- **Processing: checked thoroughly.** Recipients, transfers, retention and sensitivity per purpose are
  all there, and `ReceiptIT` asserts them.
- **Parties and Events: not applied at all.** Every remaining gap in §2 sits in those two sections.

---

## 4. The verdict on the receipt's conformance claim

Every receipt stamps a `schemaVersion`. **Partially substantiated — and overstated in three specific,
cheaply fixable ways.**

**Substantiated.** The receipt clears the *normative* receipt bar comfortably: all three DPV receipt
fields are present, and `receiptId` is durable and byte-reproducible, which is more than most
implementations manage. On the "can be converted or transformed" limb of [S1] §1, most of what the
*record* needs genuinely does exist in the ledger.

**Overstated, on three counts.**

1. **The version string was a bare token where the standard puts a profile reference.** [S2] Table 7
   maps Schema Version to `dct:conformsTo`; [S1] §2 names the four profiles that value should take.
   `iso-27560:2023` named none of them and resolved to nothing — so the one field whose entire job is
   to tell a holder which fields to expect told them nothing checkable.
2. **The stricter reading of §1 is not met, and the old Javadoc's phrase "the field set that
   specification describes" invoked exactly that reading.** Against the record's mandatory set the
   receipt lacks Postal Address (all parties), Contact (all non-controller parties), typed Role,
   enumerated Rights, Consent Type, Expression by Entity and Expression Method.
3. **Two mandatory fields are nullable by deliberate design.** `Recipients` and `Storage Condition` are
   `1..*` in the structure and nullable here — and the design reasoning is *right*: `ConsentReceipt`'s
   class Javadoc argues that answering "no recipients" where the truth is "nobody wrote down who the
   recipients are" would be a false statement issued to a data principal. That is better privacy
   engineering than a `1..*` that forces a guess. **But the platform was choosing honesty over
   conformance and then claiming conformance.** Pick one.

**Resolved by restating the claim, not by weakening the receipt.** `SCHEMA_VERSION` is now
`uds-consent-receipt/1;iso-27560:2023-receipt-subset`, and the Javadoc cites [S1] §9 and says which
reading it is. Two words of change, and the claim is one nobody can take apart. The behaviour is
unchanged; only a claim about it was wrong.

---

## 5. What cannot be determined without the paid text

Listed rather than guessed. Each is a real limit on everything above.

1. **The authoritative required/optional marking.** [S1] and [S2] disagree on at least two fields —
   Expression Method (`0..*` vs MUST) and Party Contact/Address cardinality (`1..*` vs `1`). §2's
   mandatory-field gaps are stated against [S2].
2. **The exact ISO field names.** All names above are DPV's. [S2] Table 2 surfaces some ISO snake_case
   incidentally (`privacy_notice`, `lawful_basis`, `pii_information`, `retention_period`,
   `recipient_third_parties`, `withdrawal_method`, `geographic_restrictions`, `collection_method`,
   `storage_locations`, `authority_party`, `pii_type`, `pii_attribute_id`, `pii_optional`,
   `sensitive_pii_category`) — a partial list, not a field spec. **Anyone writing a 27560 JSON schema
   from §2 would be writing DPV's names.**
3. **Whether the mandatory-fields-carry-over rule is normative or advisory.** This decides §4. [S2]
   reports it as "guidance", which is why the grade is *partial* rather than *overstated outright*. **The
   single item most worth the price of the standard, and it is one clause.**
4. **The normative form of each field** (timestamp formats, whether an ISO-8601 period satisfies
   Storage Duration, whether BCP-47 satisfies Notice Language). None of the free sources reproduces
   them.
5. **ISO's own conformance clause** — whether it admits the "can be converted or transformed" limb, on
   which the ledger-holds-it-even-if-the-receipt-does-not defence rests. [S1] §1's definition is
   DPVCG's.
6. **The sub-structure of the 5 personal-data and 22 processing fields** as ISO groups them.
7. **What ISO says a receipt is *for*.** [S2] flags a divergence: ISO has records held by organisations
   and receipts given *to* the subject; Kantara had receipts given *by* the subject *to* the
   controller. The platform's direction is the ISO one, so this is unlikely to bite.
8. **§§4, 5 and 7 of the document.** [S2] references only §3.1–3.4, §6.2.x and §6.3.4.x/6.3.5.x.
9. **Annexes A–G**, including Annex A's DPV examples — if that carries the reference JSON-LD, it is the
   document a schema claim would actually be tested against.

**One gap that is not the ISO text's fault.** The `dpv-pd` personal-data taxonomy was **not** checked
against the platform's 17 `data_category` codes. Personal Data Type is a `1..*` mandatory field and the
platform's codes (`CONTACT_BUSINESS`, `PAYROLL_FINANCIAL`, `BIOMETRIC_FACE`) are locally invented, so
that axis is **unmapped, not mapped-and-clean**. Open in [`ROADMAP.md`](../../ROADMAP.md).
