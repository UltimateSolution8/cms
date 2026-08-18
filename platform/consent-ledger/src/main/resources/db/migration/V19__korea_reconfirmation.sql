-- =====================================================================================
-- Korea: the two-year re-confirmation of consent to receive advertising information.
--
-- -------------------------------------------------------------------------------------
-- Why this exists now and did not before.
--
-- Plans v5 and v6 both recorded this as an open question and deliberately built nothing,
-- because the period could not be established from primary text and a guessed interval would
-- have been worse than an absent one. It can be established now.
--
-- Enforcement Decree of the Information and Communications Network Act, Article 62-3
-- (수신동의 여부의 확인): a sender who obtained prior consent under Article 50(1) or 50(3) must
-- confirm the recipient's consent status "그 수신동의를 받은 날부터 2년마다" — every two years from
-- the date consent was obtained, measured to the same date in the second year. Article 62-3(2)
-- requires that confirmation to disclose three things: the sender's name, the fact and date of
-- the recipient's consent, and the method of indicating an intention to maintain or withdraw
-- it.
--
-- The Network Act amendment in force 1 October 2026 strengthens the rules on transmitting
-- commercial information around this obligation, which is why it is being built six weeks
-- ahead of that date rather than at leisure.
--
-- -------------------------------------------------------------------------------------
-- Three disclosure columns, and why they are columns.
--
-- The obligation is not "we sent something". It is "we sent something containing these three
-- things". A row recording only sent_at could not discharge it — it would evidence an act
-- whose content is exactly what the Decree specifies and this table would not know. So the
-- three are recorded as sent, not as a template reference: templates change, and the question
-- asked in 2029 will be what this recipient was actually told in 2026.
--
-- -------------------------------------------------------------------------------------
-- What this table does NOT decide.
--
-- Article 62-3 prescribes the interval and the disclosure. It does not say what follows from
-- silence — whether a recipient who does not answer is treated as maintaining consent or as
-- having withdrawn it. Industry practice treats silence as maintenance; practice is not text,
-- and the difference is a Korean counsel question recorded in REGULATORY_HANDOFF.md.
--
-- So this platform tracks and evidences the check and stops there. An overdue row does not
-- expire a consent and does not deny a decision. Building the industry practice as though it
-- were the statute would be inventing law against the group's own interest, and doing the
-- opposite would silently suppress lawful contact. Both are worse than a visible gap.
--
-- Deliberately not modelled as expiry. ExpiryPolicy answers "when does this consent stop being
-- valid"; the answer here is "it does not, but somebody owes an affirmative check". Reusing the
-- expiry machinery would have collapsed those into one and made the honest position
-- unexpressible.
-- =====================================================================================

create table consent_reconfirmation (
    id             bigserial   primary key,
    entity_id      varchar(64) not null references fiduciary_entity (entity_id),
    subject_id     varchar(64) not null,
    purpose_code   varchar(64) not null,
    -- The date the two-year clock runs from. Art. 62-3(1) measures from the date consent was
    -- obtained, not from the last contact and not from the last confirmation.
    consented_at   timestamptz not null,
    due_at         timestamptz not null,
    status         varchar(24) not null default 'DUE',

    -- Art. 62-3(2). What was actually disclosed, recorded as sent.
    sender_name            text,
    disclosed_consent_date timestamptz,
    withdrawal_method      text,

    channel        varchar(32),
    sent_at        timestamptz,
    responded_at   timestamptz,
    completed_by   text,
    note           text,
    raised_at      timestamptz not null default now(),

    -- MAINTAINED and WITHDRAWN are the two answers a recipient can give. NOT_APPLICABLE covers
    -- a consent withdrawn or expired before its confirmation fell due, which is a closed row
    -- rather than an outstanding obligation. There is deliberately no status meaning "we sent
    -- it and heard nothing" that resolves the consent either way — see the header.
    constraint ck_reconfirmation_status check (
        status in ('DUE', 'SENT', 'MAINTAINED', 'WITHDRAWN', 'NOT_APPLICABLE')),
    -- One open row per subject, purpose and due date. The sweeper runs on a timer; without this
    -- it would raise the same obligation on every tick.
    constraint uq_reconfirmation unique (entity_id, subject_id, purpose_code, due_at)
);

create index idx_reconfirmation_due on consent_reconfirmation (due_at)
    where status in ('DUE', 'SENT');
create index idx_reconfirmation_subject
    on consent_reconfirmation (entity_id, subject_id, purpose_code);

comment on table consent_reconfirmation is
    'Korea, Network Act Enforcement Decree Art. 62-3: the two-yearly confirmation that a recipient '
    'still consents to receive advertising information. Records the obligation, what was disclosed '
    'and what the recipient answered. An overdue row is a compliance gap, not an expired consent — '
    'the Decree does not say what silence means and this platform does not decide it.';

comment on column consent_reconfirmation.due_at is
    'Two years from consented_at, to the same calendar date. Not 730 days: the two differ across a '
    'leap year and the Decree names the date.';

comment on column consent_reconfirmation.sender_name is
    'Art. 62-3(2), first required disclosure. Recorded as sent rather than referenced from a '
    'template, because templates change and the question asked later is what this recipient was told.';

comment on column consent_reconfirmation.withdrawal_method is
    'Art. 62-3(2), third required disclosure: how the recipient may indicate maintenance or '
    'withdrawal. A confirmation without it does not discharge the obligation.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice
            'role uds_consent_app not present; grants and isolation policy for '
            'consent_reconfirmation NOT applied.';
        return;
    end if;

    -- Updatable, unlike the evidence plane. This is a work queue: a row moves DUE -> SENT ->
    -- MAINTAINED as a human works it. The immutable record of what the recipient decided is the
    -- consent event their answer produces, which is chained; this table tracks the asking.
    execute 'grant select, insert, update on consent_reconfirmation to uds_consent_app';
    execute 'grant usage, select on sequence consent_reconfirmation_id_seq to uds_consent_app';

    execute 'alter table consent_reconfirmation enable row level security';
    execute $f$
        create policy uds_entity_isolation on consent_reconfirmation
            using (uds_entity_claim() is null or entity_id = uds_entity_claim())
            with check (uds_entity_claim() is null or entity_id = uds_entity_claim())
    $f$;
end
$$;
