# UDS Consent & Privacy Control Plane

Group-wide consent, notice and enforcement platform for UDS Group entities acting as Data
Fiduciaries under the **DPDP Act 2023 and the DPDP Rules 2025**, with jurisdiction modules for TRAI
TCCCPR 2018, GDPR/UK GDPR, Korea PIPA and the Network Act, Singapore PDPA, Malaysia PDPA and the US
state privacy statutes.

The Rules that matter bind from **13 May 2027**. Denave is the pilot entity.

---

## What is here

```
platform/     the system. Four Maven modules, Java 21, Spring Boot, PostgreSQL. Start here.
docs/         the operator runbook, the regulatory hand-off, and the walkthrough.
```

There is no build at this level and no root `pom.xml` — the reactor is `platform/pom.xml`.

### The four modules

| Module | What it holds |
|---|---|
| `consent-core` | The vocabulary: purposes, artefacts, receipts, jurisdictions, hashing, canonical JSON. No I/O. |
| `consent-ledger` | The append-only hash-chained evidence store and every table behind it. Spring JDBC, Flyway. No JPA. |
| `consent-policy` | The decision engine and the per-jurisdiction modules. Pure functions over ports. |
| `consent-service` | The Spring Boot application: HTTP API, security, sweepers, wiring. |

The split is a dependency rule, not a taxonomy: `consent-policy` cannot reach a database, so a
decision cannot acquire an undeclared lookup, and the golden decision suite runs without one.

### The three planes

- **Control plane** — purposes, notices, applications, vendors, entities. Configuration, versioned
  and published rather than edited.
- **Enforcement plane** — `POST /v1/evaluate`. The gate Athena's dialer calls before every outbound
  contact. Fails closed.
- **Evidence plane** — the ledger. Append-only, hash-chained per subject, and the thing that gets
  produced to the Data Protection Board.

---

## Running it

Docker and a JDK 21+ are the only prerequisites.

```bash
cd platform/docker && docker compose up -d postgres
```

```bash
cd platform && mvn spring-boot:run -pl consent-service -Dspring-boot.run.profiles=local
```

The service **refuses to start without an identifier pepper** — that is deliberate, and
`docs/OPERATIONS.md` §8 explains why an unpeppered identifier hash is a reversible one.

For the whole thing including the application container:

```bash
cd platform/docker && docker compose --profile app up
```

**[`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md) walks one person end to end** — notice, capture,
allow, deny, withdraw, receipt, evidence bundle, chain verification — with the real commands and
the real responses. It is the fastest way to understand what the platform does without reading
Java.

## Building and testing

```bash
cd platform && mvn verify
```

Docker must be running: the integration suites start a real PostgreSQL through Testcontainers
rather than substituting an in-memory database, because roughly a third of the platform's
correctness lives in the schema — append-only triggers, row-level security policies, and
constraints the application never sees.

**Run `verify` from `platform/`, and never `-pl <module>` without `-am`.**

`platform/README.md` describes every test suite and what each exists to prevent.

## Documents

| Document | For |
|---|---|
| [`platform/README.md`](platform/README.md) | Engineers. Architecture, the API surface, every test suite and why it exists. |
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | Whoever runs it. Configuration, sweepers, key rotation, the environment checklist, incident procedure. |
| [`docs/REGULATORY_HANDOFF.md`](docs/REGULATORY_HANDOFF.md) | Legal and compliance. What is modelled, what is deliberately not, what is being watched and by whom. |
| [`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md) | Anyone on day one, and any auditor. |

The `docs/` directory also carries the earlier planning drafts. They are kept for provenance and
are **superseded** by the four documents above wherever they disagree.

## A note on the repository itself

There is **no git remote, deliberately**. The schema and its seed data describe the group's
processing activities and its data subjects by design intent, so a push destination is a decision
for UDS rather than a default. The CI workflow in `.github/workflows/` is written and correct for
the day a remote exists.

---

Copyright © 2026 UDS Group
