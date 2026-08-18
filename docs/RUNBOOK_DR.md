# Backup and disaster recovery — UDS Consent & Privacy Control Plane

*17 August 2026. Written because there was no backup regime at all: `OPERATIONS.md` §3.2 correctly
required a full chain verification after every restore, and there was no restore procedure for it to
be the last step of.*

Everything below marked **proposed** is a number this document invents so that there is something
concrete to argue with. They are engineering suggestions, not commitments. UDS ratifies them or
replaces them, and until it does the platform has targets rather than obligations.

---

## 1. What is being protected, and why it is unusual

One PostgreSQL database, and it is not an ordinary one.

- **It is the evidence.** The hash-chained ledger is what UDS produces to the Data Protection Board.
  A restore that loses six hours does not lose six hours of convenience; it loses six hours of
  proof, and every consent captured in that window is a consent the group can no longer demonstrate.
- **It cannot be reconstructed from anywhere else.** There is no upstream system of record. DenCRM
  holds contact data, not the evidence that contact was lawful.
- **It is regulated personal data by design.** Backups are as sensitive as the database — more so,
  because they usually live somewhere with a different access model and a longer memory.
- **It is append-only.** Which helps: a restore to any point in time is internally consistent,
  because nothing was ever updated in place on the evidence tables.

---

## 2. Targets

| | **Proposed** | Meaning |
|---|---|---|
| **RPO** | 5 minutes | The most data the group is prepared to lose. Achieved by continuous WAL archiving; the number is the archive interval, not a hope — and §5.2 records the rehearsal finding that archiving can be broken while the server reports it as on |
| **RTO** | 60 minutes | Time from decision-to-restore until the decision API answers again. Excludes the time to decide, which is usually longer. The 17 August 2026 rehearsal completed the *procedure* in ~17 s of machine time on a laptop (§5.1); that measures the procedure, not this target |
| **Chain verification** | Before traffic | A restored database is not in service until `POST /v1/admin/integrity/sweep` passes across every entity |

**Why RPO is not zero.** Synchronous replication to a second site would give it and would put every
consent write behind a cross-site round trip — turning a 30 ms p95 into something an outbound
campaign notices. Five minutes of exposure against a permanent latency cost is the trade;
if UDS prefers the other side of it, that is a legitimate choice and it changes the SLO in
`OPERATIONS.md` §6.

**Why RTO is an hour rather than minutes.** A warm standby would give minutes and costs a second
database instance running continuously. The consequence of an hour without the platform is that
outbound campaigns pause — which is the *correct* behaviour, because the alternative is calling
people without checking. That is what makes an hour tolerable here and would not make it tolerable
for a payments system.

---

## 3. Backup configuration

Three layers, because they fail differently.

| Layer | Frequency | Retention (proposed) | Recovers from |
|---|---|---|---|
| Continuous WAL archiving | Continuous | 35 days | Anything, to any second |
| Base backup (`pg_basebackup`) | Nightly | 35 days | Loss of the instance |
| Logical dump (`pg_dump -Fc`) | Weekly | 12 months | Corruption that replicated, and cross-version restore |

The logical dump earns its place: WAL and base backups faithfully reproduce a corruption that was
committed. A weekly logical dump is the only layer that survives "the bad thing happened five weeks
ago and nobody noticed", which — for a platform whose integrity sweep runs nightly and whose findings
must be *read* by somebody — is not a hypothetical.

**Encryption and residency.** Backups are encrypted at rest with a key held separately from the
database credentials, and stay inside the residency region recorded on each entity
(`fiduciary_entity.data_residency_region`, `ap-south-1` for the Indian companies). DPDP Rule 13(4)
lets the Government bar offshore transfer of specified categories for a Significant Data Fiduciary,
so a backup bucket in the wrong region is a compliance decision made by an infrastructure default.

**Access.** Restoring is not a routine permission. Anyone who can read a backup can read every
identifier hash in the ledger, and — with the pepper, which is *not* in the backup — reverse them.

---

## 4. Restore procedure

Run in this order. Step 5 is not optional and is the reason this document exists.

*Rewritten 17 August 2026 after the first rehearsal (§5). Three steps were wrong as written and are
corrected in place; the rehearsal's own record of what was wrong is in §5, because a runbook that
silently repairs itself teaches nobody anything.*

1. **Stop writes.** Scale the deployment to zero. A restore racing live traffic produces a database
   whose chain heads disagree with its events, which is indistinguishable from tampering.
2. **Provision a new instance.** Never restore over the damaged one: it is the only evidence of what
   happened, and an incident review that begins by destroying its own subject is not a review.
3. **Check the archive before you trust it.** Ahead of any restore, and ideally nightly:
   ```sql
   select archived_count, failed_count, last_archived_wal, last_failed_wal from pg_stat_archiver;
   ```
   A non-zero and *rising* `failed_count` means the RPO in §2 is fiction and recovery granularity is
   the last base backup. The rehearsal found exactly this, from a directory permission — the server
   reports `archive_mode=on`, accepts writes and retries a failing `archive_command` forever, and
   the only symptom is a log line. Note also that `pg_basebackup -Xnone` **waits** for the segments
   it needs to be archived: a base backup that appears to hang at 100% is usually this, and it is
   the failure announcing itself at the least convenient moment.
4. **Restore the base backup, then replay WAL** to the chosen recovery target. For corruption rather
   than loss, target the last known-good time — the nightly integrity sweep gives you one.
   `recovery_target_time`, `recovery_target_action=promote`, and `recovery.signal` in the data
   directory **before** the server is started; without that file PostgreSQL performs crash recovery
   instead, ignores the target silently, and hands back a database restored to the wrong moment.
5. **Verify the role separation. Do not recreate the roles.** `uds_consent_owner` owns the schema;
   `uds_consent_app` serves traffic with `UPDATE`/`DELETE` revoked on the evidence tables. In a
   physical restore, roles and grants arrive with the base backup and need no action — the rehearsal
   confirmed the separation survived a PITR untouched. Creating them again is the *logical* restore
   path (§3's weekly `pg_dump`), and running that DDL here is how an operator working at speed grants
   the application ownership of its own evidence. Verify, and only act if a check fails:
   ```sql
   select tableowner from pg_tables where tablename = 'consent_event';   -- must NOT be uds_consent_app
   select has_table_privilege('uds_consent_app', 'consent_event', 'UPDATE');  -- must be false
   select has_table_privilege('uds_consent_app', 'consent_event', 'DELETE');  -- must be false
   select has_table_privilege('uds_consent_app', 'consent_event', 'INSERT');  -- must be true
   ```
6. **Verify the chain, before any traffic.**
   ```
   POST /v1/admin/integrity/sweep
   ```
   Not `…/integrity/verify`, which is what this document said until the rehearsal ran and got a 404
   from it. There has never been such a route; `sweep` walks every chain and returns
   `chainsChecked`, `chainsWithFindings` and `chainsTampered`, and the restore is verified only when
   the last two are zero **and** the first is the number of chains you expect. A step that names a
   route that does not exist is a step nobody has ever performed.

   Every entity, not a sample. A restore that ends here having found a break has told you the
   recovery target was wrong — go back to step 4 and pick an earlier one. A restore that skips this
   step has produced a ledger nobody can vouch for, which is worth less than no ledger at all
   because it will be relied on.
7. **Reconcile the outbox.** Messages published before the recovery point but after the last
   archived WAL segment were delivered and are now unpublished again. The relay will resend them;
   consumers are required to be idempotent and consent events carry an event id and a per-subject
   sequence number precisely so they can be. Check `webhook_delivery` for duplicates rather than
   assuming.

   Expect the restored `event_outbox` to hold *more* rows than the damaged instance, not fewer: the
   relay prunes published rows as it goes, so a restore to a moment before the last prune brings
   back rows that were already delivered. The rehearsal saw 20,080 against 19,581 for this reason.
   The number to check is `count(*) where published_at is null` — it was zero in both.
8. **Scale up. Watch `/actuator/health/readiness`**, not `/actuator/health` — readiness carries the
   chain state, and it is the signal that says whether this instance should be taking traffic.

---

## 5. The rehearsal

**A restore procedure that has never been run is a document, not a capability.**

Quarterly, against a copy, timed end to end. It ends with step 6 passing: a rehearsal that restores a
database and does not verify the chain has rehearsed the easy half.

Locally, `platform/docker/docker-compose.dr.yml` runs PostgreSQL with WAL archiving to a volume, so
the procedure can be walked through on a laptop before it is walked through at three in the morning.

### 5.1 First rehearsal — 17 August 2026

Performed on the laptop stack, against the 1.2 GB database left by the load run in `CAPACITY.md` §7:
1,000,000 subjects, 1,000,000 artefacts, 150,000 suppressions, ~120,000 enforcement decisions, and
**17,302 hash chains**.

**Method.** A consent was captured *before* the base backup, a second one after it, and a third one
after the chosen recovery target — so the target sits between two known states and the restore has
something to be right or wrong about. That is the part of a rehearsal that distinguishes "a database
started" from "point-in-time recovery worked".

| Step | Measured |
|---|---|
| `pg_basebackup -Xnone -c fast`, 1.2 GB | **4 s** |
| Provision fresh instance, copy base, start recovery | **3 s** |
| Replay WAL to target and promote | **2 s** |
| Application start to `/actuator/health/readiness` UP | **6 s** |
| `POST /v1/admin/integrity/sweep` — 17,302 chains | **2.2 s** |
| **Total machine time, writes stopped → chain verified** | **~17 s** |
| **Total wall clock including the operator reading each step** | **~90 s** |

**Point-in-time recovery was exact.** The restored ledger holds the consent captured before the base
backup and the one captured after it, and *not* the one captured after the target:
`recovery stopping before commit of transaction 159782, time 15:55:06.756` — the third capture. The
chain sweep then returned `chainsChecked=17302, chainsWithFindings=0, chainsTampered=0`.

**§2's targets stay proposed, and these numbers do not ratify them.** Seventeen seconds against a
proposed RTO of sixty minutes is not evidence that the RTO is generous. Everything here happened on
one machine with the base backup on local disk, no instance to provision, no object store to pull
from, no incident to diagnose and no decision to make — and the decision is usually the long part.
What the rehearsal does establish is that the *procedure* works and that nothing in the platform
resists being restored: the chains verify, the roles survive, the outbox reconciles.

### 5.2 What the rehearsal found — three defects, all in the DR machinery itself

**1. WAL archiving had never worked, and would never have worked.** `docker-compose.dr.yml` archives
into a named volume, which Docker creates root-owned; PostgreSQL runs as uid 70. Every
`archive_command` failed with `cp: can't create ...: Permission denied` while the server reported
`archive_mode=on` and cheerfully accepted writes. The RPO of five minutes was, in this configuration,
twenty-four hours — and the only symptom was a log line. It surfaced sideways: `pg_basebackup -Xnone`
waits for its required segments to be archived, so the first base backup hung for twenty-five minutes
with the data already copied. Fixed by a `wal-archive-init` one-shot container that chowns the volume
before PostgreSQL starts, with the reasoning in the compose file; step 3 of §4 is new and exists so
that `pg_stat_archiver` is read *before* a backup is trusted rather than after it is needed.

**2. Step 6 named a route that does not exist.** Both §2 and §4 required
`POST /v1/admin/integrity/verify`. The platform serves `/v1/admin/integrity/sweep`, `…/last` and
`…/{entityId}/{subjectId}`, and has never served `verify` — the rehearsal got a 404 from the step the
whole document is built around. A step naming a nonexistent route is a step nobody has performed, and
it had survived two phases of review because reviewing prose against prose cannot catch it.

**3. "Recreate the roles" was wrong for this recovery path.** Roles and grants arrive with a physical
base backup; the restored instance had `uds_consent_owner`, `uds_consent_app`, correct table
ownership and `UPDATE`/`DELETE` still revoked, with no action taken. The instruction to recreate them
belongs to the logical (`pg_dump`) path, and following it here — at speed, in an incident — is
precisely how an operator would grant the application ownership of its own evidence, which is the
failure §4 warns about. The step is now *verify, and act only if a check fails*.

One non-defect worth recording, because it looks like one: the restored `event_outbox` held **more**
rows than the damaged instance (20,080 against 19,581). The relay prunes published rows, so a restore
to an earlier moment brings back rows already delivered. Unpublished count was zero in both, which is
the number that matters.

---

## 6. What is still missing, stated plainly

- **No warm standby.** Recovery is restore-from-backup, which is what makes RTO an hour rather than
  minutes.
- **No cross-region replication.** A regional failure is a restore into another region from backups
  that are themselves in the failed region's residency zone. That tension — Rule 13(4) residency
  against regional resilience — is a decision for UDS and not one this platform can make.
- **No encryption at rest beyond peppered identifier hashing.** Notice text, provenance records,
  guardian assertions and rights-request free text are plaintext columns on whatever the volume
  provides. This belongs at the infrastructure layer: full-disk or storage-level encryption on the
  database volume and the backup bucket, which is a provisioning requirement rather than a schema
  change. It is recorded here because it is a backup property as much as a database one.
- **The pepper is not in the backup and must not be.** A backup that contained it would let anyone
  who obtained the backup reverse every identifier hash in it. This means a restore *requires* the
  pepper from the secret manager — and a lost pepper is a database of hashes nobody can ever match
  an identifier against again. Back the pepper up separately, under different access control, and
  test that path too.
