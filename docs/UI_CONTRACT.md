# UI contract

**Audience: the agent or engineer building a front end against this platform.** It is a
specification, not a narrative — the reasoning behind each rule lives in `docs/OPERATIONS.md` and
`.claude/rules/consent-management.md`, and is only summarised here where a client would otherwise
get something wrong.

Two front ends are anticipated and they are very different products:

| | **Compliance console** | **Data principal portal** |
|---|---|---|
| Who uses it | UDS compliance, DPO, entity administrators | Members of the public |
| Auth | OIDC, ADMIN scope | **None**, by design |
| Surface | `/v1/admin/**`, `/v1/rights/**`, registries | `/v1/portal/**`, `/v1/notices/**` |
| Risk if wrong | Discloses one person's whole file | Becomes a subject-enumeration oracle |

Build them as **separate applications**. An ADMIN token in the same browser context as a public page
is a larger change to this platform's threat model than anything in its code.

---

## 1. Before anything else

- **The contract is `docs/openapi.json`** — OpenAPI 3.1, 120 operations, pinned by a test that fails
  the build on drift. Generate your client from it. It is also served at `/v3/api-docs`, but that
  route is **ADMIN-gated**, not public.
- **Base URL** has no version prefix of its own; every route below is absolute (`/v1/...`).
- **Everything is JSON.** Refusals are `application/problem+json` (RFC 7807) — with two exceptions in
  §6 that will catch you out.
- **CORS is off until an origin is configured.** Ask for `uds.consent.security.cors.allowed-origins`
  to include your origin, exactly (scheme, host and port). There is no wildcard and there will not
  be one.

---

## 2. Authentication

Two schemes run side by side. **Use OIDC; Basic exists for integrators who have not migrated.**

### The flow

Authorization Code with PKCE, in the browser. There is no refresh-token endpoint on this platform —
it is a resource server, not an authorisation server. Token lifetime and renewal are the identity
provider's.

### The development issuer — build against this today

There is a Keycloak realm in the repository, committed as
`platform/docker/keycloak/uds-realm.json`, so the front end has something real to authenticate
against before anybody has configured Entra.

```bash
cd platform/docker && docker compose --profile auth up -d keycloak
```

| | |
|---|---|
| Issuer | `http://localhost:8081/realms/uds` |
| Discovery | `http://localhost:8081/realms/uds/.well-known/openid-configuration` |
| Audience — request it, or the platform refuses the token | `uds-consent-api` |
| Console client (public, PKCE) | `compliance-console` — scope `consent.admin` |
| Capture client (public, PKCE) | `denave-web` — scope `consent.capture` |
| Machine client (confidential) | `athena-dialer` — scope `consent.decision`, secret `dev-athena-secret` |
| Redirect URIs | `http://localhost:*` and `http://127.0.0.1:*` are registered |
| Access-token lifespan | **10 minutes**, not Keycloak's default 5 |

Development users, all with password `dev`:

| User | Entity | Use it for |
|---|---|---|
| `matrix.operator` | `entity.MATRIX` | the ordinary scoped-console case |
| `denave.operator` | `entity.DENAVE_IN` | the second entity, for proving a 403 |
| `over.assigned` | two entity roles | **must be refused 403** — see below |

Three things that will cost you an afternoon if you skip them:

- **Request the audience.** A token minted without `uds-consent-api` in `aud` is refused by the
  platform with a validator message that names nothing useful. In the realm this is a protocol
  mapper; against Entra it is the App ID URI.
- **Set `OIDC_ISSUER_URI` on the API** or the resource server is never registered at all, your
  `Bearer` header is ignored, and the Basic filter answers **401** — indistinguishable from a bad
  token.
- **The API will not start while the issuer is down.** The decoder fetches discovery at bean
  creation. Start Keycloak first.

Production is Microsoft Entra ID, and the endpoints are the only thing that changes: the scope→role
mapping, the entity claim, PKCE and the error shapes are all identical. `OPERATIONS.md` §2.3a is the
tenant checklist.

### Scopes map to roles

| Scope | Role | Grants |
|---|---|---|
| `consent.decision` | `DECISION` | `/v1/evaluate`, `/v1/evaluate/batch`, snapshots |
| `consent.capture` | `CAPTURE` | Recording consent, withdrawal, rights intake |
| `consent.admin` | `ADMIN` | Everything under `/v1/admin/**`, the rights queue, registries |
| `consent.relay` | `CONSENT_MANAGER` | `/v1/consent-manager/**` |

The lookup is **exact and case-sensitive**. Granted values are read from `scope`, `scp` **and**
`roles` and unioned, so both a Keycloak-style and an Entra-style token work unchanged.

### The entity claim — read this one

A token carries the fiduciary entity it may act for, in one of two ways:

- a claim, `entity_id: "DENAVE_IN"`; or
- an app role, `entity.DENAVE_IN`, for issuers that cannot mint a custom claim.

**A token with neither is group level and can read every entity in the group.** That is the same
grant an unscoped machine credential has, and it is deliberate — but it means a console token that
*should* be scoped and silently is not will look completely normal while showing one entity's staff
another entity's data. If your console is for a single subsidiary, verify the claim is present by
decoding a real token before you ship.

A token naming **two** entities is refused `403 Ambiguous entity scope`. Do not treat that as a
transient error; it is an identity-provider misconfiguration.

### `X-UDS-Actor`

Required on **every mutating administrative route** under Basic auth. Names the human behind the
credential — `admin_audit_event` records `client=<clientId>;actor=<human>`.

**Under a bearer token it is ignored entirely.** The human comes from `preferred_username`, then
`email`, then `sub`. Send it anyway if you like; it will not be read. Do not build UI that asks the
user to type their own name into it when they are signed in.

> If your tokens fall through to `sub`, the audit trail records an opaque pairwise identifier that
> is meaningless outside the directory — permanently, in an append-only table. Ask the platform team
> to confirm `preferred_username` is an optional claim on the API app registration before go-live.

---

## 3. Routes, by audience

Roles are enforced twice — a filter and `@PreAuthorize` — so a 403 is authoritative.

### Public (no credential)

| Route | Notes |
|---|---|
| `POST /v1/portal/requests` | 202. Rights request intake |
| `POST /v1/portal/requests/{reference}/verify` | 200. Redeems the one-time code |
| `GET /v1/portal/requests/{reference}?code=` | 200. Status and dates only |
| `GET /v1/notices/{noticeId}` | The notice text, by `?jurisdiction=` and `?lang=` |
| `GET /v1/notices/{noticeId}/languages` | Which languages it exists in |
| `GET /v1/keys` | Snapshot verification keys, active and retired |

### Machine / integration

`POST /v1/evaluate` · `POST /v1/evaluate/batch` (≤1000 per call) · `POST /v1/consent` ·
`POST /v1/consent/withdraw` · `POST /v1/consent/notice-served` ·
`GET /v1/consent/{entityId}/{subjectId}` · `GET /v1/consent/{entityId}/{subjectId}/receipt` ·
`GET /v1/receipts` · `POST /v1/provenance` · `POST /v1/suppression/*` · `POST /v1/rights` ·
`GET /v1/snapshot/{entityId}/{subjectId}`

### Console

The full list is in `docs/openapi.json`. The groupings a console will build around:

- **Rights** — `GET /v1/rights/queue?entityId=`, `/overdue` (**group-wide, takes no `entityId`**),
  `/summary?entityId=`, `PATCH /v1/rights/{id}`, `POST /v1/rights/{id}/fulfilment`,
  `POST /v1/rights/{id}/verification`
- **Evidence** — `GET /v1/admin/evidence/subject/{entityId}/{subjectId}` (**requires `X-UDS-Actor`
  even though it is a GET**, and writes an audit row)
- **Registers** — `/v1/admin/propagation/{targets,gaps,systems}`, `/v1/admin/fulfilment-targets`,
  `/v1/admin/subscriptions`, `/v1/admin/vendors`, `/v1/admin/processing-activities`
- **Integrity** — `/v1/admin/integrity/{sweep,last}`, `/v1/admin/projection/{sweep,last,divergences}`,
  `/v1/admin/sweeps`
- **Publishing** — notice and purpose versions, each returning a blast radius computed *before* the
  write

---

## 4. Rate limits

Per instance, not fleet-wide. Two limiters; you will normally only meet the second.

| Class | Matches | Limit |
|---|---|---|
| `PUBLIC` | `/v1/portal/**`, GET on notices and keys | 20/s, burst 60 |
| `DECISION` | `/v1/evaluate` | 200/s, burst 400 |
| `BATCH` | `/v1/evaluate/batch` | 10/s, burst 20 |
| `CAPTURE` | `/v1/consent`, `/v1/provenance`, `/v1/suppression`, `/v1/rights` | 100/s, burst 200 |
| `ADMIN` | `/v1/admin/**` | 50/s, burst 100 |

A 429 carries `Retry-After` (seconds). Honour it. Preflights are **not** counted.

---

## 5. Errors

RFC 7807, `application/problem+json`:

```json
{ "type": "about:blank", "title": "Fulfilment not evidenced", "status": 409,
  "detail": "...", "outstandingSystems": ["DENCRM", "HRMS"] }
```

Extra properties worth handling rather than logging:

| Property | On | Meaning |
|---|---|---|
| `outstandingSystems` | 409 | Systems with no terminal action; the request cannot close |
| `requestType` | 409 | The rights type whose gate refused |
| `feature` | 404 | A dark route — the flag that would enable it |
| `availableLanguages`, `noticeId`, `noticeVersion` | 404 | Offer the user a language that exists |

**403 has three distinct causes** and the `title` distinguishes them: role, `Cross-entity request
refused`, and `Ambiguous entity scope`. Only the first is worth a "you don't have permission"
message; the other two are configuration and should surface to an administrator.

**500 is deliberately opaque.** Show the user the correlation id from the `X-Correlation-Id`
response header and nothing else.

---

## 6. Ten things that will catch you out

Every one of these has already caused a defect in this platform or was found looking for them.

1. **`422` is not problem+json.** `POST /v1/consent` and the Consent Manager grant return a normal
   response body with `accepted: false` and a `violations[]` array. Parse it as a domain response.
2. **`truncation[]` on the evidence bundle carries a ready-made follow-up request in `remainderAt`.**
   Use it verbatim. Do not assemble your own page pointer — for a merged principal there is one
   entry *per merged id*, and a single concatenated pointer silently omits records.
3. **`returned` on a truncation entry is not the array length** when the subject was merged. Read
   `mergedFrom` first.
4. **`sweptAt: null` and `ageSeconds: null` mean *unknown*, never zero.** Render "not known". An
   empty `divergences` list beside a null `sweptAt` is *not* a statement that the entity is clean —
   it means the instance answering has never swept.
5. **`recipients` and `crossBorderCountries` on a receipt entry: `null` ≠ `[]`.** `null` means nobody
   recorded them; `[]` means none. Rendering `null` as "None" puts a false statement in front of a
   data principal. Render "not recorded".
6. **`systemUnmetDays` is register-level, not per person.** A zero does **not** mean this person's
   withdrawal propagated. `deliveryAttributed: false` with `delivered: 0` means *we cannot say*.
7. **`GET /v1/admin/subscriptions` returns the webhook HMAC secret in clear.** Do not render it in a
   list view, a log, or an error toast.
8. **`GET /v1/rights/overdue` is group-wide** and takes no `entityId`. If your console is
   entity-scoped, filter client-side or you will show one entity's staff another entity's backlog.
9. **`GET /v1/admin/propagation/targets` returns an untyped object**, not a schema-stable record —
   keys `entityId`, `targets`, `health`, `needsAttention`, `uncovered`. Do not codegen against it.
10. **An unknown purpose code returns `200 OK` with a DENY body**, reason `PURPOSE_UNKNOWN`. It is
    not a 400. Surface the reason, or a typo looks like a policy decision.

---

## 7. Do not build these

Each is a deliberate platform decision, not a gap.

- **A consent preference centre.** Letting a principal toggle consents from an unauthenticated page
  is a write path into an append-only evidence ledger from the open internet — the one thing two
  layers of isolation exist to refuse. Withdrawal already has a route; the capture surface that
  knows who the person is calls it.
- **A group-level evidence bundle.** Assembling one person across fifteen entities would have to
  bypass the route guard and the database policy at once. The procedure for doing it entity by
  entity is `OPERATIONS.md` §12.2a.
- **Any re-implementation of a policy decision.** Never compute "is this allowed" in the client.
  Call `/v1/evaluate`. The engine carries eleven ordered gates, jurisdiction modules and statutory
  clocks, and a client-side approximation of it will be wrong in exactly the cases that matter.
- **A retry loop that ignores `Retry-After`**, and anything that treats a 409 as transient. Both
  409s here mean a human has to do something.

---

## 8. What the contract still does not tell you

Stated so you do not go looking.

- **Which role a route needs is not in the OpenAPI document.** OpenAPI has no way to say "ADMIN or
  CAPTURE". §3 above and `@PreAuthorize` in the source are the record.
- **The 422 bodies are not documented as error responses**, because they are not errors.
- **Nothing describes token lifetime or refresh** — that is your identity provider's, and this
  platform never sees it.
