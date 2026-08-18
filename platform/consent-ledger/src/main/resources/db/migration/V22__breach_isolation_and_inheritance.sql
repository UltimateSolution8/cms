-- =====================================================================================
-- Two things this schema said were true and were not.
--
-- 1. breach_notification was outside both isolation layers.
-- 2. parent_entity_id claims policy inheritance that nothing implements, against a purpose
--    registry that has no per-entity configuration to inherit.
--
-- Both were recorded honestly — in EntityAccessGuard's javadoc and in the regulatory hand-off
-- respectively — which is better than hiding them and is not the same as fixing them.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- 1. breach_notification joins the isolation model.
--
-- The one gap EntityAccessGuard's own javadoc names. The table is scoped through breach_id
-- and carries no entity_id, so neither layer constrains GET /v1/admin/breaches/{breachId}/…
-- for a scoped credential that has guessed or been told a breach id. Layer one structurally
-- cannot help: the path carries an opaque row id, and there is nothing in it to compare
-- against a claim.
--
-- So give layer two something to bind. The column is derived from the parent breach rather
-- than supplied by a caller — a notification belongs to exactly one breach and a breach
-- belongs to exactly one entity, so any other value would be a bug, and the insert below
-- makes it impossible to write one.
--
-- What this discloses if left open is not abstract. A notification row names the party told,
-- the deadline, the method, the reference and the recipient count — which is to say, the shape
-- and scale of another group company's worst week, and whether they met the Rule 7 clock.
-- -------------------------------------------------------------------------------------

alter table breach_notification add column entity_id varchar(64);

update breach_notification n
   set entity_id = b.entity_id
  from personal_data_breach b
 where b.breach_id = n.breach_id;

alter table breach_notification
    alter column entity_id set not null,
    add constraint fk_breach_notification_entity
        foreign key (entity_id) references fiduciary_entity (entity_id);

create index idx_breach_notification_entity on breach_notification (entity_id);

comment on column breach_notification.entity_id is
    'The fiduciary the parent breach belongs to. Denormalised from personal_data_breach so the '
    'row-level isolation policy has a column to bind — layer one cannot help here, because the '
    'route carries an opaque notification id and there is nothing in the path to check.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; isolation policy for breach_notification NOT '
            'applied. Create the role and re-run before going to production.';
        return;
    end if;

    alter table breach_notification enable row level security;

    -- Identical in shape to V13's and V16's, and identical on purpose: one policy covering all
    -- commands, so a credential can never insert a row it cannot then read.
    create policy uds_entity_isolation on breach_notification
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;


-- -------------------------------------------------------------------------------------
-- 2. Policy inheritance, restated as what it actually is.
--
-- V1 says: "Policy inheritance walks this chain: an entity without its own purpose
-- configuration inherits its parent's." That describes a schema this platform does not have.
-- purpose and purpose_version carry no entity_id at all — the taxonomy is group-wide by
-- design, and correctly so: fifteen entities each maintaining their own copy of MARKETING_CALL
-- is how two of them end up with different retention on the same activity.
--
-- So there is no per-entity purpose configuration to inherit, and the sentence has been false
-- since it was written. Implementing what it describes would mean entity-scoping the purpose
-- registry to satisfy a comment, which is the wrong direction.
--
-- What IS per-entity, and what the chain is genuinely for, is the entity's own published
-- contact points. And that mattered more than a stale comment usually does: V3 seeds fifteen
-- entities and gives not one of them a dpo_contact or a grievance_uri, while ReceiptService
-- puts entity.dpoContact() straight into the ISO/IEC TS 27560 receipt and falls back to
-- entity.grievanceUri() when the notice carries none. Every receipt the platform has ever
-- issued names a null contact point and a null grievance route — which DPDP Rule 3 requires
-- the notice to carry and which the receipt is supposed to reproduce.
--
-- Resolving up the chain means UDS sets its contacts once and all fifteen are covered, and
-- keep being covered: an acquisition onboarded tomorrow inherits from the moment its row
-- exists, which is what the parent link was always for.
--
-- This migration deliberately seeds NO contact values. Inventing a DPO address to make a
-- column non-null would put a fabricated contact point on a statutory artefact — a receipt
-- naming an inbox nobody reads is worse than one naming none, because it looks discharged.
-- EntityContactCheck logs at start-up which entities still resolve to nothing, so the gap is
-- visible and closeable rather than silent. Setting the group root closes all of them:
--
--   update fiduciary_entity set dpo_contact = ?, grievance_uri = ? where entity_id = 'UDS';
-- -------------------------------------------------------------------------------------

comment on column fiduciary_entity.parent_entity_id is
    'The group hierarchy, and the chain contact resolution walks: an entity with no dpo_contact '
    'or grievance_uri of its own answers with its nearest ancestor''s, so an acquisition is '
    'covered from the moment its row exists. It does NOT carry purpose inheritance — the purpose '
    'taxonomy is group-wide and has no entity_id — and V1''s comment saying otherwise described '
    'a schema this platform deliberately does not have. See EntityStore.resolveContacts.';

comment on column fiduciary_entity.dpo_contact is
    'Published contact point for the Data Protection Officer. Null means "ask the parent" rather '
    'than "there is none" — resolution walks parent_entity_id. A subsidiary sets its own only '
    'where it genuinely has a separate one.';

comment on column fiduciary_entity.grievance_uri is
    'Where a data principal complains. DPDP Rule 3 requires the notice to carry it and the '
    'receipt reproduces it, so a null here that resolved to nothing produced a receipt that '
    'failed the Rule. Resolved up parent_entity_id for the same reason as dpo_contact.';
