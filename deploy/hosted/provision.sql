-- =====================================================================================
-- Provisioning a hosted PostgreSQL for the UDS consent control plane.
--
-- Run this AS THE OWNER (on Supabase, `postgres`) BEFORE the first Flyway migration.
--
-- Ordering is load-bearing and every failure in it is silent:
--
--   * `uds_consent_app` is created by NO migration. V2, V8, V11, V13 and fifteen others
--     wrap their grants and their row-level-security policies in
--     `if exists (select 1 from pg_roles where rolname = 'uds_consent_app')` and take the
--     else branch when it is missing — raising a NOTICE and succeeding. Migrate first and
--     the append-only revocations and every isolation policy are simply never applied,
--     while the platform starts, serves traffic and reports healthy.
--
--   * `alter default privileges` applies only to objects created by the role that
--     declared it. The Docker init script omits `for role` because docker-entrypoint runs
--     it as POSTGRES_USER, which is also the migration role. On a hosted database the
--     migration role is whatever the provider gave you, so `for role` is required — and
--     omitting it leaves `uds_consent_app` with no privileges on any table that no
--     migration explicitly granted, which is most of the schema.
--
-- Read deploy/hosted/verify.sql immediately afterwards. Do not skip it: everything this
-- script prevents fails quietly.
-- =====================================================================================

\set ON_ERROR_STOP on

-- The migration role, for the `for role` clauses below. On Supabase this is `postgres`.
-- Defaults to whoever is running this, which is the role Flyway must also use.
\set owner_role `echo "${DB_OWNER_ROLE:-postgres}"`

-- Usage:
--   psql "$OWNER_URL" -v app_password="$(openssl rand -base64 24)" -f provision.sql

-- ---------------------------------------------------------------------------------
-- 1. The application role. Not a superuser, not the owner, and never both.
-- ---------------------------------------------------------------------------------
-- The password is a psql variable, NOT a GUC read with current_setting. That was the
-- first shape of this block and it failed the way everything else here fails: PGOPTIONS
-- did not propagate the setting, `coalesce` fell through to a placeholder, the role was
-- created with a password nobody knew, and the script reported success. A missing psql
-- variable is a hard error before a single statement runs.
\if :{?app_password}
\else
\echo 'ERROR: pass the application role password, e.g.  -v app_password="$(openssl rand -base64 24)"'
\quit
\endif

-- create-or-reset, so a re-run is idempotent AND leaves a password the caller knows.
-- Built with \gexec rather than a DO block: psql does not interpolate its variables
-- inside dollar-quoted bodies, so :'app_password' inside $$ ... $$ is a syntax error
-- rather than a substitution. format(%L) does the quoting.
select case
         when exists (select 1 from pg_roles where rolname = 'uds_consent_app')
           then format('alter role uds_consent_app with login password %L', :'app_password')
         else format('create role uds_consent_app with login password %L', :'app_password')
       end \gexec

-- `grant connect on database current_database()` is not valid syntax — the database name
-- must be an identifier, so it is interpolated rather than called.
select format('grant connect on database %I to uds_consent_app', current_database()) \gexec

grant usage on schema public to uds_consent_app;

-- ---------------------------------------------------------------------------------
-- 2. Default privileges on what Flyway is ABOUT to create.
--
-- `for role` is the clause a hosted database needs and the Docker init does not.
-- `execute on functions` is here and is MISSING from OPERATIONS.md §1.1's version of
-- this script — uds_entity_claim() is a function, and every RLS policy calls it.
-- ---------------------------------------------------------------------------------
alter default privileges for role :"owner_role" in schema public
    grant select, insert, update, delete on tables to uds_consent_app;
alter default privileges for role :"owner_role" in schema public
    grant usage, select on sequences to uds_consent_app;
alter default privileges for role :"owner_role" in schema public
    grant execute on functions to uds_consent_app;

-- ---------------------------------------------------------------------------------
-- 3. Supabase only, and this is the one that would have ended the pilot.
--
-- Supabase exposes schema `public` over HTTPS through PostgREST, and its default
-- privileges grant `anon`, `authenticated` and `service_role` `arwdDxtm` — SELECT,
-- INSERT, UPDATE, DELETE and TRUNCATE — on every table `postgres` creates. `anon` is
-- the key that ships inside a browser bundle.
--
-- What holds without this block: V2's triggers are role-independent, so `consent_event`
-- and `admin_audit_event` cannot be edited by ANY role. The hash chain is safe.
--
-- What does not: those triggers cover five V1-era tables. Thirteen tables carry only a
-- `revoke ... from uds_consent_app`, and a revoke from one role says nothing about
-- another. So `anon` would hold full write on `consent_artefact` — the projection the
-- policy engine reads — and on `consent_receipt`, `enforcement_decision`,
-- `webhook_delivery`, `propagation_gap`, `rights_fulfilment_action` and `subject_alias`,
-- plus full read on everything.
--
-- And V13's policy is `using (uds_entity_claim() is null or ...)`. PostgREST never sets
-- the variable, so the claim is NULL and the policy PASSES EVERYTHING. That predicate is
-- safe under an unstated premise — that the only process connecting is the service,
-- which always sets it. A hosted provider with its own HTTP front end breaks it.
--
-- Also disable the project's Data API in the dashboard. This block removes the grants;
-- that removes the network path. Do both.
-- ---------------------------------------------------------------------------------
do $$
declare
    exposed text;
begin
    foreach exposed in array array['anon', 'authenticated', 'service_role'] loop
        if exists (select 1 from pg_roles where rolname = exposed) then
            execute format('revoke all on all tables in schema public from %I', exposed);
            execute format('revoke all on all sequences in schema public from %I', exposed);
            execute format('revoke all on all functions in schema public from %I', exposed);
            execute format('revoke all on schema public from %I', exposed);
            execute format(
                'alter default privileges for role %I in schema public '
                'revoke all on tables from %I', current_user, exposed);
            execute format(
                'alter default privileges for role %I in schema public '
                'revoke all on sequences from %I', current_user, exposed);
            -- FUNCTIONS. Missing from this loop until Phase 22's V34 was applied to a real
            -- project and came into existence with anon=X on its ACL — a SECURITY DEFINER
            -- function reachable over HTTPS with the key that ships in a browser bundle. The
            -- tables and sequences clauses above were written and this one was not, so the
            -- gap was invisible in a schema that had no functions of its own at the time.
            execute format(
                'alter default privileges for role %I in schema public '
                'revoke all on functions from %I', current_user, exposed);
            raise notice 'revoked provider role % from schema public', exposed;
        end if;
    end loop;
end
$$;

-- ---------------------------------------------------------------------------------
-- 3a. RUN THIS FILE AGAIN AFTER THE FIRST MIGRATION.
--
-- The two halves of section 3 protect different objects, and neither covers the other:
--
--   * `revoke all on all tables/sequences/functions` protects what exists NOW. At this
--     point in a fresh project that is almost nothing — the schema is created by Flyway,
--     which has not run yet.
--   * `alter default privileges ... revoke` protects what the OWNER creates LATER. It has
--     no effect on the provider's own declarations, which are made by `supabase_admin` and
--     which only `supabase_admin` can alter.
--
-- So a single run before migration leaves every object Flyway then creates covered only by
-- the default-privilege clauses — and any clause missing from them (functions, until Phase
-- 22) produces an object nobody revoked anything from. Running this file a second time
-- after `flyway migrate` closes that window for objects that already exist, and
-- verify.sql's check 4 is what proves it rather than this comment.
--
-- Migrations that create functions should not rely on any of this. V34 revokes the
-- provider roles from its own function, in the migration, because default privileges are
-- applied at creation time and a script that ran yesterday cannot protect an object
-- created today.
-- ---------------------------------------------------------------------------------

-- ---------------------------------------------------------------------------------
-- 4. Say what was done, so a copy-pasted run leaves a record in the console.
-- ---------------------------------------------------------------------------------
do $$
begin
    raise notice '---';
    raise notice 'provisioning complete. NOW RUN deploy/hosted/verify.sql.';
    raise notice 'Flyway must connect as the owner (%), the service as uds_consent_app.',
                 current_user;
    raise notice 'Pointing both at the same role silently returns the ability to edit';
    raise notice 'history AND bypasses every isolation policy, with nothing looking wrong.';
end
$$;
