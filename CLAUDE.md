# Working notes for Claude

Read this before doing anything. It exists because twelve phases of this programme re-derived the
same constraints through conversation and then lost them to compaction — which is how `-o` and *"k6
is not installed"* became believed facts across three planning passes each, both disproved later by a
single command.

The invariants a change can violate without the compiler noticing are in
[`.claude/rules/consent-management.md`](.claude/rules/consent-management.md). Read that too, before
touching anything under `platform/`.

---

## Standing constraints

**There is no git remote, deliberately.** The schema and its seed data are regulated personal data by
design intent, so a push destination is a decision for UDS and not a default. **Do not commit and do
not push without an explicit instruction in the current session.** `git commit` and `git push` are
absent from the settings allow-list on purpose; a permission prompt for either is the reminder
working, not an obstacle to route around.

**Do not over-engineer the legal-policy side.** Speculative regulatory surface is carried dark behind
a flag with the re-activating event recorded (`REGULATORY_HANDOFF.md` §2 and §4 are the two live
examples). Adding policy machinery for an obligation that is not yet in primary text is the failure
mode here, not the caution.

**Be brutally honest.** Do not praise a design unless it is genuinely strong, and say plainly when
something is not. Every review in this programme that mattered found something.

**Never run `perf/seed.sql` against anything but a database you can drop.** It writes fabricated
consent, the ledger is append-only, and there is no cleanup. Fabricated consent is a ledger defect no
integrity sweep will ever catch, because the hashes will be perfectly valid.

**A document that no longer matches the platform is worse than no document, because it is believed.**
Changing behaviour includes changing whatever describes it, in the same change.

---

## Build and verify

```bash
cd platform && mvn -B verify
```

Online. **Not `-o`** — the offline habit was mistaken for a constraint for three planning passes; §3
of the plan file records it. Testcontainers needs a running Docker daemon and nothing else.

Baseline: **508 tests, 0 failures** — 33 core + 38 ledger + 113 policy + 5 service unit + 319 service
IT. *A drop in the count is a deleted test, not a passing build.* The count comes from the
`TEST-*.xml` `tests` attributes, never the failsafe `.txt` summaries — `consent-ledger` reports
`Tests run: 0` there.

Next Flyway migration is **`V31`**, in `platform/consent-ledger/src/main/resources/db/migration/`.

---

## The map

| Where | What is in it |
|---|---|
| `DECISIONS.md` | One line per decision with its reason and a pointer to where it is argued. **Read it before re-opening a settled question** |
| `ROADMAP.md` | Delivered phases, then open items — each with the check that closes it — split into the platform's side and UDS's |
| `docs/standards/` | ISO/IEC TS 27560 and DPV structure, TCF and Consent Mode v2, **fetched from primary sources with access dates**. Read these; do not re-research them |
| `docs/TRACEABILITY.md` | Clause → field, route or behaviour, with the test that proves each. The artefact an audit asks for |
| `docs/competitive-analysis.md` | What the field gets wrong, and where this platform is behind it |
| `platform/` | The system. Four Maven modules — `consent-core`, `consent-ledger`, `consent-policy`, `consent-service`. No root `pom.xml`; the reactor is `platform/pom.xml` |
| `docs/OPERATIONS.md` | §1 provisioning · §2 secrets · §3 ledger integrity · §4 sweepers and outbox · §5 suppression · §6 SLOs · §7 taxonomy changes · §8 environment checklist · §9 breach runbook · §10 what the platform does not decide · §11 correlation ids and metrics · §12 the front door |
| `docs/REGULATORY_HANDOFF.md` | Items needing a person, not a commit. **§8 is the list of decisions UDS owns** — read it before proposing to build one of them |
| `docs/RUNBOOK_DR.md` | Backup, PITR, and §5.1 the record of the one rehearsal that has happened |
| `docs/CAPACITY.md` | The capacity model; **§7 is the only measured latency in the programme** |
| `docs/WALKTHROUGH.md` | One data principal, notice to evidence bundle, with real commands |
| `docs/openapi.json` | The pinned contract. `OpenApiContractIT` fails the build on drift; regenerate deliberately with `-Duds.openapi.snapshot=update` |
| `perf/README.md` | The load profile invocation, in one place |
| `~/.claude/plans/we-want-to-build-staged-widget.md` | Every phase plan and delivery record, including the defects each phase found |

---

## How to work here

**Plan mode before implementation, every phase.** For a compliance system, disagreement is cheapest
before the migration exists. A plan for anything touching regulated behaviour must carry the
**clause → system-behaviour mapping** as part of the plan, not as commentary afterwards — load
`/regulatory-clause-map` to build the table.

**Delegate research and review; keep implementation close.** The four roles in `.claude/agents/` are
the standing arrangement, authorised by the user for this project — call them without asking:

- `regulatory-researcher` — before writing a clause→behaviour table, so 50 pages of primary text
  never enters this thread.
- `architect-reviewer` — on a schema or API decision, **before** it is implemented.
- `qa-verifier` — against the approved plan when a phase is nominally done.
- `implementer` — for a self-contained slice of an approved plan. Default to building in the main
  thread: an implementer starts cold and re-reads what this session already holds, which usually
  costs more than it saves.

**`/next-phase` to close one phase and open the next**, and **read `docs/standards/` rather than
re-researching a standard.** Those documents record what was actually read, from where, on what date, and
what was not readable — the ISO 27560 text is paywalled and not held, so conformance is claimed against
the free W3C DPV rendering and named as such. Adding a citation nobody opened is the defect class this
project keeps correcting.

**`/phase-gate` before declaring a phase done.** It runs the checklist and ends in an adversarial
pass — argue in writing how the flow fails a DPDP audit — then updates the baseline and appends the
delivery record to the plan file.

**One session per phase or per subsidiary.** Denave's DenCRM integration and the washroom-hygiene
integration must never share a context; independent workstreams pollute each other's. Worktrees are
the stronger form of this and are **not** available yet: `git worktree add` carries no uncommitted
changes and this tree has ~180 uncommitted files over two commits, so a worktree today would be an
empty early checkout. The day a commit is authorised, `git worktree add ../uds-<phase> -b <phase>`
becomes the right answer.
