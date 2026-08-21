import {
  FiduciaryEntity,
  RightsRequestItem,
  Vendor,
  ProcessingActivity,
  WebhookSubscription,
  PropagationGapReport,
  EvidenceBundleResponse,
  IntegritySweepResult,
  AdminAuditEvent,
  PrivacyNotice,
  BlastRadiusReport
} from './types';

export const MOCK_FIDUCIARY_ENTITIES: FiduciaryEntity[] = [
  {
    id: 'UDS',
    name: 'Updater Services Limited',
    shortName: 'UDS Parent',
    category: 'PARENT',
    residencyRegion: 'ap-south-1 (Mumbai)',
    dpoContact: 'dpo@uds.co.in',
    grievanceUri: 'https://privacy.uds.co.in/grievance',
    activeConsentsCount: 142850,
    openDsrCount: 14,
    complianceRate: 99.4
  },
  {
    id: 'DENAVE_IN',
    name: 'Denave India Pvt Ltd',
    shortName: 'Denave India',
    category: 'SALES_ENABLEMENT',
    residencyRegion: 'ap-south-1 (Mumbai)',
    dpoContact: 'privacy@denave.com',
    grievanceUri: 'https://privacy.denave.com/grievance',
    activeConsentsCount: 89400,
    openDsrCount: 8,
    complianceRate: 98.7
  },
  {
    id: 'DENAVE_UK',
    name: 'Denave Europe Limited',
    shortName: 'Denave UK',
    category: 'SALES_ENABLEMENT',
    residencyRegion: 'eu-west-2 (London)',
    dpoContact: 'dpo.uk@denave.com',
    grievanceUri: 'https://privacy.denave.co.uk/grievance',
    activeConsentsCount: 12100,
    openDsrCount: 2,
    complianceRate: 100.0
  },
  {
    id: 'DENAVE_MY',
    name: 'Denave (M) SDN BHD',
    shortName: 'Denave Malaysia',
    category: 'SALES_ENABLEMENT',
    residencyRegion: 'ap-southeast-1 (Singapore)',
    dpoContact: 'dpo.my@denave.com',
    activeConsentsCount: 9400,
    openDsrCount: 1,
    complianceRate: 99.1
  },
  {
    id: 'MATRIX',
    name: 'Matrix Business Services India Pvt Ltd',
    shortName: 'Matrix BGV',
    category: 'BACKGROUND_VERIFICATION',
    residencyRegion: 'ap-south-1 (Mumbai)',
    dpoContact: 'compliance@matrixbsindia.net',
    grievanceUri: 'https://matrixbsindia.net/grievance',
    activeConsentsCount: 45200,
    openDsrCount: 5,
    complianceRate: 99.8
  },
  {
    id: 'ATHENA',
    name: 'Athena BPO Private Limited',
    shortName: 'Athena BPO',
    category: 'BPO',
    residencyRegion: 'ap-south-1 (Mumbai)',
    dpoContact: 'privacy@athenabpo.com',
    activeConsentsCount: 38100,
    openDsrCount: 3,
    complianceRate: 98.2
  },
  {
    id: 'AVON',
    name: 'Avon Solutions & Logistics Pvt Ltd',
    shortName: 'Avon Logistics',
    category: 'LOGISTICS',
    residencyRegion: 'ap-south-1 (Mumbai)',
    activeConsentsCount: 14200,
    openDsrCount: 0,
    complianceRate: 100.0
  },
  {
    id: 'GFHS',
    name: 'Global Flight Handling Services Pvt Ltd',
    shortName: 'GFH Services',
    category: 'FACILITY_MANAGEMENT',
    residencyRegion: 'ap-south-1 (Mumbai)',
    activeConsentsCount: 18900,
    openDsrCount: 1,
    complianceRate: 99.5
  },
  {
    id: 'WHC',
    name: 'Washroom Hygiene Concepts Pvt Ltd',
    shortName: 'WHC India',
    category: 'HYGIENE',
    residencyRegion: 'ap-south-1 (Mumbai)',
    activeConsentsCount: 8400,
    openDsrCount: 0,
    complianceRate: 100.0
  },
  {
    id: 'FUSION_FOODS',
    name: 'Fusion Foods and Catering Services Pvt Ltd',
    shortName: 'Fusion Foods',
    category: 'CATERING',
    residencyRegion: 'ap-south-1 (Mumbai)',
    activeConsentsCount: 6700,
    openDsrCount: 0,
    complianceRate: 100.0
  },
  {
    id: 'WYNWY',
    name: 'Wynwy Technologies Pvt Ltd',
    shortName: 'Wynwy Staffing',
    category: 'FACILITY_MANAGEMENT',
    residencyRegion: 'ap-south-1 (Mumbai)',
    activeConsentsCount: 11200,
    openDsrCount: 1,
    complianceRate: 99.0
  }
];

export const MOCK_RIGHTS_REQUESTS: RightsRequestItem[] = [
  {
    id: 'REQ-2026-0891',
    referenceNumber: 'REF-IN-981247',
    entityId: 'DENAVE_IN',
    subjectId: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    requestType: 'ERASURE',
    status: 'VERIFICATION_REQUIRED',
    receivedAt: '2026-08-17T14:22:10Z',
    statutoryDeadline: '2026-09-16T14:22:10Z',
    daysRemaining: 26,
    bornOverdue: false,
    slaRisk: 'HEALTHY',
    verificationMethod: 'UNVERIFIED',
    channel: 'PORTAL',
    details: { reason: 'No longer interested in B2B marketing webinars', contactHint: '+91 98*** ***10' }
  },
  {
    id: 'REQ-2026-0884',
    referenceNumber: 'REF-IN-884102',
    entityId: 'MATRIX',
    subjectId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
    requestType: 'ACCESS',
    status: 'IN_REVIEW',
    receivedAt: '2026-08-01T09:15:00Z',
    statutoryDeadline: '2026-08-31T09:15:00Z',
    daysRemaining: 10,
    bornOverdue: false,
    slaRisk: 'WARNING',
    verificationMethod: 'PORTAL_TOKEN',
    verifiedAt: '2026-08-01T09:20:00Z',
    channel: 'PORTAL',
    details: { scope: 'Requesting copy of completed pre-employment BGV check' }
  },
  {
    id: 'REQ-2026-0870',
    referenceNumber: 'REF-IN-771904',
    entityId: 'ATHENA',
    subjectId: '4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a',
    requestType: 'GRIEVANCE',
    status: 'ACTION_REQUIRED',
    receivedAt: '2026-07-25T11:40:00Z',
    statutoryDeadline: '2026-08-24T11:40:00Z',
    daysRemaining: 3,
    bornOverdue: false,
    slaRisk: 'CRITICAL',
    verificationMethod: 'SMS_OTP',
    verifiedAt: '2026-07-25T11:45:00Z',
    channel: 'PORTAL',
    details: { grievance: 'Received telesales call after opting out via SMS', outstandingSystems: ['ATHENA_DIALER', 'DENCRM'] }
  },
  {
    id: 'REQ-2026-0852',
    referenceNumber: 'REF-IN-662319',
    entityId: 'DENAVE_IN',
    subjectId: 'ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d',
    requestType: 'CORRECTION',
    status: 'FULFILLED',
    receivedAt: '2026-07-10T16:00:00Z',
    statutoryDeadline: '2026-08-09T16:00:00Z',
    daysRemaining: 0,
    bornOverdue: false,
    slaRisk: 'HEALTHY',
    verificationMethod: 'EMPLOYEE_ID_CHECK',
    verifiedAt: '2026-07-11T10:00:00Z',
    fulfilledAt: '2026-07-15T14:30:00Z',
    channel: 'FIELD_FORCE',
    details: { field: 'Official designation and department updated in DenCRM' }
  }
];

export const MOCK_EVIDENCE_BUNDLE: EvidenceBundleResponse = {
  entityId: 'MATRIX',
  subjectId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
  assembledAt: '2026-08-21T04:30:00Z',
  actorAttribution: 'dpo.officer@uds.co.in',
  decisionsSummary: {
    totalEvaluations: 42,
    allowedCount: 40,
    deniedCount: 2,
    lastEvaluationAt: '2026-08-20T18:12:00Z'
  },
  receipts: [
    {
      receiptId: 'RCP-2026-MTRX-001',
      schemaVersion: '27560-2023-receipt-subset',
      entityId: 'MATRIX',
      subjectId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
      jurisdiction: 'IN',
      status: 'GRANTED',
      grantedAt: '2026-06-15T10:30:00Z',
      noticeId: 'NOTICE_MATRIX_BGV_CANDIDATE',
      noticeVersion: 2,
      purposes: [
        { code: 'BGV_EMPLOYMENT_HISTORY', title: 'Employment History Verification', description: 'Verification of past employment records and salary slips with former employers', legalBasis: 'DPDP Section 6(1) Consent', retentionDays: 730 },
        { code: 'BGV_EDUCATION_CREDENTIALS', title: 'Education Credential Verification', description: 'Verification of university degrees and certifications with issuing institutions', legalBasis: 'DPDP Section 6(1) Consent', retentionDays: 730 }
      ],
      dataCategories: ['CONTACT_INFO', 'EMPLOYMENT_RECORDS', 'EDUCATION_CERTIFICATES'],
      recipients: ['VERIFICATION_PARTNER_CREDENTIALS_LTD', 'PRIMARY_EMPLOYER_CLIENT'],
      crossBorderCountries: [],
      dpoContact: 'compliance@matrixbsindia.net',
      grievanceUri: 'https://matrixbsindia.net/grievance',
      withdrawalUri: 'https://privacy.uds.co.in/withdraw'
    }
  ],
  rightsRequests: [
    MOCK_RIGHTS_REQUESTS[1]
  ],
  timeline: [
    {
      eventId: 'evt_001_genesis',
      sequenceNumber: 1,
      eventType: 'NOTICE_SERVED',
      entityId: 'MATRIX',
      subjectId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
      noticeVersion: 2,
      channel: 'WEB',
      timestamp: '2026-06-15T10:28:12Z',
      sha256Hash: 'a71b22e89d109ef32a4e98f01b7a69c0d12e847fae30129bc81792fca0199182',
      previousHash: '0000000000000000000000000000000000000000000000000000000000000000',
      verifiedChain: true
    },
    {
      eventId: 'evt_002_capture',
      sequenceNumber: 2,
      eventType: 'CONSENT_GRANTED',
      entityId: 'MATRIX',
      subjectId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
      purposeCode: 'BGV_EMPLOYMENT_HISTORY',
      noticeVersion: 2,
      channel: 'WEB',
      captureMethod: 'ELECTRONIC_SIGNATURE',
      actorId: 'candidate_direct',
      timestamp: '2026-06-15T10:30:00Z',
      sha256Hash: '49cf281a980b1928014e7a881920acb10928aee1029384756102938475610293',
      previousHash: 'a71b22e89d109ef32a4e98f01b7a69c0d12e847fae30129bc81792fca0199182',
      verifiedChain: true
    }
  ]
};

export const MOCK_SWEEPER_RESULTS: IntegritySweepResult = {
  sweepId: 'SWEEP-20260821-001',
  executedAt: '2026-08-21T02:00:00Z',
  durationMs: 1420,
  chainsScanned: 184500,
  verifiedValidChains: 184500,
  divergencesFound: 0,
  status: 'CLEAN'
};

export const MOCK_PROPAGATION_GAPS: PropagationGapReport[] = [
  {
    entityId: 'DENAVE_IN',
    systemCode: 'DENCRM',
    unmetDays: 0,
    uncoveredCount: 0,
    lastSuccessfulDelivery: '2026-08-21T04:15:00Z',
    status: 'CLEAN'
  },
  {
    entityId: 'ATHENA',
    systemCode: 'ATHENA_DIALER',
    unmetDays: 0,
    uncoveredCount: 0,
    lastSuccessfulDelivery: '2026-08-21T03:50:00Z',
    status: 'CLEAN'
  },
  {
    entityId: 'MATRIX',
    systemCode: 'BGV_PORTAL',
    unmetDays: 0,
    uncoveredCount: 0,
    lastSuccessfulDelivery: '2026-08-20T22:10:00Z',
    status: 'CLEAN'
  }
];

export const MOCK_VENDORS: Vendor[] = [
  {
    id: 'VND-001',
    entityId: 'DENAVE_IN',
    vendorName: 'Twilio India Communication Services',
    category: 'COMMUNICATION_SMS_WHATSAPP',
    dpaReference: 'DPA-2025-TW-091',
    dpaSignedAt: '2025-01-10',
    dpaExpiresAt: '2027-01-10',
    active: true,
    authorizedPurposes: ['DIRECT_MARKETING_SMS', 'TRANSACTIONAL_NOTICES'],
    dataCategories: ['PHONE_NUMBER', 'CONTACT_NAME'],
    hostingCountry: 'India (ap-south-1)'
  },
  {
    id: 'VND-002',
    entityId: 'MATRIX',
    vendorName: 'National Judicial Data & Court Search API',
    category: 'GOVERNMENT_PUBLIC_RECORDS',
    dpaReference: 'DPA-2025-NJD-112',
    dpaSignedAt: '2025-03-01',
    dpaExpiresAt: '2027-03-01',
    active: true,
    authorizedPurposes: ['BGV_CRIMINAL_RECORD'],
    dataCategories: ['IDENTIFIER_HASH', 'NAME', 'DOB'],
    hostingCountry: 'India'
  }
];

export const MOCK_PROCESSING_ACTIVITIES: ProcessingActivity[] = [
  {
    id: 'ROPA-001',
    entityId: 'DENAVE_IN',
    activityName: 'B2B Sales Enablement & Telesales Outreach',
    purposeCode: 'DIRECT_MARKETING_VOICE',
    systemName: 'DenCRM & Athena Outbound',
    dataCategories: ['BUSINESS_CONTACT', 'NAME', 'COMPANY_NAME'],
    retentionPeriodMonths: 24,
    legalBasis: 'DPDP Act 2023 Sec 6(1) Consent',
    crossBorderTransfer: false
  },
  {
    id: 'ROPA-002',
    entityId: 'MATRIX',
    activityName: 'Pre-Employment Background Verification',
    purposeCode: 'BGV_EMPLOYMENT_HISTORY',
    systemName: 'Matrix Core BGV Engine',
    dataCategories: ['EMPLOYMENT_HISTORY', 'SALARY_SLIPS', 'IDENTITY_DOCS'],
    retentionPeriodMonths: 36,
    legalBasis: 'DPDP Act 2023 Sec 6(1) Explicit Consent',
    crossBorderTransfer: false
  }
];

export const MOCK_NOTICES: PrivacyNotice[] = [
  {
    noticeId: 'NOTICE_DENAVE_B2B',
    version: 2,
    jurisdiction: 'IN',
    languageTag: 'en',
    title: 'How Denave India Uses Your Business Contact Details',
    body: 'Denave India Private Limited processes your name, job title, employer and business contact details to contact you regarding relevant enterprise technology products and services on behalf of our clients. You may withdraw your consent at any time as easily as you gave it.',
    withdrawalUri: 'https://privacy.uds.co.in/withdraw',
    rightsUri: 'https://privacy.uds.co.in/rights',
    grievanceUri: 'https://privacy.uds.co.in/grievance',
    publishedAt: '2026-08-17T03:12:00Z',
    materialChange: false,
    availableLanguages: ['en', 'hi', 'ta', 'te', 'kn', 'bn', 'mr', 'gu']
  },
  {
    noticeId: 'NOTICE_MATRIX_BGV',
    version: 1,
    jurisdiction: 'IN',
    languageTag: 'en',
    title: 'Matrix Business Services Candidate Privacy Notice',
    body: 'Matrix Business Services India Private Limited conducts background verification on behalf of your prospective employer. We verify education credentials, previous employment records, and address history in accordance with applicable laws.',
    withdrawalUri: 'https://privacy.uds.co.in/withdraw',
    rightsUri: 'https://privacy.uds.co.in/rights',
    grievanceUri: 'https://matrixbsindia.net/grievance',
    publishedAt: '2026-05-10T08:00:00Z',
    materialChange: false,
    availableLanguages: ['en', 'hi', 'ta', 'te']
  }
];
