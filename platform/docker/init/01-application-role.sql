-- Creates the role the application connects as.
--
-- This runs before Flyway, which matters: V2 revokes UPDATE, DELETE and TRUNCATE on the evidence
-- tables from uds_consent_app, and it can only do that if the role already exists. V2 skips the
-- revocation with a notice when it does not — so a database provisioned without this file still
-- has the triggers and the hash chain, but has quietly lost one of the three layers.
--
-- The separation is the point. Migrations run as the owner, which must be able to create and
-- alter tables. The service runs as a role that can insert into the ledger and read from it, and
-- cannot edit history even if a code path tried.

create role uds_consent_app with login password 'uds_consent_app';

grant connect on database uds_consent to uds_consent_app;
grant usage on schema public to uds_consent_app;

-- Applied to what Flyway will create, not to what exists now — at this point nothing does.
alter default privileges in schema public
    grant select, insert, update, delete on tables to uds_consent_app;
alter default privileges in schema public
    grant usage, select on sequences to uds_consent_app;
alter default privileges in schema public
    grant execute on functions to uds_consent_app;

-- The blanket grant above is deliberately broad, and V2 then takes back exactly what must never
-- be available: UPDATE, DELETE and TRUNCATE on consent_event, admin_audit_event, notice_version,
-- notice_translation and purpose_version. Granting broadly and revoking precisely keeps the list
-- of things that are forbidden in one place, next to the reasoning for each.
