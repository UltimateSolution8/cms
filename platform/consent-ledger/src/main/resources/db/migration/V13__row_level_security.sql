-- =====================================================================================
-- Per-entity isolation, in the database.
--
-- The largest security gap the platform had, and the one that grows with every entity
-- onboarded. Every entity-scoped endpoint takes an entity_id and every one of them trusted
-- it, so a Matrix ADMIN credential could read Denave's consent records, audit trail and RoPA
-- by changing one string — with nothing anywhere noticing, because every field in the request
-- is individually well-formed.
--
-- EntityAccessGuard refuses those requests before they run. This is the second layer, and it
-- exists because the first one is code: code acquires a new endpoint that somebody forgets to
-- check, or a query built from a body field the filter deliberately does not parse. A policy
-- here applies to every statement whether or not anybody remembered.
--
-- -------------------------------------------------------------------------------------
-- How the claim reaches the database.
--
-- Through a session variable, uds.entity_id, set on every connection checkout by
-- EntityScopedDataSource. Set on EVERY checkout rather than once per session, because the
-- pool hands the same physical connection to different requests — a variable set once would
-- be a Denave claim answering a Matrix request within minutes of start-up, which is worse
-- than no isolation at all because it would look like isolation.
--
-- An empty value means group level. current_setting(..., true) returns NULL when the variable
-- has never been set in this session, so both spellings of "no claim" are handled: they read
-- as group level, which is the behaviour the platform had before this migration and therefore
-- the safe direction for anything that has not been updated.
--
-- -------------------------------------------------------------------------------------
-- What this deliberately does NOT do.
--
-- No FORCE ROW LEVEL SECURITY. Policies do not apply to a table's owner, and the owner is the
-- migration role — so Flyway keeps working, the store integration suites keep working, and
-- the policies bind exactly the role that serves traffic. Forcing it would mean every
-- migration and every fixture had to set the variable first, which is a great deal of
-- ceremony to protect the account that already has to be trusted with the schema.
-- =====================================================================================

create or replace function uds_entity_claim() returns text as $$
    -- The second argument makes current_setting return NULL instead of raising when the
    -- variable has never been set. Without it, the first query on a fresh connection errors
    -- rather than falling back to group level.
    select nullif(current_setting('uds.entity_id', true), '');
$$ language sql stable;

comment on function uds_entity_claim() is
    'The fiduciary entity the current database session may see, or NULL for group level. Set '
    'per connection checkout from the authenticated credential.';

do $$
declare
    entity_table text;
    -- Every table with an entity_id that is not itself configuration. fiduciary_entity and
    -- application_registry are deliberately absent: the group's own structure and the list of
    -- registered surfaces are readable by everyone, and hiding them would break the decision
    -- path for every entity without protecting anything personal.
    entity_tables text[] := array[
        'consent_event', 'consent_artefact', 'subject', 'suppression_entry',
        'provenance_record', 'processing_activity', 'vendor', 'rights_request',
        'admin_audit_event', 'enforcement_decision', 'scrub_run', 'personal_data_breach',
        'retention_action', 'consent_receipt', 'notice', 'dlt_header', 'dlt_template'
    ];
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; row-level security policies NOT applied. '
            'Create the role and re-run V13 before going to production.';
        return;
    end if;

    foreach entity_table in array entity_tables loop
        if not exists (select 1 from information_schema.tables
                        where table_schema = 'public' and table_name = entity_table) then
            raise notice 'table % not present; skipping its policy', entity_table;
            continue;
        end if;

        execute format('alter table %I enable row level security', entity_table);

        -- One policy covering all commands. Separate read and write policies would let a
        -- credential insert a row it cannot then read, which is a state nobody can reason
        -- about and which shows up as data that vanishes.
        execute format($f$
            create policy uds_entity_isolation on %I
                using (uds_entity_claim() is null or entity_id = uds_entity_claim())
                with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
        $f$, entity_table);
    end loop;

    raise notice 'row-level security applied to % entity-scoped table(s)',
        array_length(entity_tables, 1);
end
$$;

comment on function uds_entity_claim() is
    'NULL means group level and sees everything. That is a grant rather than a gap — group '
    'compliance genuinely needs it, and pretending otherwise produces a shared credential '
    'nobody can attribute.';
