import React, { useState } from 'react';
import {
  Clock,
  ShieldAlert,
  UserCheck,
  CheckCircle2,
  Filter,
  Eye,
  AlertCircle,
  FileSpreadsheet
} from 'lucide-react';
import { DataTable, Column, Badge, HashDisplay, Modal, formatDate } from '@uds/ui-core';
import { MOCK_RIGHTS_REQUESTS, RightsRequestItem, VerificationMethod } from '@uds/api-client';
import { useAuth } from '../auth/AuthProvider';

export const RightsQueuePage: React.FC = () => {
  const { selectedEntityId, user } = useAuth();
  const [requests, setRequests] = useState<RightsRequestItem[]>(MOCK_RIGHTS_REQUESTS);
  const [selectedRequest, setSelectedRequest] = useState<RightsRequestItem | null>(null);
  const [isVerifyModalOpen, setIsVerifyModalOpen] = useState(false);
  const [isFulfillModalOpen, setIsFulfillModalOpen] = useState(false);
  const [verificationMethod, setVerificationMethod] = useState<VerificationMethod>('OPERATOR_ASSERTED');
  const [verificationNote, setVerificationNote] = useState('');
  const [fulfilmentSystem, setFulfilmentSystem] = useState('DENCRM');
  const [fulfilmentActionId, setFulfilmentActionId] = useState('');

  const filteredRequests = requests.filter((r) => !selectedEntityId || r.entityId === selectedEntityId);

  const handleVerify = () => {
    if (!selectedRequest) return;
    setRequests((prev) =>
      prev.map((r) =>
        r.id === selectedRequest.id
          ? {
              ...r,
              verificationMethod,
              status: 'IN_REVIEW',
              verifiedAt: new Date().toISOString(),
              verifiedBy: user?.email || 'operator'
            }
          : r
      )
    );
    setIsVerifyModalOpen(false);
  };

  const handleFulfill = () => {
    if (!selectedRequest) return;
    setRequests((prev) =>
      prev.map((r) =>
        r.id === selectedRequest.id
          ? {
              ...r,
              status: 'FULFILLED',
              fulfilledAt: new Date().toISOString()
            }
          : r
      )
    );
    setIsFulfillModalOpen(false);
  };

  const columns: Column<RightsRequestItem>[] = [
    {
      header: 'Reference & Entity',
      accessorKey: 'referenceNumber',
      cell: (r) => (
        <div>
          <div className="font-mono font-bold text-slate-900 flex items-center gap-1.5">
            {r.referenceNumber}
            <span className="text-[10px] font-sans px-1 py-0.2 bg-slate-100 rounded text-slate-600">
              {r.entityId}
            </span>
          </div>
          <div className="text-[11px] text-slate-400 font-mono mt-0.5">
            <HashDisplay hash={r.subjectId} lead={5} trail={4} copyable={false} />
          </div>
        </div>
      )
    },
    {
      header: 'Type',
      accessorKey: 'requestType',
      cell: (r) => <span className="font-semibold text-slate-800 text-xs">{r.requestType}</span>
    },
    {
      header: 'Statutory 30-Day SLA Clock',
      accessorKey: 'daysRemaining',
      cell: (r) => {
        if (r.status === 'FULFILLED') {
          return (
            <Badge variant="success" size="sm" dot>
              Fulfilled on {formatDate(r.fulfilledAt).split(',')[0]}
            </Badge>
          );
        }
        if (r.daysRemaining <= 3) {
          return (
            <Badge variant="critical" size="sm" dot>
              {r.daysRemaining} Days Left (CRITICAL SLA)
            </Badge>
          );
        }
        if (r.daysRemaining <= 10) {
          return (
            <Badge variant="warning" size="sm" dot>
              {r.daysRemaining} Days Left (Warning)
            </Badge>
          );
        }
        return (
          <Badge variant="neutral" size="sm">
            {r.daysRemaining} Days Remaining
          </Badge>
        );
      }
    },
    {
      header: 'Verification State',
      accessorKey: 'verificationMethod',
      cell: (r) => {
        if (r.verificationMethod === 'UNVERIFIED') {
          return <Badge variant="warning">UNVERIFIED IDENTITY</Badge>;
        }
        return (
          <Badge variant="primary" size="sm">
            Verified ({r.verificationMethod})
          </Badge>
        );
      }
    },
    {
      header: 'Status',
      accessorKey: 'status',
      cell: (r) => {
        const variantMap: Record<string, any> = {
          RECEIVED: 'neutral',
          IN_REVIEW: 'primary',
          VERIFICATION_REQUIRED: 'warning',
          ACTION_REQUIRED: 'critical',
          FULFILLED: 'success'
        };
        return <Badge variant={variantMap[r.status] || 'neutral'}>{r.status}</Badge>;
      }
    },
    {
      header: 'Actions',
      cell: (r) => (
        <div className="flex items-center gap-2">
          {r.verificationMethod === 'UNVERIFIED' && (
            <button
              onClick={() => {
                setSelectedRequest(r);
                setIsVerifyModalOpen(true);
              }}
              className="px-2 py-1 bg-indigo-50 text-indigo-700 hover:bg-indigo-100 rounded text-xs font-semibold transition-colors"
            >
              Verify Identity
            </button>
          )}
          {r.verificationMethod !== 'UNVERIFIED' && r.status !== 'FULFILLED' && (
            <button
              onClick={() => {
                setSelectedRequest(r);
                setIsFulfillModalOpen(true);
              }}
              className="px-2 py-1 bg-emerald-50 text-emerald-700 hover:bg-emerald-100 rounded text-xs font-semibold transition-colors"
            >
              Record Fulfilment
            </button>
          )}
          <button
            onClick={() => setSelectedRequest(r)}
            className="p-1 text-slate-400 hover:text-slate-600 rounded"
            title="Inspect Request"
          >
            <Eye className="w-4 h-4" />
          </button>
        </div>
      )
    }
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Statutory Rights Request Queue (DSR)
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Enforces DPDP Rule 14(3) statutory 30-day response clock and evidence-gated fulfilment.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium text-slate-600 bg-white border border-slate-300 px-3 py-1.5 rounded shadow-sm">
            Total Queue Depth: <strong className="text-slate-900">{filteredRequests.length}</strong>
          </span>
        </div>
      </div>

      {/* Main Table */}
      <DataTable
        columns={columns}
        data={filteredRequests}
        keyExtractor={(item) => item.id}
        searchPlaceholder="Filter requests by reference, subject, or type..."
        searchFilter={(item, query) =>
          item.referenceNumber.toLowerCase().includes(query) ||
          item.subjectId.toLowerCase().includes(query) ||
          item.requestType.toLowerCase().includes(query) ||
          item.entityId.toLowerCase().includes(query)
        }
        pageSize={10}
      />

      {/* Verification Modal */}
      {isVerifyModalOpen && selectedRequest && (
        <Modal
          isOpen={isVerifyModalOpen}
          onClose={() => setIsVerifyModalOpen(false)}
          title={`Record Identity Verification — ${selectedRequest.referenceNumber}`}
          subtitle="DPDP Rule 14 / GDPR Art 12 Identity Verification Gate"
          footer={
            <>
              <button
                onClick={() => setIsVerifyModalOpen(false)}
                className="px-3 py-1.5 bg-white border border-slate-300 rounded text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                onClick={handleVerify}
                className="px-3 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900"
              >
                Commit Verification Evidence
              </button>
            </>
          }
        >
          <div className="space-y-4">
            <div className="bg-slate-50 p-3 rounded border border-slate-200">
              <div className="text-xs font-semibold text-slate-700">Subject Identifier (Hash):</div>
              <div className="font-mono text-xs text-slate-800 break-all mt-1">{selectedRequest.subjectId}</div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                Verification Method
              </label>
              <select
                value={verificationMethod}
                onChange={(e) => setVerificationMethod(e.target.value as VerificationMethod)}
                className="w-full p-2 border border-slate-300 rounded text-xs font-medium focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              >
                <option value="OPERATOR_ASSERTED">OPERATOR_ASSERTED (Desk / Document Check)</option>
                <option value="EMPLOYEE_ID_CHECK">EMPLOYEE_ID_CHECK (HRMS Verified)</option>
                <option value="SMS_OTP">SMS_OTP (One-Time Passcode Redeemed)</option>
                <option value="GOVERNMENT_ID_CHECK">GOVERNMENT_ID_CHECK (Aadhaar / PAN verification)</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                Evidence Note / Document Reference
              </label>
              <textarea
                rows={3}
                value={verificationNote}
                onChange={(e) => setVerificationNote(e.target.value)}
                placeholder="e.g. Employee ID #8841 verified in HRMS portal by compliance officer"
                className="w-full p-2 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              />
            </div>
          </div>
        </Modal>
      )}

      {/* Fulfilment Modal */}
      {isFulfillModalOpen && selectedRequest && (
        <Modal
          isOpen={isFulfillModalOpen}
          onClose={() => setIsFulfillModalOpen(false)}
          title={`Record Terminal Fulfilment — ${selectedRequest.referenceNumber}`}
          subtitle="Evidence Gate: Confirm terminal actions across connected systems"
          footer={
            <>
              <button
                onClick={() => setIsFulfillModalOpen(false)}
                className="px-3 py-1.5 bg-white border border-slate-300 rounded text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                onClick={handleFulfill}
                className="px-3 py-1.5 bg-emerald-700 text-white rounded text-xs font-semibold hover:bg-emerald-800"
              >
                Mark as FULFILLED
              </button>
            </>
          }
        >
          <div className="space-y-4">
            <div className="bg-emerald-50 border border-emerald-200 p-3 rounded text-xs text-emerald-800">
              Identity has been verified via <strong>{selectedRequest.verificationMethod}</strong>. Enter the downstream system ticket or action reference to close this statutory request.
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                Target System
              </label>
              <select
                value={fulfilmentSystem}
                onChange={(e) => setFulfilmentSystem(e.target.value)}
                className="w-full p-2 border border-slate-300 rounded text-xs font-medium focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              >
                <option value="DENCRM">DenCRM (Sales & Lead Records)</option>
                <option value="ATHENA_DIALER">Athena BPO Outbound Dialer</option>
                <option value="HRMS_CORE">UDS Group HRMS</option>
                <option value="BGV_CORE">Matrix Core Verification DB</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">
                System Action / Confirmation ID
              </label>
              <input
                type="text"
                value={fulfilmentActionId}
                onChange={(e) => setFulfilmentActionId(e.target.value)}
                placeholder="e.g. ACT-ERASURE-2026-091"
                className="w-full p-2 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              />
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
