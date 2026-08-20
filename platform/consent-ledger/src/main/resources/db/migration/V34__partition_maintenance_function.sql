-- =====================================================================================
-- Partition maintenance the application role can invoke and cannot abuse, and the second
-- append-only layer that never reached a partition.
--
-- -------------------------------------------------------------------------------------
-- What was wrong, part one: the sweeper cannot do its job in any correctly-separated
-- deployment.
--
-- PartitionStore issues `create table ... partition of enforcement_decision` through the
-- @Primary DataSource, which is EntityScopedDataSource — the APPLICATION role. Creating a
-- partition requires ownership of the parent, not merely CREATE on the schema, so this
-- fails with a permission error wherever the two roles are actually separate.
--
-- It has never been seen because it has never run in such a deployment: the Compose stack
-- and every integration test connect as the owner, and PartitionMaintenanceIT inherits
-- that. PartitionStore's own javadoc said "see OPERATIONS.md for the grant, which is
-- narrow — CREATE on the schema, nothing more". That grant is in no document in this
-- repository, and CREATE on the schema would not have been sufficient anyway. A pointer
-- to a runbook step that does not exist, describing a fix that would not have worked.
--
-- The fix is NOT a second owner-credentialled DataSource in the application. An owner
-- connection inside the app process bypasses every V13 policy for whatever else picks that
-- bean up — a larger hole than the one being closed.
--
-- It is a SECURITY DEFINER function, owned by whoever runs this migration (the owner), with
-- EXECUTE granted to uds_consent_app and to nobody else. Three properties make that safe,
-- and all three are load-bearing:
--
--   * The parent table name is HARDCODED. A parameterised
--     `create table ... partition of $1` executed as the owner is a privilege-escalation
--     primitive wearing a friendly signature. A second partitioned table needs a second
--     function, deliberately.
--   * The partition name is derived from a date, so no caller-shaped identifier reaches
--     the DDL.
--   * The body reads pg_catalog and issues DDL. It MUST NOT read or write any
--     entity-scoped table. On a hosted database — Supabase, where the schema owner is
--     `postgres` — the definer holds BYPASSRLS, so a select inside this function would
--     cross every fiduciary boundary silently and no test could see it. Anything added
--     here later is subject to that constraint.
--
-- -------------------------------------------------------------------------------------
-- What was wrong, part two: the revoke stopped at the parent.
--
-- V28 grants insert/select and revokes update/delete/truncate on `enforcement_decision`,
-- and then pre-creates fourteen partitions plus the default, granting nothing on any of
-- them — because it did not need to. The provisioning scripts
-- (platform/docker/init/01-application-role.sql and deploy/hosted/provision.sql) set
--
--     alter default privileges ... grant select, insert, update, delete on tables
--
-- so every partition is created WITH update and delete granted to uds_consent_app. That is
-- the broad-grant/precise-revoke pattern V2 argues for, working exactly as designed on the
-- parent and never applied to the children.
--
-- PostgreSQL checks DML privileges on the relation NAMED IN THE QUERY. Verified against the
-- Compose database on 20 August 2026, as uds_consent_app:
--
--     update enforcement_decision           ...  ERROR: permission denied
--     update enforcement_decision_2026_08   ...  permission GRANTED
--
-- The row was not modified, because V28's row triggers propagate to every partition and
-- refused it. So this is NOT an open hole in the evidence plane — it is the platform down
-- to ONE layer on partitions where it deliberately keeps two on the parent, and the layer
-- that survived is the one V28 happens to implement with a trigger. `scrub_run`, which V8
-- guards the same way, has no partitions and is unaffected.
--
-- Recorded at its real severity rather than at the one first suspected. It is still worth
-- closing: the whole reason two layers exist is that either one can be got wrong.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- 1. The function.
-- -------------------------------------------------------------------------------------
--
-- search_path pins pg_catalog FIRST so nothing in public can shadow format(), to_regclass()
-- or date_trunc() for a SECURITY DEFINER body; the create target is therefore written
-- schema-qualified rather than left to resolution.
--
-- Returns whether it created one. False means it already existed, which is the ordinary
-- case on every pass after the first.
create or replace function uds_ensure_enforcement_partition(p_month date)
    returns boolean
    language plpgsql
    security definer
    set search_path = pg_catalog, public
as $$
declare
    v_start date := date_trunc('month', p_month)::date;
    v_name  text := 'enforcement_decision_' || to_char(date_trunc('month', p_month), 'YYYY_MM');
begin
    if to_regclass('public.' || quote_ident(v_name)) is not null then
        return false;
    end if;

    execute format(
        'create table public.%I partition of public.enforcement_decision '
        'for values from (%L) to (%L)',
        v_name, v_start, (v_start + interval '1 month')::date);

    -- The second layer, applied at birth. Without this the partition arrives with update
    -- and delete granted by the default privileges above, and the only thing standing
    -- between the application role and an edited enforcement decision is the trigger.
    if exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        execute format(
            'revoke update, delete, truncate on public.%I from uds_consent_app', v_name);
    end if;

    return true;
end;
$$;

comment on function uds_ensure_enforcement_partition(date) is
    'Creates one monthly partition of enforcement_decision and revokes update/delete/truncate '
    'on it from uds_consent_app. SECURITY DEFINER because partition creation requires '
    'ownership of the parent, which the application role must never hold. The parent table is '
    'hardcoded deliberately: a parameterised form would be a privilege-escalation primitive.';

do $$
declare
    exposed text;
begin
    -- PUBLIC holds EXECUTE on a new function by default, which is the second thing a
    -- SECURITY DEFINER migration gets wrong after forgetting search_path.
    revoke all on function uds_ensure_enforcement_partition(date) from public;

    -- And the third thing, which cost this migration a real finding on the hosted database.
    --
    -- Revoking from PUBLIC does not remove a DIRECT grant, and on a managed provider the
    -- objects a migration creates are handed direct grants automatically. Supabase declares
    --
    --     alter default privileges in schema public grant execute on functions
    --         to anon, authenticated, service_role
    --
    -- and `anon` is the key that ships inside a browser bundle. So on first application this
    -- function came into existence with anon=X on its ACL, reachable over HTTPS through
    -- PostgREST — an unauthenticated DDL primitive on a database holding regulated personal
    -- data. Its blast radius is small by construction (one hardcoded parent, a date-derived
    -- name, no reads) and the direction is entirely wrong.
    --
    -- **Default privileges are applied at creation time, so a provisioning script run before
    -- the first migration can never protect an object a later migration creates.** That is
    -- the general lesson and it is why this block lives in the migration rather than in
    -- deploy/hosted/provision.sql, which has also been corrected. Every migration that adds
    -- a function to a database with an HTTP-exposed schema needs this, and there is no
    -- inherited protection to rely on.
    --
    -- Named conditionally, the same shape every migration here uses for uds_consent_app: a
    -- database that has never heard of these roles skips the block silently and correctly.
    foreach exposed in array array['anon', 'authenticated', 'service_role'] loop
        if exists (select 1 from pg_roles where rolname = exposed) then
            execute format(
                'revoke all on function uds_ensure_enforcement_partition(date) from %I', exposed);
            raise notice 'revoked EXECUTE on uds_ensure_enforcement_partition from %', exposed;
        end if;
    end loop;

    if exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        grant execute on function uds_ensure_enforcement_partition(date) to uds_consent_app;
    else
        raise notice
            'role uds_consent_app not present; EXECUTE on uds_ensure_enforcement_partition '
            'NOT granted. PartitionMaintenanceSweeper will fail on every pass in this database.';
    end if;
end
$$;


-- -------------------------------------------------------------------------------------
-- 2. The partitions that already exist.
-- -------------------------------------------------------------------------------------
--
-- Derived from pg_inherits rather than listed. V28 created fourteen and the sweeper has
-- been adding one a month since; a hand-written list would be stale before this migration
-- shipped. The default partition is included — it is where a row lands when a month was
-- missed, which is precisely the evidence somebody would want to tidy away.
do $$
declare
    child record;
    revoked int := 0;
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; partition-level revokes NOT applied.';
        return;
    end if;

    for child in
        select c.relname
          from pg_inherits i
          join pg_class c on c.oid = i.inhrelid
          join pg_class p on p.oid = i.inhparent
          join pg_namespace n on n.oid = p.relnamespace
         where p.relname = 'enforcement_decision'
           and n.nspname = 'public'
    loop
        execute format(
            'revoke update, delete, truncate on public.%I from uds_consent_app', child.relname);
        revoked := revoked + 1;
    end loop;

    raise notice 'partition-level append-only revoke applied to % partitions of enforcement_decision',
        revoked;
end
$$;
