-- =====================================================================================
-- The Significant Data Fiduciary obligations, and a flag that finally does something.
--
-- -------------------------------------------------------------------------------------
-- What was wrong.
--
-- fiduciary_entity.significant_fiduciary has existed since V1. It is set by the seed, mapped
-- by EntityStore, and documented in its own javadoc as "set by government notification only".
-- It is read by nothing. grep across all four modules returns the migration, the seed, the
-- mapper and the record component, and no consumer anywhere.
--
-- So the platform held the designation and modelled none of what the designation means. If
-- Denave were notified an SDF — plausible, given the volume it processes for clients — the
-- group would have a boolean and nothing behind it, on obligations that run to an annual cycle
-- and are reported to the Board.
--
-- -------------------------------------------------------------------------------------
-- DPDP Rule 13, and what it actually asks for.
--
-- Four things, and they are not one thing with four names:
--
--   1. a Data Protection Impact Assessment, at least once every twelve months;
--   2. an independent data audit, on the same annual cycle;
--   3. algorithmic due diligence — a documented verification that the algorithmic systems used
--      to process personal data do not pose a risk to data principals' rights;
--   4. observations from (1) and (2) furnished to the Board.
--
-- The first two are entity-level and annual. The third is not: it is a check about a system,
-- and a group running three scored-ranking systems owes three answers rather than one. Hence
-- algorithmic_system as its own table rather than a third obligation_type on a yearly cycle.
--
-- -------------------------------------------------------------------------------------
-- Why the artefact is hashed.
--
-- Rule 13's requirement is that the evidence be available on audit. A register row pointing at
-- "DPIA_2026_final_v3.pdf" on somebody's drive is a register of assertions: the document can be
-- replaced, and the row would still read as satisfied. Hashing it makes the row evidence — the
-- same reasoning, and the same Hashes utility, as the consent ledger uses.
--
-- The document itself stays wherever the group keeps documents. This platform holds consent
-- evidence, not a DMS, and a table that accepted uploads would become one.
--
-- -------------------------------------------------------------------------------------
-- Rule 14 and the empty list.
--
-- The Government may, on a committee's recommendation, specify categories of personal data
-- that a Significant Data Fiduciary must not transfer outside India at all. As at today no
-- such categories are notified — checked, not assumed.
--
-- transfer_restricted is therefore a column that is false on every row on delivery. That is
-- deliberate. When a notification arrives, honouring it must be an update statement rather than
-- a release, and the RoPA cross-border report must already be consulting the column. A hook
-- built after the notification is a hook built in a hurry.
-- =====================================================================================

create table sdf_obligation (
    id               bigserial   primary key,
    entity_id        varchar(64) not null references fiduciary_entity (entity_id),
    obligation_type  varchar(32) not null,
    -- The twelve-month window the assessment or audit covers. Both dates, because "the 2026
    -- DPIA" is ambiguous the moment an entity is designated mid-year.
    period_start     date        not null,
    period_end       date        not null,
    due_at           timestamptz not null,
    completed_at     timestamptz,
    conducted_by     text,
    -- Where the document lives, and proof it is the document that was assessed.
    artefact_ref     text,
    artefact_sha256  char(64),
    board_reported_at timestamptz,
    findings         text,
    -- For ALGORITHMIC_DUE_DILIGENCE only: which system was checked.
    algorithmic_system_id bigint,
    created_at       timestamptz not null default now(),

    constraint ck_sdf_obligation_type check (
        obligation_type in ('DPIA', 'INDEPENDENT_AUDIT', 'ALGORITHMIC_DUE_DILIGENCE')),
    constraint ck_sdf_period check (period_end > period_start),
    -- A completed obligation must say who did it and point at something. Enforced here rather
    -- than in the service because a row marked complete with no evidence is the exact shape of
    -- the problem this table was built to end, and it should be impossible rather than
    -- discouraged.
    constraint ck_sdf_completion_evidenced check (
        completed_at is null
        or (conducted_by is not null and artefact_ref is not null
            and artefact_sha256 is not null)),
    -- NULLS NOT DISTINCT, and it has to be.
    --
    -- algorithmic_system_id is null for the two entity-level obligations, and Postgres treats
    -- nulls as distinct in a unique constraint by default — so without this clause two DPIA rows
    -- for the same entity and period would not conflict, ON CONFLICT DO NOTHING would never fire,
    -- and every pass of the raiser would add another copy. The register would grow by two rows a
    -- day and read as an entity falling steadily further behind on duties it had already met.
    constraint uq_sdf_obligation unique nulls not distinct (entity_id, obligation_type,
                                                            period_start, algorithmic_system_id)
);

create index idx_sdf_obligation_open on sdf_obligation (entity_id, due_at)
    where completed_at is null;

comment on table sdf_obligation is
    'DPDP Rule 13: the annual DPIA, the annual independent audit, and the algorithmic due '
    'diligence a Significant Data Fiduciary owes. Empty for every entity the Government has not '
    'notified, which is the correct answer rather than a hidden one.';

comment on column sdf_obligation.artefact_sha256 is
    'Hash of the assessment or audit report. Rule 13 requires the evidence to be available on '
    'audit; a reference with no hash is a register of assertions, because the document behind it '
    'can be replaced without the row changing.';

comment on column sdf_obligation.board_reported_at is
    'When the observations were furnished to the Board. A completed assessment nobody reported is '
    'half an obligation, and the two dates are separate so the gap is visible.';


-- -------------------------------------------------------------------------------------
-- The systems the diligence is about.
-- -------------------------------------------------------------------------------------

create table algorithmic_system (
    id               bigserial   primary key,
    entity_id        varchar(64) not null references fiduciary_entity (entity_id),
    name             varchar(128) not null,
    -- What it decides about people. Free text, because the interesting answer is never a
    -- category — "which prospects the dialer calls first" is the sentence an auditor needs.
    decides          text        not null,
    -- Which purposes it touches. Not a foreign key set: a system commonly spans several, and
    -- the join table would add a hop to a register nobody queries by purpose.
    purpose_codes    jsonb       not null default '[]'::jsonb,
    -- Whether it makes decisions that PIPA treats as automated decision-making, which carries
    -- its own separate-consent requirement. This is the join PurposeDefinition
    -- .requiresSeparateConsent has always implied and never had a source for.
    automated_decision_making boolean not null default false,
    last_diligence_at timestamptz,
    owner            text,
    active           boolean     not null default true,
    created_at       timestamptz not null default now(),

    constraint uq_algorithmic_system unique (entity_id, name)
);

comment on table algorithmic_system is
    'The algorithmic systems processing personal data, per entity. Rule 13 asks a Significant Data '
    'Fiduciary to verify that these do not pose a risk to data principals'' rights; a register is '
    'the precondition for verifying anything, and a group that cannot list its systems cannot '
    'assert they are safe.';

alter table sdf_obligation
    add constraint fk_sdf_obligation_system
        foreign key (algorithmic_system_id) references algorithmic_system (id) on delete cascade;


-- -------------------------------------------------------------------------------------
-- Rule 14: the restriction hook, deliberately empty.
-- -------------------------------------------------------------------------------------

alter table data_category
    add column transfer_restricted boolean not null default false,
    add column transfer_restriction_ref text;

comment on column data_category.transfer_restricted is
    'DPDP Rule 14: whether the Government has specified this category as one a Significant Data '
    'Fiduciary may not transfer outside India. False on every row as at August 2026 because no '
    'categories are notified — checked, not assumed. The RoPA cross-border report reads this '
    'column already, so honouring a future notification is an update rather than a release.';

comment on column data_category.transfer_restriction_ref is
    'The notification that imposed the restriction. A restriction nobody can cite is one nobody '
    'can lift when it is withdrawn.';


do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; SDF grants and policies NOT applied.';
        return;
    end if;

    execute 'grant select, insert, update on sdf_obligation to uds_consent_app';
    execute 'grant usage, select on sequence sdf_obligation_id_seq to uds_consent_app';
    execute 'grant select, insert, update on algorithmic_system to uds_consent_app';
    execute 'grant usage, select on sequence algorithmic_system_id_seq to uds_consent_app';

    -- Entity-scoped, like everything else that names an entity's own affairs. An SDF register
    -- discloses which group companies are under a designation and how far behind they are on
    -- it, which is not something one entity should read about another.
    execute 'alter table sdf_obligation enable row level security';
    execute $f$
        create policy uds_entity_isolation on sdf_obligation
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;

    execute 'alter table algorithmic_system enable row level security';
    execute $f$
        create policy uds_entity_isolation on algorithmic_system
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;
end
$$;
