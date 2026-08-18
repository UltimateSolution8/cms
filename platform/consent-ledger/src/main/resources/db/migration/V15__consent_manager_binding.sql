-- =====================================================================================
-- A relay must prove its registration number, not assert it.
--
-- V14 built the Consent Manager path and checked, on every relay, that the registration
-- number on the request belonged to a Consent Manager that was on the register and active.
-- What it never checked was that the number belonged to *the caller*. The credential and the
-- registration number were two independent facts and nothing joined them, so any credential
-- holding the CONSENT_MANAGER role could relay grants and withdrawals under any other
-- registration on the register — and the ledger would record the other CM's number as the
-- actor, in the one plane whose entire purpose is recording who did what.
--
-- ConsentManagerStore.findByClient() was written for exactly this join and was called from
-- nowhere. The check is now made in ConsentManagerRelayService and there is no unbound variant
-- of it left to call.
--
-- -------------------------------------------------------------------------------------
-- Why this needs a migration at all.
--
-- The refusal is recorded as evidence, like the other two, and it needs its own reason so
-- that an investigator can tell "somebody we have never heard of" from "somebody we have,
-- claiming to be somebody else". Those are different incidents with different responses.
--
-- V14's enforcement_decision_shape constraint named CONSENT_MANAGER_NOT_REGISTERED as the one
-- reason permitted to omit a purpose and a jurisdiction. A binding refusal happens at the same
-- point — before any question about a principal, a purpose or a jurisdiction has been asked —
-- so it needs the same exemption. The constraint is replaced rather than widened in place
-- because a check constraint cannot be altered.
--
-- The exemption stays an explicit list of two rather than becoming a shape rule. A rule like
-- "any reason starting CONSENT_MANAGER_" would silently exempt the next one somebody adds, and
-- the guarantee being protected here — that a genuine decision about processing always names
-- its purpose and its jurisdiction — is worth restating by hand each time it acquires an
-- exception.
-- =====================================================================================

alter table enforcement_decision drop constraint enforcement_decision_shape;

alter table enforcement_decision add constraint enforcement_decision_shape
    check (
        reason in ('CONSENT_MANAGER_NOT_REGISTERED', 'CONSENT_MANAGER_NOT_BOUND')
        or (purpose_code is not null and jurisdiction is not null)
    );

comment on constraint enforcement_decision_shape on enforcement_decision is
    'Every decision about processing names its purpose and jurisdiction. The exceptions are the '
    'refused Consent Manager relays, which are refused before either has been asked about: an '
    'unregistered or suspended registration, and a registration the calling credential does not '
    'hold.';

-- -------------------------------------------------------------------------------------
-- A second *active* test registration, and why one was not enough.
--
-- V14 seeded one registered CM and one deregistered one, which is sufficient to test status
-- and useless for testing binding. A relay from the registered credential naming the
-- deregistered number is refused for being deregistered — the binding check never runs, and a
-- test asserting the 403 would pass whether the binding existed or not.
--
-- Proving the binding needs two registrations that are both active and held by different
-- credentials, so that status is held constant and the only thing left to refuse on is who is
-- asking. Named like its siblings so nobody mistakes it for a Board registration, and retired
-- through the same go-live step.
-- -------------------------------------------------------------------------------------
insert into consent_manager (registration_id, name, status, api_client_id, registered_at,
                             contact_email)
values ('CM-TEST-0003', 'UDS pilot second test Consent Manager (not a Board registration)',
        'REGISTERED', 'cm-other-client', timestamptz '2026-11-13T00:00:00Z',
        'privacy@uds.example')
on conflict (registration_id) do nothing;
