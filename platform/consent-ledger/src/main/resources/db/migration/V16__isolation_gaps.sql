-- =====================================================================================
-- Two entity-scoped tables V13 missed.
--
-- Found by RowLevelSecurityIT, which is the first test in the tree to connect as
-- uds_consent_app and therefore the first to run under the policies at all. V13 deliberately
-- does not FORCE ROW LEVEL SECURITY, so the policies never bind a table's owner — and every
-- integration suite connects the application as the migration role. Layer one
-- (EntityAccessGuard) had eight cases; layer two had none, and these two gaps sat inside it.
--
-- -------------------------------------------------------------------------------------
-- subject_identifier
--
-- The mapping from a peppered identifier hash to a subject id, per entity. It is the most
-- sensitive join table the platform has and it was left open, which is the failure mode V13's
-- own list warns about: the list was written by hand and this table sits fifteen lines below
-- `subject` in V1, so it reads as part of the same thing and was not.
--
-- The disclosure is worse than it first looks. A hash is deterministic under one pepper, so a
-- session scoped to Matrix could take a phone number, hash it, and learn whether that person
-- is a Denave subject and under which subject id — then use the id against any other table.
-- The policies on those tables would have refused a direct read, and this one handed over the
-- key to them.
--
-- -------------------------------------------------------------------------------------
-- consent_chain_head
--
-- The head of the hash chain per (entity, subject): the last sequence number and the last
-- hash. Not personal data in the ordinary sense, and that is presumably why it was skipped —
-- but it discloses that a given subject id exists at a given entity and how many events they
-- have, which is enough to confirm that a named person is a Denave data principal and roughly
-- how active their record is. It is also the value an integrity check reads, so a
-- cross-entity write here would let one entity corrupt another's chain verification.
--
-- -------------------------------------------------------------------------------------
-- What is still deliberately open, restated so the next reader does not "fix" it.
--
-- fiduciary_entity, application_registry and application_entity_scope carry an entity_id and
-- have no policy on purpose. They are the group's own structure and the list of registered
-- surfaces — configuration, not personal data. Hiding them would break the decision path for
-- every entity while protecting nothing about anybody. consent_manager likewise: a Consent
-- Manager is registered with the Board, not with a fiduciary.
--
-- Repeated as a new migration rather than folded into V13 because V13 has run everywhere that
-- exists; editing it would either be ignored by Flyway or fail its checksum. A migration is
-- append-only for the same reason the ledger is.
-- =====================================================================================

do $$
declare
    gap_table text;
    gap_tables text[] := array['subject_identifier', 'consent_chain_head'];
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; isolation policies for % NOT applied. '
            'Create the role and re-run before going to production.', gap_tables;
        return;
    end if;

    foreach gap_table in array gap_tables loop
        execute format('alter table %I enable row level security', gap_table);

        -- Identical in shape to V13's, and identical on purpose: one policy covering all
        -- commands, so a credential can never insert a row it cannot then read. Two tables
        -- protected by a subtly different rule than the fifteen beside them would be a
        -- difference nobody could account for later.
        execute format($f$
            create policy uds_entity_isolation on %I
                using (uds_entity_claim() is null or entity_id = uds_entity_claim())
                with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
        $f$, gap_table);
    end loop;
end
$$;

comment on table subject_identifier is
    'Peppered identifier hash to subject id, per entity. Entity-scoped and covered by the '
    'isolation policy: a hash is deterministic under one pepper, so an unscoped read here is a '
    'way of testing whether a known person is another entity''s data principal.';

comment on table consent_chain_head is
    'Per-subject hash chain head. Entity-scoped and covered by the isolation policy: it '
    'discloses that a subject exists at an entity and how many events they have, and it is the '
    'value integrity verification reads.';
