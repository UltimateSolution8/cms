-- =====================================================================================
-- A rotation procedure that has a mechanism behind it.
--
-- OPERATIONS.md §2.2 tells an operator to publish the retired verification key alongside the
-- new one during a rotation. SigningKeys.verificationKeys() says the same thing in its javadoc
-- — "publishing the retired key alongside the new one for one snapshot lifetime is what makes
-- rotation a non-event" — and returns Map.of(keyId, publicKey): exactly one entry, from
-- configuration, with nowhere for a second to come from. GET /v1/keys returns one key.
--
-- So the runbook describes something the platform cannot do. Rotating today means every
-- snapshot signed by the outgoing key stops verifying the moment the new one is configured,
-- and a field device holding one is a device that silently stops enforcing.
--
-- -------------------------------------------------------------------------------------
-- Public halves only.
--
-- The private key is not here and must never be. This table holds what a verifier needs — the
-- key id, the algorithm, the public point — plus the lifecycle a verifier has to respect. The
-- private half stays in the process environment today and in a KMS when there is one; that
-- move changes SigningKeys and nothing in this schema, which is the point of separating them
-- now rather than after the first rotation.
--
-- -------------------------------------------------------------------------------------
-- Three states, and the difference between two of them is the whole design.
--
--   ACTIVE      — may sign, and verifies.
--   RETIRED     — no longer signs, still verifies. This is the state that did not exist, and
--                 without it rotation is a cliff rather than an overlap.
--   COMPROMISED — must NOT verify. Different from retired in the only way that matters: a
--                 retired key's signatures are still good evidence of what the platform said,
--                 and a compromised key's are not evidence of anything.
--
-- Retirement is an explicit administrative act, never inferred. Start-up registers the key the
-- instance is holding and retires nothing — during a rolling deploy two instances legitimately
-- hold different keys, and an instance that auto-retired "the other one" would revoke a key
-- its sibling is still signing with.
-- =====================================================================================

create table signing_key (
    key_id            varchar(64) primary key,
    algorithm         varchar(32) not null default 'Ed25519',
    -- X.509 SubjectPublicKeyInfo, base64. The same encoding
    -- uds.consent.snapshot.verification-key-base64 takes, so a key can be moved between
    -- configuration and this table without re-encoding it by hand at 3 a.m.
    public_key_base64 text        not null,
    state             varchar(16) not null default 'ACTIVE',
    activated_at      timestamptz not null default now(),
    retired_at        timestamptz,
    retired_by        text,
    notes             text,
    constraint ck_signing_key_state check (state in ('ACTIVE', 'RETIRED', 'COMPROMISED')),
    -- A retired or compromised key must say when it stopped being trusted for signing. Without
    -- the date, "was this snapshot signed before or after we pulled the key" is unanswerable,
    -- and that is precisely the question an incident asks.
    constraint ck_signing_key_retired_dated check (
        state = 'ACTIVE' or retired_at is not null)
);

create index idx_signing_key_state on signing_key (state, activated_at desc);

comment on table signing_key is
    'Public halves of the Ed25519 keys that sign offline consent snapshots, with their '
    'lifecycle. The private key is never stored here. RETIRED keys still verify — that overlap '
    'is what makes rotation a non-event rather than a cliff for every device holding a snapshot '
    'signed moments before.';

comment on column signing_key.state is
    'ACTIVE signs and verifies. RETIRED verifies only. COMPROMISED does neither — and the '
    'difference from RETIRED is the point: a retired key''s signatures remain good evidence of '
    'what the platform asserted, and a compromised key''s prove nothing.';

-- Not append-only, unlike the ledger. A key's state is a lifecycle and has to move: retiring
-- one is an update, and forcing it to be an insert would mean reconstructing current state from
-- a history every verification. What must not be silently editable is the record that somebody
-- retired it — and that lands in admin_audit_event, which is append-only, on every transition.
-- Same split as personal_data_breach: the mutable document and the immutable trail side by side.
do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; signing_key grants NOT applied.';
        return;
    end if;
    -- Deliberately no row-level security: a verification key is public by construction, and
    -- GET /v1/keys serves it without a credential. Scoping it per entity would break exactly the
    -- device that has lost its credential and still needs to verify what it holds.
    execute 'grant select, insert, update on signing_key to uds_consent_app';
end
$$;
