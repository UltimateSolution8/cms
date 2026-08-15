-- =====================================================================================
-- Notice language coverage.
--
-- DPDP Rule 3 requires a notice in English or any of the twenty-two languages in the Eighth
-- Schedule to the Constitution. The seed ships English, Hindi and Tamil. The other nineteen
-- are a translation procurement, not an engineering task — but the gap between "required"
-- and "present" has to be visible to someone, or it is discovered by a data principal.
--
-- What this migration deliberately does NOT do is insert placeholder translations. Two
-- reasons, and the second is the decisive one:
--
--   1. A placeholder body is indistinguishable from a real notice to the endpoint that
--      serves it, and therefore to the subject who reads it. A consent record captured
--      against lorem ipsum looks valid and is not, which is worse than no record.
--   2. V2 makes notice_translation immutable. A placeholder row could never be corrected
--      in place — replacing it would mean publishing a whole new notice version, and the
--      version chain would then carry a fictitious material change forever.
--
-- So the requirement is recorded separately from the text, and a missing language reports
-- as missing. Absence is the honest state and the API says so out loud.
-- =====================================================================================

create table notice_language_requirement (
    notice_id    varchar(64) not null references notice (notice_id) on delete cascade,
    language_tag varchar(16) not null,
    -- Whether the notice is legally required in this language, as opposed to offered as a
    -- courtesy. Both are tracked; only the first is a compliance gap.
    mandatory    boolean     not null default true,
    rationale    text,
    primary key (notice_id, language_tag)
);

comment on table notice_language_requirement is
    'Languages a notice must exist in. Compared against notice_translation to produce the '
        'coverage gap. Deliberately separate from the text so a gap is never filled by a '
        'placeholder that could be served to a subject as though it were a notice.';

-- The twenty-two Eighth Schedule languages plus English, applied to every notice that faces
-- an Indian data principal. Athena's call-handling notice and Matrix's candidate notice are
-- included: a contact-centre caller and a BGV candidate are data principals like any other,
-- and the language obligation does not soften because the surface is a phone call.
--
-- Denave's B2B notice is included too. It is tempting to argue that a business contact reads
-- English, and the group may well conclude that most do — but that is a decision to record
-- against the requirement rows, by setting mandatory = false with a rationale, not one to
-- make silently by never listing the language at all.
insert into notice_language_requirement (notice_id, language_tag, mandatory, rationale)
select n.notice_id, l.tag, true, 'DPDP Rules 2025, Rule 3 — Eighth Schedule language'
  from notice n
 cross join (values
    ('en'), ('as'), ('bn'), ('brx'), ('doi'), ('gu'), ('hi'), ('kn'), ('ks'), ('kok'),
    ('mai'), ('ml'), ('mni'), ('mr'), ('ne'), ('or'), ('pa'), ('sa'), ('sat'), ('sd'),
    ('ta'), ('te'), ('ur')
 ) as l(tag)
 where n.notice_id in ('NOTICE_DENAVE_B2B', 'NOTICE_UDS_WEB', 'NOTICE_UDS_WORKFORCE',
                       'NOTICE_MATRIX_BGV', 'NOTICE_ATHENA_CALL')
on conflict do nothing;

-- English is not an Eighth Schedule language; it is the alternative Rule 3 permits alongside
-- them. Recording the distinction here means a coverage report reads correctly rather than
-- appearing to cite the wrong provision.
update notice_language_requirement
   set rationale = 'DPDP Rules 2025, Rule 3 — English, permitted alternative'
 where language_tag = 'en';

create index if not exists idx_notice_language_requirement_mandatory
    on notice_language_requirement (notice_id)
 where mandatory = true;
