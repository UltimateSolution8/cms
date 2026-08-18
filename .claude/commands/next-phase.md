---
description: Close out the current phase and plan the next one — re-read the decisions, confirm the acceptance criteria, then plan with clause citations before any code.
---

Phase closing and next-phase planning. Work through these in order; do not compress them.

## 1. Re-read, do not remember

Read these **directly**, now, rather than relying on anything in this conversation:

- `DECISIONS.md` — what has been decided and why.
- `docs/standards/` — the standards grounding, already fetched. Do not re-research what is in there;
  if something is missing, that is what `regulatory-researcher` is for.
- `.claude/rules/consent-management.md` — the invariants.

Conversation memory of a decision is not the decision. Three planning passes of this programme reasoned
from a premise nobody re-checked, twice, and both times one command disproved it.

## 2. Confirm the phase that is ending actually met its criteria

Against `ROADMAP.md`. For each acceptance criterion: met, or not. **Flag every gap explicitly — do not
carry one forward silently.** A gap named now is a scoping decision for the user; a gap carried is a
defect discovered later by somebody who trusted the roadmap.

Run `/phase-gate` if it has not been run for this phase. Its verdict, including the adversarial pass, is
part of the answer to this step.

## 3. Plan the next phase — in plan mode, and wait

Enter plan mode. The proposal must include:

- **Which standards and regulations it must satisfy, citing specific clauses.** Load
  `/regulatory-clause-map` for the table; spawn `regulatory-researcher` for anything not already in
  `docs/standards/` or the map. A plan for regulated behaviour without a clause→behaviour table is not
  ready.
- Schema and API changes, with the next migration number and whether any unique constraint or partition
  key is affected.
- Which invariants the work risks breaking, and how each will be proven still to hold.
- What is deliberately **not** in scope, stated rather than left silent.
- Where a design decision is genuinely open, `architect-reviewer` before implementation rather than
  after.

**Then stop and wait for approval.** Write no code, run no migration, change no configuration until the
plan is approved. On a compliance system the cheapest disagreement is the one that happens before the
migration exists.

## 4. After approval

Implement; self-verify against the suite (`cd platform && mvn -B verify`, count not below
`.claude/state/test-baseline`); run `/phase-gate`; then update `DECISIONS.md` with what was decided and
`ROADMAP.md` with what shipped and what is still open, and append the delivery record to the plan file.

**Do not commit.** There is no remote, deliberately, and the bar stands without an explicit instruction
in this session.
