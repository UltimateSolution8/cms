-- =====================================================================================
-- The register becomes something an administrator can maintain.
--
-- V14 created consent_manager and left it writable only by migration. The reasoning was
-- sound as far as it went — a fiduciary that could edit the list of who is registered could
-- authorise its own relays — but it produced a control that cannot be operated.
--
-- Rule 4 gives the Board power to suspend or cancel a registration after a hearing. When it
-- does, UDS has to stop honouring that Consent Manager's relays *that day*. As things stood
-- that needed a DBA with a psql session and schema rights, at whatever hour the notice
-- arrived, leaving no admin_audit_event and no way to rehearse the procedure beforehand. The
-- practical result is not a safer register; it is a register nobody updates, which is the
-- failure that matters: honouring relays from a Consent Manager the Board removed last month.
--
-- So the write path is narrowed rather than absent. ADMIN only, at the HTTP layer and in the
-- filter chain, and every status change appends to admin_audit_event like every other
-- consequential write — because "who suspended this one, when, and on what authority" is
-- exactly the question asked after a relay that should not have been honoured.
--
-- -------------------------------------------------------------------------------------
-- last_reconciled_at, and being honest about what it is.
--
-- This table is a *copy* of the Board's register. There is no feed to poll — the Board
-- publishes no API — so the copy can only be as fresh as the last time a person compared it
-- with the published list. A copy with no staleness signal is a copy that rots silently, and
-- the failure looks exactly like normal operation.
--
-- The column therefore records a human's reconciliation rather than a machine's sync, and the
-- health indicator surfaces the oldest one. That is a weaker control than an automatic feed
-- and it is the true one; a column called last_synced_at would imply a mechanism that does
-- not exist.
-- =====================================================================================

alter table consent_manager
    add column last_reconciled_at timestamptz,
    add column last_reconciled_by varchar(128);

comment on column consent_manager.last_reconciled_at is
    'When a person last compared this entry against the Board''s published register. Not a sync '
    'timestamp: the Board publishes no feed, so this records a human check and nothing else. The '
    'health indicator reports the oldest value across the register.';

comment on column consent_manager.last_reconciled_by is
    'Who performed that comparison. A reconciliation nobody is named for is a reconciliation '
    'nobody did.';

-- The application may now maintain the register. Still no DELETE: a Consent Manager that has
-- ever relayed is referenced by consent_manager_link and by events in the ledger, so removing
-- the row would orphan the provenance of consents that are still live. Deregistration is a
-- status, which is what the Board's own vocabulary calls it.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; register grants NOT applied.';
        return;
    end if;
    execute 'grant insert, update on consent_manager to uds_consent_app';
end
$$;

-- The two seeds V14 introduced and the one V15 added are fixtures, and REGULATORY_HANDOFF.md
-- asks somebody to retire them before go-live. Marking them reconciled by nobody, at no time,
-- is deliberate: they will show up as the oldest entries in the health report and stay there
-- until a person deals with them, which is the behaviour wanted from a go-live checklist item
-- that must not be forgotten.
