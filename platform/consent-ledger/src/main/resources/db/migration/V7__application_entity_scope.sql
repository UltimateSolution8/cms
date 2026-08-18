-- =====================================================================================
-- Which entities a surface may act for.
--
-- The application registry has always carried one entity_id: the entity that owns the
-- surface. That is the right answer to "whose system is this", and the wrong answer to
-- "whose data may it ask about" — and until the decision path started reading applicationId
-- at all, nothing had to distinguish them.
--
-- The distinction is not hypothetical, and the seed already contained the case. ATHENA_DIALER
-- belongs to Athena BPO and its whole function is to pre-flight calls for Denave's campaigns.
-- Under a flat ownership rule that surface is denied every request it exists to make. The
-- alternatives were both worse than a table: re-register the dialer under Denave, which makes
-- the registry lie about who operates it and breaks attribution during an incident; or drop
-- the entity check, which discards the one control that catches a credential from one entity
-- being replayed against another.
--
-- So ownership stays on application_registry and reach lives here. An outsourced-services
-- group is exactly the shape that needs the two separated: shared operational systems are the
-- business model, not an exception to it.
--
-- Note what this deliberately does NOT grant. Scope is per entity and enumerated. There is no
-- wildcard and no inheritance down the ownership tree — UDS owning 89.57% of Denave India does
-- not make a UDS surface entitled to Denave's data principals, because the fiduciary is the
-- company that determined the purpose, not its holding company.
-- =====================================================================================

create table application_entity_scope (
    application_id varchar(64) not null references application_registry (application_id)
                                   on delete cascade,
    entity_id      varchar(64) not null references fiduciary_entity (entity_id),
    -- Why this surface may act for this entity. Read during an audit of who could see what,
    -- which is a question asked after an incident and never answerable from a join table.
    rationale      text,
    granted_at     timestamptz not null default now(),
    primary key (application_id, entity_id)
);

comment on table application_entity_scope is
    'Entities an application may submit consent for and ask decisions about. Owning entity is '
        'on application_registry; this is reach. Enumerated, never wildcarded.';

-- Every surface reaches its own entity. Without this row the table would have to be read as
-- "scope if present, ownership otherwise", and a fallback that only applies to rows nobody
-- has touched is a rule that changes meaning the first time somebody edits one.
insert into application_entity_scope (application_id, entity_id, rationale)
select application_id, entity_id, 'Owning entity'
  from application_registry
on conflict do nothing;

-- Athena BPO operates the predictive dialer for Denave's outbound campaigns. This is the
-- arrangement the registry's own description records, and it is the reason this table exists.
insert into application_entity_scope (application_id, entity_id, rationale)
values ('ATHENA_DIALER', 'DENAVE_IN',
        'Athena BPO operates outbound calling for Denave India campaigns under an intra-group '
        'services agreement; the dialer must pre-flight every call against Denave consent')
on conflict do nothing;

create index if not exists idx_application_entity_scope_entity
    on application_entity_scope (entity_id);
