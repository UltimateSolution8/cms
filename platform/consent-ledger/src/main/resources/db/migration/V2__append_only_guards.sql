-- =====================================================================================
-- Append-only enforcement for the evidence plane.
--
-- The consent ledger is the artefact the burden of proof rests on. If a row in it can be
-- edited, none of it proves anything. Three independent layers guard that, on the principle
-- that any single one of them can be defeated by someone with enough privilege:
--
--   1. Triggers below reject UPDATE, DELETE and TRUNCATE outright.
--   2. Grants (further down) remove those privileges from the application role, so the
--      running service could not attempt them even if a code path tried.
--   3. The hash chain in the application layer detects any alteration that got past both —
--      which is the layer that still works against a compromised superuser, because the
--      attacker would have to recompute every subsequent hash in the subject's chain.
--
-- Layer 3 is why the chain exists at all. A determined superuser can disable a trigger
-- (ALTER TABLE ... DISABLE TRIGGER, or session_replication_role = 'replica'); they cannot
-- quietly rewrite history without the nightly verification job noticing.
-- =====================================================================================

create or replace function evidence_row_is_immutable() returns trigger as $$
begin
    raise exception
        'consent ledger is append-only: % rejected on table %',
        tg_op, tg_table_name
        using errcode = '42501',
              hint = 'Record a compensating event instead. Consent is corrected by appending, '
                     'never by editing. See ConsentLedger#append.';
end;
$$ language plpgsql;

create or replace function evidence_table_is_immutable() returns trigger as $$
begin
    raise exception
        'consent ledger is append-only: TRUNCATE rejected on table %', tg_table_name
        using errcode = '42501';
end;
$$ language plpgsql;


create trigger trg_consent_event_no_update
    before update on consent_event
    for each row execute function evidence_row_is_immutable();

create trigger trg_consent_event_no_delete
    before delete on consent_event
    for each row execute function evidence_row_is_immutable();

create trigger trg_consent_event_no_truncate
    before truncate on consent_event
    for each statement execute function evidence_table_is_immutable();


-- The administrative audit trail is evidence too. An administrator who can delete the record
-- of their own action leaves the ledger technically intact and practically worthless.
create trigger trg_admin_audit_no_update
    before update on admin_audit_event
    for each row execute function evidence_row_is_immutable();

create trigger trg_admin_audit_no_delete
    before delete on admin_audit_event
    for each row execute function evidence_row_is_immutable();


-- Immutable published artefacts. A notice version or purpose version that can be edited after
-- publication destroys the ability to reproduce what a subject actually saw — which is the
-- single most important thing the evidence plane has to do.
create trigger trg_notice_version_no_update
    before update on notice_version
    for each row execute function evidence_row_is_immutable();

create trigger trg_notice_translation_no_update
    before update on notice_translation
    for each row execute function evidence_row_is_immutable();

create trigger trg_purpose_version_no_update
    before update on purpose_version
    for each row execute function evidence_row_is_immutable();


-- -------------------------------------------------------------------------------------
-- Layer 2: privilege removal.
--
-- Runs only if the application role exists, so that the migration is safe on a developer
-- machine where everything runs as the owner. Provisioning must create this role and connect
-- the service as it — see docs/OPERATIONS.md. A deployment that skips this still has layers
-- 1 and 3, but loses defence in depth and should not be considered production-ready.
-- -------------------------------------------------------------------------------------

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        revoke update, delete, truncate on consent_event      from uds_consent_app;
        revoke update, delete, truncate on admin_audit_event   from uds_consent_app;
        revoke update, delete, truncate on notice_version      from uds_consent_app;
        revoke update, delete, truncate on notice_translation  from uds_consent_app;
        revoke update, delete, truncate on purpose_version     from uds_consent_app;
        raise notice 'append-only grants applied to role uds_consent_app';
    else
        raise notice
            'role uds_consent_app not present; append-only grants NOT applied. '
            'Create the role and re-run V2 verification before going to production.';
    end if;
end
$$;
