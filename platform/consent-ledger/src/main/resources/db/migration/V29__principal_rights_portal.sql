-- =====================================================================================
-- A way in for the person the data is about.
--
-- Every route on this platform requires a credential. Every one. A data principal wanting to
-- exercise a right has to find somebody inside UDS who holds one and ask them to file it — which
-- means the group's ability to receive a rights request is the group's ability to answer its
-- phone.
--
-- DPDP **Rule 14(1)** requires a Data Fiduciary to prominently publish the means by which a
-- principal makes a request. NoticeStore carries rightsUri per notice version and the consent
-- receipt reproduces it, so every receipt the platform has ever issued points at a page. This is
-- that page's backend.
--
-- -------------------------------------------------------------------------------------
-- Two design decisions that are the whole of this migration.
--
-- FIRST: the intake route must not be able to tell anyone whether an identifier is known.
--
-- An unauthenticated endpoint that answers differently for a phone number the group holds and one
-- it does not is an oracle over regulated personal data, reachable by anyone, at the rate the
-- limiter allows. Somebody with a list of numbers learns which of them UDS has a file on — which
-- is a disclosure about every person on the list, made by the feature built to protect them.
--
-- So the response is constant. A reference is minted either way; a request is created only when
-- the identifier verifies. The unknown-identifier case walks the same code path and returns the
-- same shape, and PrincipalPortalIT asserts the two are byte-identical.
--
-- SECOND: the statutory clock starts at VERIFICATION, not at submission.
--
-- rights_request.received_at is when the principal asked, and StatutoryClock derives a deadline
-- from it that Rule 14(3) caps at ninety days. If an anonymous submission started that clock, then
-- anyone could burn the group's entire response window for somebody else — repeatedly, and without
-- ever proving they were that person. An unverified submission is not yet a request from the
-- principal. It becomes one when the token comes back.
--
-- -------------------------------------------------------------------------------------
-- What this table is not.
--
-- It is not evidence, and it is deliberately not append-only. Attempt counts and consumption are
-- mutable operational state — a token is used once and burns attempts on the way — and revoking
-- UPDATE here would mean modelling a counter as an insert stream to work around a guarantee it
-- does not need. The evidence is the rights_request this produces, which is subject to every
-- guarantee the evidence plane already has.
--
-- The platform still sends nothing. It mints the token, stores only its hash, and enqueues an
-- outbox message carrying the plaintext for whichever system sends messages. That boundary has
-- held since V1 and holds here: nothing in this platform has ever been able to reach a person.
-- =====================================================================================

create table rights_request_verification (
    -- What the principal is given and what they come back with. Opaque and random: it appears in
    -- a URL, so anything derived from the identifier would put a function of a phone number into
    -- browser history, proxy logs and referrer headers.
    reference     varchar(64)  primary key,
    entity_id     varchar(64)  not null references fiduciary_entity (entity_id),

    -- What was claimed, hashed with the platform's existing peppered hasher — the same path
    -- consent capture and suppression use. Never the raw value: an unauthenticated endpoint that
    -- wrote plaintext contact details into a table would be a collection point rather than a
    -- rights channel.
    identifier_type varchar(32) not null,
    identifier_hash varchar(64) not null,
    request_type  varchar(32)  not null,
    jurisdiction  varchar(8)   not null,

    -- Only the hash. A leaked backup of this table must not let the reader verify anybody's
    -- request; SHA-256 of the token with the same pepper, so the stored value is useless without
    -- the secret the database does not hold.
    token_hash    varchar(64)  not null,

    -- Null until verified. Set once, and it is the join to the evidence plane.
    request_id    varchar(64)  references rights_request (request_id),

    created_at    timestamptz  not null default now(),
    expires_at    timestamptz  not null,
    verified_at   timestamptz,

    -- Bounded guessing. A six-character token is short enough to be read over the phone and short
    -- enough to brute-force at HTTP speed; the attempt cap is what makes the first true without
    -- making the second.
    attempts      integer      not null default 0,

    constraint ck_rights_verification_type check (request_type in (
        'ACCESS', 'CORRECTION', 'COMPLETION', 'ERASURE', 'NOMINATION', 'GRIEVANCE',
        'CONSENT_WITHDRAWAL', 'PORTABILITY', 'OPT_OUT_OF_SALE')),
    -- A verified row must name the request it produced, and an unverified one must not claim to
    -- have produced one. The same shape of constraint as rights_request's closure rule, for the
    -- same reason: state that is "sometimes null" is how a report becomes quietly wrong.
    constraint ck_rights_verification_consumed check (
        (verified_at is null and request_id is null)
        or (verified_at is not null and request_id is not null))
);

-- Expiry sweeps and the operational question "is anybody submitting these".
create index idx_rights_verification_expiry on rights_request_verification (expires_at)
    where verified_at is null;

comment on table rights_request_verification is
    'Pending data-principal rights requests, before the person has proved they hold the identifier '
    'they filed under. DPDP Rule 14(1). The statutory clock starts on verification, not on '
    'submission — see the migration header.';

comment on column rights_request_verification.token_hash is
    'Peppered SHA-256 of the single-use token. The plaintext leaves the platform once, through the '
    'outbox, to whichever system sends messages. It is never stored and never logged.';

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'uds_consent_app') then
        raise notice 'role uds_consent_app not present; portal isolation NOT applied.';
        return;
    end if;

    -- UPDATE and DELETE deliberately NOT revoked: attempts and consumption are mutable state, and
    -- this table is not evidence. See the header.
    --
    -- Row-level security still applies, and here it does something the other policies do not. The
    -- intake route is unauthenticated, so it runs with no entity claim — group level — which is
    -- correct, because a person filing a request has no credential and the entity is a parameter
    -- of what they are filing. The policy binds every *credentialed* read: a Denave console
    -- cannot enumerate pending Matrix rights requests, which would otherwise disclose that a named
    -- person is in dispute with another group company.
    alter table rights_request_verification enable row level security;
    create policy uds_entity_isolation on rights_request_verification
        using (uds_entity_claim() is null or entity_id = uds_entity_claim())
        with check (uds_entity_claim() is null or entity_id = uds_entity_claim());
end
$$;
