# Walkthrough — one person, end to end

*Run by hand on 17 August 2026 against the `local` profile, in the container, on a clean database.
Every command below was executed and every response below is the response that came back — trimmed
of nothing except line wrapping. Where a response is long it is shown in full anyway, because the
point of this document is what the platform actually returns rather than what it is supposed to.*

This is the fastest way to understand the platform without reading Java, and the document to hand
an auditor who wants to see what it does. It follows one data principal from being served a notice
to having their whole file produced as evidence.

**It also found two defects that no test in the build could have found.** Both are fixed, and both
are described in §14 — that section is the most useful part of this document for an engineer,
because it is a worked example of what a hand-run catches and a test suite structurally cannot.

---

## 1. Bringing it up the way an operator would

Not the way the test harness does. A real pepper and a real Ed25519 key pair, supplied as
environment variables rather than inherited from `application.yml`'s development defaults.

```bash
openssl genpkey -algorithm ed25519 -out ed.pem
openssl pkey -in ed.pem -outform DER | base64        # the signing key
openssl pkey -in ed.pem -pubout -outform DER | base64 # the verification key
```

```bash
export IDENTIFIER_PEPPER='<32+ characters from the KMS>'
export SNAPSHOT_KEY_ID='walkthrough-2026-08'
export SNAPSHOT_SIGNING_KEY='<PKCS#8 private key, base64>'
export SNAPSHOT_VERIFICATION_KEY='<X.509 public key, base64>'
cd platform/docker && docker compose --profile app up -d --build
```

> **The compose file did not pass these four through** before this walkthrough. It set the database
> variables and nothing else, so bringing up the "whole stack" always ran on the development pepper
> and an ephemeral signing key, and said so in two warnings that are easy to read past. Fixed in
> `docker/docker-compose.yml`; an unset variable still falls back to the `local` defaults, which is
> right for a laptop.

Two lines in the startup log are worth reading every time. Neither is an error and both are things
an operator needs to know:

```
WARN  c.u.c.s.config.SecurityConfiguration : configured 3 API client(s): [denave-web, athena-dialer, compliance-console]
WARN  c.u.c.s.config.SecurityConfiguration : group-level API client(s) with access to every entity: [compliance-console]
```

The second is the operator-facing half of the entity isolation story — see `OPERATIONS.md` §2.4. A
client with no `entity-id` reaches every fiduciary in the group. That is a grant, not a bug, and it
is the line to read after any credential change.

**Credentials used below** (`local` profile only): `denave-web:dev` has `CAPTURE`,
`athena-dialer:dev` has `DECISION`, `compliance-console:dev` has `ADMIN`.

---

## 2. Serve the notice

Unauthenticated on purpose. A privacy notice that requires a credential to read is not a notice.

```bash
curl -s 'http://localhost:8080/v1/notices/NOTICE_DENAVE_B2B?jurisdiction=IN&lang=en'
```

```json
{
  "noticeId": "NOTICE_DENAVE_B2B",
  "version": 1,
  "jurisdiction": "IN",
  "languageTag": "en",
  "title": "How Denave uses your business contact details",
  "body": "Denave India Private Limited processes your name, job title, employer and business contact details to contact you about products and services relevant to your professional role, on behalf of itself and its clients. You can withdraw your consent at any time, as easily as you gave it, using the link below.",
  "withdrawalUri": "https://privacy.uds.co.in/withdraw",
  "rightsUri": "https://privacy.uds.co.in/rights",
  "grievanceUri": "https://privacy.uds.co.in/grievance",
  "publishedAt": "2026-08-17T03:12:23.908766Z",
  "materialChange": false
}
```

The three URIs are DPDP Rule 3's requirement that each be a specific link rather than a general
contact page. They travel onto the receipt in §8. Ask for a language the notice does not exist in
and you get a 404 naming the languages that do exist — deliberately, rather than a quiet fallback
to English, because a subject shown a notice they cannot read has not been informed.

## 3. Capture the consent

```bash
curl -s -u denave-web:dev -X POST http://localhost:8080/v1/consent \
  -H 'Content-Type: application/json' -d '{
  "entityId": "DENAVE_IN",
  "subjectId": "walkthrough-subject-001",
  "jurisdiction": "IN",
  "languageTag": "en",
  "channel": "WEB",
  "applicationId": "DENAVE_WEB",
  "captureMethod": "CHECKBOX_OPT_IN",
  "actorType": "SUBJECT",
  "actorId": "walkthrough-subject-001",
  "noticeId": "NOTICE_DENAVE_B2B",
  "noticeVersion": 1,
  "choices": [{"purposeCode": "MKT_OUTBOUND_CALL", "granted": true,
               "preTicked": false, "separateAction": true}],
  "rejectAllOffered": true,
  "idempotencyKey": "walkthrough-capture-1"
}'
```

`201 Created`:

```json
{
  "accepted": true,
  "subjectId": "walkthrough-subject-001",
  "events": [{
    "eventId": "5ad4f90b-ec9f-49f6-9608-52a90ce9ef5b",
    "purposeCode": "MKT_OUTBOUND_CALL",
    "purposeVersion": 1,
    "eventType": "GRANTED",
    "status": "GRANTED",
    "occurredAt": "2026-08-17T03:12:27.568718Z",
    "sequenceNumber": 1,
    "eventHash": "8b4492c8d6ed9583b715155b6cfee4d46d73b36cbd25d26864454c85dd7d1f6d"
  }],
  "violations": []
}
```

Three things to notice. **One event per purpose**, so a subject who accepted two purposes and
declined a third has three separate records rather than one "agreed to terms". **`sequenceNumber`
and `eventHash`** — this is event 1 in this subject's chain, and the hash is what event 2 will
point back to. And **`violations: []`**: a rejected capture comes back `accepted: false` with the
reasons named (`PRE_TICKED_CONSENT`, `BUNDLED_CONSENT`, `UNREGISTERED_APPLICATION`,
`GUARDIAN_VERIFICATION_NOT_EVIDENCED`, …) rather than as an opaque 400.

Set `"preTicked": true` and the capture is refused. That is the whole product in one field.

## 4. Ask the enforcement gate — allowed

This is the call Athena's dialer makes before every outbound contact.

```bash
curl -s -u athena-dialer:dev -X POST http://localhost:8080/v1/evaluate \
  -H 'Content-Type: application/json' -d '{
  "entityId": "DENAVE_IN", "subjectId": "walkthrough-subject-001",
  "purposeCode": "MKT_OUTBOUND_CALL", "channel": "VOICE_CALL",
  "jurisdiction": "IN", "applicationId": "ATHENA_DIALER"
}'
```

```json
{
  "outcome": "ALLOW",
  "reason": "NONE",
  "explanation": "permitted",
  "legalBasis": "CONSENT",
  "purposeCode": "MKT_OUTBOUND_CALL",
  "purposeVersion": 1,
  "policyVersion": "policy-2026.08.1",
  "evaluatedAt": "2026-08-17T03:12:27.729535334Z",
  "obligations": ["provide-withdrawal-link", "provide-grievance-link",
                  "scrub-against-ncpr-before-send"]
}
```

**`obligations` is the part people miss.** An `ALLOW` is not "go ahead": it is "go ahead, and these
three things are still your responsibility". `scrub-against-ncpr-before-send` is TRAI's Do Not
Disturb registry, which a valid consent record does **not** satisfy — that is the single most
commonly misunderstood rule in Indian outbound marketing, and the platform states it on every
allowance rather than assuming the caller knows.

## 5. Ask about a purpose they never consented to — denied

```bash
curl -s -u athena-dialer:dev -X POST http://localhost:8080/v1/evaluate \
  -H 'Content-Type: application/json' -d '{
  "entityId": "DENAVE_IN", "subjectId": "walkthrough-subject-001",
  "purposeCode": "MKT_OUTBOUND_EMAIL", "channel": "EMAIL",
  "jurisdiction": "IN", "applicationId": "ATHENA_DIALER"
}'
```

```json
{
  "outcome": "DENY",
  "reason": "NO_CONSENT_RECORD",
  "explanation": "no consent interaction recorded for this subject and purpose",
  "purposeCode": "MKT_OUTBOUND_EMAIL",
  "purposeVersion": 1,
  "policyVersion": "policy-2026.08.1",
  "evaluatedAt": "2026-08-17T03:12:27.871387584Z",
  "obligations": []
}
```

`200`, not `403`. A denial is a successful answer to a question, and returning an error status
would push every integrator into treating denials as failures to retry around.

`NO_CONSENT_RECORD` is a distinct reason from `CONSENT_WITHDRAWN` (§7), from `CONSENT_EXPIRED`, and
from `PURPOSE_UNKNOWN` — which is what you get for a purpose code that is not in the registry at
all. Distinguishing "we never asked" from "they said no" from "the code is wrong" is what makes
`DenialReason` answerable in a grievance rather than a shrug.

## 6. Withdraw

```bash
curl -s -u denave-web:dev -X POST http://localhost:8080/v1/consent/withdraw \
  -H 'Content-Type: application/json' -d '{
  "entityId": "DENAVE_IN", "subjectId": "walkthrough-subject-001",
  "purposeCodes": ["MKT_OUTBOUND_CALL"], "jurisdiction": "IN",
  "channel": "WEB", "applicationId": "DENAVE_WEB",
  "actorType": "SUBJECT", "actorId": "walkthrough-subject-001",
  "idempotencyKey": "walkthrough-withdraw-1",
  "reason": "asked to stop being called"
}'
```

```json
{
  "accepted": true,
  "subjectId": "walkthrough-subject-001",
  "events": [{
    "eventId": "dfb3457f-520e-474e-b578-431d50d23967",
    "purposeCode": "MKT_OUTBOUND_CALL",
    "purposeVersion": 1,
    "eventType": "WITHDRAWN",
    "status": "WITHDRAWN",
    "occurredAt": "2026-08-17T03:12:28.014949Z",
    "sequenceNumber": 2,
    "eventHash": "a86fe7581f90f0e7d914b90264a8dd3192b6dc721aa0ad90a622e915dc08e053"
  }],
  "violations": []
}
```

`sequenceNumber: 2`. **Nothing was updated.** The grant is still there, unchanged, and the
withdrawal is a new link in the chain — which is what lets the platform answer "what did they agree
to, and when did they change their mind" rather than only "what is true now".

Note the withdrawal is served under **no notice**: a person stopping something does not need to be
informed of anything first. That reasonable fact caused the second defect in §14.

## 7. Ask again — denied, and for the right reason

```bash
curl -s -u athena-dialer:dev -X POST http://localhost:8080/v1/evaluate \
  -H 'Content-Type: application/json' -d '{
  "entityId": "DENAVE_IN", "subjectId": "walkthrough-subject-001",
  "purposeCode": "MKT_OUTBOUND_CALL", "channel": "VOICE_CALL",
  "jurisdiction": "IN", "applicationId": "ATHENA_DIALER"
}'
```

```json
{
  "outcome": "DENY",
  "reason": "CONSENT_WITHDRAWN",
  "explanation": "consent status is WITHDRAWN",
  "purposeCode": "MKT_OUTBOUND_CALL",
  "purposeVersion": 1,
  "policyVersion": "policy-2026.08.1",
  "evaluatedAt": "2026-08-17T03:12:28.149403709Z",
  "obligations": []
}
```

Same call as §4, opposite answer, and the reason names the cause. Both this denial and §5's were
written to the enforcement evidence log without the caller asking — they appear in §9.

## 8. The receipt

The ISO/IEC TS 27560 document the data principal is entitled to.

```bash
curl -s -u compliance-console:dev \
  http://localhost:8080/v1/consent/DENAVE_IN/walkthrough-subject-001/receipt
```

```json
{
  "schemaVersion": "uds-consent-receipt/1;iso-27560:2023-receipt-subset",
  "receiptId": "57ff792c-26c0-4e67-8f45-3cf7d46b613d",
  "consentRecordId": "DENAVE_IN:walkthrough-subject-001",
  "issuedAt": "2026-08-17T03:12:28.281901667Z",
  "fiduciaryName": "Denave India Private Limited",
  "fiduciaryId": "DENAVE_IN",
  "subjectId": "walkthrough-subject-001",
  "jurisdiction": "IN",
  "languageTag": "en",
  "noticeId": "NOTICE_DENAVE_B2B",
  "noticeVersion": 1,
  "entries": [{
    "purposeCode": "MKT_OUTBOUND_CALL",
    "purposeVersion": 1,
    "purposeName": "Promotional outbound call",
    "dataCategories": ["IDENTITY", "CONTACT_BUSINESS"],
    "legalBasis": "CONSENT",
    "status": "WITHDRAWN",
    "grantedAt": "2026-08-17T03:12:27.568718Z",
    "withdrawnAt": "2026-08-17T03:12:28.104233Z",
    "sensitive": false
  }],
  "withdrawalUri": "https://privacy.uds.co.in/withdraw",
  "rightsUri": "https://privacy.uds.co.in/rights",
  "grievanceUri": "https://privacy.uds.co.in/grievance",
  "evidenceHash": "8b4492c8d6ed9583b715155b6cfee4d46d73b36cbd25d26864454c85dd7d1f6d"
}
```

**One line above is composed rather than captured**, and it is said here rather than left to be assumed:
`withdrawnAt` was added in Phase 15, after the 17 August run this document records, so its value is
consistent with the rest of the output rather than taken from it. Everything else is real output. The
field carries the instant the *subject acted* — not the server's, which matters for a field capture
syncing days later — and appears only while the entry is withdrawn: consent given again clears it.

`receiptId` is durable: `GET /v1/receipts/{receiptId}` returns this document byte-for-byte,
parsed back from the stored canonical payload rather than rebuilt, so a copy the subject printed
last year still matches. `GET /v1/receipts/{receiptId}/verification` returns the hash they can
check theirs against.

`purposeName`, `dataCategories` and `sensitive` describe **version 1** — the version this person
agreed to — not whatever the registry says today. `evidenceHash` is the chain hash of the event
that established the consent, which is what ties this document to §10.

Fields that are absent rather than empty (`recipients`, `retentionPeriod`, `crossBorderCountries`
here) are absent because nothing is recorded, and the receipt does not assert an absence it cannot
support. "We share this with nobody" is a statement; "we have not recorded who we share it with" is
a different one.

## 9. The evidence bundle

One call, everything held about one person, for a Board complaint. Abridged here — the full
response is around 6 KB — but nothing is omitted from the structure.

```bash
curl -s -u compliance-console:dev \
  http://localhost:8080/v1/admin/evidence/subject/DENAVE_IN/walkthrough-subject-001
```

| Section | This subject |
|---|---|
| `events` | **2** — the grant and the withdrawal, each with `previousHash`, `eventHash` and the **verbatim canonical payload that was hashed** |
| `currentState` | 1 — `WITHDRAWN`, with `grantedAt`, `withdrawnAt` and the last chain hash |
| `noticesServed` | 1 — `NOTICE_DENAVE_B2B` v1 in English, with its **full title and body as published**, not a reference to it |
| `receipts` | 1 — the stored payload and its hash |
| `enforcementDenials` | **2** — both denials from §5 and §7, with reason, channel, application and the instant of the decision |
| `rightsRequests`, `suppressions`, `consentManagerLinks`, `ageAssertions`, `reconfirmations`, `retentionActions` | 0 each — present and empty, so a reader can tell "none" from "not looked at" |
| `truncation` | **Empty**, meaning this bundle is complete. For a principal past 100 receipts or 200 denials it carries one entry per capped section **per subject id**, naming the cap and a ready-to-run request that returns that remainder — a merged principal produces one per overflowing id, because the cap is applied per id and a single pointer over a concatenated list could not be run |
| `propagation` | **Empty here**, because no `propagation_target` is registered for this entity — the register is UDS's to populate (`REGULATORY_HANDOFF.md` §8.7). Once it is, one row per registered system: whether anything can currently reach it, what was delivered *to this principal*, and a register-level count of days the system was recorded as untold. Read the record's javadoc before reading a zero as "not propagated" |
| `integrity` | The chain verification, inline |

The `enforcementDenials` section is the one that surprises people. It means the platform can show a
regulator not only that consent was withdrawn, but that **two subsequent attempts to contact this
person were refused and when** — evidence that the withdrawal was honoured, which is the thing
actually in dispute in a complaint.

`noticesServed` carries the notice text rather than a pointer, because a bundle that cites a notice
by id is only evidence if the notice is still there to look up, and the whole point of the evidence
plane is that it does not depend on the control plane not having changed.

**This bundle is for one entity.** A Board complaint is about a *person*, and a person can appear
under more than one of the fifteen. There is deliberately no group-level route — it would have to
bypass both isolation layers at once — so the fifteen-call assembly is an SOP: `OPERATIONS.md`
§12.2a, including the instruction to read `truncation` before filing.

## 10. Verify the chain

```bash
curl -s -u compliance-console:dev \
  http://localhost:8080/v1/admin/integrity/DENAVE_IN/walkthrough-subject-001
```

```json
{
  "entityId": "DENAVE_IN",
  "subjectId": "walkthrough-subject-001",
  "eventsChecked": 2,
  "findings": []
}
```

`findings: []` is the whole result. Four kinds of finding can appear: `SEQUENCE_GAP` (an event was
removed), `CHAIN_BREAK` (history was altered), `HASH_MISMATCH` (the stored hash does not match the
stored payload) and `PAYLOAD_DIVERGENCE` (the structured columns disagree with what was hashed).

**On the day of this walkthrough this call returned two `PAYLOAD_DIVERGENCE` findings on an
untampered, minutes-old ledger.** See §14.

## 11. The operator's half — health

The first thing anyone tries:

```bash
curl -s http://localhost:8080/actuator/health
```

```json
{"status": "UP"}
```

That is all an unauthenticated caller gets, and `OPERATIONS.md` §8's runbook is built entirely on
counters that are not in it. `management.endpoint.health.show-details` is `when-authorized`.
**Use a credential:**

```bash
curl -su compliance-console:dev http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP", "details": {"database": "PostgreSQL", "validationQuery": "isValid()"}},
    "platform": {
      "status": "UP",
      "details": {
        "ledgerIntegrity": "VERIFIED",
        "lastIntegritySweep": "NOT_YET_RUN",
        "chainsChecked": 0,
        "outboxPending": 2,
        "outboxBacklog": false,
        "failedEvidenceWrites": 0,
        "recordedDenials": 2,
        "consentManagerRegisterLastReconciled": "NEVER",
        "consentManagersNeverReconciled": ["CM-TEST-0001", "CM-TEST-0003"],
        "koreanReconfirmationsOverdue": 0,
        "sdfObligationsOverdue": 0
      }
    }
  }
}
```

Reading it: `outboxPending: 2` is the two consent events waiting for a broker that is not running,
which is correct under the default `log` publisher. `lastIntegritySweep: NOT_YET_RUN` is honest
rather than optimistic — the nightly sweep has not fired on a five-minute-old container, and saying
`VERIFIED` about a sweep that never ran would be the reassuring answer. `recordedDenials: 2` is
§5 and §7. `consentManagersNeverReconciled` names the seeded test registrations; on a real
deployment a name in that list is a Consent Manager whose Board register entry has never been
checked against the platform's copy.

⚠️ **`ledgerIntegrity: VERIFIED` and `chainsChecked: 0` appear together**, and they did so on the
day the chain check was returning findings for every event. `intact()` deliberately excludes
`PAYLOAD_DIVERGENCE`, so the health endpoint would not have shown it either way — but a reader
should treat `VERIFIED` alongside `chainsChecked: 0` as "nothing has been checked", not as a
statement about the ledger.

## 12. The verification key

Unauthenticated, deliberately: a public verification key is public, and requiring a credential to
fetch it would mean a field device that lost its credential also lost the ability to verify
snapshots it already holds.

```bash
curl -s http://localhost:8080/v1/keys
```

```json
[{"keyId": "walkthrough-2026-08", "algorithm": "Ed25519",
  "publicKeyBase64": "MCowBQYDK2VwAyEAbXuGoimUPqVMOsjM1JmYdFiMjv5ZNabYumw+tqCsNt0="}]
```

The `keyId` is the one exported in §1 rather than `dev-ephemeral`, which is how you can tell the
configured key pair was picked up. Had the environment variables been unset, this would read
`dev-ephemeral` and the startup log would carry a warning that every snapshot signed by this
process stops verifying when it restarts.

**One entry, and there can only ever be one.** `OPERATIONS.md` §2.2 asks an operator to publish the
retired key alongside the new one during rotation; there is no configuration behind that. See
`REGULATORY_HANDOFF.md` §8.3.

## 13. Cross-entity refusal

Not part of the principal's journey, but worth one call because it is the platform's largest
security control and its refusal has a shape an integrator must handle:

```bash
curl -s -u denave-console:denave-secret http://localhost:8080/v1/admin/ropa/MATRIX
```

```json
{
  "type": "about:blank",
  "title": "Cross-entity request refused",
  "status": 403,
  "detail": "credential is not authorised for that fiduciary entity"
}
```

`application/problem+json`, like every other error the platform returns. The entity that was asked
about is deliberately **not** echoed back — a caller who could read its own refusals could
enumerate which entity ids exist.

---

## 14. What the walkthrough found

Two defects, both real, both fixed in the same change as this document, and **neither reachable by
any test in the build**. That is the argument for doing this by hand, and it is worth being precise
about why each was invisible.

### 14.1 🔴 The tamper detector fired on every event ever written

`GET /v1/admin/integrity/{entityId}/{subjectId}` reported `PAYLOAD_DIVERGENCE` on both events of a
minutes-old, untampered chain. It reported it on every event in the database.

**The mechanism.** `Instant.now()` on a modern JVM has nanosecond resolution; PostgreSQL's
`timestamptz` stores microseconds. The hashed payload was serialised from the in-memory value and
the column was written from the same value through `Timestamp.from`, which **rounds** to the
microsecond — while the event record **truncates**. On roughly half of all events the two differed
by one microsecond, so re-serialising the columns produced a different string from the one that had
been hashed, and the divergence check said so.

**Why it mattered more than it looks.** The chain itself was fine: `eventHash` covers the stored
payload, the payload is stored verbatim, and `intact()` excludes `PAYLOAD_DIVERGENCE` — which is
exactly why nobody noticed. What was broken was the one check that exists to catch somebody editing
the structured columns without being able to forge the payload. **A detector that fires on
everything detects nothing**, and this one had been firing on everything since the ledger was
written.

**Why no test caught it.** Every integration suite passes either a fixed literal
(`Instant.parse("2026-08-15T09:00:00Z")`) or `Instant.now().truncatedTo(ChronoUnit.SECONDS)`. Not
one had ever carried a sub-microsecond component into the ledger, because a test author choosing an
instant chooses a tidy one. Production does the opposite on every single event.

**The fix.** `ConsentEvent` truncates `occurredAt`, `recordedAt` and `expiresAt` to microseconds in
its compact constructor, and `ConsentEventStore` writes the column from the stored event rather
than from a loose local variable that had bypassed it. What is hashed is now what the evidence
plane can hold. `LedgerAppendOnlyIT.subMicrosecondPrecisionDoesNotLookLikeTampering` pins it, and
records ten events rather than one — with a single event this test would have passed by luck about
half the time.

### 14.2 🟠 A receipt issued after a withdrawal lost the notice and all three Rule 3 links

The receipt in §8 arrived with `noticeId`, `noticeVersion`, `languageTag`, `withdrawalUri`,
`rightsUri`, `grievanceUri` and `evidenceHash` **all null** — but only after the subject withdrew.
Issued a minute earlier, the same receipt carried every one of them.

**The mechanism.** `ConsentArtefact` is a projection of *current* state. A withdrawal is served
under no notice (§6), so after one the artefact carries no `noticeId` — and the receipt was reading
the notice off the artefact. The three Rule 3 links hang off the notice version, so losing the
notice lost all three.

**Why it mattered.** DPDP Rule 3 requires each of those to be a specific link rather than a general
contact page, and the receipt is where a data principal finds them. **The receipt a person is most
likely to ask for is the one issued after they withdrew** — that is the moment they want a record
of what just happened and how to complain about it. The document was at its least useful at exactly
the point it was needed most.

**Why no test caught it.** `ReceiptIT` had thorough coverage of receipts on granted consent and of
reproduction, and issued its post-withdrawal receipts through a path that did not assert on the
notice fields. Nothing wrong with the suite — the case simply had not occurred to anyone, and it
occurs to you immediately the first time you follow one person through in order.

**The fix.** When no artefact carries a notice, `ReceiptService` reads the most recent
notice-bearing event off the chain instead, which still holds what the person was actually shown.
`ReceiptIT.withdrawalDoesNotStripTheNoticeFromTheReceipt` compares the receipt before and after a
withdrawal field by field.

### 14.3 Two smaller things, fixed in passing

- **`docker-compose.yml` did not pass the pepper or the signing keys through**, so `--profile app`
  always ran on development defaults no matter what was in the environment. §1.
- **The unauthenticated health endpoint returns `{"status":"UP"}` and nothing else**, which is not
  a defect but is a trap: `OPERATIONS.md` §8's runbook is built on counters an operator will not
  see on their first attempt. Now documented in `OPERATIONS.md` §2.4 and in §11 above.

### 14.4 What was uneventful, which is also a result

Everything else worked exactly as documented, first time, with no undocumented startup
requirements: the notice served, the capture was accepted and hashed, the allowance carried its
three obligations, both denials named the right reason, the withdrawal appended rather than updated,
the receipt reproduced, the bundle assembled all thirteen sections, and the cross-entity refusal
came back RFC 7807. **This is the first time anyone has been able to say that**, and it is worth
saying: every previous plan carried "run it by hand once" as a bullet, and four of them passed
without it being done.

---

## Re-running this

```bash
cd platform/docker && docker compose --profile app down -v
```

Then §1. The `-v` matters: without it the database survives and the walkthrough runs against a
subject that already exists, which changes what §9 returns and makes §10 less interesting.

Anyone re-running this should **update this document with what they find, including nothing**. A
walkthrough that is not re-run is a screenshot; one that is re-run each release is a control.


---

## 15. Propagation — who was told, and who was not

Phase 17. Everything above ends at the ledger; this is the half that says whether a withdrawal
reached anybody. Run it after §8, against the same Compose stack.

**Register a system that must be told, with nothing subscribed to reach it.**

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD -X PUT \
  -H 'Content-Type: application/json' -H 'X-UDS-Actor: walkthrough@uds' \
  -d '{"entityId":"DENAVE_IN","topic":"uds.consent.events","systemCode":"DENCRM",
       "mandatory":true,"active":true,"description":"walkthrough"}' \
  http://localhost:8080/v1/admin/propagation/targets
```

**Read the register back.** `subscriptionId` is `null` and `uncovered` names `DENCRM` — the platform
now says, in one call, that a system it was told must hear about withdrawals cannot be reached:

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD \
  'http://localhost:8080/v1/admin/propagation/targets?entityId=DENAVE_IN'
```

The same number is on the management port, as a detail and never a DOWN condition:

```bash
curl -sS http://localhost:9090/actuator/health | jq '.components.platform.details.propagationUncovered'
```

**Withdraw, then read the gap.** After the relay's next pass (two seconds):

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD \
  'http://localhost:8080/v1/admin/propagation/gaps?entityId=DENAVE_IN'
```

**Read `reason` before concluding anything.** On a stack running the default `log` publisher it is
`NO_DELIVERY_CHANNEL` — the platform recording that it *cannot observe* delivery, not that DenCRM
was not told. `NO_SUBSCRIPTION` is the one that means nobody was reachable. See `OPERATIONS.md`
§4.0a.

**Then register the subscription and watch it clear.** `uncovered` returns to zero, which is the
whole point of deriving current state from the register rather than from history:

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD -X PUT \
  -H 'Content-Type: application/json' -H 'X-UDS-Actor: walkthrough@uds' \
  -d '{"subscriptionId":"DENCRM","entityId":"DENAVE_IN","topic":"uds.consent.events",
       "url":"http://host.docker.internal:9099/hook","secret":"shared","active":true}' \
  http://localhost:8080/v1/admin/subscriptions
```

**The gap row does not disappear, and that is correct.** It is append-only evidence that on that day
the obligation was unmet. One row per system per day: it names the first principal and event type
observed that day as an exemplar, not the whole set.

---

## 16. Identity before disclosure, and a notice that does not erase a grant

Phase 18. Two behaviours, run by hand against the Compose stack on **19 August 2026**; the output
below is what the platform actually returned, trimmed, not an illustration.

### 16.1 A disclosing right cannot be closed on an identity nobody recorded

**File one the way an operator takes it over the telephone** — no identity established, which is the
honest default and deliberately not refused at intake:

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD -X POST \
  -H 'Content-Type: application/json' -H 'X-UDS-Actor: walkthrough@uds' \
  -d '{"entityId":"DENAVE_IN","identifierType":"EMAIL","identifierValue":"asha.rao@example.in",
       "type":"ACCESS","jurisdiction":"IN","details":"filed by telephone"}' \
  http://localhost:8080/v1/rights
```

```json
{ "requestId": "RR-7d420503-…", "type": "ACCESS", "status": "RECEIVED",
  "dueAt": "2026-09-17T22:42:07Z", "verification": "UNVERIFIED" }
```

The clock is running — Art. 12(2) forbids refusing to *act* — and `verification` says plainly that
nobody has been established as the person asking.

**Now try to close it.** `409`:

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD -X PATCH \
  -H 'Content-Type: application/json' -H 'X-UDS-Actor: walkthrough@uds' \
  -d '{"status":"FULFILLED","resolution":"file exported"}' \
  http://localhost:8080/v1/rights/$REQUEST_ID
```

```json
{ "title": "Identity not verified", "status": 409, "requestType": "ACCESS",
  "detail": "… is a ACCESS request and cannot be closed as FULFILLED while its verification_method
             is UNVERIFIED: fulfilling it discloses or irreversibly changes this person's data, and
             nothing on the record says who was established to be asking. Record what was checked at
             POST /v1/rights/RR-…/verification, then close it. Withdrawals, opt-outs and grievances
             are deliberately not gated this way." }
```

**Read the refusal, not the status code.** Both this and the fulfilment-evidence gate answer `409`,
and an operator told the wrong one fixes the wrong thing — which is why the message names
*verification* and the route that clears it.

**Record what was checked.** `X-UDS-Actor` is required: this route asserts that a *person* checked,
and a shared console credential cannot answer "who". Sent without it, `400`:

> `X-UDS-Actor is required on administrative changes. Send the identity of the person taking the
> action — a username or work email, not a team name and not the client id.`

```bash
curl -sS -u compliance-console:$ADMIN_PASSWORD -X POST \
  -H 'Content-Type: application/json' -H 'X-UDS-Actor: r.menon@uds' \
  -d '{"method":"OPERATOR_ASSERTED",
       "detail":"call-back to the mobile already on file; DOB confirmed"}' \
  http://localhost:8080/v1/rights/$REQUEST_ID/verification
```

```json
{ "verification": "OPERATOR_ASSERTED", "verifiedAt": "2026-08-18T22:42:21Z",
  "verificationDetail": "call-back to the mobile already on file; DOB confirmed" }
```

**A second attempt is refused, and the first record stands.** `409`:

> `… already records a verification of OPERATOR_ASSERTED, and verification is written once. It is
> evidence about what a person did, so it is not overwritten.`

The `PATCH` to `FULFILLED` then succeeds, and the closed request still carries the sentence the
operator wrote. **What the platform cannot check is the sentence itself** — what an adequate check
must involve is UDS's to publish, `REGULATORY_HANDOFF.md` §8.6, and a gate with no standard behind
its field is satisfied by typing the word "verified".

**And a withdrawal is deliberately not gated.** File a `CONSENT_WITHDRAWAL`, leave it `UNVERIFIED`,
and it closes `200`:

```json
{ "type": "CONSENT_WITHDRAWAL", "status": "FULFILLED", "verification": "UNVERIFIED" }
```

Consent is given by a ticked box with no identity check at all; demanding one to withdraw fails
DPDP s.6(4)'s and GDPR Art. 7(3)'s *comparable ease* on its face. `OPT_OUT_OF_SALE` and `GRIEVANCE`
are ungated for the same family of reasons. **Applying the gate uniformly is the wrong turn here.**

**The gate is on the claim, not the act.** `GET /v1/admin/evidence/subject/**` is unlinked from any
rights request, so a file can still be disclosed without one ever being opened. `ROADMAP.md` carries
that as the open item it is.

### 16.2 Re-serving a notice does not erase the grant

Capture a consent for `MKT_OUTBOUND_CALL`, then serve the notice again for the same purpose — a
Hindi re-serve, which a capture surface does routinely:

```bash
curl -sS -u denave-web:dev -X POST http://localhost:8080/v1/consent/notice-served \
  -H 'Content-Type: application/json' -d '{
  "entityId":"DENAVE_IN","subjectId":"walkthrough-p18-001","purposeCode":"MKT_OUTBOUND_CALL",
  "noticeId":"NOTICE_DENAVE_B2B","noticeVersion":1,"languageTag":"hi","jurisdiction":"IN",
  "applicationId":"DENAVE_WEB","idempotencyKey":"p18-notice-2"}'
```

The event is written and its `status` is `NOT_ASKED` — correct, because a notice asserts nothing
about agreement. Current state, before and after, is unchanged:

```json
[{ "purposeCode":"MKT_OUTBOUND_CALL", "purposeVersion":1, "status":"GRANTED",
   "legalBasis":"CONSENT", "grantedAt":"2026-08-18T22:42:41Z" }]
```

and the dialer's gate still allows:

```json
{ "outcome":"ALLOW", "reason":"NONE", "legalBasis":"CONSENT", "purposeVersion":1 }
```

**Before Phase 18 both of those read differently** — the projection was overwritten to `NOT_ASKED`
and the decision denied, with the grant still in the ledger and every hash valid. The overwrite also
destroyed `expiresAt`, `captureMethod` and `channel`, which for a `TRAI_TRANSACTIONAL_7D` purpose
silently removed the seven-day lapse the TCCCPR module exists to enforce. What the notice *does*
update is the notice: `noticeId`, `noticeVersion` and `languageTag` move forward, so a receipt names
the notice the person was most recently shown.

Serve a notice for a purpose with no artefact and the behaviour is unchanged — a `NOT_ASKED`
artefact is created, which is the s.7(i) workforce path the route exists for.
