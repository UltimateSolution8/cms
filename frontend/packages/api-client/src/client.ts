/**
 * UDS Consent Control Plane API Client
 * Supports both live Spring Boot REST API and offline mock fallback
 */

import {
  FiduciaryEntity,
  FiduciaryEntityId,
  RightsRequestItem,
  RightsQueueSummary,
  EvaluateRequest,
  EvaluateResponse,
  ConsentCaptureRequest,
  ConsentCaptureResponse,
  EvidenceBundleResponse,
  Vendor,
  ProcessingActivity,
  WebhookSubscription,
  PropagationGapReport,
  PrivacyNotice,
  BlastRadiusReport,
  IntegritySweepResult,
  AdminAuditEvent,
  ProblemDetail
} from './types';

export interface ApiClientConfig {
  baseUrl: string;
  getToken?: () => string | null;
  getEntityScope?: () => FiduciaryEntityId | null;
  getActor?: () => string | null;
}

export class UdsApiClient {
  private config: ApiClientConfig;

  constructor(config: ApiClientConfig) {
    this.config = config;
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.config.baseUrl}${path}`;
    const headers = new Headers(options.headers || {});

    if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
      headers.set('Content-Type', 'application/json');
    }
    if (!headers.has('Accept')) {
      headers.set('Accept', 'application/json, application/problem+json');
    }

    const token = this.config.getToken?.();
    if (token && !headers.has('Authorization')) {
      headers.set('Authorization', `Bearer ${token}`);
    }

    const actor = this.config.getActor?.();
    if (actor && !headers.has('X-UDS-Actor')) {
      headers.set('X-UDS-Actor', actor);
    }

    try {
      const response = await fetch(url, { ...options, headers });
      const correlationId = response.headers.get('X-Correlation-Id') || undefined;

      if (!response.ok) {
        let errorBody: any;
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('json')) {
          errorBody = await response.json();
        } else {
          errorBody = { title: response.statusText, detail: await response.text() };
        }

        const problem: ProblemDetail = {
          type: errorBody.type || 'about:blank',
          title: errorBody.title || `HTTP ${response.status}`,
          status: response.status,
          detail: errorBody.detail || 'An unexpected error occurred',
          instance: path,
          outstandingSystems: errorBody.outstandingSystems,
          requestType: errorBody.requestType,
          feature: errorBody.feature,
          availableLanguages: errorBody.availableLanguages,
          noticeId: errorBody.noticeId,
          noticeVersion: errorBody.noticeVersion,
          correlationId: correlationId || errorBody.correlationId
        };
        throw problem;
      }

      if (response.status === 204) {
        return {} as T;
      }

      return await response.json();
    } catch (err: any) {
      if (err.status && err.title) {
        throw err;
      }
      // Network failure
      throw {
        type: 'urn:uds:network-error',
        title: 'Service Connection Refused',
        status: 0,
        detail: err.message || 'Could not connect to UDS Consent Control Plane API.',
        instance: path
      } as ProblemDetail;
    }
  }

  // ==================== DASHBOARD & ENTITIES ====================

  async getFiduciaryEntities(): Promise<FiduciaryEntity[]> {
    return this.request<FiduciaryEntity[]>('/v1/admin/entities');
  }

  async getDashboardSummary(entityId?: FiduciaryEntityId): Promise<{
    activeConsents: number;
    complianceRate: number;
    openRightsRequests: number;
    sweepsClean: boolean;
    unmetPropagationGaps: number;
    entities: FiduciaryEntity[];
  }> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request(`/v1/admin/dashboard/summary${query}`);
  }

  // ==================== RIGHTS REQUESTS (DSR) ====================

  async getRightsQueue(entityId?: FiduciaryEntityId): Promise<RightsRequestItem[]> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<RightsRequestItem[]>(`/v1/rights/queue${query}`);
  }

  async getRightsSummary(entityId?: FiduciaryEntityId): Promise<RightsQueueSummary> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<RightsQueueSummary>(`/v1/rights/summary${query}`);
  }

  async getOverdueRights(): Promise<RightsRequestItem[]> {
    return this.request<RightsRequestItem[]>('/v1/rights/overdue');
  }

  async recordRightsVerification(requestId: string, method: string, details?: string): Promise<{ success: boolean }> {
    return this.request(`/v1/rights/${requestId}/verification`, {
      method: 'POST',
      body: JSON.stringify({ method, details, verifiedAt: new Date().toISOString() })
    });
  }

  async recordRightsFulfilment(requestId: string, systemActions: Array<{ systemCode: string; actionId: string }>): Promise<{ success: boolean; fulfilledAt: string }> {
    return this.request(`/v1/rights/${requestId}/fulfilment`, {
      method: 'POST',
      body: JSON.stringify({ systemActions, fulfilledAt: new Date().toISOString() })
    });
  }

  // ==================== EVIDENCE & AUDIT ====================

  async getSubjectEvidenceBundle(entityId: FiduciaryEntityId, subjectId: string): Promise<EvidenceBundleResponse> {
    return this.request<EvidenceBundleResponse>(`/v1/admin/evidence/subject/${entityId}/${subjectId}`);
  }

  async getAdminAuditTrail(entityId?: FiduciaryEntityId, limit = 50): Promise<AdminAuditEvent[]> {
    const query = new URLSearchParams();
    if (entityId) query.append('entityId', entityId);
    query.append('limit', limit.toString());
    return this.request<AdminAuditEvent[]>(`/v1/admin/audit?${query.toString()}`);
  }

  // ==================== REGISTRIES & BLAST RADIUS ====================

  async getVendors(entityId?: FiduciaryEntityId): Promise<Vendor[]> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<Vendor[]>(`/v1/admin/vendors${query}`);
  }

  async upsertVendor(vendor: Vendor): Promise<Vendor> {
    return this.request<Vendor>(`/v1/admin/vendors/${vendor.id}`, {
      method: 'PUT',
      body: JSON.stringify(vendor)
    });
  }

  async getProcessingActivities(entityId?: FiduciaryEntityId): Promise<ProcessingActivity[]> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<ProcessingActivity[]>(`/v1/admin/processing-activities${query}`);
  }

  async getWebhookSubscriptions(entityId?: FiduciaryEntityId): Promise<WebhookSubscription[]> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<WebhookSubscription[]>(`/v1/admin/subscriptions${query}`);
  }

  async getPropagationGaps(entityId?: FiduciaryEntityId): Promise<PropagationGapReport[]> {
    const query = entityId ? `?entityId=${entityId}` : '';
    return this.request<PropagationGapReport[]>(`/v1/admin/propagation/gaps${query}`);
  }

  async calculateNoticeBlastRadius(noticeId: string, targetVersion: number, materialChange: boolean): Promise<BlastRadiusReport> {
    return this.request<BlastRadiusReport>(`/v1/admin/notices/${noticeId}/blast-radius`, {
      method: 'POST',
      body: JSON.stringify({ targetVersion, materialChange })
    });
  }

  // ==================== SWEEPERS & SYSTEM HEALTH ====================

  async triggerIntegritySweep(entityId?: FiduciaryEntityId): Promise<IntegritySweepResult> {
    return this.request<IntegritySweepResult>('/v1/admin/integrity/sweep', {
      method: 'POST',
      body: JSON.stringify({ entityId: entityId || null })
    });
  }

  async getLastSweepResults(): Promise<{ integrity: IntegritySweepResult; projection: any; retention: any }> {
    return this.request('/v1/admin/sweeps/last');
  }

  // ==================== PUBLIC PORTAL API ====================

  async getPublicNotice(noticeId: string, jurisdiction = 'IN', lang = 'en'): Promise<PrivacyNotice> {
    return this.request<PrivacyNotice>(`/v1/notices/${noticeId}?jurisdiction=${jurisdiction}&lang=${lang}`);
  }

  async submitPortalRightsRequest(payload: {
    entityId: FiduciaryEntityId;
    identifier: string;
    requestType: string;
    details?: string;
  }): Promise<{ reference: string; expiresAt: string; message: string }> {
    return this.request('/v1/portal/requests', {
      method: 'POST',
      body: JSON.stringify(payload)
    });
  }

  async verifyPortalRequestToken(reference: string, code: string): Promise<{ success: boolean; status: string; subjectReceipts?: any[] }> {
    return this.request(`/v1/portal/requests/${reference}/verify`, {
      method: 'POST',
      body: JSON.stringify({ code })
    });
  }

  async getPortalRequestStatus(reference: string, code?: string): Promise<{ status: string; receivedAt: string; statutoryDeadline: string; details?: any }> {
    const query = code ? `?code=${encodeURIComponent(code)}` : '';
    return this.request(`/v1/portal/requests/${reference}${query}`);
  }

  // ==================== ENFORCEMENT & DECISION ====================

  async evaluateDecision(request: EvaluateRequest): Promise<EvaluateResponse> {
    return this.request<EvaluateResponse>('/v1/evaluate', {
      method: 'POST',
      body: JSON.stringify(request)
    });
  }

  async captureConsent(request: ConsentCaptureRequest): Promise<ConsentCaptureResponse> {
    return this.request<ConsentCaptureResponse>('/v1/consent', {
      method: 'POST',
      body: JSON.stringify(request)
    });
  }
}
