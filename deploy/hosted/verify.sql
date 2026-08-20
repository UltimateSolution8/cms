-- =====================================================================================
-- Verifying a hosted database AFTER provisioning and AFTER the first migration.
--
-- Every check here exists because the thing it checks fails silently. Run it as the
-- owner. Nothing in it writes.
--
-- A green platform proves none of this: the migrations succeed with the app role
-- absent, the policies are created but bind nothing if the app connects as the owner,
-- and a provider role with a network path leaves no trace in any log this platform
-- writes.
-- =====================================================================================

\set ON_ERROR_STOP on
\pset pager off

\echo '=== 1. The two roles exist and are different ==============================='
-- The app role must exist. If it did not when V2 ran, every revoke below is absent and
-- the migration still succeeded.
select rolname,
       rolsuper   as must_be_false,
       rolbypassrls as must_be_false_for_the_app_role
  from pg_roles
 where rolname in ('uds_consent_app', current_user)
 order by rolname;

\echo ''
\echo '=== 2. Append-only holds AS THE APPLICATION ROLE =========================='
-- Expect SELECT and INSERT, and NO UPDATE or DELETE, on every evidence table.
-- Any row appearing here with UPDATE or DELETE is a table whose guard did not apply.
select table_name, string_agg(privilege_type, ', ' order by privilege_type) as granted
  from information_schema.table_privileges
 where grantee = 'uds_consent_app'
   and table_name in ('consent_event', 'admin_audit_event', 'consent_receipt',
                      'enforcement_decision', 'subject_alias', 'rights_fulfilment_action',
                      'webhook_delivery', 'propagation_gap', 'subject_age_assertion',
                      'scrub_run', 'notice_version', 'notice_translation',
                      'purpose_version')
 group by table_name
 order by table_name;

\echo ''
\echo '--- the same question asked the other way: anything that should NOT be there'
select table_name, privilege_type
  from information_schema.table_privileges
 where grantee = 'uds_consent_app'
   and privilege_type in ('UPDATE', 'DELETE', 'TRUNCATE')
   and table_name in ('consent_event', 'admin_audit_event', 'consent_receipt',
                      'enforcement_decision', 'subject_alias', 'rights_fulfilment_action',
                      'webhook_delivery', 'propagation_gap', 'subject_age_assertion',
                      'scrub_run', 'notice_version', 'notice_translation',
                      'purpose_version')
 order by table_name, privilege_type;
\echo '(the block above must be EMPTY)'

\echo ''
\echo '=== 3. Row-level security is enabled AND the owner is not the app role ===='
-- Both halves matter. V13 deliberately does not FORCE row level security, so policies
-- never apply to a table's owner — which is correct, and is exactly why the service
-- must not connect as the owner.
select c.relname,
       c.relrowsecurity as rls_enabled_must_be_true,
       pg_get_userbyid(c.relowner) as owner_must_not_be_uds_consent_app
  from pg_class c
  join pg_namespace n on n.oid = c.relnamespace
 where n.nspname = 'public'
   and c.relname in ('consent_event', 'consent_artefact', 'subject', 'rights_request',
                     'admin_audit_event', 'propagation_gap')
 order by c.relname;

\echo ''
\echo '=== 4. No provider role can reach the evidence plane ======================'
-- Supabase ships anon / authenticated / service_role with a network path through
-- PostgREST. Any row here is a role that can read or write regulated personal data over
-- HTTPS with a key that is public by design.
select grantee, table_name, string_agg(privilege_type, ', ' order by privilege_type)
  from information_schema.table_privileges
 where grantee in ('anon', 'authenticated', 'service_role', 'PUBLIC')
   and table_schema = 'public'
 group by grantee, table_name
 order by grantee, table_name;
\echo '(the block above must be EMPTY)'

\echo ''
\echo '--- and no provider role may EXECUTE a function in this schema'
-- Added in Phase 22, because check 4 above reads information_schema.table_privileges and
-- therefore says nothing at all about functions — while claiming, in its own heading, that
-- no provider role can reach the evidence plane. V34 was applied to a real project and
-- arrived with anon=X on a SECURITY DEFINER function that creates tables: the provider's
-- own `alter default privileges ... grant execute on functions to anon, authenticated,
-- service_role` had granted it at creation time, and `revoke ... from public` does not
-- remove a direct grant. A check that covers a subset of what its heading claims is worse
-- than no check, because the empty result was read as the whole answer.
select p.proname,
       p.prosecdef as security_definer,
       array_to_string(p.proacl, ' ') as acl
  from pg_proc p
  join pg_namespace n on n.oid = p.pronamespace
 where n.nspname = 'public'
   and (array_to_string(p.proacl, ' ') like '%anon=%'
     or array_to_string(p.proacl, ' ') like '%authenticated=%'
     or array_to_string(p.proacl, ' ') like '%service_role=%')
 order by p.prosecdef desc, p.proname;
\echo '(the block above must be EMPTY. A SECURITY DEFINER row here is the serious one:'
\echo ' it runs with the owner rights, and on this provider the owner holds BYPASSRLS.)'

\echo ''
\echo '--- schema USAGE: revoking from a role does not remove PUBLIC grant'
-- `revoke all on schema public from anon` leaves `GRANT USAGE ON SCHEMA public TO PUBLIC`
-- untouched, and anon is a member of PUBLIC like everything else. So a grant on any single
-- object in this schema is reachable. Printed rather than asserted, because removing
-- PUBLIC's usage on a managed project breaks the provider's own tooling — the answer is to
-- hold no object grants, which the two blocks above check.
select nspname, coalesce(array_to_string(nspacl, ' '), '(default)') as schema_acl
  from pg_namespace where nspname = 'public';

\echo ''
\echo '--- and no provider role may hold BYPASSRLS with a grant on this schema'
select rolname, rolbypassrls
  from pg_roles
 where rolbypassrls
   and rolname <> current_user
 order by rolname;
\echo '(BYPASSRLS on a provider role is only safe while it has no grants above)'

\echo ''
\echo '=== 5. Isolation actually binds, asked as the application role ============'
-- The only check that proves the policy is BOUND rather than merely ENABLED. Run it in
-- a separate session AS uds_consent_app:
--
--   psql "postgresql://uds_consent_app:...@host:5432/db" -c "
--     select set_config('uds.entity_id', 'DENAVE_IN', false);
--     select count(*) as visible_to_denave from consent_event;
--     select set_config('uds.entity_id', 'MATRIX', false);
--     select count(*) as visible_to_matrix from consent_event;"
--
-- The two counts must differ once both entities hold events. Equal counts with data in
-- both means the policy is passing everything — which is what happens when the app
-- connects as the owner, or as a BYPASSRLS role.
\echo '(see the comment block above — this one runs in a separate session)'

\echo ''
\echo '=== 6. Flyway is at head and nothing failed ==============================='
select version, description, success, installed_on
  from flyway_schema_history
 order by installed_rank desc
 limit 5;
