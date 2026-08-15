-- =====================================================================================
-- Control-plane reference data: the UDS group structure and the initial purpose taxonomy.
--
-- This is configuration, not demo data. The taxonomy below is the starting point for the
-- Phase 0 discovery sessions, not the finished article — legal sign-off on the purpose ×
-- jurisdiction × legal-basis matrix is the gate for leaving Phase 1. Every row here should
-- be treated as a proposal until that sign-off happens.
-- =====================================================================================


-- -------------------------------------------------------------------------------------
-- Group entities. Ownership percentages are as recorded in the programme plan.
-- Denave's five international step-downs are modelled as entities in their own right
-- because they sit in different jurisdictions, and jurisdiction is what selects the
-- policy module. A single "Denave" row could not express that Korea forbids the bundled
-- consent request that India merely discourages.
-- -------------------------------------------------------------------------------------

insert into fiduciary_entity
    (entity_id, legal_name, short_name, parent_entity_id, uds_stake_percent,
     primary_jurisdiction, data_residency_region, significant_fiduciary)
values
    ('UDS',        'Updater Services Limited',                'UDS',           null,     null,  'IN', 'ap-south-1', false),
    ('DENAVE_IN',  'Denave India Private Limited',            'Denave India',  'UDS',    89.57, 'IN', 'ap-south-1', false),
    ('DENAVE_UK',  'Denave UK Limited',                       'Denave UK',     'DENAVE_IN', 89.57, 'UK', 'eu-west-2',  false),
    ('DENAVE_MY',  'Denave Malaysia Sdn Bhd',                 'Denave MY',     'DENAVE_IN', 89.57, 'MY', 'ap-southeast-1', false),
    ('DENAVE_SG',  'Denave Singapore Pte Limited',            'Denave SG',     'DENAVE_IN', 89.57, 'SG', 'ap-southeast-1', false),
    ('DENAVE_SG2', 'Denave Marketing Services Pte Limited',   'Denave SG 2',   'DENAVE_IN', 89.57, 'SG', 'ap-southeast-1', false),
    ('DENAVE_KR',  'Denave Korea Limited',                    'Denave KR',     'DENAVE_IN', 89.57, 'KR', 'ap-northeast-2', false),
    ('MATRIX',     'Matrix Business Services India Pvt Ltd',  'Matrix',        'UDS',   100.00, 'IN', 'ap-south-1', false),
    ('ATHENA',     'Athena BPO Private Limited',              'Athena BPO',    'UDS',    73.50, 'IN', 'ap-south-1', false),
    ('GFH',        'Global Flight Handling Services Pvt Ltd', 'GFH',           'UDS',    83.25, 'IN', 'ap-south-1', false),
    ('AVON',       'Avon Solutions & Logistics Pvt Ltd',      'Avon',          'UDS',    76.00, 'IN', 'ap-south-1', false),
    ('FUSION',     'Fusion Foods & Catering Private Limited', 'Fusion Foods',  'UDS',   100.00, 'IN', 'ap-south-1', false),
    ('WHC',        'Washroom Hygiene Concepts Private Ltd',   'WHC',           'UDS',   100.00, 'IN', 'ap-south-1', false),
    ('WYNWY',      'Wynwy Technologies Private Limited',      'Wynwy',         'UDS',   100.00, 'IN', 'ap-south-1', false),
    ('UDS_FDN',    'UDS Foundation',                          'UDS Foundation','UDS',   100.00, 'IN', 'ap-south-1', false);


-- -------------------------------------------------------------------------------------
-- Data categories. Kept deliberately separate from purposes.
-- -------------------------------------------------------------------------------------

insert into data_category (code, name, description, sensitive, biometric) values
    ('CONTACT_BUSINESS',   'Business contact details',   'Work email, direct line, job title, employer',       false, false),
    ('CONTACT_PERSONAL',   'Personal contact details',   'Personal mobile, personal email, postal address',    false, false),
    ('IDENTITY',           'Identity details',           'Name, date of birth, gender',                        false, false),
    ('GOVERNMENT_ID',      'Government identifiers',     'Aadhaar, PAN, passport, driving licence',            true,  false),
    ('EMPLOYMENT',         'Employment record',          'Role, grade, site, shift, reporting line',           false, false),
    ('PAYROLL_FINANCIAL',  'Payroll and bank details',   'Salary, bank account, statutory deductions',         true,  false),
    ('BIOMETRIC_FINGERPRINT','Fingerprint template',     'Used for attendance and access control',             true,  true),
    ('BIOMETRIC_FACE',     'Facial template',            'Used for attendance and airside access control',     true,  true),
    ('GEOLOCATION',        'Location data',              'Device GPS position and derived movement',           true,  false),
    ('DEVICE_TELEMETRY',   'Device and app telemetry',   'Device model, OS version, app diagnostics',          false, false),
    ('CALL_RECORDING',     'Voice recordings',           'Recorded inbound and outbound calls',                true,  false),
    ('WEB_BEHAVIOUR',      'Website behaviour',          'Pages viewed, referrer, session and campaign data',  false, false),
    ('MARKETING_PREFERENCE','Marketing preferences',     'Channel and topic preferences, opt-out state',       false, false),
    ('EDUCATION_RECORD',   'Education history',          'Institutions, qualifications, dates',                false, false),
    ('EMPLOYMENT_HISTORY', 'Prior employment history',   'Previous employers, dates, designations',            false, false),
    ('CRIMINAL_RECORD',    'Criminal record check',      'Court and police record search results',             true,  false),
    ('HEALTH',             'Health information',         'Fitness-to-work and occupational health records',    true,  false);


-- -------------------------------------------------------------------------------------
-- Notices. One per audience. Translations seeded in English, Hindi and Tamil; the
-- remaining Eighth Schedule languages are a content task tracked separately — a notice
-- with no translation in the subject's language is not an informed notice, and the
-- console refuses to serve one.
-- -------------------------------------------------------------------------------------

insert into notice (notice_id, entity_id, name) values
    ('NOTICE_DENAVE_B2B',  'DENAVE_IN', 'Denave B2B prospect and outreach notice'),
    ('NOTICE_UDS_WEB',     'UDS',       'UDS group website and cookie notice'),
    ('NOTICE_UDS_WORKFORCE','UDS',      'UDS workforce privacy notice'),
    ('NOTICE_MATRIX_BGV',  'MATRIX',    'Matrix background verification candidate notice'),
    ('NOTICE_ATHENA_CALL', 'ATHENA',    'Athena contact centre call handling notice');

insert into notice_version
    (notice_id, version, jurisdiction, material_change, withdrawal_uri, rights_uri, grievance_uri, published_by)
values
    ('NOTICE_DENAVE_B2B',   1, 'IN', false, 'https://privacy.uds.co.in/withdraw', 'https://privacy.uds.co.in/rights', 'https://privacy.uds.co.in/grievance', 'seed'),
    ('NOTICE_UDS_WEB',      1, 'IN', false, 'https://privacy.uds.co.in/withdraw', 'https://privacy.uds.co.in/rights', 'https://privacy.uds.co.in/grievance', 'seed'),
    ('NOTICE_UDS_WORKFORCE',1, 'IN', false, 'https://privacy.uds.co.in/withdraw', 'https://privacy.uds.co.in/rights', 'https://privacy.uds.co.in/grievance', 'seed'),
    ('NOTICE_MATRIX_BGV',   1, 'IN', false, 'https://privacy.uds.co.in/withdraw', 'https://privacy.uds.co.in/rights', 'https://privacy.uds.co.in/grievance', 'seed'),
    ('NOTICE_ATHENA_CALL',  1, 'IN', false, 'https://privacy.uds.co.in/withdraw', 'https://privacy.uds.co.in/rights', 'https://privacy.uds.co.in/grievance', 'seed');

insert into notice_translation (notice_version_id, language_tag, title, body)
select nv.id, t.language_tag, t.title, t.body
from notice_version nv
join (values
    ('NOTICE_DENAVE_B2B', 'en',
     'How Denave uses your business contact details',
     'Denave India Private Limited processes your name, job title, employer and business contact '
     'details to contact you about products and services relevant to your professional role, on '
     'behalf of itself and its clients. You can withdraw your consent at any time, as easily as '
     'you gave it, using the link below.'),
    ('NOTICE_DENAVE_B2B', 'hi',
     'Denave आपके व्यावसायिक संपर्क विवरण का उपयोग कैसे करता है',
     'Denave India Private Limited आपके नाम, पदनाम, नियोक्ता और व्यावसायिक संपर्क विवरण का उपयोग '
     'आपकी व्यावसायिक भूमिका से संबंधित उत्पादों और सेवाओं के बारे में आपसे संपर्क करने के लिए करता है। '
     'आप किसी भी समय अपनी सहमति वापस ले सकते हैं, उतनी ही आसानी से जितनी आसानी से आपने दी थी।'),
    ('NOTICE_UDS_WORKFORCE', 'en',
     'How UDS uses your information as a member of the workforce',
     'Updater Services Limited and its group companies process your identity, employment, '
     'attendance and payroll information to manage your employment, meet statutory obligations '
     'and safeguard the business. Most of this processing does not rest on your consent — it is '
     'a legitimate use under section 7 of the Digital Personal Data Protection Act 2023. This '
     'notice tells you what is processed, for how long it is kept, and how to exercise your '
     'rights or raise a grievance.'),
    ('NOTICE_UDS_WORKFORCE', 'ta',
     'UDS உங்கள் தகவலை எவ்வாறு பயன்படுத்துகிறது',
     'Updater Services Limited மற்றும் அதன் குழும நிறுவனங்கள் உங்கள் அடையாளம், பணி, வருகை மற்றும் '
     'ஊதியத் தகவல்களை உங்கள் பணியை நிர்வகிக்கவும், சட்டப்பூர்வ கடமைகளை நிறைவேற்றவும் பயன்படுத்துகின்றன.'),
    ('NOTICE_MATRIX_BGV', 'en',
     'Background verification: what we check and why',
     'Matrix Business Services India Private Limited will verify the identity, education, prior '
     'employment and, where you separately agree, criminal record information you have provided, '
     'on the instruction of the organisation considering you for engagement. Verification is '
     'carried out only with your consent. You may withdraw that consent at any time, though doing '
     'so may mean the organisation cannot complete its assessment.'),
    ('NOTICE_UDS_WEB', 'en',
     'Cookies and site analytics',
     'This site sets cookies that are strictly necessary for it to work. With your consent it '
     'also sets analytics and advertising cookies. You can accept, reject or change your choice '
     'at any time, and rejecting is exactly as easy as accepting.'),
    ('NOTICE_ATHENA_CALL', 'en',
     'Call handling and recording',
     'Athena BPO Private Limited handles this call on behalf of our client. Calls may be recorded '
     'for quality and training purposes where you consent to recording. You may decline recording '
     'and continue with the call.')
) as t(notice_id, language_tag, title, body) on t.notice_id = nv.notice_id
where nv.version = 1;


-- -------------------------------------------------------------------------------------
-- Purpose taxonomy.
--
-- The controlled vocabulary the whole platform decides against. Free text is never
-- accepted here: the moment two teams can write their own purpose strings, the group
-- loses the ability to answer "what is this person's data used for" in one query.
-- -------------------------------------------------------------------------------------

insert into purpose (code, name, owner) values
    ('MKT_OUTBOUND_CALL',      'Promotional outbound call',              'Denave / Athena'),
    ('MKT_OUTBOUND_SMS',       'Promotional SMS',                        'Denave'),
    ('MKT_OUTBOUND_EMAIL',     'Promotional email',                      'Denave'),
    ('MKT_OUTBOUND_WHATSAPP',  'Promotional messaging',                  'Denave'),
    ('TXN_SERVICE_SMS',        'Transactional service message',          'Denave'),
    ('SALES_RELATIONSHIP',     'Managing an existing customer relationship','Denave'),
    ('PROSPECT_ENRICHMENT',    'Enriching prospect records',             'Denave data services'),
    ('LEAD_PROFILING',         'Lead scoring and profiling',             'Denave'),
    ('WEB_STRICTLY_NECESSARY', 'Strictly necessary site function',       'Group marketing'),
    ('WEB_ANALYTICS',          'Website analytics',                      'Group marketing'),
    ('WEB_ADVERTISING',        'Advertising and remarketing',            'Group marketing'),
    ('HR_EMPLOYMENT_ADMIN',    'Employment administration',              'Group HR'),
    ('HR_PAYROLL_STATUTORY',   'Payroll and statutory filings',          'Group HR'),
    ('HR_ATTENDANCE_BIOMETRIC','Biometric attendance',                   'Group HR'),
    ('HR_FIELD_LOCATION',      'Field attendance verification by location','Denave / UDS operations'),
    ('BGV_IDENTITY',           'Identity verification',                  'Matrix'),
    ('BGV_EDUCATION',          'Education verification',                 'Matrix'),
    ('BGV_EMPLOYMENT',         'Prior employment verification',          'Matrix'),
    ('BGV_CRIMINAL_RECORD',    'Criminal record check',                  'Matrix'),
    ('CALL_RECORDING_QUALITY', 'Call recording for quality and training','Athena'),
    ('SECURITY_FRAUD',         'Security and fraud prevention',          'Group IT security');

insert into purpose_version
    (purpose_code, version, name, description, expiry_policy, expiry_days, failure_behavior,
     notice_id, requires_separate_consent, permitted_for_children, published_by)
values
    ('MKT_OUTBOUND_CALL', 1, 'Promotional outbound call',
     'Calling you about products and services we or our clients offer.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('MKT_OUTBOUND_SMS', 1, 'Promotional SMS',
     'Sending you promotional text messages. In India this requires a DLT-registered sender '
     'identifier and template in addition to your consent.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('MKT_OUTBOUND_EMAIL', 1, 'Promotional email',
     'Sending you marketing email relevant to your professional role.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('MKT_OUTBOUND_WHATSAPP', 1, 'Promotional messaging',
     'Contacting you on WhatsApp or a comparable messaging service with promotional content.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    -- TRAI TCCCPR gives explicit consent for a transactional communication a seven-day life.
    -- Modelled as its own expiry policy rather than a configurable number of days, so that
    -- nobody can quietly retune it to thirty and call it a preference.
    ('TXN_SERVICE_SMS', 1, 'Transactional service message',
     'Sending you a service message about a transaction you initiated. Consent for this '
     'purpose lapses seven days after it is given.',
     'TRAI_TRANSACTIONAL_7D', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    -- TRAI permits consent inferred from a live contractual relationship, valid only while
    -- that relationship lasts. The contract end date rides on the artefact.
    ('SALES_RELATIONSHIP', 1, 'Managing an existing customer relationship',
     'Contacting you in connection with a contract you or your employer holds with us, for as '
     'long as that relationship lasts.',
     'CONTRACT_LIFETIME', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('PROSPECT_ENRICHMENT', 1, 'Enriching prospect records',
     'Adding information about your professional role from third-party sources to records we '
     'already hold.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('LEAD_PROFILING', 1, 'Lead scoring and profiling',
     'Scoring and segmenting records to decide who to approach and with what.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_DENAVE_B2B', false, false, 'seed'),

    ('WEB_STRICTLY_NECESSARY', 1, 'Strictly necessary site function',
     'Cookies and storage without which the site cannot function — session, security, load '
     'balancing. No consent is required for these, in any jurisdiction we operate in.',
     'NONE', null, 'FAIL_OPEN', 'NOTICE_UDS_WEB', false, true, 'seed'),

    ('WEB_ANALYTICS', 1, 'Website analytics',
     'Measuring how the site is used so we can improve it.',
     'FIXED_DAYS', 365, 'FAIL_CLOSED', 'NOTICE_UDS_WEB', false, false, 'seed'),

    ('WEB_ADVERTISING', 1, 'Advertising and remarketing',
     'Showing you advertising for our services on other sites, and measuring whether it worked.',
     'FIXED_DAYS', 365, 'FAIL_CLOSED', 'NOTICE_UDS_WEB', false, false, 'seed'),

    -- Section 7(i) of the DPDP Act makes employment processing a legitimate use. These
    -- purposes therefore need no consent record; what they need is notice, retention
    -- discipline and working rights machinery. This is a large scope reduction against the
    -- assumption that all ~76,000 workforce records are a consent problem.
    ('HR_EMPLOYMENT_ADMIN', 1, 'Employment administration',
     'Managing your engagement: role, site, shift, performance, discipline and exit.',
     'NONE', null, 'FAIL_OPEN', 'NOTICE_UDS_WORKFORCE', false, false, 'seed'),

    ('HR_PAYROLL_STATUTORY', 1, 'Payroll and statutory filings',
     'Paying you and meeting provident fund, ESI, tax and other statutory obligations.',
     'NONE', null, 'FAIL_OPEN', 'NOTICE_UDS_WORKFORCE', false, false, 'seed'),

    -- Biometric attendance is a legitimate use in India but sensitive personal data in
    -- Malaysia under the 2024 amendment, so the jurisdiction rows below diverge. Fails
    -- closed everywhere regardless: a biometric template is not recoverable once misused.
    ('HR_ATTENDANCE_BIOMETRIC', 1, 'Biometric attendance',
     'Recording your attendance using a fingerprint or facial template at a site terminal.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_UDS_WORKFORCE', false, false, 'seed'),

    ('HR_FIELD_LOCATION', 1, 'Field attendance verification by location',
     'Confirming you were at the site you were rostered to, using your device location during '
     'your shift only. This is a different purpose from any marketing use of location, and is '
     'answered separately.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_UDS_WORKFORCE', false, false, 'seed'),

    ('BGV_IDENTITY', 1, 'Identity verification',
     'Confirming you are who you say you are, against the identity documents you provide.',
     'FIXED_DAYS', 180, 'FAIL_CLOSED', 'NOTICE_MATRIX_BGV', false, false, 'seed'),

    ('BGV_EDUCATION', 1, 'Education verification',
     'Confirming the qualifications you have declared with the awarding institutions.',
     'FIXED_DAYS', 180, 'FAIL_CLOSED', 'NOTICE_MATRIX_BGV', false, false, 'seed'),

    ('BGV_EMPLOYMENT', 1, 'Prior employment verification',
     'Confirming your declared employment history with your previous employers.',
     'FIXED_DAYS', 180, 'FAIL_CLOSED', 'NOTICE_MATRIX_BGV', false, false, 'seed'),

    -- Criminal record data is sensitive in every jurisdiction the group operates in, and
    -- Korea's PIPA requires consent to it to be sought separately and itemised. Bundling it
    -- into a general background-check agreement makes the whole consent invalid there.
    ('BGV_CRIMINAL_RECORD', 1, 'Criminal record check',
     'Searching court and police records for any criminal record in your name.',
     'FIXED_DAYS', 180, 'FAIL_CLOSED', 'NOTICE_MATRIX_BGV', true, false, 'seed'),

    ('CALL_RECORDING_QUALITY', 1, 'Call recording for quality and training',
     'Recording this call so it can be reviewed for quality and used to train our agents.',
     'NONE', null, 'FAIL_CLOSED', 'NOTICE_ATHENA_CALL', false, false, 'seed'),

    ('SECURITY_FRAUD', 1, 'Security and fraud prevention',
     'Detecting and preventing fraud, misuse and security incidents affecting our systems.',
     'NONE', null, 'FAIL_OPEN', 'NOTICE_UDS_WORKFORCE', false, true, 'seed');


-- -------------------------------------------------------------------------------------
-- Legal basis per purpose per jurisdiction.
--
-- A jurisdiction with no row for a purpose is one where that purpose is not permitted at
-- all — the decision engine denies rather than silently falling back to consent.
-- -------------------------------------------------------------------------------------

insert into purpose_legal_basis (purpose_version_id, jurisdiction, legal_basis, assessment_ref, notes)
select pv.id, x.jurisdiction, x.legal_basis, x.assessment_ref, x.notes
from purpose_version pv
join (values
    -- Promotional calling. India requires consent and TRAI's registry sits on top of it.
    -- The UK and EU permit legitimate interest for B2B contact with a documented assessment;
    -- Germany in practice requires consent for B2B cold contact, which is a member-state
    -- carve-out to model when the EU row is split by country.
    ('MKT_OUTBOUND_CALL', 'IN', 'CONSENT',             null,          'TRAI TCCCPR also applies: NCPR scrub and DLT registration are mandatory'),
    ('MKT_OUTBOUND_CALL', 'UK', 'LEGITIMATE_INTEREST', 'LIA-2026-001','TPS and CTPS scrub required before dialling'),
    ('MKT_OUTBOUND_CALL', 'EU', 'LEGITIMATE_INTEREST', 'LIA-2026-002','Member-state variation: DE requires consent for B2B cold contact'),
    ('MKT_OUTBOUND_CALL', 'SG', 'CONSENT',             null,          'PDPA Do Not Call Registry check required before dialling'),
    ('MKT_OUTBOUND_CALL', 'MY', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_CALL', 'KR', 'CONSENT',             null,          'PIPA: must be sought separately and itemised, never bundled'),

    ('MKT_OUTBOUND_SMS',  'IN', 'CONSENT',             null,          'DLT-registered sender and template required'),
    ('MKT_OUTBOUND_SMS',  'UK', 'CONSENT',             null,          'PECR requires consent for electronic marketing to individuals'),
    ('MKT_OUTBOUND_SMS',  'EU', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_SMS',  'SG', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_SMS',  'MY', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_SMS',  'KR', 'CONSENT',             null,          'PIPA: separate itemised consent'),

    ('MKT_OUTBOUND_EMAIL','IN', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_EMAIL','UK', 'LEGITIMATE_INTEREST', 'LIA-2026-003','PECR corporate subscriber exemption; opt-out must be honoured'),
    ('MKT_OUTBOUND_EMAIL','EU', 'LEGITIMATE_INTEREST', 'LIA-2026-004','Member-state variation: DE requires consent'),
    ('MKT_OUTBOUND_EMAIL','SG', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_EMAIL','MY', 'CONSENT',             null,          null),
    ('MKT_OUTBOUND_EMAIL','KR', 'CONSENT',             null,          null),

    ('MKT_OUTBOUND_WHATSAPP','IN','CONSENT',           null,          null),
    ('MKT_OUTBOUND_WHATSAPP','UK','CONSENT',           null,          null),
    ('MKT_OUTBOUND_WHATSAPP','SG','CONSENT',           null,          null),

    ('TXN_SERVICE_SMS',   'IN', 'CONSENT',             null,          'TCCCPR: explicit transactional consent lapses after seven days'),
    ('TXN_SERVICE_SMS',   'UK', 'CONTRACT_PERFORMANCE',null,          null),
    ('TXN_SERVICE_SMS',   'SG', 'CONTRACT_PERFORMANCE',null,          null),

    ('SALES_RELATIONSHIP','IN', 'INFERRED_CONSENT',    null,          'TCCCPR: valid only for the duration of the contractual relationship'),
    ('SALES_RELATIONSHIP','UK', 'CONTRACT_PERFORMANCE',null,          null),
    ('SALES_RELATIONSHIP','EU', 'CONTRACT_PERFORMANCE',null,          null),
    ('SALES_RELATIONSHIP','SG', 'CONTRACT_PERFORMANCE',null,          null),

    ('PROSPECT_ENRICHMENT','IN','CONSENT',             null,          'Requires substantiated provenance for the source record'),
    ('PROSPECT_ENRICHMENT','UK','LEGITIMATE_INTEREST', 'LIA-2026-005',null),
    ('PROSPECT_ENRICHMENT','EU','LEGITIMATE_INTEREST', 'LIA-2026-006',null),

    ('LEAD_PROFILING',    'IN', 'CONSENT',             null,          null),
    ('LEAD_PROFILING',    'UK', 'LEGITIMATE_INTEREST', 'LIA-2026-007',null),
    ('LEAD_PROFILING',    'KR', 'CONSENT',             null,          'PIPA requires separate consent for automated decision-making'),

    -- Strictly necessary storage needs no consent anywhere, which is why it fails open.
    ('WEB_STRICTLY_NECESSARY','IN','LEGITIMATE_USE_VOLUNTARY',null,   null),
    ('WEB_STRICTLY_NECESSARY','UK','CONTRACT_PERFORMANCE',null,       'PECR strictly-necessary exemption'),
    ('WEB_STRICTLY_NECESSARY','EU','CONTRACT_PERFORMANCE',null,       'ePrivacy strictly-necessary exemption'),
    ('WEB_STRICTLY_NECESSARY','SG','CONTRACT_PERFORMANCE',null,       null),
    ('WEB_STRICTLY_NECESSARY','MY','CONTRACT_PERFORMANCE',null,       null),
    ('WEB_STRICTLY_NECESSARY','KR','CONTRACT_PERFORMANCE',null,       null),
    ('WEB_STRICTLY_NECESSARY','US_CA','CONTRACT_PERFORMANCE',null,    null),

    -- Analytics and advertising need consent under ePrivacy regardless of the GDPR basis.
    -- The Digital Omnibus proposal may change this; the rule is configuration, not code,
    -- precisely so that it can be changed without a release.
    ('WEB_ANALYTICS',     'IN', 'CONSENT',             null,          null),
    ('WEB_ANALYTICS',     'UK', 'CONSENT',             null,          'PECR: consent required for non-essential storage'),
    ('WEB_ANALYTICS',     'EU', 'CONSENT',             null,          null),
    ('WEB_ANALYTICS',     'SG', 'CONSENT',             null,          null),
    ('WEB_ANALYTICS',     'MY', 'CONSENT',             null,          null),
    ('WEB_ANALYTICS',     'KR', 'CONSENT',             null,          null),

    ('WEB_ADVERTISING',   'IN', 'CONSENT',             null,          null),
    ('WEB_ADVERTISING',   'UK', 'CONSENT',             null,          null),
    ('WEB_ADVERTISING',   'EU', 'CONSENT',             null,          null),
    ('WEB_ADVERTISING',   'KR', 'CONSENT',             null,          null),

    ('HR_EMPLOYMENT_ADMIN','IN','LEGITIMATE_USE_EMPLOYMENT',null,     'DPDP s.7(i): no consent required'),
    ('HR_EMPLOYMENT_ADMIN','UK','CONTRACT_PERFORMANCE',null,          null),
    ('HR_EMPLOYMENT_ADMIN','MY','CONTRACT_PERFORMANCE',null,          null),
    ('HR_EMPLOYMENT_ADMIN','SG','CONTRACT_PERFORMANCE',null,          null),

    ('HR_PAYROLL_STATUTORY','IN','LEGAL_OBLIGATION',   null,          'Survives withdrawal of any other consent'),
    ('HR_PAYROLL_STATUTORY','UK','LEGAL_OBLIGATION',   null,          null),
    ('HR_PAYROLL_STATUTORY','MY','LEGAL_OBLIGATION',   null,          null),

    ('HR_ATTENDANCE_BIOMETRIC','IN','LEGITIMATE_USE_EMPLOYMENT',null, 'Notice still required; retention must be short'),
    ('HR_ATTENDANCE_BIOMETRIC','MY','CONSENT',         null,          'PDPA Amendment 2024: biometric data is sensitive personal data'),
    ('HR_ATTENDANCE_BIOMETRIC','SG','CONSENT',         null,          null),

    ('HR_FIELD_LOCATION', 'IN', 'LEGITIMATE_USE_EMPLOYMENT',null,     'Shift hours only; not a basis for any marketing use of location'),
    ('HR_FIELD_LOCATION', 'MY', 'CONSENT',             null,          null),
    ('HR_FIELD_LOCATION', 'SG', 'CONSENT',             null,          null),

    ('BGV_IDENTITY',      'IN', 'CONSENT',             null,          'Consent is the basis; there is no fallback if it is withdrawn'),
    ('BGV_IDENTITY',      'UK', 'CONSENT',             null,          null),
    ('BGV_IDENTITY',      'MY', 'CONSENT',             null,          null),
    ('BGV_IDENTITY',      'SG', 'CONSENT',             null,          null),

    ('BGV_EDUCATION',     'IN', 'CONSENT',             null,          null),
    ('BGV_EDUCATION',     'UK', 'CONSENT',             null,          null),
    ('BGV_EMPLOYMENT',    'IN', 'CONSENT',             null,          null),
    ('BGV_EMPLOYMENT',    'UK', 'CONSENT',             null,          null),

    ('BGV_CRIMINAL_RECORD','IN','CONSENT',             null,          'Sought separately from the rest of the check'),
    ('BGV_CRIMINAL_RECORD','UK','CONSENT',             null,          null),
    ('BGV_CRIMINAL_RECORD','KR','CONSENT',             null,          'PIPA: separate itemised consent for sensitive data'),

    ('CALL_RECORDING_QUALITY','IN','CONSENT',          null,          'Subject may decline recording and continue the call'),
    ('CALL_RECORDING_QUALITY','UK','CONSENT',          null,          null),

    ('SECURITY_FRAUD',    'IN', 'LEGITIMATE_USE_EMPLOYMENT',null,     'DPDP s.7(i): safeguarding the employer from loss or liability'),
    ('SECURITY_FRAUD',    'UK', 'LEGITIMATE_INTEREST', 'LIA-2026-008',null),
    ('SECURITY_FRAUD',    'EU', 'LEGITIMATE_INTEREST', 'LIA-2026-009',null)
) as x(purpose_code, jurisdiction, legal_basis, assessment_ref, notes)
    on x.purpose_code = pv.purpose_code
where pv.version = 1;


-- -------------------------------------------------------------------------------------
-- Channels a purpose may be exercised over. An empty set means "any".
-- -------------------------------------------------------------------------------------

insert into purpose_channel (purpose_version_id, channel)
select pv.id, x.channel
from purpose_version pv
join (values
    ('MKT_OUTBOUND_CALL', 'VOICE_CALL'),
    ('MKT_OUTBOUND_SMS', 'SMS'),
    ('MKT_OUTBOUND_EMAIL', 'EMAIL'),
    ('MKT_OUTBOUND_WHATSAPP', 'WHATSAPP'),
    ('TXN_SERVICE_SMS', 'SMS'),
    ('SALES_RELATIONSHIP', 'VOICE_CALL'),
    ('SALES_RELATIONSHIP', 'EMAIL'),
    ('WEB_STRICTLY_NECESSARY', 'WEB'),
    ('WEB_ANALYTICS', 'WEB'),
    ('WEB_ANALYTICS', 'MOBILE_APP'),
    ('WEB_ADVERTISING', 'WEB'),
    ('HR_ATTENDANCE_BIOMETRIC', 'KIOSK'),
    ('HR_FIELD_LOCATION', 'MOBILE_APP'),
    ('CALL_RECORDING_QUALITY', 'VOICE_CALL')
) as x(purpose_code, channel) on x.purpose_code = pv.purpose_code
where pv.version = 1;


-- -------------------------------------------------------------------------------------
-- Data categories each purpose touches. This is what turns the registry into a RoPA.
-- -------------------------------------------------------------------------------------

insert into purpose_data_category (purpose_version_id, data_category_code)
select pv.id, x.code
from purpose_version pv
join (values
    ('MKT_OUTBOUND_CALL', 'CONTACT_BUSINESS'),
    ('MKT_OUTBOUND_CALL', 'IDENTITY'),
    ('MKT_OUTBOUND_SMS', 'CONTACT_BUSINESS'),
    ('MKT_OUTBOUND_EMAIL', 'CONTACT_BUSINESS'),
    ('MKT_OUTBOUND_WHATSAPP', 'CONTACT_PERSONAL'),
    ('TXN_SERVICE_SMS', 'CONTACT_PERSONAL'),
    ('SALES_RELATIONSHIP', 'CONTACT_BUSINESS'),
    ('PROSPECT_ENRICHMENT', 'CONTACT_BUSINESS'),
    ('PROSPECT_ENRICHMENT', 'IDENTITY'),
    ('LEAD_PROFILING', 'CONTACT_BUSINESS'),
    ('LEAD_PROFILING', 'WEB_BEHAVIOUR'),
    ('WEB_STRICTLY_NECESSARY', 'DEVICE_TELEMETRY'),
    ('WEB_ANALYTICS', 'WEB_BEHAVIOUR'),
    ('WEB_ANALYTICS', 'DEVICE_TELEMETRY'),
    ('WEB_ADVERTISING', 'WEB_BEHAVIOUR'),
    ('HR_EMPLOYMENT_ADMIN', 'IDENTITY'),
    ('HR_EMPLOYMENT_ADMIN', 'EMPLOYMENT'),
    ('HR_PAYROLL_STATUTORY', 'PAYROLL_FINANCIAL'),
    ('HR_PAYROLL_STATUTORY', 'GOVERNMENT_ID'),
    ('HR_ATTENDANCE_BIOMETRIC', 'BIOMETRIC_FINGERPRINT'),
    ('HR_ATTENDANCE_BIOMETRIC', 'BIOMETRIC_FACE'),
    ('HR_FIELD_LOCATION', 'GEOLOCATION'),
    ('BGV_IDENTITY', 'IDENTITY'),
    ('BGV_IDENTITY', 'GOVERNMENT_ID'),
    ('BGV_EDUCATION', 'EDUCATION_RECORD'),
    ('BGV_EMPLOYMENT', 'EMPLOYMENT_HISTORY'),
    ('BGV_CRIMINAL_RECORD', 'CRIMINAL_RECORD'),
    ('CALL_RECORDING_QUALITY', 'CALL_RECORDING'),
    ('SECURITY_FRAUD', 'DEVICE_TELEMETRY')
) as x(purpose_code, code) on x.purpose_code = pv.purpose_code
where pv.version = 1;


-- -------------------------------------------------------------------------------------
-- Applications. The surfaces named in the programme plan, seeded so that Phase 0
-- discovery starts from a list to correct rather than a blank page.
-- -------------------------------------------------------------------------------------

insert into application_registry (application_id, entity_id, name, platform, environment, description)
values
    ('DENCRM_PROD',     'DENAVE_IN', 'DenCRM',        'BACKEND', 'PRODUCTION', 'Denave CRM and prospect database'),
    ('DENPRM_PROD',     'DENAVE_IN', 'DenPRM',        'BACKEND', 'PRODUCTION', 'Partner relationship management'),
    ('DENSFA_PROD',     'DENAVE_IN', 'DenSFA',        'ANDROID', 'PRODUCTION', 'Sales force automation'),
    ('ISFA_ANDROID',    'DENAVE_IN', 'iSFA Connect',  'ANDROID', 'PRODUCTION', 'Field sales app, offline-first'),
    ('ISFA_IOS',        'DENAVE_IN', 'iSFA Connect',  'IOS',     'PRODUCTION', 'Field sales app, offline-first'),
    ('MYDEN_ANDROID',   'DENAVE_IN', 'myDEN Connect', 'ANDROID', 'PRODUCTION', 'Workforce app'),
    ('DENTRACK_PROD',   'DENAVE_IN', 'DenTrack',      'BACKEND', 'PRODUCTION', 'Retail audit and tracking'),
    ('DENAVE_WEB',      'DENAVE_IN', 'denave.com',    'WEB',     'PRODUCTION', 'Public website'),
    ('UDS_WEB',         'UDS',       'updaterservices.com', 'WEB', 'PRODUCTION', 'Group website'),
    ('ATHENA_DIALER',   'ATHENA',    'Outbound dialer', 'BACKEND','PRODUCTION', 'Predictive dialer; must pre-flight every call'),
    ('MATRIX_BGV',      'MATRIX',    'BGV workflow',  'BACKEND', 'PRODUCTION', 'Candidate verification workflow'),
    ('UDS_HRMS',        'UDS',       'HRMS',          'BACKEND', 'PRODUCTION', 'Group HR and attendance');
