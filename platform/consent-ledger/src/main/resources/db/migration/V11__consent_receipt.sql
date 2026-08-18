-- =====================================================================================
-- Consent receipts, made durable.
--
-- ConsentReceipt's javadoc has always described receiptId as "a stable identifier the
-- subject can quote in a grievance". It was neither: ReceiptService.issue minted a fresh
-- UUID on every call and persisted nothing, so two requests a second apart produced two
-- different identifiers for the same facts and neither could be looked up afterwards. A
-- principal quoting their receipt number to the grievance officer would have been quoting a
-- number that existed nowhere.
--
-- What makes an identifier worth quoting is that somebody can fetch it and get back exactly
-- what was issued — which is why the canonical payload is stored verbatim alongside its hash
-- rather than the receipt being regenerated on read. Regeneration would produce today's
-- answer to a question about last year: the purpose registry moves, the entity's DPO contact
-- changes, and a consent that was live in March has since expired. All of those would rewrite
-- a document the subject is holding a copy of.
--
-- -------------------------------------------------------------------------------------
-- Retention: seven years.
--
-- The only figure an Indian regulator has put on paper for consent records — the First
-- Schedule, Part B, binding on Consent Managers rather than on us, but the nearest thing to
-- an authority that exists. It comfortably exceeds Rule 6's one-year floor for processing
-- logs. The table is append-only regardless, so this is a statement of intent for whatever
-- archival policy eventually runs over it rather than something enforced here.
-- =====================================================================================

create table consent_receipt (
    receipt_id        varchar(64) primary key,
    entity_id         varchar(64) not null references fiduciary_entity (entity_id),
    subject_id        varchar(64) not null,
    issued_at         timestamptz not null,
    -- The document as issued, canonicalised by the same code that builds the ledger's hash
    -- chain. Stored rather than regenerated, for the reason above.
    payload           text        not null,
    -- SHA-256 of the payload. Lets a subject or an auditor verify a copy they were sent
    -- against what the platform holds, using the same verification path the chain uses.
    payload_hash      char(64)    not null,
    -- The ledger event the receipt attests to, so a receipt can be tied back into the chain.
    evidence_hash     char(64),
    notice_id         varchar(64),
    notice_version    int,
    language_tag      varchar(16),
    purpose_count     int         not null default 0,
    created_at        timestamptz not null default now()
);

comment on table consent_receipt is
    'Receipts as issued, byte-for-byte. Fetching one by id returns what the subject was given, '
    'not what the same query would produce today.';

create index idx_consent_receipt_subject
    on consent_receipt (entity_id, subject_id, issued_at desc);

-- Same trigger family as the rest of the evidence plane. A receipt the platform can quietly
-- amend is a receipt whose number is worth nothing to the person holding it.
create trigger trg_consent_receipt_no_update
    before update on consent_receipt
    for each row execute function evidence_row_is_immutable();

create trigger trg_consent_receipt_no_delete
    before delete on consent_receipt
    for each row execute function evidence_row_is_immutable();

do $$
begin
    if exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        grant insert, select on consent_receipt to uds_consent_app;
        revoke update, delete, truncate on consent_receipt from uds_consent_app;
        raise notice 'append-only grants applied to consent_receipt';
    else
        raise notice 'role uds_consent_app not present; consent_receipt grants NOT applied.';
    end if;
end
$$;
