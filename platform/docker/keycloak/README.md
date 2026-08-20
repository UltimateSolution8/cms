# The development realm

`uds-realm.json` is the artefact, not the instructions. A realm somebody clicked together in an
admin console is a realm nobody can reproduce, and this programme has already priced what an
unrehearsed procedure is worth (`RUNBOOK_DR.md` §5, and the three defects rehearsing it found).

Start it:

```bash
cd platform/docker && docker compose --profile auth up -d keycloak
```

Console at <http://localhost:8081>, `admin` / `admin`. Nothing starts it by default — the same
posture `redpanda` has under `--profile events`.

Point the platform at it:

```
OIDC_ISSUER_URI=http://localhost:8081/realms/uds
OIDC_AUDIENCE=uds-consent-api
```

## What is in it, and why each piece has to be there

| Piece | Why |
|---|---|
| An **audience mapper** on every client | Keycloak does not put a resource's identifier in `aud` by itself, and `SecurityConfiguration` does an exact `List.contains` against `uds.consent.security.jwt.audience`. Without it every token is refused by the audience validator. **This is the single most likely thing to get wrong.** |
| Client scopes named `consent.decision` / `.capture` / `.admin` / `.relay` | They reach the token's `scope` claim and map through `scope-roles` onto the four `ROLE_` names. Not one `requestMatchers` line in `SecurityConfiguration` knows an identity provider exists |
| A **realm-role mapper emitting `roles` flat** | Keycloak's default puts realm roles under `realm_access.roles`, which `JwtRoleConverter.grantedValues` does not read. A top-level `roles` array is also the shape Entra uses for app roles, so one configuration covers both issuers |
| `entity_id` hardcoded on `athena-dialer`, `entity.<ID>` realm roles on the human users | The two entity paths Phase 21 built. Only one of them had ever been exercised against a real issuer, and neither had been exercised against any issuer at all |
| A user assigned **two** entity roles | So the refusal — 403, never first-wins — is provable against a real token rather than only against one this repository minted for itself |
| `accessTokenLifespan: 600` | `perf/k6/decision.js` runs a ten-minute plateau. The Keycloak default is five minutes, and a token expiring mid-ramp produces a cliff of 401s that reads as a platform failure and gets measured as one |

## What it is not

**A production realm.** `sslRequired: none`, secrets in the file, `admin`/`admin`, and a
ten-minute access token. Production is Entra — `OPERATIONS.md` §2.4 is the checklist, and the
entity assignment there is an app role a directory administrator grants, which no test in this
repository can execute. `ROADMAP.md` carries that as the thing the platform cannot verify for
itself.
