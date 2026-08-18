---
name: phase-gate
description: The checklist a phase must pass before it can be called done — build green and the test count not below baseline, documents re-verified rather than re-read, every new claim measured or labelled, the four standing assertions, then an adversarial pass arguing how the work would fail a DPDP audit. Ends by updating the baseline and appending the delivery record. Run it before telling the user a phase is finished.
---

# Phase gate

Work through this in order. Do not skip a step because you are confident about it — every step here
exists because a previous phase was confident and wrong.

## 1. The build, honestly

```bash
cd platform && rm -rf */target/failsafe-reports && mvn -B verify
```

Count from the XML, not the summaries — `consent-ledger`'s failsafe `.txt` reports `Tests run: 0`:

```bash
cd platform && find . -name 'TEST-*.xml' -path '*-reports/*' -exec sed -n 's/.*tests="\([0-9]*\)".*/\1/p' {} + | paste -sd+ - | bc
```

Compare against `.claude/state/test-baseline`. **Below it is a deleted test, not a passing build.**
Green and above baseline, or the gate has failed and the phase is not done. The `Stop` hook enforces
this mechanically; do not work around it.

## 2. The four standing assertions

They are what every phase risks breaking, and each has an owning suite that must pass **unmodified**:

1. Append-only survives — `LedgerAppendOnlyIT`, refusals proven as `uds_consent_app`.
2. RLS covers every entity-scoped table — `RowLevelSecurityIT` derives its set from
   `information_schema`, so editing it to pass is defeating it.
3. The metrics port is not the traffic port — `MetricsEndpointIT`.
4. Attribution cannot be spoofed under JWT — `JwtAuthenticationIT`.

If one needed editing to pass, the change was not additive and that is the finding.

## 3. Every claim this phase introduced

- Any number now published: **measured under stated conditions**, or explicitly **labelled an
  objective by a document that says why it was not measured**. Both honest; unqualified is not.
- Any document the phase touched, and any document describing behaviour the phase changed: re-read for
  statements that have stopped being true.
- **Verify instructions rather than reading them.** Make the call, run the command. A runbook step
  naming `/v1/admin/integrity/verify` survived months of review because everyone read it and nobody
  ran it.
- The API contract: `docs/openapi.json` drift is deliberate or it is a defect.

## 4. Spawn `qa-verifier`

Against the phase's plan section. It reports gaps, silent scope reductions, and tests that assert a
mechanism rather than a property. Its verdict is advisory, not binding — but overriding it goes in the
delivery record with the reason.

## 5. The adversarial pass — in writing, before signing off

Argue the other side. Not a summary of the above; a genuine attempt to fail the work.

> **How would this phase's flow fail a DPDP audit?**

- The Board asks for everything held about one principal on one identifier. Is the answer complete
  across merges, entities and purposes — or only across the ones this phase happened to touch?
- A principal withdrew. Prove it reached every consuming system. Name the link that is assumed rather
  than evidenced.
- A record says `FULFILLED`. What happened, in which system, evidenced how?
- Can you produce the notice version shown at capture, and the purpose version consented to?
- Who performed each administrative act, as a person, and could someone else have written that name?
- Rule 13(4) residency — does it hold for backups and archives, or only the primary?
- When did the clock start, and is that defensible if the request came from a stranger?

Write the answers down. An answer of "it would not" needs the evidence beside it.

## 6. Record it

- Update `.claude/state/test-baseline` to the new count.
- Append a delivery record to `~/.claude/plans/we-want-to-build-staged-widget.md` in the shape §1b
  established: a table of what each task delivered; **the finding that carried the phase**; every
  defect found, marked pre-existing or introduced; deviations from the plan **with the reason**; and
  what is left. Anything the phase found that contradicts what was written before it goes in
  explicitly — that record is the only reason this programme catches its own believed premises.
- **Do not commit.** There is no remote, deliberately, and the bar stands without an explicit
  instruction.
