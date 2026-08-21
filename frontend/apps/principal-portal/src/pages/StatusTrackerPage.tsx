import React, { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Search, Clock, ShieldCheck, Download, CheckCircle2, AlertCircle } from 'lucide-react';
import { Badge } from '@uds/ui-core';

export const StatusTrackerPage: React.FC = () => {
  const [searchParams] = useSearchParams();
  const [refInput, setRefInput] = useState(searchParams.get('ref') || 'REF-IN-981247');
  const [hasQueried, setHasQueried] = useState(true);

  const mockStatus = {
    reference: refInput,
    entityName: 'Denave India Pvt Ltd',
    requestType: 'ERASURE',
    status: 'IN_REVIEW',
    statutoryDeadline: '16 Sep 2026',
    daysRemaining: 26,
    receivedAt: '17 Aug 2026, 14:22:10 UTC',
    verified: true,
    verificationMethod: 'PORTAL_TOKEN (One-Time Token Redeemed)'
  };

  const handleQuery = (e: React.FormEvent) => {
    e.preventDefault();
    setHasQueried(true);
  };

  return (
    <div className="max-w-3xl mx-auto py-8 px-4 space-y-6">
      <div className="text-center space-y-2">
        <h1 className="text-3xl font-bold tracking-tight text-slate-900 font-['Inter']">
          Track Your Privacy Request Status
        </h1>
        <p className="text-xs text-slate-500">
          Check real-time statutory SLA countdown and status for your rights request under DPDP Rule 14.
        </p>
      </div>

      {/* Query Bar */}
      <form onSubmit={handleQuery} className="bg-white p-4 rounded border border-slate-200 shadow-sm flex gap-3">
        <div className="relative flex-1">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            required
            value={refInput}
            onChange={(e) => setRefInput(e.target.value)}
            placeholder="Enter Reference Number (e.g. REF-IN-981247)..."
            className="w-full pl-9 pr-3 py-2 text-xs font-mono font-bold border border-slate-300 rounded focus:ring-1 focus:ring-indigo-600 focus:outline-none"
          />
        </div>
        <button
          type="submit"
          className="px-4 py-2 bg-slate-900 text-white rounded text-xs font-semibold hover:bg-black transition-colors"
        >
          Check Status
        </button>
      </form>

      {/* Status Details Card */}
      {hasQueried && (
        <div className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-6 sm:p-8 space-y-6">
          <div className="flex flex-wrap items-start justify-between gap-3 border-b border-slate-100 pb-4">
            <div>
              <div className="text-[11px] font-semibold text-slate-400 uppercase">
                Privacy Rights Request
              </div>
              <div className="font-mono text-xl font-bold text-slate-900 mt-0.5">
                {mockStatus.reference}
              </div>
              <div className="text-xs text-slate-500 mt-1">{mockStatus.entityName}</div>
            </div>
            <Badge variant="primary">{mockStatus.status.replace(/_/g, ' ')}</Badge>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
            <div className="p-3 bg-slate-50 rounded border border-slate-200">
              <div className="text-slate-500 font-medium">Request Type:</div>
              <div className="font-bold text-slate-900 mt-0.5">{mockStatus.requestType}</div>
            </div>

            <div className="p-3 bg-slate-50 rounded border border-slate-200">
              <div className="text-slate-500 font-medium">Submission Timestamp:</div>
              <div className="font-mono text-slate-900 mt-0.5">{mockStatus.receivedAt}</div>
            </div>

            <div className="p-3 bg-indigo-50 border border-indigo-200 rounded">
              <div className="text-indigo-900 font-semibold">Statutory SLA Deadline:</div>
              <div className="font-bold text-indigo-950 mt-0.5 flex items-center gap-1.5">
                <Clock className="w-4 h-4 text-indigo-700" />
                {mockStatus.statutoryDeadline} ({mockStatus.daysRemaining} Days Remaining)
              </div>
            </div>

            <div className="p-3 bg-emerald-50 border border-emerald-200 rounded">
              <div className="text-emerald-900 font-semibold">Identity Verification:</div>
              <div className="font-bold text-emerald-950 mt-0.5 flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 text-emerald-700" />
                {mockStatus.verificationMethod}
              </div>
            </div>
          </div>

          <div className="pt-4 border-t border-slate-100 flex items-center justify-between text-xs">
            <span className="text-slate-500">
              Under DPDP Rule 14(3), requests are resolved within 30 days of verification.
            </span>
          </div>
        </div>
      )}
    </div>
  );
};
