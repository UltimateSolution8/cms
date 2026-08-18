-- =====================================================================================
-- Who actually did it.
--
-- admin_audit_event.actor_id has always held the authenticated API client — which for the
-- compliance console is one shared credential held by a team. The table records that
-- "compliance-console" retired a purpose, invalidated a consent, or assembled an evidence
-- bundle for a named data principal. It cannot say which person did, and attribution is the
-- entire purpose of an audit table.
--
-- The append-only guarantee makes this worse rather than better: the record is immutable, so
-- it is permanently and unfixably ambiguous. A Board asking "who authorised this" gets the
-- name of a service account.
--
-- -------------------------------------------------------------------------------------
-- The split.
--
-- Two facts, two columns, because they are different facts and a single column has to lie
-- about one of them:
--
--   client_id  — the credential the request authenticated with. Always known.
--   actor_id   — the human. Supplied by the console as X-UDS-Actor on every mutation, and
--                refused with 400 if absent.
--
-- Existing rows carry a client name in actor_id, so the backfill copies it into client_id and
-- leaves actor_id alone. That deliberately does NOT relabel history as anonymous: those rows
-- really were attributed to a credential, and rewriting them to say "unknown" would be an
-- append-only table being edited to look better than it was.
--
-- -------------------------------------------------------------------------------------
-- Why a header and not OIDC.
--
-- Because OIDC is not buildable here yet — spring-boot-starter-oauth2-resource-server is not
-- in the local Maven repository and this build is offline. A header asserted by the console is
-- weaker than a signed subject claim and is not pretending otherwise: it is trustworthy
-- exactly as far as the console is, which is a system inside the group's network holding an
-- ADMIN credential. What it buys today is that the console has to know who is driving it, and
-- that the ledger has somewhere to put the answer. When OIDC lands, the value comes from the
-- token's subject claim instead and nothing else about this schema changes — which is the
-- point of splitting the columns now rather than after.
-- =====================================================================================

alter table admin_audit_event add column client_id varchar(64);

update admin_audit_event set client_id = actor_id where client_id is null;

create index idx_admin_audit_actor on admin_audit_event (actor_id, occurred_at desc);

comment on column admin_audit_event.actor_id is
    'The human who took the action, asserted by the calling console as X-UDS-Actor. Rows written '
    'before V23 hold a client name here instead — they were attributed to a credential and the '
    'backfill does not rewrite history to pretend otherwise. Read alongside client_id.';

comment on column admin_audit_event.client_id is
    'The API credential the request authenticated with. Always known, and never sufficient on its '
    'own: the compliance console is one credential held by a team, so a table recording only this '
    'cannot answer who authorised anything.';
