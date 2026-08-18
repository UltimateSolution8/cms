# W3C DPV v2 against the platform's vocabulary

**Sources and access dates: [`README.md`](README.md).** Term spellings verified against the live DPV
2.0 taxonomy pages [S4] on 17 August 2026; structural framing from [S3] §4–5.

**Why this matters and what it is not for.** Interoperability here is what lets a receipt this platform
issues be *read* by somebody else's system. It is **not** an argument for renaming the purpose
registry: [S3] §5.1 describes DPV as *"a controlled taxonomy with a lightweight ontology that supports
extending the taxonomy in use-cases"*, and its own worked example is a local term (`ex:CampaignA`)
declared `broader` than a DPV one (`dpv:DirectMarketing`). **The correct posture is a `broader` pointer
from each platform code to its nearest DPV term.** Nothing in this file is a defect list against the
registry.

---

## 1. Legal basis — `LegalBasis` (9 values)

| Platform | DPV v2 | Fit |
|---|---|---|
| `CONSENT` | `dpv:Consent` (`eu-gdpr:A6-1-a` under GDPR) | exact |
| `INFERRED_CONSENT` | `dpv:ImpliedConsent` | **approximate.** TRAI's contract-derived consent is nearer `dpv:ImpliedConsent` **plus** `dpv:ContractPerformance` together; DPV has no single term |
| `LEGITIMATE_USE_EMPLOYMENT` | **none** | DPDP s.7(i). The nearest DPV terms (`dpv:HumanResourceManagement`, `PersonnelManagement`) are *purposes* — a different axis |
| `LEGITIMATE_USE_VOLUNTARY` | **none** | DPDP s.7(a). No analogue in any direction |
| `CONTRACT_PERFORMANCE` | `dpv:ContractPerformance` | exact |
| `LEGAL_OBLIGATION` | `dpv:LegalObligation` | exact |
| `LEGITIMATE_INTEREST` | `dpv:LegitimateInterestOfController` | exact, and DPV is **more** precise — it distinguishes controller / third-party / data-subject interest, which an LIA has to state anyway |
| `VITAL_INTEREST` | `dpv:VitalInterestOfDataSubject` | exact |
| `PUBLIC_INTEREST` | `dpv:PublicInterest` | partial — DPV splits state function out as `dpv:OfficialAuthorityOfController` |

Seven of nine map cleanly. **Both that do not are DPDP "legitimate use" grounds, and DPV has no India
extension** — [S3] §5 lists jurisdictional extensions for IE, DE, GB and US, and namespaces for EU
GDPR, DGA and the AI Act. Nothing for DPDP. Not a platform defect; a genuine gap in DPV, and worth
recording because those two grounds cover the group's largest processing population (the ~76,000-record
workforce base).

## 2. Consent status — `ConsentStatus` (9 values)

| Platform | DPV v2 | Fit |
|---|---|---|
| `GRANTED` | `dpv:ConsentGiven` | exact |
| `DENIED` | `dpv:ConsentRefused` | exact |
| `WITHDRAWN` | `dpv:ConsentWithdrawn` | exact — DPV's term is specifically withdrawal *by the data subject* |
| `EXPIRED` | `dpv:ConsentExpired` | exact |
| `INVALIDATED` | `dpv:ConsentInvalidated` | exact |
| `UNKNOWN` | `dpv:ConsentUnknown` | exact |
| `NOT_ASKED` | **none** | nearest are `dpv:ConsentRequested` / `ConsentRequestDeferred`, both of which assert a request *was* made — the opposite fact |
| `PENDING_SYNC` | **none** | offline-first operational state; no counterpart needed |
| `CONFLICTED` | **none** | as above |

**Two DPV terms the platform has no value for, and both matter:**

- **`dpv:ConsentRevoked`** — revocation by an entity *other than* the data subject. The platform
  collapses that into `WITHDRAWN` or `INVALIDATED`, losing who ended it.
- **`dpv:RenewedConsentGiven`** — *"previously given consent renewed or reaffirmed to form a new
  instance"*, which is precisely the Korea PIPA re-confirmation concept carried in
  `V19__korea_reconfirmation.sql`. There is a standard term for it and the platform does not use it.

Also unmapped, and not needed: `dpv:ConsentControl` / `ObtainConsent` / `ProvideConsent` /
`WithdrawConsent` / `ReaffirmConsent`.

## 3. Purposes — the 21 seeded codes in `V3__seed_group_and_purpose_taxonomy.sql`

| Platform purpose code | Nearest DPV v2 Purpose | Fit |
|---|---|---|
| `MKT_OUTBOUND_CALL` / `_SMS` / `_EMAIL` / `_WHATSAPP` | `dpv:DirectMarketing` | good (DPV does not split by channel; the platform must) |
| `TXN_SERVICE_SMS` | `dpv:CommunicationManagement`, `dpv:RequestedServiceProvision` | approximate |
| `SALES_RELATIONSHIP` | `dpv:CustomerRelationshipManagement` | good |
| `PROSPECT_ENRICHMENT` | `dpv:CustomerRelationshipManagement` | **weak** — DPV has no data-enrichment or third-party-append purpose. Notable: purchased and appended contact data is a defining Denave risk |
| `LEAD_PROFILING` | `dpv:CustomerRelationshipManagement` + `dpv:Personalisation` | **weak** — DPV models profiling as a *Processing operation*, not a purpose (§4) |
| `WEB_STRICTLY_NECESSARY` | `dpv:ServiceProvision`, `dpv:EnforceSecurity` | approximate |
| `WEB_ANALYTICS` | nearest `dpv:CommercialResearch` / `ServiceProvision` | **weak** — DPV v2 core has no Analytics purpose |
| `WEB_ADVERTISING` | `dpv:TargetedAdvertising` | exact |
| `HR_EMPLOYMENT_ADMIN` | `dpv:PersonnelManagement` | exact |
| `HR_PAYROLL_STATUTORY` | `dpv:PersonnelPayment` + `dpv:LegalCompliance` | exact (two terms) |
| `HR_ATTENDANCE_BIOMETRIC` | `dpv:PersonnelManagement` + `dpv:IdentityAuthentication` | approximate — no attendance/time-tracking term |
| `HR_FIELD_LOCATION` | `dpv:PersonnelManagement` + `dpv:Verification` | approximate |
| `BGV_IDENTITY` | `dpv:IdentityAuthentication`, `dpv:Verification` | good |
| `BGV_EDUCATION` / `BGV_EMPLOYMENT` | `dpv:Verification` + `dpv:PersonnelHiring` | approximate |
| `BGV_CRIMINAL_RECORD` | `dpv:Verification` + `dpv:PersonnelHiring` | approximate — no criminal-record-check term |
| `CALL_RECORDING_QUALITY` | `dpv:CommunicationForCustomerCare` + `dpv:PersonnelManagement` | **weak** — no quality-monitoring or agent-training purpose |
| `SECURITY_FRAUD` | `dpv:FraudPreventionAndDetection`, `dpv:MisusePreventionAndDetection` | exact |

Four exact, seven good, ten approximate-to-weak. **No platform purpose is *wrong* against DPV; several
have no DPV term at the granularity the platform needs** — which is the expected outcome and the reason
for the `broader`-pointer posture above.

**Two DPV purposes with no platform counterpart that are worth a look:** `dpv:SocialMediaMarketing` and
`dpv:SellDataToThirdParties`. If any UDS entity does either, there is no purpose code for it, and
`PROSPECT_ENRICHMENT` is not it. (Also unmapped and not needed: `AccountManagement`,
`CustomerClaimsManagement`, `CustomerOrderManagement`, `CustomerSolvencyMonitoring`,
`EnforceAccessControl`, `EstablishContractualAgreement`, `ProtectionOfIPR`, `OrganisationGovernance`,
`RecordManagement`, `VendorManagement`, `SellProductsToDataSubject`, `PublicRelations`, `PublicBenefit`,
`NonCommercialPurpose`, `CounterMoneyLaundering`, `MaintainFraudDatabase`, `RightsFulfillment`,
`ProvidePersonalisedRecommendations`.)

## 4. Processing operations — the whole axis is missing

[S3] §4 lists Processing (Collect, Store, Share, …) as one of DPV's ten core concepts, and [S2] Table 4
gives it `dpv:hasProcessing` at `0..*`. **The platform has no processing-operation vocabulary at all.**
`Channel` is the nearest thing and it is a delivery surface, not a processing operation.

Optional in 27560, so **not a conformance defect** — but it means the receipt says *why* and *what
data*, never *what is done*. It is also the axis on which profiling would properly be expressed, which
is where `LEAD_PROFILING` currently strains.

## 5. Capture method — no conflict, and a free win

`CaptureMethod`'s 12 values map to `dpv:hasIndicationMethod`, for which [S2] Table 6 records **"DPV
Concept: N/A"** — there is no taxonomy to contradict, so the enum stands as it is.

But its *Consent Type* dimension **is** modelled by DPV (`ImpliedConsent` / `ExpressedConsent` /
`ExplicitlyExpressedConsent`), and `CaptureMethod` already encodes it implicitly:
`CHECKBOX_OPT_IN` is expressed, `INFERRED_FROM_RELATIONSHIP` is implied. **A derived accessor would fill
the receipt's missing Consent Type field (`1..*`) with no new data and no schema change** — the cheapest
of the gaps in
[`iso-27560-consent-records.md` §2.4](iso-27560-consent-records.md).

## 6. Not checked

The **`dpv-pd` personal-data taxonomy** against the platform's 17 `data_category` codes. Personal Data
Type is a mandatory `1..*` field in the record structure and the platform's codes are locally invented,
so that axis is **unmapped, not mapped-and-clean**. The only `pd:` term seen in a primary source here is
`pd:Email` ([S3] §4's example).
