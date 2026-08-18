# IAB TCF and Google Consent Mode v2, against this platform

**TCF is an adapter, never the core model.** `UDS_Consent_Control_Plane_v2_FINAL.md:47` already corrects
an earlier document that conflated an adtech signalling framework with a consent record format, and this
file must not re-introduce the conflation. Consent *records* are Kantara → ISO/IEC TS 27560 (see
[`iso-27560-consent-records.md`](iso-27560-consent-records.md)); TCF and GCM are **projections outward**.

**What was read, and when.** All access dates **17 August 2026**.

- **IAB TCF** — `InteractiveAdvertisingBureau/GDPR-Transparency-and-Consent-Framework`, 951 stars, 370
  forks, no repository licence declared, created 7 March 2018, **last pushed 7 August 2026** — actively
  maintained, and the only target here that is a live standard. `TCFv2/IAB Tech Lab - Consent string and
  vendor list formats v2.md`, **1,837 lines, read in full**, plus the tree of the other v2 specs (CMP API
  v2, Additional Vendor Information List, device storage duration, implementation guidelines).
  <https://github.com/InteractiveAdvertisingBureau/GDPR-Transparency-and-Consent-Framework>
- **Google Consent Mode v2** — via `tagticians/consent-management-platform`, **1 star, 9 commits, created
  and last pushed 2 March 2026** (one day's work; `CLAUDE.md` in the tree; **no LICENSE file** despite
  the README saying "MIT", GitHub reports `null`). `banner/cmp.js` (33.5 KB) and `cmp-config.json` read
  directly. **A reference for the contract, not an architecture reference.**
- **ConsentStack/cmp** — 24 stars, 20 commits, MIT, **last pushed 5 September 2018**, dormant. Read for
  its dark-pattern critique only. Sizes and dates for both OSS repositories, and the critique in full,
  are in [`../competitive-analysis.md`](../competitive-analysis.md) §A.5.

---

## 1. TCF's purpose / vendor / legal-basis model

**Purposes are a global registry of numbered slots**, `id` 1–24, defined in the Global Vendor List with
`name`, `description`, `illustrations`, and two governance flags: `consentable` (false ⇒ a CMP must never
offer an opt-in) and `rightToObject` (false ⇒ never offer an objection). Special purposes, features
(1–64) and special features (1–8) are parallel registries; **stacks** group purposes for presentation.

**Legal basis is two parallel bit fields over the same numbering** — `PurposesConsent` (24 bits) and
`PurposesLITransparency` (24 bits). Vendors declare per-purpose *flexibility*; publishers override with
**publisher restrictions** carrying a 2-bit `RestrictionType`: `0` purpose not allowed regardless of
flexibility, `1` require consent, `2` require legitimate interest. TCF v2.2 deprecated legitimate interest
for purposes 3–6 — *"Bits 2 to 5 are required to be set to 0."*

**The string** is dot-joined base64url bitfields, `[Core].[DisclosedVendors].[PublisherTC]`. The core
segment's metadata is the interesting part: `Version`(6), `Created`(36), `LastUpdated`(36), `CmpId`(12),
`CmpVersion`(12), `ConsentScreen`(6), `ConsentLanguage`(12), `VendorListVersion`(12), `TcfPolicyVersion`(6),
`IsServiceSpecific`(1), `UseNonStandardTexts`(1), `SpecialFeatureOptIns`(12), `PurposesConsent`(24),
`PurposesLITransparency`(24), `PurposeOneTreatment`(1), `PublisherCC`(12), then vendor sections and
publisher restrictions. Only a registered CMP may create one: *"Vendors or any other third-party service
providers must neither create nor alter TC Strings."* Storage is unconstrained; where JS cannot run it
travels in `&gdpr_consent=${GDPR_CONSENT_XXXXX}`.

**What TCF gets right, and it is worth naming.** Positive consent may not be encoded before affirmative
action: *"A TC String that contains positive consent signals must not be created before clear affirmative
action is taken by a user that unambiguously signifies that user's consent."* `ConsentScreen` records
*which* screen captured it. `UseNonStandardTexts` flags that the CMP altered IAB wording — a built-in
signal that the disclosure was non-standard.

## 2. Why the consent string is the wrong shape for a hash-chained ledger

Not a matter of taste. Four properties of the format are incompatible with the evidence plane.

1. **`Created` and `LastUpdated` were deliberately collapsed to one day-level value.** The change log
   records the update *"to have the same value corresponding to the day-level timestamp of when the TC
   String was last updated"*, reasoning at line 330 that they *"previously corresponded to decisecond
   timestamps"* and citing *"practical guidance from DPAs… and the limited relevance of the Created
   field."* **So a TC String cannot say when consent was first given, only which day it was last touched,
   and it cannot represent a history at all.** A record that overwrites its own creation timestamp is not
   a record.
2. **A policy change requires destroying the record.** Line 1450: when the Managing Organisation
   increments the policy version, *"a CMP is required to discard the user's current TC String and
   resurface the user interface… and encode a new TC String **without migrating any old values over from
   the old one**."* This is the purpose-version failure mode elevated to a mandate, and it is the exact
   inverse of our invariant that a receipt pins the consented purpose version and a taxonomy change never
   retroactively alters what a principal agreed to. **TCF's answer to "the wording changed" is to erase
   the prior answer and re-ask — which also produces a fresh opportunity to obtain consent that was
   previously refused.** That is the mechanism behind ConsentStack's "aims to gather high rates of
   consent".
3. **Purposes have no version of their own.** The GVL purpose object is `{id, name, description,
   illustrations}`. Wording changes are tracked two levels above the purpose — `vendorListVersion` (any
   file change) and `tcfPolicyVersion` (annotated as incrementing on *"a change in Purpose wording"* that
   *"legally invalidates existing TC Strings"*). So fidelity to what the person read is a pointer to a
   whole-list version, and when it matters most the pointer is invalidated and the string discarded.
4. **Client-side by construction, one identifier at best.** No data-subject identifier, no server-side
   evidence obligation. **TCF cannot express "this person, known by phone and email, withdrew."** And it
   has no withdrawal propagation and no delivery evidence: propagation is "the next ad call carries a
   different string", and a vendor that cached a prior string is invisible.

Also baked in: the 13-month re-ask cycle (*"as appropriate and at least every 13 months"*) is part of the
reasoning for the timestamp collapse, which normalises periodic re-consent as the maintenance model.

**Where TCF is genuinely ahead of us.** Interoperability, unambiguously — a TC String is understood by
thousands of vendors with no bilateral agreement, and every one of our integrations is bespoke. And the
publisher-restriction model is **more expressive than anything we have**: a publisher can, per purpose,
downgrade a vendor's declared legitimate interest to require consent, or forbid the purpose outright,
binding regardless of the vendor's flexibility. We have subsidiary override of purpose *values* by nearest
ancestor; we have no way to say *"this entity forbids this purpose for this processor even though the
processor claims a legitimate interest."*

## 3. Google Consent Mode v2 — the contract Denave's website will need

**The seven parameters:** `ad_storage`, `ad_user_data`, `ad_personalization`, `analytics_storage`,
`functionality_storage`, `personalization_storage`, `security_storage`. Categories map **many-to-one**
onto them; from `banner/cmp.js`'s own default config: `necessary → [security_storage]`, `analytics →
[analytics_storage]`, `marketing → [ad_storage, ad_user_data, ad_personalization]`, `functional →
[functionality_storage]`, `personalization → [personalization_storage]`.

**The lifecycle is three steps:** on load push `consent('default', …)` with **every parameter `denied`**;
on choice push `consent('update', …)` per category; also fire a `cmp_consent_update` dataLayer event
carrying the full state.

**The state API shape, verbatim:**

```javascript
CMP.getConsent();      // → { necessary: true, analytics: true, marketing: false, ... }
CMP.getGcmState();     // → { ad_storage: 'denied', analytics_storage: 'granted', ... }
CMP.onConsentChange(function (state) { ... });
CMP.resetConsent();    // clears cookie, re-shows banner
CMP.showPreferences();
```

Note the deliberate **two-vocabulary split**: `getConsent()` returns the platform's own category booleans,
`getGcmState()` returns the vendor-facing `'granted'`/`'denied'` strings. One state, two projections, and
the mapping is data in a config file rather than logic. **That is the borrowable part.**

**Why we need this at all:** for any UDS property running GTM, Google's tags read GCM and **cannot see our
decision API**. That is a wiring gap, not a philosophical one.

## 4. Borrow

**From TCF**

1. **Publisher restrictions, adapted to the entity hierarchy** — the most valuable structural idea in the
   standard and the one thing in the field we have no equivalent of. **There is no existing walk to
   resolve it through** — this paragraph cited "the existing recursive-CTE nearest-ancestor walk in
   `PurposeRegistryStore`" on the authority of an invariant that turned out to describe machinery the
   platform does not have (rules §3, corrected in Phase 16's closure). The only walk is
   `EntityStore.inheritanceChain`, an iterative loop over the parent link serving entity contacts alone.
   So this is a build, not a reuse: a new entity-scoped table, its RLS policy on `uds_entity_claim()` in
   the same migration, and an ancestor resolution written for it — modelled on `EntityStore`'s shape,
   which is the only precedent there is.
2. **`consentable: false` / `rightToObject: false` as registry data.** `CaptureValidator` already refuses
   to record consent for a DPDP s.7(i) legitimate use — but that judgement lives in code, so the purpose
   registry does not carry the fact and nothing reading the registry can see it. As a column on
   `PurposeDefinition` it becomes visible to every consumer (a future banner, the RoPA export) and turns
   "why was this refused" into a lookup.
3. **`ConsentScreen` and `UseNonStandardTexts` as capture-context fields.** Two cheap, high-value evidence
   facts: *which* screen captured this, and *was the approved wording altered*. The second is the sharpest
   idea in the spec. Both belong inside `canonical_payload` and therefore inside the hash chain — where
   the guardian-verification precedent already puts capture-context evidence.
4. **Jurisdiction asserted at capture (`PublisherCC`), recorded distinctly from the one the engine
   derived** — so an audit replay can tell "the engine chose wrong" from "the surface told us wrong".
5. **Stacks, for presentation only.** Group purposes for the human, store per purpose. Note the direction:
   it is the inverse of the single-`location`-toggle failure that is the most common way a consent system
   becomes indefensible.

**From Consent Mode v2**

1. **The two-vocabulary projection** — one authoritative state, two named projections, mapping as
   declarative config. A `GET`-side GCM projection of a decision belongs in the platform, **computed by
   the same engine**: `consent-core` is framework-free precisely so the same logic runs server-side, in
   the offline evaluator and in an SDK. A GCM projection is a third consumer of that engine, not a fourth
   policy.
2. **Deny-by-default before any signal** — all seven `denied` on load, before the user acts. The same
   instinct as provenance defaulting to quarantined and the engine denying on `POLICY_ERROR`: encode the
   safe state as the default so nobody has to remember to choose it. Any UDS capture surface must push
   denied-default before GTM loads, and that belongs in the integrator contract.
3. **Many-to-one mapping as data**, never a `switch` — our purposes are finer-grained than seven
   parameters and always will be, so the fan-in must be a table an operator can inspect and an auditor can
   read, in the purpose registry. If a subsidiary must be able to differ, the ancestor resolution has to
   be **built** for that table — nothing in the purpose registry inherits today (rules §3).
4. **An `onConsentChange` observer and a change *event*.** `cmp_consent_update` announces the change with
   a payload rather than leaving it in mutable state a consumer must poll — our outbox in miniature, and
   an argument for the browser-side contract being event-shaped too.

## 5. Refuse

**From TCF**

1. **The consent string itself, in every form.** No subject identifier, a state rather than a history, a
   `Created` field overwritten on every update, no way to express a merge, no hash link. **There is no
   partial adoption that is safe: a TC String is a cache of a decision**, and treating one as evidence is
   the mistake the evidence plane exists to prevent. If adtech interoperability is ever needed, **emit** a
   string as a derived, disposable projection of the ledger — never accept one as an input to it, and
   never store one as the record.
2. **Day-level `Created == LastUpdated`.** Our ordering uses `occurredAt` with the server sequence number
   as tiebreak, and a five-minute conflict window that sends a projection to `CONFLICTED` and denies.
   Day-granularity would make that window meaningless and thousands of skewed Android devices unorderable.
   The DPA guidance TCF cites concerns what a controller must be able to *show*; it is not a licence to
   reduce timestamp resolution in a system whose ordering is load-bearing.
3. **"Discard the string and re-ask on a policy change" — the single most important refusal here.** A
   wording change produces a new purpose version and a re-consent *obligation*, surfaced the way
   `reconfirmation-overdue` is: raised, exposed on `/actuator/health`, with the existing evidence
   untouched.
4. **Global numbered purpose slots.** 24 centrally-assigned integers cannot express "GPS location, for
   field-attendance verification" versus "GPS location, for marketing personalisation" across seven
   regimes — and TCF's own v2.2 deprecation of legitimate interest for purposes 3–6 shows what happens
   when semantics are pinned to integers. Keep `purpose_code` plus version.
5. **The 13-month re-ask cycle as a maintenance model.** Not a statutory requirement in any regime we
   serve. Korea's Art. 62-3 two-year re-confirmation is real and is modelled as an obligation that still
   *allows*; a generic 13-month re-ask would suppress lawful contact on the platform's own authority,
   which `OPERATIONS.md` §10 forbids.
6. **"Only a registered CMP may create or alter the record."** A governance rule that reads like an
   integrity control and is not one. Our integrity control is that the chain is verifiable by anyone
   holding the data and the database refuses `UPDATE` to the application role.

**From Consent Mode v2 / the reference implementation**

1. **Cookie-as-record-of-consent.** `tagticians` stores `{categories, timestamp, version:'1.0'}` in one
   365-day cookie and makes **no consent-logging network call at all** — the only XHR fetches its config.
   That is amnesia with a UI. The projection must be derived from the ledger append, never the reverse;
   a capture surface's cookie is a cache.
2. **`resetConsent()` as a withdrawal.** Deleting the cookie and re-showing the banner produces no
   withdrawal event, no suppression, no propagation and no evidence — **and it re-asks, which is
   consent-rate optimisation wearing a privacy control's name.** Withdrawal here is an appended event,
   then canonicalisation so one identifier's withdrawal suppresses the others, then the outbox, then a
   `webhook_delivery` row proving arrival.
3. **Seven parameters as the consent model.** They are Google's storage-and-use categories, not purposes.
   **Project outward to seven; never store seven.**
4. **A format-version field named like a consent version.** `version: '1.0'` beside a consent record that
   does not identify what was consented to is worse than no field, because a reader assumes it does. A GCM
   projection carries the purpose versions it was derived from, or no version field at all.
5. **An unauthenticated admin surface over the live taxonomy.** *"Without it, the admin is open (suitable
   for local development)"* — over the file that *is* the consent taxonomy. Whatever front end is built,
   its config is a control-plane object under `ROLE_ADMIN`, swept in both directions by `AdminApiIT`, not
   a JSON file the banner fetches over public HTTP.

## 6. The dark-pattern critique, read against `DpdpModule`

ConsentStack's Motivation section is the field's clearest statement of the problem, and it is an argument
about **objective functions**, not about any one banner: opt-in rate as the metric produces dark patterns
whether or not the vendor's own guide advises against them, because the metric cannot distinguish a
clearer explanation from a better-hidden reject button. Usercentrics ships that metric as instrumented
product; TCF's architecture rewards it structurally (§2.2).

Read against this platform: **`DpdpModule` Rule 8 refuses the capture rather than warning about it** —
pre-selection and disguised refusal are validation failures at the door, and `CaptureValidator` is where a
dark pattern stops being representable rather than being discouraged. That is stronger than a guideline.

**But the honest limit, stated because it is the group's real exposure:** the refusals bind what the
*platform* will record. **The banner is where a dark pattern lives, and we do not ship one** — every
capture surface in the group builds its own, so the layer a regulator actually looks at is the one layer
outside these guarantees. See [`../competitive-analysis.md`](../competitive-analysis.md) §C.1.
