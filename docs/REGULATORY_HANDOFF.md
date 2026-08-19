# Regulatory hand-off — items for UDS, not for the code

*17 August 2026. Regulatory sweep re-run today; §6 records what it did not find. This revision
closes the Korean re-confirmation question that three previous passes left open, and adds the
Significant Data Fiduciary and children's-consent obligations that the platform now models.*

Everything here came out of building the platform and **cannot be closed by changing it**. Each item
is either a decision for UDS, a question for counsel, or a fact to keep watching. They are recorded
in one place because the alternative is a comment in a Java file that the person who needs to act on
it will never read.

Where an item has a platform side, it is named and it is done; what is left is the part that needs a
person.

---

## 1. Dated obligations

| Date | What | Platform side | What UDS must do |
|---|---|---|---|
| **Now** | The **Data Protection Board is constituted** — Chairperson and Members appointed 6 June 2026, grievance portal live | `GET /v1/admin/evidence/subject/{entityId}/{subjectId}` assembles a principal's whole file in one call, chain-verified and audited | Name who answers a complaint, and rehearse it once against a real subject before the first one arrives. The evidence plane can answer; nobody has ever tried |
| **11 Sep 2026** | **Korea PIPA amendment** commences (promulgated 10 March 2026) | `BreachClock`'s Korean leg re-derived; `StatutoryClock`'s 10-day rights period re-checked and unchanged | Three governance items, below |
| **1 Oct 2026** | **Korea Network Act amendment** commences (passed 12 March 2026, promulgated 31 March 2026). Separate statute, separate date, six weeks after PIPA. Expands CISO responsibilities, creates an administrative-fine regime for breaches, and **tightens the rules on transmitting commercial information** — the leg that touches marketing directly. Its information-security assessment provisions follow on **1 April 2027** | **Built.** The Enforcement Decree's Art. 62-3 two-yearly re-confirmation is now modelled: a queue raised by `ReconfirmationSweeper`, the three required disclosures recorded as sent, an obligation surfaced on the Korean decision path, and an overdue count on `/actuator/health`. See §2 | Two Korean commencement dates now, not one. Confirm which UDS entity carries the CISO obligation. Name an owner for the re-confirmation queue — the platform raises the obligation and cannot send the message |
| **13 Nov 2026** | **DPDP Consent Manager framework (Rule 4)** becomes operational | Full interoperability: inbound grant and withdrawal relays bound to the authenticating credential, outbound record, an administrable register with suspension and reconciliation, refusals recorded as evidence | Decide whether to accept relays from day one, and from whom. Reconcile the register before go-live — see §4 |
| **13 May 2027** | Substantive **DPDP Rules** enforceable | The platform is built against them | The programme's binding date, unchanged |
| **On designation** | **DPDP Rule 13 — Significant Data Fiduciary obligations.** Annual DPIA, annual independent data audit, algorithmic due diligence, observations furnished to the Board. Triggered by a Government notification, not by a threshold UDS can self-assess against | **Built.** `sdf_obligation` and `algorithmic_system` registers, a rolling twelve-month cycle anchored on the last completion, artefact hashes so a completion is evidence rather than a claim, and `GET /v1/admin/sdf/{entityId}`. Empty for every entity today, because none is designated | If a notification arrives: supply the designation date, register the group's algorithmic systems, and name who conducts each assessment. The register is built and unpopulated by design |
| **Now** | **DPDP s.9 and Rule 10 — children.** Verifiable parental consent, plus **due diligence that the person identifying as a parent is an identifiable adult** — by reference to identity and age details the fiduciary reliably holds, or to a **virtual token issued by a Digital Locker service provider** | **Built.** A capture on a child's behalf is now refused unless it records which of Rule 10's routes was taken, against what (hashed) reference, when and by whom; minority is an append-only dated assertion rather than a mutable flag | Decide which route the group's surfaces will use. Route two needs a DigiLocker integration that does not exist — see §4 |

---

## 2. Korea — governance, not code

The September 2026 amendment changes who carries the risk more than it changes any number.

- **Administrative fines to 10% of total turnover** for the severe tier: intentional or grossly
  negligent repeat conduct within three years, a single incident touching ≥10 million principals, or
  ignoring a PIPC corrective order. This moves Korea from "a jurisdiction we serve" to one of the two
  sharpest exposures in the group. It is a board-level number, not an engineering one.
- **The business owner or representative is named as the person ultimately responsible.** Somebody
  specific carries this. Identify them, tell them, and make sure they can get at the evidence — which
  is the practical reason §1's rehearsal matters.
- **ISMS-P certification** becomes mandatory for designated large-scale controllers from
  **1 July 2027**. Determine whether any UDS entity is in scope. If it is, certification has a lead
  time measured in quarters and cannot be started in June 2027.
- ✅ **Resolved: the Network Act's periodic re-confirmation of marketing consent.** Three previous
  passes recorded this as unanswerable from primary text and deliberately built nothing. The
  primary text was located on 17 August 2026: **Enforcement Decree of the Information and
  Communications Network Act, Article 62-3 (수신동의 여부의 확인)**.

  - **Art. 62-3(1)** — a sender who obtained prior consent under Art. 50(1) or 50(3) must confirm
    the recipient's consent status **every two years from the date consent was obtained**
    ("그 수신동의를 받은 날부터 2년마다"), measured to the same date in the second year.
  - **Art. 62-3(2)** — the confirmation must disclose **the sender's name**, **the fact of the
    recipient's consent and the date it was given**, and **the method of indicating an intention to
    maintain or withdraw it**.
  - KISA and the KCC published revised illegal-spam guidance on **4 March 2026** covering this
    surface.

  All of it is built. The interval is two calendar years rather than 730 days — the two differ
  across a leap year and the Decree names the date — and all three disclosures are recorded as
  sent, because the obligation is not "we sent something" but "we sent something containing these
  three things".

- ⚠️ **Still open, and now a much narrower question: what silence means.** Art. 62-3 prescribes the
  interval and the disclosure. It does **not** say whether a recipient who never answers is treated
  as maintaining consent or as having withdrawn it. Industry practice treats silence as
  maintenance; practice is not text.

  **The platform therefore does not decide it.** An overdue confirmation raises the obligation
  `reconfirmation-overdue` on the Korean decision path and the decision still **allows**. Denying
  would enforce a rule nobody can cite, against the group's own commercial interest, on the
  platform's own authority; treating silence as consent without saying so would hide the question.
  `ReconfirmationIT.anOverdueConfirmationDoesNotDenyTheDecision` pins this, and its comment points
  back here.

  **This is the one Korean question left for counsel**, and it is a single sentence rather than a
  subject. If the answer is that silence withdraws, the change is one branch in `PipaModule` and
  the inversion of that test.

---

## 3. Still open from earlier reviews

Carried forward because none of them has moved and all of them are still real.

- **Malaysia's DPO registration obligation.** Determine whether the Malaysian entity crosses the
  threshold and register if so.
- **EU AI Act Art.5(1)(f)** prohibits employee-facing emotion recognition. If any tool in the group's
  stack infers emotional state from a call recording — several call-centre analytics products do,
  sometimes as a feature nobody switched on deliberately — it is prohibited, not merely regulated.
  Audit what is enabled.
- **The CCPA B2B exemption expired in 2023.** Business contacts in California have full consumer
  rights. Any process that still treats a B2B contact as out of scope is wrong and has been for three
  years.
- **The Indian rights-response periods in `StatutoryClock` are the group's own undertaking as a
  figure — but Rule 14(3) sets a ceiling on them.** *Corrected 17 August 2026; earlier revisions of
  this line said the DPDP framework prescribed nothing at all, which was half right and therefore
  misleading.* **Rule 14(3)** requires the fiduciary to prominently publish the period within which
  it responds under its grievance redressal system, and that period must be "within a reasonable
  period not exceeding ninety days". So: the number is the group's, the outer bound is the Rules'.
  The platform's 30 days sit inside the bound and are therefore lawful as well as tight, and
  `StatutoryClockTest.theIndianGrievancePeriodIsWithinTheStatutoryCeiling` fails the build if anyone
  widens them past ninety. **Two things still need legal.** First, the figure must be reconciled
  against the published privacy notice and signed off — a deadline the platform believes in and the
  notice contradicts makes the group's own records the evidence against it, and Rule 14(3) makes
  publishing that figure an obligation rather than a courtesy, so a mismatch is now a breach and not
  merely an embarrassment. Second, see the new Rule 14(1) item in §4.
- **TRAI's third amendment is still a draft.** Released for consultation 13 March 2026; comments
  closed 19 April, counter-comments 4 May 2026; **no gazette notification as of 17 August 2026** —
  the fourth consecutive planning pass at which this has been checked and found un-notified.
  Direction: mandatory AI/ML spam detection by access providers, and **advance declaration of A2P
  traffic by senders** — which lands on Athena's dialer, not only on the carriers. One detail
  sharpened this pass: a proposed **termination charge of up to five paise per minute on A2P
  calls**, which is a cost line for the dialer rather than a compliance control. Industry responses
  (Airtel, MyOperator, IAMAI) are published on TRAI's site. Track it; if notified, it becomes a
  platform item.
- **EU Digital Omnibus — cookie and tracking consent moves into the GDPR.** The ePrivacy Regulation
  was withdrawn on 11 February 2025; the November 2025 Digital Omnibus proposes new **Articles 88a
  and 88b**, with final text expected late 2026 or early 2027. **Art. 88b would oblige controllers
  to honour browser-level consent signals within 24 months of entry into force.** The platform is
  already ahead of this — Global Privacy Control ingestion shipped with the US-state work — but that
  feature changes character if the article is adopted: it stops being an accommodation for
  California and becomes an EU obligation on a clock. Track the article numbers; **do not build
  against them**, because they are a proposal in trilogue and not law.

---

## 4. Before the Consent Manager framework goes live

The platform accepts relays today. Three things need a person before 13 November 2026.

- **The register is seeded with fixtures.** `V14__consent_manager.sql` inserts `CM-TEST-0001`
  (REGISTERED) and `CM-TEST-0002` (DEREGISTERED); `V15` adds `CM-TEST-0003` (REGISTERED, held by a
  second credential) for the credential-binding tests. All three are named so nobody mistakes them
  for Board registrations. **Set them to `DEREGISTERED` in production** and load the real register
  when the Board publishes it. This is now an operation rather than a DBA task — `PUT
  /v1/admin/consent-managers/{registrationId}/status`, audited with the reason.
- **The register is a copy, and copies rot.** The Board publishes no feed, so UDS's copy is only as
  fresh as the last time a person compared it with the published list. `POST
  /v1/admin/consent-managers/{registrationId}/reconciled` records that comparison, and
  `/actuator/health` reports the oldest reconciliation and lists every entry nobody has ever
  checked. The failure this exists to prevent is honouring a relay from a Consent Manager the Board
  suspended a month ago — which looks exactly like normal operation until somebody asks. **Set a
  review cadence and name whose job it is.**
- **Signature verification is designed for and not wired.** `consent_manager.public_key` exists and
  is unused, because the Board has published no signing standard. A Consent Manager currently
  authenticates as an API client over TLS, which is what the rest of the platform does and is an
  interim position. Verifying against a scheme nobody else implements would be worse than not
  verifying — it would look like proof. Watch for the standard; now that the Board is constituted it
  can actually appear.

**A fourth thing needs a person, and it is not about Consent Managers.** Rule 10's second
verification route runs against a **virtual token issued by a Digital Locker service provider**,
mapped to a government-issued credential. UDS has no DigiLocker integration and this platform does
not build one — wiring an identity provider is a procurement decision, not a schema one. What is
built is the evidence model: a capture on a child's behalf now records *which route was taken,
against what reference, when, and by whom*, and is refused outright if it records none of that.
So a surface using route one — a parent already verified on the group's own service — works today
and is evidenced today. A surface needing route two is blocked on the integration, and blocked
visibly rather than by quietly accepting an unevidenced claim, which is what happened before.
**Decide which route each capture surface will use, and commission the integration if any needs
the second.**

**A fifth thing needs a person, and it is a content decision rather than a build.** **Rule 14(1)**
requires a Data Fiduciary to prominently publish on its website or app *both* the means by which a
principal makes a request to exercise their rights *and* "the particulars of such information as may
be required to identify" them. **Rule 14(5)** defines an *Identifier* as any sequence of characters
issued to identify the principal — a customer id, an application reference number, an enrolment id,
an email address, a mobile number, a licence number.

The **means** half is now *served*, not merely modelled. `NoticeStore` has carried `rightsUri`,
`grievanceUri` and `withdrawalUri` per notice version since the notice work, and the consent receipt
reproduces them — but every one of those links pointed at a page that did not exist, because every
route on the platform required a credential. `/v1/portal/**` is that page's backend: a principal
submits a request, proves the identifier is theirs with a single-use code, and can read the status
and the statutory deadline back. The clock starts at verification rather than at submission, so an
anonymous caller cannot burn the group's response window on somebody else's behalf.

**One thing must be built outside this platform before that URL is published.** The platform sends
nothing and never has: it enqueues `rights.verification.requested` carrying the code and the
identifier *hash*, and something has to consume that topic, resolve the hash to a contact and send
the message. Until it does, every submission expires unverified. That is a small integration and it
is a hard prerequisite, not a nicety.

The **required identifiers** half is not modelled, deliberately. `IdentifierType` is already the
vocabulary it would be expressed in, so this is a small change once the answer exists — but the
answer is a policy about *how hard UDS makes it to exercise a right*, and that is a decision for
legal and not one code should make by picking a default. Ask for too little and the group cannot
safely authenticate a request; ask for too much and the identification requirement becomes the
obstacle, which is the failure mode a regulator reads as obstruction. **Decide the list, publish it
under Rule 14(1), and it can then be enforced at intake** — the portal is where it would be
enforced, and it currently accepts any single identifier because no list exists to check against. Note the coupling with §3: Rule 14(3)
also requires the *response period* to be published, so both halves of the same page are waiting on
the same sign-off.

**UDS registering as a Consent Manager stays permanently deferred**, and this is a structural point
rather than a scheduling one. The Rules were re-read on 17 August 2026 and the eligibility
conditions confirmed: a Consent Manager must be **incorporated in India**, hold a **minimum net
worth of ₹2 crore**, act in a **fiduciary capacity toward the principal**, keep the personal data it
routes **unreadable to itself**, and **retain consent records for at least seven years**.
Registration is with the Board, which may **suspend or cancel** it after a hearing.

Two of those are decisive. The First Schedule requires independence from the fiduciaries the
Consent Manager intermediates for, and UDS is a Data Fiduciary for the same principals — no internal
separation cures that. And "unreadable to itself" is flatly incompatible with being the party that
processes the data: UDS cannot simultaneously be unable to read a phone number and be dialling it.
This is not a threshold UDS could grow into.

The suspension and cancellation power is the reason §4's administration endpoints exist. Those are
real events with same-day consequences, not paperwork.

---

## 5. Two things the platform cannot do for you

Stated plainly because a control that looks automatic and is not is worse than an absent one.

- **Nothing detects a breach.** The clock, the affected population and the record of what was done
  are the platform's; forming the awareness is a human's, and the whole two-stage Rule 7 obligation
  hangs off the instant somebody writes down. See `docs/OPERATIONS.md` §9.
- **Nothing enforces a DLT header on somebody else's SMS.** The decision response names the
  registered header and template. Whether the sending system uses them is between UDS and the
  sender.

---

## 6. What the sweep did *not* find

Recorded because a checked absence and an unchecked one look identical in a document, and the
difference is exactly what a reader needs. As of **17 August 2026**:

- **No gazette notification of TRAI's third amendment to TCCCPR 2018.** Checked at six consecutive
  planning passes now. Still a consultation: it opened 13 March 2026, comments closed 12 April and
  counter-comments 27 April. Its substance is an AI/ML unsolicited-commercial-communication
  detection mandate on **access providers**, not on fiduciaries, so gazetting it would not by itself
  create an obligation here — but the comment window closed four months ago and a gazette before
  13 May 2027 is now plausible rather than remote. Moved to the watch list in §9.
- **Rule 6's one-year log-retention floor is already modelled — checked, not missing.** Recorded
  here so the next pass does not re-derive it. Rule 6 (reasonable security safeguards) requires
  logs and personal data to be retained for at least one year unless another law requires longer.
  `V8__enforcement_evidence.sql` and `V11__consent_receipt.sql` both cite it correctly, and receipt
  retention comfortably exceeds it. No action.
- **Korea's Network Act Enforcement Decree Art. 62-3 still does not say what silence means.**
  Third consecutive confirmed absence, searched again against the Enforcement Decree and against
  KISA and PIPC material. The Article prescribes the two-year interval and the three disclosures
  and stops. The platform therefore raises the re-confirmation and records the outcome without
  inferring consent, or its withdrawal, from no reply — see §2. Position unchanged and correct.
- **No Board-published signing standard for Consent Manager relays.** `consent_manager.public_key`
  stays accepted, stored and unused. Now that the Board is constituted this can appear at short
  notice; it is the single item most likely to move before 13 November.
- **No published Board register of Consent Managers**, and no API for one. This is why the platform's
  register is a manually reconciled copy rather than a synchronised one, and why the health endpoint
  reports staleness instead of connectivity.
- **No prescribed rights-response *figure* under DPDP — but there is a ceiling.** Rule 14(3)
  requires the fiduciary to prominently publish the period within which it responds under its
  grievance redressal system, "within a reasonable period not exceeding ninety days". The number is
  the group's to choose; the outer bound is not. The 30 days in `StatutoryClock` sit inside it and
  are therefore lawful as well as tight — see §3, and §7 for the correction to what this document
  previously said.
- **No categories notified under DPDP Rule 13(4).** The Central Government may, on the
  recommendation of the committee constituted under Rule 13(5), specify categories of personal data
  a Significant Data Fiduciary must not transfer outside India at all. **None are notified as at 17
  August 2026.** The hook is built anyway — `data_category.transfer_restricted`, false on every row,
  consulted by the RoPA cross-border report — so honouring a notification is an update against one
  column rather than a release. `SdfObligationIT
  .theRestrictedCategoryListIsEmptyBecauseItWasChecked` asserts the empty state, so a future seed
  adding one has to come past a test rather than slip in. (Cited as "Rule 14" here and in seven
  other files until 17 August 2026 — see §7.)
- **Rule 13(4)'s traffic-data limb is out of scope.** The rule reaches "the personal data and the
  traffic data pertaining to its flow". The platform models the personal data half by category and
  holds no traffic data at all — it is a consent evidence plane, not a message log. Recorded as a
  boundary rather than left looking covered; the CPaaS and dialer log owners carry it.
- **No UDS entity notified a Significant Data Fiduciary.** The Rule 13 register is built and empty
  for every entity, and empty because the flag says so rather than because nothing was looked at.
- **On the Korean re-confirmation period: found, not missing.** Previous revisions listed this here.
  It has moved to §2 as a resolved item. What remains open is only what silence means.
- **Nothing in the reference implementations worth adopting.** `ConsentStack/cmp`,
  `tsi-coop/tsi-dpdp-cms` and `sovio-id/consent-management-demo` were reviewed against this platform.
  All three are consent *collectors*: none carries an evidence plane, a hash chain or an enforcement
  log. The **W3C DPV rendering of ISO/IEC TS 27560** (updated February 2026) was the one useful
  reference and was used as the conformance checklist for the receipt work — as a checklist, not as
  a dependency, since the build is offline and a vocabulary is not a library.

---

## 7. Corrections — citations this document got wrong

*Added 17 August 2026. This section exists rather than a silent edit.*

Three planning passes relied on the Rule numbers below, and they appear in this hand-off, in the
README, in `OPERATIONS.md`, in a migration and in four Java files. Somebody may have quoted them
onward — into a client questionnaire, a DPIA, an email to counsel — and a document that quietly
changes a citation teaches its readers nothing and leaves the wrong number in circulation. The whole
discipline of this hand-off is that a checked fact and an assumed one look different on the page, so
a fact that turned out to be wrong is recorded as having been wrong.

### 7.1 The Significant Data Fiduciary localisation power is Rule 13(4), not Rule 14

**What was written:** that "DPDP Rule 14" empowers the Central Government to specify categories of
personal data a Significant Data Fiduciary may not transfer outside India.

**What the Rules say:**

| Rule | Subject |
|---|---|
| **13** | Obligations of a Significant Data Fiduciary — annual DPIA, annual independent audit, algorithmic due diligence, observations to the Board |
| **13(4)** | The Government may specify categories of personal data — **and the traffic data pertaining to its flow** — that shall not be transferred outside India |
| **13(5)** | The committee on whose recommendation 13(4) is exercised |
| **14** | **Rights of Data Principals** — publication of the means of exercising them, the identification particulars required, grievance redressal, nomination |
| **15** | **Transfer of personal data outside India** — the general restriction, binding **every** Data Fiduciary, not only Significant ones |

**Scale:** thirteen occurrences across eight files.

**What changed, and what did not.** Only citations. No column, constraint, index, policy or line of
logic was altered, because the hook was built to the right *substance* — a named-category
prohibition, empty until notified, already consulted by the RoPA cross-border report. That is the
good version of this mistake and it was still worth fixing at the first opportunity: a wrong rule
number in a compliance platform is the thing an auditor checks first and the thing that makes them
doubt everything after it.

**Why the correction is a migration.** `V20__significant_data_fiduciary.sql` is applied in
environments, and Flyway checksums applied migrations — editing its header would stop those
environments starting. So `V21__correct_rule_citations.sql` reissues the affected `comment on`
statements and records the correction, and V20's header stays as delivered, wrong. An applied
migration is a historical record of what was believed at the time, and rewriting history in a
compliance platform is the habit the append-only ledger exists to refuse.

**One consequence beyond the numbering.** Rule 13(4) reaches traffic data as well as personal data.
This platform holds neither message logs nor call detail records — it is a consent evidence plane,
and the DLT registry knows header and template registrations rather than deliveries. That limb is
therefore **out of scope**, recorded as a boundary in §6 and in the column comment rather than left
looking covered. It belongs to whoever operates the CPaaS and dialer logs.

### 7.2 India does prescribe a boundary on the rights-response period

**What was written:** that the DPDP framework leaves the response period to be prescribed and the
Indian figures in `StatutoryClock` are therefore purely the group's undertaking, with no legal
boundary.

**What Rule 14(3) says:** the Data Fiduciary must prominently publish the period within which it
responds under its grievance redressal system, and that period must be "**within a reasonable period
not exceeding ninety days**".

So the earlier statement was half right, which made it more misleading than an outright error: a
reader came away believing there was no bound at all. The **figure** is the group's to choose; the
**ceiling** is not.

**No behaviour changed and none should.** The platform's 30 days sit well inside ninety and are
therefore lawful as well as tight. Widening toward the ceiling would be a decision to answer people
more slowly, which is not a decision code makes on its own.
`StatutoryClockTest.theIndianGrievancePeriodIsWithinTheStatutoryCeiling` now fails the build if the
constant is raised past the bound, and the deadline's recorded *basis* names Rule 14(3) — a stated
working that omits the legal boundary teaches the next reader there isn't one, which is how this
error propagated in the first place.

**What it adds to §4.** Rule 14(3) makes publishing the period an obligation. A mismatch between the
published notice and this platform is now a breach rather than an embarrassment, and it lands on the
same sign-off as Rule 14(1)'s identifier list.

---

## 8. Decisions the platform is deliberately not making

Two questions that look like unfinished code and are not. Each is a decision for UDS and Denave
about how the business actually works; guessing either in the platform would be worse than leaving
it visible, because a guess buried in a store method is a policy nobody signed off.

Both are recorded here rather than closed, and both have a real cost being paid today.

### 8.1 Identity resolution — mechanism built, keying policy still yours

**Half of this is closed. The half that was ours to close.**

The item said the platform maps one identifier to one subject, so a person the group knows by a
mobile number and by an email address is **two subjects** — two consent records, two hash chains,
two evidence bundles — and that a principal who withdraws by email leaves their phone contactable.
That was true and it is no longer, provided somebody asserts the link. Two mechanisms now exist:

1. **`alsoKnownAs` on the capture request.** A surface that collected both a mobile and an email
   from the person in front of it declares them together, and the platform records one subject. This
   prevents the split at source and is the cheap half.
2. **`POST /v1/admin/subjects/merge`.** Repairs the records already split. Identifiers are
   re-pointed, so the very next decision for either of them lands on the surviving subject and the
   withdrawal reaches the whole person. Events are *not* moved and cannot be — the ledger is
   append-only and rewriting a subject id would break the chain that makes it evidence — so the old
   id becomes an alias and every read that assembles a person unions across it. The evidence bundle
   names what was folded in, so a reader can see why it contains events under ids they have not
   seen before.

**Not reversible, on purpose.** `subject_alias` has `UPDATE` and `DELETE` revoked from the
application role, like the ledger. A merge that turns out to be wrong has joined two people's
records; the only useful thing afterwards is a permanent record of who said they were the same
person and on what basis, which is why `reason` is required and free text.

**Nothing infers.** There is no fuzzy matching on names, no cross-entity phone normalisation, no
similarity measure. Every join happens because a capture surface or a named administrator asserted
it. Inference would merge two people eventually and the first evidence of it would be a call to
somebody who had withdrawn — strictly worse than the incompleteness it would be fixing. Merging
across entities is refused outright: it would move one fiduciary's data principal into another's
evidence plane, which is what two layers of isolation exist to prevent.

**What is still UDS's to answer, and it is the part that decides how much of this gets used.**
When DenCRM, the HRMS and a purchased list all describe the same contact, *what makes them the same
contact?* A phone number? An email? A CRM id already treated as authoritative? The platform can now
act on any answer — capture surfaces pass `alsoKnownAs`, or an operations process merges from a
reconciliation report. Without an answer, the mechanisms sit unused and the incompleteness stays,
in which case it has to be stated in the group's privacy notice rather than discovered during a
grievance.

Do **not** answer it by turning on inference. The platform deliberately provides no way to.

### 8.2 Policy inheritance — resolved, and it was the wrong question

**Closed by `V22`, and the resolution is worth reading because the premise was false.**

The item said `V1` promises purpose inheritance down `parent_entity_id`, that
`EntityStore.inheritanceChain` exists to walk it, and that nothing calls it — so a subsidiary with
nothing configured is governed by nothing. Two of those three were true. The third was not, and it
changes the answer.

**There is no per-entity purpose configuration to inherit.** `purpose` and `purpose_version` carry
no `entity_id` at all: the taxonomy is group-wide by design, and correctly so — fifteen entities
each maintaining their own `MARKETING_CALL` is how two of them end up with different retention on
the same activity. Every entity already sees every purpose. A subsidiary with nothing configured is
therefore **not** governed by nothing; it is governed by the group taxonomy, which is what was
wanted. `V1`'s comment described a schema this platform deliberately does not have, and building
what it described would have meant entity-scoping the purpose registry to satisfy a comment.

**What the parent chain is genuinely for, and the live defect it was hiding.** The per-entity
configuration that does exist and does need inheriting is the entity's own published contact
points. `V3` seeds fifteen entities and sets **neither `dpo_contact` nor `grievance_uri` on any of
them**, while `ReceiptService` puts `dpoContact()` straight onto the ISO/IEC TS 27560 receipt and
falls back to `grievanceUri()` when the notice carries none. **Every receipt this platform has ever
issued names a null DPO contact**, and — for any capture whose notice carried no grievance route —
a null grievance route. **s.6(3) requires the DPO or authorised-person contact on the consent
request itself, and Rule 9 requires it published standingly on the site or app and repeated in every
rights response; Rule 3 carries the notice's Board-complaint link.** That attribution said "Rule 3
requires both" until Phase 17, which is wrong on the DPO half — recorded here because the
correction changes who the obligation binds and when, not merely which number is printed. The
defect itself was silent because a null serialises out of the JSON without complaint.

`EntityStore.resolveContacts` now walks the chain per field, so a subsidiary that publishes its own
grievance route and shares the group DPO gets both right. `V1`'s comment is corrected in `V22`.

**What UDS still has to do — one line, and it is the only thing standing between the platform and
a compliant receipt:**

```sql
update fiduciary_entity set dpo_contact = ?, grievance_uri = ? where entity_id = 'UDS';
```

Setting it on the group root answers for all fifteen. `V22` deliberately seeds **no** value:
inventing a DPO address to make a column non-null would put a fabricated contact point on a
statutory artefact, and a receipt naming an inbox nobody reads is worse than one naming none,
because it looks discharged. `EntityContactCheck` logs at WARN on every start-up naming the
entities that still resolve to nothing, so the gap is visible until it is closed.

### 8.3 Two smaller items in the same family

- **`breach_notification` isolation — closed by `V22`.** The item said the table is scoped through
  `breach_id`, carries no `entity_id`, and is therefore constrained by neither layer: the guard
  cannot help because the path carries an opaque row id, and the row-level policy had no column to
  bind. It also argued that adding a column purely for a control's benefit would be denormalising
  the schema to satisfy the guard. On re-reading, that argument does not survive contact with what
  the rows contain: a notification names the party told, the deadline, the method, the reference and
  the recipient count — the shape and scale of another group company's worst week, and whether they
  met the Rule 7 clock. That is not a marginal disclosure to trade against schema purity.
  `V22` denormalises `entity_id` from the parent breach, and `BreachStore.addObligation` **selects**
  it from the breach rather than accepting it as an argument, so a notification filed under the
  wrong fiduciary is unrepresentable rather than merely unlikely. `RowLevelSecurityIT` asserts both
  directions.
- **The evidence plane's tamper detector was firing on every event, and had been since it was
  written.** Not a hand-off item — it is fixed — but recorded here because it changes what an
  earlier `PAYLOAD_DIVERGENCE` finding means. `Instant.now()` carries nanoseconds; PostgreSQL
  stores microseconds; the hashed payload truncated and the column rounded, so about half of all
  events disagreed with their own payload by one microsecond. The **chain was never affected** and
  no consent record was ever wrong. What was affected is the check that would have caught somebody
  editing the structured columns, which was drowned in false positives. Any divergence finding
  against an event written before this fix should be read as noise; one written after it should
  not. Found by walking the platform by hand — see `WALKTHROUGH.md` §14.1.
- **Snapshot key rotation — closed.** The item said `OPERATIONS.md` §2.2 tells an operator to
  publish the retired key alongside the new one while `SigningKeys` holds one pair and `/v1/keys`
  returns one entry, so the runbook described something the platform could not do. `V25` adds a
  `signing_key` registry holding the **public half** of every key with its lifecycle; instances
  register their own key at start-up; `/v1/keys` publishes everything still trusted; and
  `POST /v1/admin/signing-keys/{id}/retire` moves a key to `RETIRED` (stops signing, still
  verifies) or `COMPROMISED` (stops verifying at all), audited with a reason. The rotation
  procedure in §2.2 is rewritten against the mechanism. **What is still open is the private half**:
  it lives in the process environment, not a KMS or an HSM. That is a custody question for UDS —
  which service holds it, who can read it, and what the recovery path is if the process environment
  is lost — and moving it touches `SigningKeys` and nothing in the schema.

### 8.5 Rights fulfilment — the scope statement UDS has to sign

**This is the largest compliance exposure in the system and it cannot be closed by code.**

`RightsService` accepted a request, ran the statutory clock, and let an operator close it as
`FULFILLED` with a sentence of resolution text. Nothing in this platform erases, exports or corrects
anything in DenCRM, the HRMS or the BGV workflow. So a closure by somebody who had done the work and
a closure by somebody who had not were indistinguishable on the record — permanently, because the
audit trail is append-only.

**What the platform now does.** `V26` adds two things and a gate:

- **`fulfilment_target`** — the configured set of systems that must act, per entity and per request
  type. Erasure reaches every system holding the person's data; an access request reaches whichever
  can produce an export, so the sets genuinely differ.
- **`rights_fulfilment_action`** — append-only, one row per system per request, recording what was
  done, by whom, and an **`evidence_ref`** a reviewer can follow into a system other than this one.
  A ticket id, an export hash, a deletion job reference. Required.
- **The gate.** `FULFILLED` is refused with 409 while any mandatory target has no `COMPLETED`
  action. A recorded `FAILED` attempt does not satisfy it — that is the point of recording failures.

This converts *"an operator asserted"* into *"an operator asserted, against a named system, with a
reference"*, and — the part that matters most — makes the systems that were **left out**
enumerable instead of invisible.

**What it explicitly does not do.** It performs no act of erasure, export or correction. There are
no connectors and there are deliberately no stubs: writing something plausible against a system
nobody on this side can call would be worse than nothing, because it would look like fulfilment.

**An empty register blocks nothing, and this is the state today.** No entity has a target
configured, so a request can still be closed on an operator's word alone. That is deliberate — a
platform refusing every closure until somebody filled in a table would get the table filled with
placeholder rows on the first busy afternoon — and it is exactly why the following needs a
signature rather than a commit.

---

**Scope statement — for UDS to complete and sign.**

> The UDS Consent & Privacy Control Plane provides **intake, the statutory clock, the evidence
> gate, and the record** for data-principal rights requests under DPDP ss.11–13 and the equivalent
> provisions in each jurisdiction the group operates in.
>
> The **acts** of erasure, export and correction are performed **outside** the platform, by the
> systems listed below, under the standard operating procedure named below, by the role named
> below.
>
> | Request type | System | Who acts | SOP reference |
> |---|---|---|---|
> | ERASURE | DenCRM | ________ | ________ |
> | ERASURE | HRMS | ________ | ________ |
> | ERASURE | BGV workflow | ________ | ________ |
> | ACCESS | ________ | ________ | ________ |
> | CORRECTION | ________ | ________ | ________ |
>
> Each system above is registered as a `fulfilment_target` so that a request cannot be closed as
> fulfilled until that system has recorded what it did, with a reference.
>
> Accountable owner: ________________  Date: __________

**Until this is signed and the register populated, the honest description of the platform is
"intake, clock and evidence" — not "rights fulfilment".** Describing it otherwise in a privacy
notice, a client contract or a Board response is the exposure; describing it accurately is not.

The next real step beyond this is connectors, and those are a project with the owning teams of
DenCRM and the HRMS rather than a task on this platform.

---

### 8.6 What `OPERATOR_ASSERTED` has to mean, before a console offers the field

**Owner: UDS compliance. Since Phase 18 it blocks the closure of a disclosing right, and the
absence of a published standard is now the risk rather than the absence of a gate.**

`POST /v1/rights` accepts a `verifiedAs` note. Supplying one records the request as
`OPERATOR_ASSERTED`; leaving it blank records `UNVERIFIED`. **Neither refuses anything at intake**,
deliberately: parking requests outside the statutory clock until somebody fills in a field produces
exactly the outcome Rule 14(3) penalises, and GDPR Art. 12(2) forbids refusing to act on a request
except where the principal cannot be identified.

**Phase 18 changed the other end, and it makes this section more urgent rather than less.** A
disclosing or destructive right — access, portability, erasure, correction, completion, nomination —
can no longer be recorded as `FULFILLED` while the row reads `UNVERIFIED`. Withdrawals, opt-outs and
grievances are deliberately not gated. Verification can now be recorded after intake, at
`POST /v1/rights/{requestId}/verification`, which is what makes the gate a prompt rather than an
obstruction.

⚠️ **So an operator will now be asked for this field at the moment they most want the request
closed, and there is still no published standard for what it has to mean.** That is the failure
shape this programme has already had once, on a different field: the backdate bound refused a value
and the workaround was to file with today's date, which destroyed the provenance the bound existed
to protect. A gate with no standard behind the field it demands is a gate satisfied by typing
*"verified"*. **This decision is now blocking in practice, not merely in principle.**

Two things the platform does and does not do, stated so the boundary is not assumed:

- **It does** now require the human behind the credential. An `OPERATOR_ASSERTED` filing without
  `X-UDS-Actor` is refused, and `admin_audit_event.actor_id` carries the person beside the client.
  Until Phase 16's closure it recorded the credential alone — one password held by a team — while
  three artefacts, including `V30`'s own column comment, claimed it named somebody.
- **It cannot** check the claim. `verification_detail` is free text and the platform has no way to
  know whether a call-back happened.

**So the standard for what counts as an adequate check does not exist, and the platform cannot
supply it.** One paragraph is enough — a call-back to a number already on file, an employee ID
checked at a desk, a document reference — published to whoever will be typing in that field. Without
it, the first year's data is whatever the first console's placeholder text suggests, and the label
becomes decoration on a field nobody applied a rule to.

Also UDS's, and it belongs with the above rather than on a dashboard backlog: **watch the share of
open requests reading `UNVERIFIED`.** The platform can answer the question in one query; nothing
currently asks it. A control whose output nobody reads is not a control.

*`ROADMAP.md` carries the same item as a one-line acceptance criterion. This is the argument behind
it — recorded here because CLAUDE.md designates §8 as the list of decisions UDS owns, and two lists
of the same thing that disagree is how one of them stops being read.*

---

### 8.7 Propagation — who must be told, and the honest limits of the evidence

**The question the platform could not answer.** *A principal withdrew. Prove it reached every
consuming system.* Until `V31`, it could not — not because the mechanism was missing, but because
nothing named the systems that were supposed to receive it. `webhook_delivery` proves arrival **at
subscribers that exist**, and a delivery row is structurally impossible for a system nobody
registered. So a downstream system that was never subscribed received nothing and left **no trace of
not having received it**, and `event_outbox.published_at` meant only *"the publisher did not throw"*.

**What the platform now does.** `propagation_target` is the register of systems that must be told,
per entity and per topic — the structural sibling of §8.5's `fulfilment_target`, and deliberately a
second register rather than an extension of the first, because they are two different obligations.
A mandatory target with no active subscription is reported as **uncovered**, on an admin route, on
`/actuator/health`, and on `uds.consent.propagation.uncovered`, which a critical alert watches.
Separately, `propagation_gap` records, append-only and once per system per day, the obligations the
platform could not show were met.

**Three things this register does not claim, each of which UDS needs to know before relying on it.**

1. **Propagation is evidenced on the webhook channel only.** `webhook_delivery` is written by the
   webhook publisher and by nothing else. Under the `log` publisher — which is the default and what
   the pilot runs — and under `kafka`, the platform has **no way to observe** whether a downstream
   system received anything. It records `NO_DELIVERY_CHANNEL` in that case and does **not** record
   "not delivered", because a Kafka consumer may be processing everything perfectly and asserting
   otherwise would be a false statement in an append-only table. *Switching the publisher to
   `webhook` is what turns this register from a configuration check into evidence.*
2. **`rights.verification.requested` cannot be covered at all.** `PrincipalPortalService` enqueues it
   keyed on the request reference alone, with no entity and no subject in the key, so it can never
   route to a subscription and can never be reconciled. This is separate from the missing consumer
   already on `ROADMAP.md`: even once that consumer exists, this topic is **structurally** outside
   the register. Recorded here because it was unrecorded anywhere until Phase 17.
3. **A gap row names one principal per system per day, not every affected one.** The record is
   deduplicated on `(entity, topic, system, day)` so its growth stays bounded by the register rather
   than by population. It answers *"was this obligation unmet on that day"* — a register-level fact.
   It does **not** answer *"was this person's withdrawal propagated"*, and nothing built on it should
   be read as if it did.
4. **Two streams can be registered, and one cannot.** `uds.consent.events` and
   `uds.consent.retention` both carry an entity in their key and are registrable.
   `rights.verification.requested` is keyed on the request reference alone, carries no entity, and can
   never route to a subscription — separate from the missing consumer already on `ROADMAP.md`, and
   unrecorded anywhere until Phase 17.
5. **The erasure *act* stays with §8.5.** DPDP s.8(7)(b) — *"cause its Data Processor to erase"* — is
   discharged by `fulfilment_target` and its evidence, not here. This register is about
   **notification of a consent-state change**: s.6(6)'s *"cease and cause its Data Processors to
   cease processing"*, and GDPR Art. 19's duty to communicate to each recipient. Two registers for
   one obligation is how both stop being filled.

**On DPDP specifically, and this is a position rather than a gap.** DPDP has **no Art. 19 analogue**.
Neither the Act nor the Rules 2025 require a fiduciary to *demonstrate* that cessation reached a
processor, to notify third-party recipients generally, or to tell the principal who they were. The
evidence this register produces therefore **exceeds what DPDP asks**. It is built because GDPR Art. 19
and Art. 5(2) do ask, and because "we told them" without a record is the claim the group would
otherwise have to make. Do not let it be described as a DPDP requirement.

**An empty register reports nothing, and this is the state today.** No entity has a propagation
target configured, so the gauge reads zero and nothing is recorded — which is indistinguishable, on
the instruments, from complete coverage. The same deliberate no-op as §8.5, and the same reason this
needs a signature rather than a commit.

---

**Scope statement — for UDS to complete and sign.**

| Topic | System | Mandatory? | Who owns the integration | Endpoint, or why not | Signed |
|---|---|---|---|---|---|
| `uds.consent.events` | `DENCRM` | | | | |
| `uds.consent.events` | `ATHENA_DIALER` | | | | |
| `uds.consent.retention` | | | | | |

Two operational notes for whoever fills this in:

- **`system_code` must match on both sides, exactly and in upper case.** The register joins
  `propagation_target.system_code` to `webhook_subscription.system_code`; a target for `DENCRM`
  against a subscription labelled `DENCRM_PROD` produces a permanent phantom gap. `GET
  /v1/admin/propagation/targets` returns the subscription resolved against each target, so a
  mismatch is visible on that page rather than only in the gap table.
- **A persistently failing endpoint produces no gap rows.** A message that fails to deliver stays
  unpublished, so the reconciler never runs for it; it surfaces instead as `FAILED` rows in
  `webhook_delivery` and as the relay's alert after ten attempts. The two artefacts answer different
  questions, and neither one alone answers *"is DenCRM current?"*

---

### 8.4 Two regulatory surfaces are now dark by default, and this is the record of why

Both were built ahead of the obligation that triggers them, both are correct, and both were
**running by default** — a scheduled sweep and a write-capable relay for obligations nobody in the
group currently owes. That is not free. It is review budget on every release, test time on every
build, and worst of all an operator looking at a queue for an obligation that does not apply, which
is how a real one stops standing out.

Off is a decision with an owner and a re-enabling condition, not neglect. The code stays and the
migrations stay: dropping `consent_reconfirmation` or the Consent Manager register would make
re-enabling a schema change rather than a property change, and a table costs nothing to keep.

| Feature | Property | Off because | **Turn on when** |
|---|---|---|---|
| Korea Art. 62-3 two-year re-confirmation | `uds.consent.features.korea-reconfirmation` | Art. 62-3 fixes the interval and the disclosures and is silent on what follows from a recipient who never answers. That silence has now been recorded as absent from primary text **three consecutive reviews**. Denave Korea is one entity of fifteen and is not the pilot | Korean counsel confirms the consequence of silence, **or** Denave Korea begins consent-based marketing at volume. Nothing is lost by waiting: `ReconfirmationStore.findDue` derives the queue from consent dates already in the ledger, so switching it on later raises every obligation that accrued while it was off |
| DPDP Rule 4 Consent Manager relay | `uds.consent.features.consent-manager-relay` | Three routes and a role for an intermediary ecosystem that does not exist yet. Registration with the Board is permanently deferred (§4), and `consent_manager.public_key` is populated but **unverified** because the Board has published no signing standard. An open relay accepting consent on a principal's behalf, authenticated by HTTP Basic and verifying no signature, is the widest write surface the platform has | UDS registers with the Board **and** a signing standard exists to verify against. Both, not either — registration without verification is the surface without the control. Rule 4 is operational from 13 November 2026, which is a date for *accepting* relays, not a date this must be on |

**What "dark" means precisely, because the two are gated differently.** The relay is a whole
controller and is conditional on the bean: with the flag off its routes are not mapped, and a caller
gets `404`. The Korean queue is four routes inside a sixty-route admin controller, so it is gated at
the handler and refuses with an RFC 7807 `ProblemDetail` naming the property. Both answer 404 rather
than 403 on purpose — 403 would tell an integrator the surface exists and to go and ask for a
permission nobody can grant, and an empty `200` from the queue would read to an operator as *"Korea
owes nothing today"* when it means *"nothing has been looking."*

The **Consent Manager register** stays administrable with the relay dark
(`/v1/admin/consent-managers/**`). An entity may legitimately want registrations recorded before the
relay opens, and those routes are ADMIN-only and write nothing to the ledger. That asymmetry is
deliberate and is asserted in `FeatureFlagIT`, so nobody later "fixes" it into one switch.

`application-integrationtest.yml` turns both **on**, because the suites proving they work must keep
running. `FeatureFlagIT` boots a second context at the production defaults and asserts the surface
is genuinely gone — which is the other half of the same control, and without it "off by default"
would be an assertion about a YAML file nobody executes.

---

## 9. The watch list — who is looking, and at what

This replaces the per-plan research sweep. Six consecutive planning passes have now opened with a
research section reporting "unchanged", which is a good result and a poor use of a plan: the
regulatory model is mature, and what is left is **waiting for three specific things**, not
re-reading the same sources.

Each item below names what would trigger work. Anything not on this list is not being watched, and
that is the point of writing it down.

| # | What | Why it matters here | What triggers work | Suggested cadence |
|---|---|---|---|---|
| W1 | **TRAI's third amendment to TCCCPR 2018 is gazetted** | Its substance binds access providers rather than fiduciaries, so the likely outcome is *no code change* — but that has to be read rather than assumed, and TRAI's expiry semantics are already first-class here | Publication in the Gazette of India | Monthly. Most likely of the three to move; the comment window closed 27 April 2026 |
| W2 | **Categories notified under DPDP Rule 13(4)** | A notified category may not be transferred outside India by a Significant Data Fiduciary. The hook is built and empty (`data_category.transfer_restricted`), so honouring a notification is a seed update rather than a release — *if* somebody notices it | Any notification on the recommendation of the Rule 13(5) committee | Quarterly, and immediately on any UDS entity being designated an SDF |
| W3 | **The Board publishes a signing standard for Consent Manager relays** | `consent_manager.public_key` is accepted, stored and unused. Relay signature verification is written and unwired, blocked on this and nothing else. Rule 4's framework is operational from 13 November 2026 | Any Board publication naming an algorithm or key format | Monthly until 13 November 2026, then on any Board publication |

**Owner: the group DPO's office.** These are not engineering questions — each is "has a thing been
published", and the engineering response to each is small and already scoped. What engineering
cannot do is notice.

**A note on what is *not* on this list, deliberately.** Korea's Art. 62-3 silence rule (§6): three
consecutive confirmed absences from primary text is not a pending publication, it is a rule that
does not exist, and the platform's position — raise the re-confirmation, record the outcome, infer
nothing from no reply — is correct whether or not it ever appears. Re-checking it a fourth time
would be a way of feeling thorough.
