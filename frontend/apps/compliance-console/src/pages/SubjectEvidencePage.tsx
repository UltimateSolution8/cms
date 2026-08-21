import React, { useState } from 'react';
import {
  Search,
  ShieldCheck,
  Download,
  FileCode,
  Layers,
  History,
  CheckCircle,
  Copy,
  AlertCircle
} from 'lucide-react';
import { Timeline, Badge, HashDisplay, formatDate } from '@uds/ui-core';
import { MOCK_EVIDENCE_BUNDLE, EvidenceBundleResponse, FiduciaryEntityId } from '@uds/api-client';
import { useAuth } from '../auth/AuthProvider';

export const SubjectEvidencePage: React.FC = () => {
  const { selectedEntityId } = useAuth();
  const [searchHash, setSearchHash] = useState('5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8');
  const [activeTab, setActiveTab] = useState<'timeline' | 'receipts' | 'decisions'>('timeline');
  const [bundle, setBundle] = useState<EvidenceBundleResponse | null>(MOCK_EVIDENCE_BUNDLE);
  const [isExporting, setIsExporting] = useState(false);
  const [exportSuccess, setExportSuccess] = useState(false);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchHash.trim()) return;
    setBundle({
      ...MOCK_EVIDENCE_BUNDLE,
      subjectId: searchHash,
      entityId: selectedEntityId || 'MATRIX'
    });
  };

  const handleExport = () => {
    setIsExporting(true);
    setTimeout(() => {
      setIsExporting(false);
      setExportSuccess(true);
      setTimeout(() => setExportSuccess(false), 4000);
    }, 1200);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Subject Evidence Inspector
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Regulator-ready ISO 27560 evidence bundles with cryptographic SHA-256 hash chaining.
          </p>
        </div>
        {bundle && (
          <button
            onClick={handleExport}
            disabled={isExporting}
            className="inline-flex items-center gap-2 px-3.5 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900 shadow-sm transition-colors disabled:opacity-50"
          >
            {isExporting ? (
              <>Assembling Evidence Bundle...</>
            ) : (
              <>
                <Download className="w-3.5 h-3.5" />
                Export Signed Bundle (JSON)
              </>
            )}
          </button>
        )}
      </div>

      {/* Export Success Alert */}
      {exportSuccess && (
        <div className="bg-emerald-50 border border-emerald-200 p-3 rounded flex items-center justify-between text-xs text-emerald-800">
          <div className="flex items-center gap-2">
            <CheckCircle className="w-4 h-4 text-emerald-600" />
            <span>
              <strong>Evidence Bundle Exported:</strong> SHA-256 Checksum <span className="font-mono">e3b0c442...</span> digitally signed with UDS Ed25519 key.
            </span>
          </div>
          <span className="font-semibold text-emerald-900">Downloaded</span>
        </div>
      )}

      {/* Lookup Bar */}
      <div className="bg-white p-4 rounded border border-slate-200 shadow-sm">
        <form onSubmit={handleSearch} className="flex flex-col sm:flex-row gap-3">
          <div className="relative flex-1">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              type="text"
              value={searchHash}
              onChange={(e) => setSearchHash(e.target.value)}
              placeholder="Enter SHA-256 Subject ID, phone hash, email hash, or PAN..."
              className="w-full pl-9 pr-3 py-2 text-xs font-mono bg-slate-50 border border-slate-300 rounded focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            />
          </div>
          <button
            type="submit"
            className="px-4 py-2 bg-slate-900 text-white rounded text-xs font-semibold hover:bg-black transition-colors"
          >
            Query Evidence
          </button>
        </form>
      </div>

      {/* Bundle Overview Card */}
      {bundle && (
        <div className="space-y-6">
          <div className="bg-white p-5 rounded border border-slate-200 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-100 pb-4">
              <div>
                <div className="text-[11px] uppercase tracking-wider font-semibold text-slate-400">
                  Data Principal Subject Identifier
                </div>
                <div className="font-mono text-sm font-bold text-slate-900 mt-1 flex items-center gap-2">
                  <HashDisplay hash={bundle.subjectId} lead={12} trail={12} />
                  <Badge variant="primary">{bundle.entityId}</Badge>
                </div>
              </div>

              <div className="flex items-center gap-6 text-xs">
                <div>
                  <div className="text-slate-400 font-medium">Assembled By</div>
                  <div className="font-semibold text-slate-800 mt-0.5">{bundle.actorAttribution}</div>
                </div>
                <div>
                  <div className="text-slate-400 font-medium">Assembled At</div>
                  <div className="font-mono text-slate-800 mt-0.5">{formatDate(bundle.assembledAt)}</div>
                </div>
                <div>
                  <div className="text-slate-400 font-medium">Decisions Evaluated</div>
                  <div className="font-mono font-bold text-slate-800 mt-0.5">
                    {bundle.decisionsSummary.totalEvaluations} ({bundle.decisionsSummary.allowedCount} Allowed)
                  </div>
                </div>
              </div>
            </div>

            {/* Navigation Tabs */}
            <div className="flex items-center gap-4 mt-4 text-xs font-semibold">
              <button
                onClick={() => setActiveTab('timeline')}
                className={`pb-2 border-b-2 transition-colors flex items-center gap-1.5 ${
                  activeTab === 'timeline'
                    ? 'border-indigo-600 text-indigo-700'
                    : 'border-transparent text-slate-500 hover:text-slate-800'
                }`}
              >
                <History className="w-3.5 h-3.5" />
                Append-Only Hash Timeline ({bundle.timeline.length})
              </button>
              <button
                onClick={() => setActiveTab('receipts')}
                className={`pb-2 border-b-2 transition-colors flex items-center gap-1.5 ${
                  activeTab === 'receipts'
                    ? 'border-indigo-600 text-indigo-700'
                    : 'border-transparent text-slate-500 hover:text-slate-800'
                }`}
              >
                <FileCode className="w-3.5 h-3.5" />
                ISO 27560 Consent Receipts ({bundle.receipts.length})
              </button>
            </div>
          </div>

          {/* Tab 1: Timeline */}
          {activeTab === 'timeline' && (
            <div className="bg-white p-6 rounded border border-slate-200 shadow-sm">
              <h3 className="text-sm font-bold text-slate-900 mb-6 uppercase tracking-wider">
                Cryptographic Event Chain (SHA-256 Linked)
              </h3>
              <Timeline events={bundle.timeline} />
            </div>
          )}

          {/* Tab 2: ISO 27560 Receipts */}
          {activeTab === 'receipts' && (
            <div className="space-y-4">
              {bundle.receipts.map((rcp) => (
                <div key={rcp.receiptId} className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
                  <div className="flex items-start justify-between border-b border-slate-100 pb-3">
                    <div>
                      <div className="font-mono font-bold text-slate-900">{rcp.receiptId}</div>
                      <div className="text-[11px] text-slate-500 font-mono mt-0.5">
                        Schema: {rcp.schemaVersion} · Notice: {rcp.noticeId} (v{rcp.noticeVersion})
                      </div>
                    </div>
                    <Badge variant={rcp.status === 'GRANTED' ? 'success' : 'critical'}>
                      {rcp.status}
                    </Badge>
                  </div>

                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
                    <div>
                      <div className="font-semibold text-slate-700 mb-1">Consented Purposes:</div>
                      <div className="space-y-2">
                        {rcp.purposes.map((p) => (
                          <div key={p.code} className="bg-slate-50 p-2.5 rounded border border-slate-200">
                            <div className="font-semibold text-indigo-700">{p.title} ({p.code})</div>
                            <div className="text-slate-500 mt-0.5">{p.description}</div>
                            <div className="text-[10px] text-slate-400 font-mono mt-1">Legal Basis: {p.legalBasis}</div>
                          </div>
                        ))}
                      </div>
                    </div>

                    <div className="space-y-3">
                      <div>
                        <div className="font-semibold text-slate-700 mb-0.5">Data Categories:</div>
                        <div className="flex flex-wrap gap-1">
                          {rcp.dataCategories.map((c) => (
                            <span key={c} className="text-[11px] font-mono bg-slate-100 px-1.5 py-0.5 rounded text-slate-700">
                              {c}
                            </span>
                          ))}
                        </div>
                      </div>

                      <div>
                        <div className="font-semibold text-slate-700 mb-0.5">Authorized Third-Party Recipients:</div>
                        <div className="text-slate-600 text-xs">
                          {rcp.recipients && rcp.recipients.length > 0 ? rcp.recipients.join(', ') : 'None / Not Shared'}
                        </div>
                      </div>

                      <div className="pt-2 border-t border-slate-100 text-[11px] text-slate-500 font-mono">
                        Granted: {formatDate(rcp.grantedAt)}
                      </div>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
};
