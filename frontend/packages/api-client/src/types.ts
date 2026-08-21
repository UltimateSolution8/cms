/**
 * UDS Consent & Privacy Control Plane - TypeScript API Models
 * Mapped to OpenAPI 3.1 contract (docs/openapi.json) and UI Contract (docs/UI_CONTRACT.md)
 */

export type FiduciaryEntityId =
  | 'UDS'
  | 'DENAVE_IN'
  | 'DENAVE_UK'
  | 'DENAVE_MY'
  | 'DENAVE_SG'
  | 'DENAVE_KR'
  | 'MATRIX'
  | 'ATHENA'
  | 'AVON'
  | 'GFHS'
  | 'WHC'
  | 'FUSION_FOODS'
  | 'WYNWY'
  | 'UDS_FOUNDATION'
  | 'TANGY';

export interface FiduciaryEntity {
  id: FiduciaryEntityId;
  name: string;
  shortName: string;
  category: 'PARENT' | 'SALES_ENABLEMENT' | 'BACKGROUND_VERIFICATION' | 'BPO' | 'FACILITY_MANAGEMENT' | 'LOGISTICS' | 'HYGIENE' | 'CATERING' | 'FOUNDATION';
  residencyRegion: string;
  dpoContact?: string | null;
  grievanceUri?: string | null;
  activeConsentsCount?: number;
  openDsrCount?: number;
  complianceRate?: number;
}

export type JurisdictionCode =
  | 'IN'
  | 'UK'
  | 'EU'
  | 'MY'
  | 'SG'
  | 'KR'
  | 'US_CA'
  | 'US_VA'
  | 'US_CO'
  | 'US_CT'
  | 'US_UT';

export type CaptureChannel =
  | 'WEB'
  | 'MOBILE'
  | 'IVR'
  | 'KIOSK'
  | 'FIELD_FORCE'
  | 'BULK_IMPORT'
  | 'API'
  | 'PORTAL';

export type CaptureMethod =
  | 'CHECKBOX_OPT_IN'
  | 'FORM_SUBMISSION'
  | 'ELECTRONIC_SIGNATURE'
  | 'VOICE_CONFIRMATION'
  | 'IMPLIED_ENGAGEMENT'
  | 'OPERATOR_ASSERTED'
  | 'GUARDIAN_VERIFIED';

export type ConsentState =
  | 'GRANTED'
  | 'WITHDRAWN'
  | 'DENIED'
  | 'NOT_ASKED'
  | 'EXPIRED'
  | 'INVALIDATED'
  | 'PENDING_SYNC'
  | 'CONFLICTED'
  | 'UNKNOWN';

export type RightsRequestType =
  | 'ACCESS'
  | 'CORRECTION'
  | 'ERASURE'
  | 'GRIEVANCE'
  | 'NOMINATION'
  | 'CONSENT_WITHDRAWAL'
  | 'PORTABILITY'
  | 'COMPLETION'
  | 'OPT_OUT_OF_SALE';

export type RightsRequestStatus =
  | 'RECEIVED'
  | 'IN_REVIEW'
  | 'VERIFICATION_REQUIRED'
  | 'ACTION_REQUIRED'
  | 'FULFILLED'
  | 'REJECTED'
  | 'EXPIRED';

export type VerificationMethod =
  | 'PORTAL_TOKEN'
  | 'EMAIL_OTP'
  | 'SMS_OTP'
  | 'GOVERNMENT_ID_CHECK'
  | 'EMPLOYEE_ID_CHECK'
  | 'OPERATOR_ASSERTED'
  | 'UNVERIFIED';

export interface RightsRequestItem {
  id: string;
  referenceNumber: string;
  entityId: FiduciaryEntityId;
  subjectId: string;
  requestType: RightsRequestType;
  status: RightsRequestStatus;
  receivedAt: string;
  statutoryDeadline: string;
  daysRemaining: number;
  bornOverdue: boolean;
  slaRisk: 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'OVERDUE';
  verificationMethod: VerificationMethod;
  verifiedAt?: string | null;
  verifiedBy?: string | null;
  fulfilledAt?: string | null;
  channel: CaptureChannel;
  details?: Record<string, any>;
  outstandingSystems?: string[];
}

export interface RightsQueueSummary {
  totalOpen: number;
  urgentCount: number;
  bornOverdueCount: number;
  breachRiskCount: number;
  byEntity: Record<string, number>;
  byType: Record<string, number>;
}

export interface EvaluateRequest {
  entityId: FiduciaryEntityId;
  subjectId: string;
  applicationId: string;
  purposeCode: string;
  dataCategory?: string;
  vendorId?: string;
  jurisdiction?: JurisdictionCode;
}

export interface EvaluateResponse {
  decision: 'ALLOW' | 'DENY';
  reason?: string;
  applicableLaw?: string;
  noticeVersion?: number;
  consentedAt?: string;
  retryAfterDays?: number | null;
  evaluatedAt: string;
  gatePassedCount?: number;
  gateFailed?: string | null;
}

export interface ConsentCaptureRequest {
  entityId: FiduciaryEntityId;
  subjectId: string;
  jurisdiction: JurisdictionCode;
  languageTag: string;
  channel: CaptureChannel;
  applicationId: string;
  captureMethod: CaptureMethod;
  purposes: Array<{
    purposeCode: string;
    granted: boolean;
    noticeId: string;
    noticeVersion: number;
  }>;
  guardianVerified?: boolean;
  guardianSubjectId?: string;
}

export interface ConsentCaptureResponse {
  accepted: boolean;
  eventId?: string;
  recordedAt?: string;
  violations?: string[];
}

export interface ConsentReceipt {
  receiptId: string;
  schemaVersion: string;
  entityId: FiduciaryEntityId;
  subjectId: string;
  jurisdiction: JurisdictionCode;
  status: ConsentState;
  grantedAt: string;
  withdrawnAt?: string | null;
  noticeId: string;
  noticeVersion: number;
  purposes: Array<{
    code: string;
    title: string;
    description: string;
    legalBasis: string;
    retentionDays?: number;
  }>;
  dataCategories: string[];
  recipients?: string[] | null;
  crossBorderCountries?: string[] | null;
  dpoContact?: string | null;
  grievanceUri?: string | null;
  withdrawalUri?: string | null;
}

export interface ConsentEventTimelineItem {
  eventId: string;
  sequenceNumber: number;
  eventType: 'CONSENT_GRANTED' | 'CONSENT_WITHDRAWN' | 'NOTICE_SERVED' | 'CONSENT_MODIFIED' | 'SUBJECT_MERGED';
  entityId: FiduciaryEntityId;
  subjectId: string;
  purposeCode?: string;
  noticeVersion?: number;
  channel: CaptureChannel;
  captureMethod?: CaptureMethod;
  actorId?: string;
  timestamp: string;
  sha256Hash: string;
  previousHash: string;
  verifiedChain: boolean;
  details?: Record<string, any>;
}

export interface EvidenceBundleResponse {
  entityId: FiduciaryEntityId;
  subjectId: string;
  assembledAt: string;
  actorAttribution: string;
  timeline: ConsentEventTimelineItem[];
  receipts: ConsentReceipt[];
  rightsRequests: RightsRequestItem[];
  decisionsSummary: {
    totalEvaluations: number;
    allowedCount: number;
    deniedCount: number;
    lastEvaluationAt?: string;
  };
  truncation?: {
    isTruncated: boolean;
    returned: number;
    mergedFrom?: string[];
    remainderAt?: string | null;
  };
}

export interface Vendor {
  id: string;
  entityId: FiduciaryEntityId;
  vendorName: string;
  category: string;
  dpaReference: string;
  dpaSignedAt: string;
  dpaExpiresAt: string;
  active: boolean;
  authorizedPurposes: string[];
  dataCategories: string[];
  hostingCountry: string;
}

export interface ProcessingActivity {
  id: string;
  entityId: FiduciaryEntityId;
  activityName: string;
  purposeCode: string;
  systemName: string;
  dataCategories: string[];
  retentionPeriodMonths: number;
  legalBasis: string;
  crossBorderTransfer: boolean;
  destinationCountries?: string[];
}

export interface WebhookSubscription {
  id: string;
  entityId: FiduciaryEntityId;
  systemCode: string;
  targetUrl: string;
  eventTypes: string[];
  active: boolean;
  lastDeliveryAt?: string;
  consecutiveFailures: number;
}

export interface PropagationGapReport {
  entityId: FiduciaryEntityId;
  systemCode: string;
  unmetDays: number;
  uncoveredCount: number;
  lastSuccessfulDelivery?: string | null;
  status: 'CLEAN' | 'WARNING' | 'ALERT';
}

export interface PrivacyNotice {
  noticeId: string;
  version: number;
  jurisdiction: JurisdictionCode;
  languageTag: string;
  title: string;
  body: string;
  withdrawalUri: string;
  rightsUri: string;
  grievanceUri: string;
  publishedAt: string;
  materialChange: boolean;
  availableLanguages?: string[];
}

export interface BlastRadiusReport {
  noticeId: string;
  fromVersion: number;
  toVersion: number;
  isMaterialChange: boolean;
  totalAffectedConsents: number;
  requiresReConsentCount: number;
  requiresNoticeUpdateOnlyCount: number;
  noActionRequiredCount: number;
  affectedApplications: string[];
}

export interface IntegritySweepResult {
  sweepId: string;
  executedAt: string;
  durationMs: number;
  chainsScanned: number;
  verifiedValidChains: number;
  divergencesFound: number;
  status: 'CLEAN' | 'DIVERGENCE_DETECTED' | 'FAILED';
  divergences?: Array<{
    entityId: string;
    subjectId: string;
    brokenAtSequence: number;
    expectedHash: string;
    actualHash: string;
  }>;
}

export interface AdminAuditEvent {
  id: string;
  entityId?: string;
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  timestamp: string;
  ipAddress?: string;
  details?: Record<string, any>;
}

export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
  outstandingSystems?: string[];
  requestType?: string;
  feature?: string;
  availableLanguages?: string[];
  noticeId?: string;
  noticeVersion?: number;
  correlationId?: string;
}
