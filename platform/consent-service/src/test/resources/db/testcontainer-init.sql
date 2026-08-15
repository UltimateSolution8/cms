-- Mirrors platform/docker/init/01-application-role.sql.
--
-- Present so that V2's privilege-revocation branch actually executes during the integration run.
-- Without the role, V2 takes its other path, logs a notice and skips the REVOKE statements — and a
-- mistake in them would then go unnoticed until the first production deployment, which is the one
-- place nobody wants to discover that the append-only grants never applied.
create role uds_consent_app with login password 'uds_consent_app';

grant usage on schema public to uds_consent_app;

alter default privileges in schema public
    grant select, insert, update, delete on tables to uds_consent_app;
alter default privileges in schema public
    grant usage, select on sequences to uds_consent_app;
