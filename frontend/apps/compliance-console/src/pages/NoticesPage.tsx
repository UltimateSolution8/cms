import React, { useState } from 'react';
import { ScrollText, Play, AlertOctagon, CheckCircle2, Globe2, ArrowRight } from 'lucide-react';
import { MOCK_NOTICES, PrivacyNotice, BlastRadiusReport } from '@uds/api-client';
import { Badge, Modal } from '@uds/ui-core';

export const NoticesPage: React.FC = () => {
  const [notices, setNotices] = useState<PrivacyNotice[]>(MOCK_NOTICES);
  const [selectedNotice, setSelectedNotice] = useState<PrivacyNotice>(MOCK_NOTICES[0]);
  const [targetVersion, setTargetVersion] = useState(selectedNotice.version + 1);
  const [isMaterialChange, setIsMaterialChange] = useState(true);
  const [blastRadius, setBlastRadius] = useState<BlastRadiusReport | null>(null);
  const [isSimulating, setIsSimulating] = useState(false);
  const [publishSuccess, setPublishSuccess] = useState(false);

  const handleRunSimulation = () => {
    setIsSimulating(true);
    setTimeout(() => {
      setIsSimulating(false);
      setBlastRadius({
        noticeId: selectedNotice.noticeId,
        fromVersion: selectedNotice.version,
        toVersion: targetVersion,
        isMaterialChange,
        totalAffectedConsents: 14200,
        requiresReConsentCount: isMaterialChange ? 3100 : 0,
        requiresNoticeUpdateOnlyCount: isMaterialChange ? 11100 : 14200,
        noActionRequiredCount: 0,
        affectedApplications: ['DENAVE_CRM', 'DENAVE_SFA_MOBILE', 'ATHENA_DIALER']
      });
    }, 600);
  };

  const handlePublish = () => {
    setNotices((prev) =>
      prev.map((n) =>
        n.noticeId === selectedNotice.noticeId ? { ...n, version: targetVersion } : n
      )
    );
    setSelectedNotice((prev) => ({ ...prev, version: targetVersion }));
    setBlastRadius(null);
    setPublishSuccess(true);
    setTimeout(() => setPublishSuccess(false), 4000);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Privacy Notices & Blast Radius Simulator
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Section 5 versioned notice manager with automated downstream re-consent calculation.
          </p>
        </div>
      </div>

      {/* Publish Alert */}
      {publishSuccess && (
        <div className="bg-emerald-50 border border-emerald-200 p-3 rounded flex items-center gap-2 text-xs text-emerald-800">
          <CheckCircle2 className="w-4 h-4 text-emerald-600" />
          <span>
            <strong>Notice Published Successfully:</strong> Version <span className="font-mono">v{targetVersion}</span> is now active across all decision gates.
          </span>
        </div>
      )}

      {/* Notice Selection & Editor Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left: Notice List */}
        <div className="bg-white p-4 rounded border border-slate-200 shadow-sm space-y-3">
          <h3 className="text-xs font-bold uppercase tracking-wider text-slate-500">
            Registered Privacy Notices
          </h3>
          <div className="space-y-2">
            {notices.map((n) => (
              <button
                key={n.noticeId}
                type="button"
                onClick={() => {
                  setSelectedNotice(n);
                  setTargetVersion(n.version + 1);
                  setBlastRadius(null);
                }}
                className={`w-full text-left p-3 rounded border transition-colors ${
                  selectedNotice.noticeId === n.noticeId
                    ? 'bg-indigo-50/70 border-indigo-300'
                    : 'bg-slate-50/50 border-slate-200 hover:bg-slate-100'
                }`}
              >
                <div className="flex items-center justify-between">
                  <span className="font-mono font-bold text-xs text-slate-900">{n.noticeId}</span>
                  <Badge variant="primary" size="sm">v{n.version}</Badge>
                </div>
                <div className="text-xs text-slate-600 mt-1 truncate">{n.title}</div>
                <div className="text-[10px] text-slate-400 font-mono mt-1">
                  Languages: {n.availableLanguages?.join(', ')}
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* Right: Blast Radius Simulator & Version Publisher */}
        <div className="lg:col-span-2 space-y-6">
          <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
            <div className="flex items-center justify-between border-b border-slate-100 pb-3">
              <div>
                <h2 className="text-sm font-bold text-slate-900">
                  {selectedNotice.title} (<span className="font-mono">v{selectedNotice.version}</span>)
                </h2>
                <div className="text-[11px] text-slate-400 font-mono">
                  Jurisdiction: {selectedNotice.jurisdiction} · Published: {selectedNotice.publishedAt}
                </div>
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">Notice Body Text</label>
              <textarea
                rows={4}
                defaultValue={selectedNotice.body}
                className="w-full p-2.5 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              />
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 mb-1">Target Version</label>
                <input
                  type="number"
                  value={targetVersion}
                  onChange={(e) => setTargetVersion(Number(e.target.value))}
                  className="w-full p-2 border border-slate-300 rounded text-xs font-mono focus:ring-1 focus:ring-indigo-600"
                />
              </div>
              <div className="flex items-center gap-2 pt-5">
                <input
                  type="checkbox"
                  id="materialChange"
                  checked={isMaterialChange}
                  onChange={(e) => setIsMaterialChange(e.target.checked)}
                  className="rounded text-indigo-600 focus:ring-indigo-500"
                />
                <label htmlFor="materialChange" className="text-xs font-semibold text-slate-700">
                  Material Change (Requires active re-consent)
                </label>
              </div>
            </div>

            <div className="pt-3 border-t border-slate-100 flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={handleRunSimulation}
                disabled={isSimulating}
                className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-slate-900 text-white rounded text-xs font-semibold hover:bg-black transition-colors"
              >
                <Play className="w-3.5 h-3.5 text-indigo-400" />
                {isSimulating ? 'Calculating Impact...' : 'Calculate Blast Radius'}
              </button>
            </div>
          </div>

          {/* Blast Radius Results Box */}
          {blastRadius && (
            <div className="bg-white p-5 rounded border border-indigo-200 shadow-md space-y-4">
              <div className="flex items-center justify-between border-b border-slate-100 pb-3">
                <div className="flex items-center gap-2">
                  <AlertOctagon className="w-4 h-4 text-indigo-600" />
                  <h3 className="text-sm font-bold text-slate-900">
                    Downstream Blast Radius Analysis (v{blastRadius.fromVersion} → v{blastRadius.toVersion})
                  </h3>
                </div>
                <Badge variant={blastRadius.isMaterialChange ? 'warning' : 'neutral'}>
                  {blastRadius.isMaterialChange ? 'MATERIAL CHANGE DETECTED' : 'MINOR UPDATE'}
                </Badge>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-center">
                <div className="bg-slate-50 p-3 rounded border border-slate-200">
                  <div className="text-[11px] font-semibold text-slate-500 uppercase">Total Affected Consents</div>
                  <div className="text-xl font-bold text-slate-900 font-mono mt-1">
                    {blastRadius.totalAffectedConsents.toLocaleString()}
                  </div>
                </div>
                <div className="bg-amber-50 p-3 rounded border border-amber-200">
                  <div className="text-[11px] font-semibold text-amber-700 uppercase">Requires Re-Consent</div>
                  <div className="text-xl font-bold text-amber-900 font-mono mt-1">
                    {blastRadius.requiresReConsentCount.toLocaleString()}
                  </div>
                </div>
                <div className="bg-emerald-50 p-3 rounded border border-emerald-200">
                  <div className="text-[11px] font-semibold text-emerald-700 uppercase">Notice Update Only</div>
                  <div className="text-xl font-bold text-emerald-900 font-mono mt-1">
                    {blastRadius.requiresNoticeUpdateOnlyCount.toLocaleString()}
                  </div>
                </div>
              </div>

              <div className="text-xs text-slate-600">
                <strong>Impacted Applications:</strong>{' '}
                {blastRadius.affectedApplications.map((app) => (
                  <span key={app} className="font-mono bg-slate-100 px-1.5 py-0.5 rounded mr-1">
                    {app}
                  </span>
                ))}
              </div>

              <div className="pt-3 border-t border-slate-100 flex items-center justify-between">
                <span className="text-xs text-slate-500">
                  Publishing will update the decision engine and trigger outbox re-consent tasks.
                </span>
                <button
                  type="button"
                  onClick={handlePublish}
                  className="px-4 py-2 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900 transition-colors shadow-sm"
                >
                  Publish Notice Version {blastRadius.toVersion}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
