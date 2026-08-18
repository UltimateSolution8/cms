# Competitive analysis — what the field gets wrong, and where this platform is behind it

A teardown, not a feature grid. **Public documentation only**; access date for every source is
**17 August 2026**. Nothing here rests on a page behind a login, no pricing is reproduced, and no claim
is attributed to a product that its own documentation does not support. **Absence of documentation is
recorded as a finding, not filled in by inference.**

**On citations:** each product's sources are named at the head of its section and the load-bearing
findings carry their own URL. Some do not — Didomi's five-year retention, OneTrust's 10/20 purpose cap,
and the repository statistics for `tagticians` are quoted from the section's named sources without a
per-line link. That is stated here rather than claimed away, because "every finding carries a URL" was
the first draft's wording and it was not true of every line.

§C is the section to read if you are deciding whether to keep building. It is unsparing on purpose: a
teardown that finds only weaknesses in other people's products is marketing.

---

## A. Per target

### A.1 OneTrust

Largest commercial privacy platform; Universal Consent & Preference Management plus Cookie Consent.
Developer portal at `developer.onetrust.com`, open. **Read:** the v2 receipts references (list, create,
create-identified), the withdraw-by-purpose reference, the mobile Save-and-Log-Consent guide. The
`my.onetrust.com` knowledge base is a Salesforce SPA that returned a CSS error to a plain fetch — **not
read, and nothing below rests on it.**

Server-side Receipt/Transaction model: a collection point authenticates with a platform-minted JWT, and
each interaction produces a Receipt of Transactions, one per purpose, each with a `TransactionType`
(`OPT_IN`, `OPT_OUT`, `CONFIRMED`, `WITHDRAWN`, …).

**Ahead of us, or level:**

- **Purpose version is on the wire.** `DsPurpose.Version`, plus a purpose version and a collection-point
  version on read-back — the same discipline as our receipt pinning the consented purpose version.
  Parity, not a lead. <https://developer.onetrust.com/onetrust/reference/createconsentreceiptusingpost>
- **Multi-identifier by design** — `identifier` + `identifierType`, `additionalIdentifiers`, and
  `parentPrimaryIdentifiers` for guardian/child linkage. We reach the same place through `subject_alias`
  canonicalisation; they *document* it and we publish nothing.
- **Tracker discovery and zero-code auto-blocking**, categorised against Cookiepedia's "over 11 million
  pre-categorized technologies". We have nothing resembling it.
  <https://www.onetrust.com/products/cookie-consent/>
- **No documented mutate or delete path on receipts.** Their evidence plane is at least not *documented*
  as rewritable — more than Didomi can say.

**What it gets wrong:**

- **Withdrawal is a `GET`.** `GET /api/consentmanager/v1/transactions/withdraw/purpose/{purposeId}`, the
  subject identifier in a header. A legally consequential state change on a cacheable, prefetchable,
  link-followable verb — and the same page says *"This API is not designed to be used in synchronous
  workflows"*, so the 200 "Consent withdrawn successfully" does not mean the withdrawal has been
  applied. **A 200 that is not an assertion about state is the worst possible response to a
  withdrawal.**
  <https://developer.onetrust.com/onetrust/reference/withdrawtransactionbypurposeandidentifierusingget>
- **Notice version is inferred from a date, not pinned by the caller.** *"the version for
  `PrivacyNotices` will be used based on the consent date."* The record says which version was *current
  on that date*, not which text the person was shown — so a republished notice with a backdated
  effective date, or a capture synced late from an offline device, silently attaches the wrong text.
  `NoticeIT` exists because a notice version must reproduce byte-identically **as served**.
- **No actor on the receipt.** Neither the create nor the read-back schema has an actor or created-by
  field; the collection-point JWT is the only attribution. You can tell which collection point wrote it,
  never which human. Ours records `client=…;actor=…`.
- **The documented receipt is a symmetric MAC.** The success response in their own mobile guide contains
  a `receipt` JWS whose header decodes to `{"kid":"…","alg":"HS512"}` — HMAC with a shared secret, so it
  is verifiable only by a party holding the issuer's key, which means **it is not evidence against the
  issuer**. Stated narrowly: one documented example on one endpoint, and no receipt-verification
  specification was findable. <https://developer.onetrust.com/onetrust/docs/save-and-log-consent>
- **Authoritative next-request state lives on the device.** *"It will be the application's
  responsibility to store the otConsentString locally on the device and pass it in subsequent API
  calls."*
- **Validation deliberately off.** *"This API does not perform data type validation to ensure high
  performance."* A capture surface that does not validate records malformed consent as consent. Our
  `CaptureValidator` refuses at the door, including refusing consent for a legitimate-use purpose.
- **`purposes` capped by recommendation** — 10 recommended, 20 absolute. A schema constraint dressed as
  guidance; a group with a real purpose registry hits it.
- **Withdrawal propagation: absence is the finding.** Integrations are marketed as how consent reaches
  downstream systems; no public documentation of per-recipient delivery records, retry state, or a
  queryable delivery log. Nothing says it does not exist; nothing published says it does.
- **Auto-blocking's own documented gap:** a newly appearing cookie is not blocked until categorised and
  the script republished — the window between a new tracker landing and a human classifying it is
  unblocked by design.

### A.2 Didomi

The most credible public developer documentation of the three. **Read:** the consents/events model page,
webhooks integration, Versions & Proofs introduction, the "How Didomi manages proof of consent" support
article, `/consents/proofs`.

Explicitly event-sourced: an event is *"a partial update to the consent status of a user"* and current
status is a **projection recomputed from the event set**. Events carry `user.id`,
`user.organization_user_id`, `regulation`, `consents.purposes[]`, `consents.vendors`, an optional TCF
string, metadata, a `delegate` block, `proofs_id`, and a `confirmed`/`pending_approval` status.

**Ahead of us:**

- **The notice *as configured* is pinned to the record** — vendors, purposes, text, formatting and theme
  at the time of collection. We pin the purpose version and reproduce the notice text; we do not capture
  the rendered banner's layout or theme. **If the dispute is "the button was styled to mislead me", they
  can answer it and we cannot.** <https://support.didomi.io/how-didomi-manages-proof-of-consent>
- **Attachable physical proof** — a signed paper form, scan or recording bound to the electronic event
  (≤5 files, 10 MB, PDF/PNG/JPG/GIF/DOCX/DOC/MSG). We have no artefact-attachment path on a consent
  event at all, and we run a field force that captures on paper.
- **A `delegate` field** — consent given on behalf of someone, by a named delegate, in the event itself.
- **Published retention** — five years. A stated number beats our silence.
- **Published webhook retry semantics** — *"we retry at least five times every five minutes"*, then
  archive undelivered events for later retrieval.
- **A preference centre**, which we have declined to build.

**What it gets wrong:**

- **Consent events are deletable, and deleting one rewrites history.** `DELETE /consents/events` and
  `DELETE /consents/events/{id}`, and *"the user consent status will be automatically re-computed by
  re-applying the remaining (non-deleted) consent events"*. **This is the mutable-evidence failure mode
  in its purest form:** after a delete, nothing distinguishes "the subject never granted this" from
  "someone deleted the grant". Ours refuses `UPDATE` and `DELETE` to the application role in the
  database and `LedgerAppendOnlyIT` proves it *as that role*; a correction is a new appended event.
  <https://developers.didomi.io/api-and-platform/consents/events>
- **Events are also patchable**, and `event.updated` / `event.deleted` sit beside `event.created` in the
  webhook catalogue — the mutability is not an edge case, it is a first-class part of the model
  downstream systems are expected to react to.
- **Purpose-version fidelity is not in the event.** The purpose object is `{id, enabled, values}` — no
  version. The notice *configuration* is versioned at console level, which tells you what the whole
  notice looked like, not which version of `marketing_email` was accepted. And their own caveat narrows
  even that: *"If you are not using the console to manage your notices… Didomi does not store the
  notices' configuration versions."* Configure in code and you lose notice versioning entirely.
- **Proof is not available for 24 hours** — a batch pipeline sits between the act and the evidence. Our
  evidence bundle is a synchronous read of the chain.
- **No immutability or tamper-evidence claim anywhere** in the Versions & Proofs pages or the support
  article. Given that `DELETE` is documented the absence is consistent rather than an oversight, but it
  means the audit answer is "trust our database".
- **Webhook authenticity is not documented** — OAuth and an IP allowlist are offered, HMAC request
  signing is not mentioned. An allowlist authenticates a network path, not a payload.
- **Retry counts are not delivery evidence.** "Retry five times then archive" is what the *sender*
  attempted. No queryable per-delivery record of arrival. Our `webhook_delivery` row is the thing that
  proves it landed.

### A.3 Usercentrics

German CMP; web CMP v3, in-app and browser SDKs, TCF 2.3 / GPP / GCM v2 surfaces. **Read:** the in-app
SDK session-restore page, the support article on a visitor's information request, the Consent Management
API page, the consent-rate and A/B testing resources. **Their docs root is a JS SPA that returns a bare
title to a fetch, so this is the thinnest coverage of the three and the API-level detail is
correspondingly thinner.**

Client-first. The `controllerID` is *"a Usercentrics generated ID, used to identify a user's consent
history"*, minted by the SDK when consent is given; cross-device continuity means extracting it on one
device and calling `restoreUserSession(controllerId)` on another.

**Ahead of us:**

- **Two independent version pins per exported row** — `CMP Configuration Version` *and* `Data Processing
  Service Version`. On version fidelity in the exported record this is the strongest of the three, and
  at least parity with us.
- **A banner, a second layer and a preference UI nobody has to build.**
- **Dynamic notice text by jurisdiction and a translation pipeline that exists.** Ours does not.
- **Documented cross-device continuity**, including the honest carve-out that it is *"not supported for
  CCPA"*. <https://docs.usercentrics.com/cmp_in_app_sdk/latest/features/restore-user-sessions/>

**What it gets wrong:**

- **One identifier per subject, and the subject has to carry it.** To answer an access request *"end
  users can send Usercentrics customers their ControllerID, which they can retrieve from the CMP's
  second layer"*; the operator pastes it in and downloads a CSV. The evidence key is a device-generated
  opaque token, the data subject is responsible for producing it, and consent given on a phone is
  unfindable from a laptop or after clearing storage. **No documented resolution from an email address
  or phone number to a consent history** — the model cannot express that a phone and an email are one
  person, which is exactly what `subject_alias` exists for.
  <https://support.usercentrics.com/hc/en-us/articles/15209263427740-How-can-I-comply-with-a-visitor-s-request-for-information>
- **A twelve-month evidence horizon** — the export returns *"all consents made by that visitor in the
  last 12 months."* A complaint about processing eighteen months ago is unanswerable from that tool.
- **Cross-device continuity is a paid unlock** — *"a Premium Feature that is only enabled on request."*
  Whether a person is one person or several is a commercial tier, not a property of the model.
- **Opt-in rate is openly the success metric, and it ships as product.** Banner A/B testing that
  *"collects data about each variant's engagement levels, consent choices, and opt-in rates"*, plus a
  consent-rate optimisation guide and an opt-in optimisation resource. To their credit the same material
  advises against dark patterns and for granular choice — but **an instrumented optimiser whose
  objective function is opt-in rate is an instrument for finding dark patterns whether or not the guide
  recommends them, because the metric cannot tell a clearer explanation from a better-hidden reject
  button.** <https://usercentrics.com/resources/ab-testing-cookie-banner/>
- **Withdrawal propagation is the integrator's problem** — call the client API, read current state, keep
  your own copy. <https://usercentrics.com/consent-management-api/>
- **Evidence delivery is an operator action on a CSV.** No documented API for a subject's evidence
  bundle and no audit of the read. `EvidenceBundleIT` audits the read itself and fails the build if a
  subject-scoped table is missing from the bundle.

### A.4 Kavach / KavachOne — ConsentiQo

**Identification first.** The Indian consent-manager offering trading under this name is **KavachOne**,
whose consent product is **ConsentiQo** (`kavachone.com`, `consentiqo.com`). **This is not the Government
of India's Kavach security/2FA application, which is unrelated and is not discussed here.**

**It publishes marketing and blog pages, and nothing else — checked, not assumed.**
`docs.kavachone.com`, `developer.kavachone.com` and `api.kavachone.com` do not resolve; `/docs` and
`/api-docs` return 404; neither product page links to any API reference, SDK doc, integration guide,
OpenAPI file or data model; npm has **zero** packages matching `consentiqo` or `kavachone`; GitHub
returns three unrelated zero-star personal repositories. **Its self-service technical evidence base is
empty; the site directs an evaluator to book a demo.**

**Claims** (unverifiable, and reproduced as claims): REST API and webhooks at Professional tier and
above; SDKs for React, Next.js, WordPress, Flutter, Android, iOS; cookie scanning and categorisation;
DSAR handling; *"complete audit trail for regulatory evidence"*; PDF/CSV audit exports; *"7-year consent
log retention"*; 22 scheduled languages plus English; an India-hosted dedicated tenant at Enterprise.

**Ahead of us, on its own claims:** **twenty-two scheduled languages actually served.** This is the one
place a competitor claim, if true, beats us outright on a statutory obligation — nineteen of our
twenty-three required languages have no text, and `GET /v1/notices/reports/coverage` names that honestly
without closing it. Also cookie scanning, a consent UI, DSAR tooling and audit exports, none of which we
have.

**What it gets wrong — almost nothing, and that is the finding.** There is no architecture to critique.
Three observations that stay inside what the pages say:

- **"Complete audit trail" is an unexamined phrase.** No statement about immutability, append-only
  storage, hash chaining, cryptographic verification, or who may edit a consent log. Under our own rule
  — a consent log an administrator can update is not evidence whatever it is called — the claim is not
  assessable, and a product being evaluated as the *evidence* layer for a DPDP obligation should be
  publishing its audit model.
- **No claim of Data Protection Board registration** as a Consent Manager, despite being marketed as a
  "DPDP Consent Manager". Worth reading as a checked absence: Rule 4 becomes operational 13 November
  2026, and marketing the title is not holding it. *(We do not register either, and structurally cannot
  — the First Schedule independence requirement.)*
- **"7-year retention *as required by DPDP regulations*"** attributes a specific number to a
  requirement. That is a legal characterisation the page asserts and this teardown did not verify against
  the Rules. **Flag for counsel rather than repeat.**

### A.5 The two OSS repositories, at their real size

**`ConsentStack/cmp`** — 24 stars, 0 forks, 20 commits, MIT (LICENSE present), created 22 August 2018,
**last pushed 5 September 2018**. Dormant for nearly eight years, never archived, and its own README says
*"Technical Documentation: Coming soon…"*. A two-week 2018 side project with a manifesto. **Not a
production architecture reference**, and calling one that is how a nine-commit repository ends up cited
in an architecture decision.

Worth reading for one idea, its Motivation section, verbatim: *"it was a striking fact that the goal was
to drive the highest possible consent / opt-in rates… the publishing group Future, achieved a 95% opt-in
rate through the optimisation and usage of dark patterns… There was no thought given to the soft squidgy
animals at the other end of the screen."* The argument is not that 95% is suspicious; it is that **95% is
what you get when opt-in rate is the objective function**, so a CMP quoting opt-in uplift has told you
what it optimises. It also reaches for Cranor's *"A 'Nutrition Label' for Privacy"* (CMU SOUPS 2009) —
the strongest idea in the repository and the one worth taking, as a framing for how a purpose registry is
*presented*. Its TCF critique is four bullets, one of which is literally "etc"; the load-bearing one
("aims to gather high rates of consent") is correct and is argued properly in
[`docs/standards/tcf-and-consent-mode.md`](standards/tcf-and-consent-mode.md).

**What it gets wrong:** its own `docs/database.md` keys the events collection on **`client_id` — the
website operator, not the data subject.** A project that campaigns for the person at the other end of the
screen stores no per-person consent record: consent is a pair of booleans on a page-view event, resolved
against cookies. No purpose model, no versioning, no withdrawal path, no identity, no audit model.
"Decentralised Consent Storage" is Roadmap aspiration, not code. <https://github.com/ConsentStack/cmp>

**`tagticians/consent-management-platform`** — 1 star, 0 forks, 9 commits, created **and** last pushed
**2 March 2026**: the whole repository is one day's work, and the tree contains a `CLAUDE.md`, so read it
as an AI-scaffolded reference rather than a maintained project. **Correction to the brief: it is not
established as MIT** — the README's final line says "MIT", there is **no LICENSE file**, and GitHub's
licence detection reports `null`. Treat the licence as unstated.

Useful for exactly one thing, and it is genuinely useful: a clean, current statement of the GCM v2 +
CMP-state contract, extracted in
[`docs/standards/tcf-and-consent-mode.md`](standards/tcf-and-consent-mode.md).

**What it gets wrong:** persistence is a single first-party cookie (`cmp_consent`, 365 days) holding
category booleans, a timestamp and `version: '1.0'` — and **the only network call in the entire banner
fetches its own config file.** There is no consent-logging request at all: no server-side record, no
evidence plane, no identity, no withdrawal propagation (`resetConsent()` deletes the cookie, which is
amnesia), and `version` is the *format* version, not the version of what was consented to. The admin
panel — which edits the live consent taxonomy — is **unauthenticated unless `ADMIN_PASSWORD` is set**:
*"Without it, the admin is open (suitable for local development)."*

---

## B. Cross-cutting: what recurs across the field

Against the eight failure modes this teardown was briefed on:

1. **Purpose-version fidelity — split field, and the split is instructive.** OneTrust and Usercentrics do
   it. Didomi versions the notice configuration, not the purpose, and only when the console manages
   notices. TCF has no purpose version and *mandates discarding* the record when wording changes. The two
   enterprise products get this right, the open standard gets it structurally wrong, and the small
   projects never reach the question.
2. **Withdrawal-propagation evidence — universally absent.** Not one target publishes a per-recipient
   record that a withdrawal *arrived*. Didomi is closest and stops at sender-side retry counts, unsigned.
   OneTrust returns 200 on an asynchronous `GET`. Usercentrics tells you to keep your own copy. TCF has
   nothing. **Everyone records the intent; nobody records the arrival — the field's largest common hole,
   and the one we are built around.**
3. **Mutable audit trails — the field's default.** Didomi documents `DELETE` plus re-derivation, `PATCH`,
   and `event.deleted` webhooks. TCF mandates discarding strings on policy change. Only OneTrust
   publishes no mutate path, and its documented receipt is HS512 — verifiable only by the issuer. **No
   target publishes an append-only, tamper-evident consent plane.**
4. **Credential-not-person attribution — universal.** No actor field at OneTrust; Didomi's `delegate` is
   a consent-giving proxy, not an operator; Usercentrics' key is a device-minted token. Nobody records
   which named human made an administrative change.
5. **One identifier per subject.** Worst at Usercentrics (device-scoped, subject-supplied, cross-device
   a paid unlock). TCF has no subject identifier at all. ConsentStack keys on the publisher. Didomi and
   OneTrust handle it properly.
6. **Multi-entity isolation — nobody publishes an answer.** Didomi has `organization_id`; OneTrust has
   organisations and collection points. **Neither publishes a subsidiary hierarchy, an inheritance rule,
   or a database-level isolation guarantee.** This is where we are furthest ahead and it is the least
   visible thing we do.
7. **Operator-asserted fulfilment.** Usercentrics' DSAR answer is a human pasting an ID and downloading a
   CSV; Kavach markets PDF/CSV exports. **Nothing in the field documents a fulfilment gate** — no product
   publishes a refusal to close a rights request until named downstream systems have acted.
8. **Opt-in rate as the success metric.** Usercentrics ships it as instrumented product. TCF's
   architecture rewards it: a policy bump discards prior refusals and re-asks. ConsentStack exists to
   object to it. Our tree contains no consent-rate metric, no banner and no A/B surface — **largely
   because we have no banner at all, so this is currently virtue by absence rather than by
   construction.**

---

## C. Where this platform is behind the field

Sorted by what it would cost in a real evaluation. This is the section that matters.

1. **No banner, no second layer, no consent UI of any kind.** A one-day, nine-commit, one-star repository
   has a working injectable banner, a preferences panel, eight themes, IE11 support and a live
   diagnostics panel. "Consent is a platform, not a banner" is a true statement about where the value is
   and it is not an answer to *how does a person on a website say no*. Every capture surface in the group
   builds its own, differently — **so the layer a regulator actually looks at is the one we exert no
   control over, and the group's real dark-pattern exposure sits entirely outside this platform's
   guarantees.**
2. **No Google Consent Mode v2 signal.** Nothing emits `consent('default'…)` / `consent('update'…)`. For
   any UDS property running GTM, Google's tags read GCM and **cannot see our decision API**. A wiring
   gap, not a philosophical one, and the reference implementation is ~200 lines.
3. **No preference centre — and "declined" is a weaker position than it reads.** The engineering
   reasoning is sound (no session model; no unauthenticated write path into an append-only ledger). But
   DPDP Rule 14 and the Rule 4 Consent Manager framework both point toward the principal managing consent
   directly, three of five targets ship one, and Kavach markets one for India specifically. **The honest
   statement is that we declined a capability the field considers table stakes for reasons about our
   architecture rather than about the principal's interest**, and it should be re-litigated at the Phase 1
   gate rather than treated as settled.
4. **No tracker discovery, cookie scanning or auto-blocking.** We cannot answer "what is actually running
   on our web properties", which means **we cannot answer whether our own consent decisions are being
   honoured client-side.** Not plausibly buildable — it is a data asset, not a component.
5. **Nineteen of twenty-three required languages have no notice text.** Reported honestly by the coverage
   report, which correctly refuses placeholder rows. Kavach claims all 22 plus English; Usercentrics has
   a pipeline. **The one competitor claim that, if true, beats us on a statutory obligation.**
6. **~50 rps per instance, and authentication is the ceiling.** Decision p95 2.6 ms server-side against
   115 ms client-observed, ~110 ms of it BCrypt re-hashing the Basic credential per request; a 401 costs
   the same. No competitor publishes throughput, so this is not a measured deficit against them — but a
   decision point serving fifty requests a second is not one a hundred-thousand-call-a-day dialer
   consults synchronously. The fix is the auth scheme and **there is no IdP to point OIDC at.**
7. **An unauthenticated CPU-exhaustion path — closed 18 August 2026, Phase 16.** It was open as
   written: `RateLimitFilter` sat behind authentication, so invalid credentials produced 401s and zero
   429s at ~110 ms each, and refusing cost the defender more than the attacker. `PreAuthRateLimitFilter`
   now refuses ahead of the security chain, asserted as **429 and not 401** rather than merely as some
   429. Two caveats kept rather than dropped: the ~110 ms figure **has not been re-measured**, and the
   pre-auth ceiling is deliberately loose (400/s per address per instance) because behind a NAT it is one
   bucket for a building — so a distributed flood still passes it and the ingress control is defence in
   depth rather than absent. `OPERATIONS.md` §12.2, `CAPACITY.md` §7.
8. **The platform cannot reach a person.** Nothing consumes `rights.verification.requested`, so **every
   portal submission expires unverified — and since the clock starts at verification, the portal cannot
   currently start a clock.** Every competitor here can email a data subject.
9. **No rights-request fulfilment behind the gate.** Intake, clock, breach alerting and the `FULFILLED`
   gate are built; federated retrieval across DenCRM, the HRMS and BGV is not. The gate is better than the
   field's operator-assertion norm — **and a gate with nothing behind it produces 409s naming systems
   that have no integration.**
10. **No physical-proof attachment on a consent event.** Didomi binds a signed paper form or recording to
    the event. For a field-force business capturing on paper and doorsteps this is a real gap, and one of
    the few competitor features that would *strengthen* our evidence plane rather than dilute it.
11. **We pin the purpose version and the notice text; we do not pin what was rendered.** If a complaint is
    about presentation — button prominence, pre-selection, layout — Didomi's record answers it and ours
    does not.
12. **No published retention period for consent evidence.** Didomi says five years, Kavach markets seven,
    we say nothing.
13. **No signature verification on relayed Consent Manager requests.** `consent_manager.public_key` exists
    and is unused because the Board has published no signing standard. The reasoning is right — verifying
    against a scheme nobody implements would *look* like proof — and on 13 November 2026 somebody asks.
14. **No traces.** OTel bridge and exporter on the classpath, tracing off, no collector.
15. **No interoperability surface at all.** No TC String, no GPP string, no GCM signal. A TC String is
    understood by thousands of vendors with zero bilateral work; **every one of our integrations is
    bespoke.** Whatever is wrong with TCF as an evidence model, it is a lingua franca and we speak nothing.
16. **`consent_event` is unpartitioned by design** — correctly, since partitioning would force
    `recorded_at` into the two constraints the chain rests on. Name it anyway: an unbounded table with a
    deliberate ceiling on the tooling available to it.
17. **Nothing about us is published.** Every competitor has public docs an evaluator can read; three
    publish enough to be torn down properly. **If the build-versus-buy decision at the Phase 1 gate is
    made by people reading vendor sites, we are the option with no site.**

---

## D. What to borrow and what to refuse

The structural borrowings and refusals from IAB TCF and Google Consent Mode v2 are in
[`docs/standards/tcf-and-consent-mode.md`](standards/tcf-and-consent-mode.md) §4–§5, because they are
design decisions about this platform rather than observations about competitors. In one line each, the
five worth acting on:

- **Borrow TCF's publisher restrictions** — the one structural idea in the field we have no equivalent
  of. This line said "resolved through the *existing* nearest-ancestor walk"; no such walk exists for the
  purpose registry (rules §3, Phase 16 closure C6), so it is a build rather than a reuse.
- **Borrow `consentable: false` as registry data** — our validator already refuses consent for a DPDP
  s.7(i) legitimate use, and the registry does not carry the fact.
- **Borrow `ConsentScreen` and `UseNonStandardTexts`** as capture-context fields inside the hash chain.
- **Borrow GCM's two-vocabulary projection and deny-by-default**, as a read-only projection computed by
  the same engine.
- **Refuse the consent string as an input or a record**, refuse day-level timestamps, and refuse
  "discard and re-ask on a policy change" — that last one is the most important refusal in this document.
