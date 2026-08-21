import React, { useState } from 'react';
import { RotateCw, ShieldCheck, CheckCircle2, Clock, Play, AlertCircle } from 'lucide-react';
import { MOCK_SWEEPER_RESULTS, IntegritySweepResult } from '@uds/api-client';
import { Badge, formatDate } from '@uds/ui-core';
import { useAuth } from '../auth/AuthProvider';

export const SweepersPage: React.FC = () => {
  const { selectedEntityId } = useAuth();
  const [isRunning, setIsRunning] = useState(false);
  const [lastResult, setLastResult] = useState<IntegritySweepResult>(MOCK_SWEEPER_RESULTS);

  const handleRunSweep = (type: string) => {
    setIsRunning(true);
    setTimeout(() => {
      setIsRunning(false);
      setLastResult({
        ...MOCK_SWEEPER_RESULTS,
        sweepId: `SWEEP-${Date.now().toString().slice(-6)}`,
        executedAt: new Date().toISOString(),
        durationMs: Math.floor(Math.random() * 800) + 900
      });
    }, 1400);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Cryptographic Integrity Sweepers & Background Diagnostics
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Automated hash-chain verification, projection reconciliation, and data retention sweeps.
          </p>
        </div>
        <button
          onClick={() => handleRunSweep('all')}
          disabled={isRunning}
          className="inline-flex items-center gap-1.5 px-3.5 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900 shadow-sm transition-colors disabled:opacity-50"
        >
          <RotateCw className={`w-3.5 h-3.5 ${isRunning ? 'animate-spin' : ''}`} />
          {isRunning ? 'Executing Global Sweeps...' : 'Run All Sweepers Now'}
        </button>
      </div>

      {/* Sweeper Control Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-5">
        {/* Sweeper 1 */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-3 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <ShieldCheck className="w-5 h-5 text-indigo-600" />
              <Badge variant="success">SCHEDULED DAILY</Badge>
            </div>
            <h3 className="text-sm font-bold text-slate-900 mt-2">
              Cryptographic Hash-Chain Sweep
            </h3>
            <p className="text-xs text-slate-500 mt-1">
              Verifies the SHA-256 parent-to-child cryptographic hash link across every consent event in the append-only ledger.
            </p>
          </div>
          <button
            onClick={() => handleRunSweep('integrity')}
            disabled={isRunning}
            className="w-full py-1.5 bg-slate-100 hover:bg-slate-200 text-slate-800 rounded text-xs font-semibold transition-colors flex items-center justify-center gap-1"
          >
            <Play className="w-3 h-3 text-indigo-600" />
            Run Ledger Hash Scan
          </button>
        </div>

        {/* Sweeper 2 */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-3 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <RotateCw className="w-5 h-5 text-indigo-600" />
              <Badge variant="success">SCHEDULED HOURLY</Badge>
            </div>
            <h3 className="text-sm font-bold text-slate-900 mt-2">
              Projection Divergence Reconciler
            </h3>
            <p className="text-xs text-slate-500 mt-1">
              Compares the materialized `consent_artefact` query table against the event ledger to ensure absolute state agreement.
            </p>
          </div>
          <button
            onClick={() => handleRunSweep('projection')}
            disabled={isRunning}
            className="w-full py-1.5 bg-slate-100 hover:bg-slate-200 text-slate-800 rounded text-xs font-semibold transition-colors flex items-center justify-center gap-1"
          >
            <Play className="w-3 h-3 text-indigo-600" />
            Reconcile Projections
          </button>
        </div>

        {/* Sweeper 3 */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-3 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <Clock className="w-5 h-5 text-indigo-600" />
              <Badge variant="success">SCHEDULED DAILY</Badge>
            </div>
            <h3 className="text-sm font-bold text-slate-900 mt-2">
              Statutory Retention & Erasure Sweeper
            </h3>
            <p className="text-xs text-slate-500 mt-1">
              Identifies expired consent periods and flags overdue retention schedules across all registered processing activities.
            </p>
          </div>
          <button
            onClick={() => handleRunSweep('retention')}
            disabled={isRunning}
            className="w-full py-1.5 bg-slate-100 hover:bg-slate-200 text-slate-800 rounded text-xs font-semibold transition-colors flex items-center justify-center gap-1"
          >
            <Play className="w-3 h-3 text-indigo-600" />
            Execute Retention Check
          </button>
        </div>
      </div>

      {/* Sweep Execution Summary Box */}
      <div className="bg-white p-6 rounded border border-slate-200 shadow-sm space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-4">
          <div className="flex items-center gap-2.5">
            <CheckCircle2 className="w-5 h-5 text-emerald-600" />
            <div>
              <h3 className="text-sm font-bold text-slate-900">
                Last Cryptographic Audit Summary ({lastResult.sweepId})
              </h3>
              <div className="text-[11px] text-slate-400 font-mono">
                Executed: {formatDate(lastResult.executedAt)} · Scope:{' '}
                {selectedEntityId || 'All UDS Group Entities'}
              </div>
            </div>
          </div>
          <Badge variant="success">STATUS: 100% VERIFIED CLEAN</Badge>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 text-center">
          <div className="p-3 bg-slate-50 rounded border border-slate-100">
            <div className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">Chains Scanned</div>
            <div className="text-xl font-bold font-mono text-slate-900 mt-1">
              {lastResult.chainsScanned.toLocaleString()}
            </div>
          </div>
          <div className="p-3 bg-slate-50 rounded border border-slate-100">
            <div className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">Verified Valid</div>
            <div className="text-xl font-bold font-mono text-emerald-700 mt-1">
              {lastResult.verifiedValidChains.toLocaleString()}
            </div>
          </div>
          <div className="p-3 bg-slate-50 rounded border border-slate-100">
            <div className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">Divergences</div>
            <div className="text-xl font-bold font-mono text-slate-900 mt-1">
              {lastResult.divergencesFound}
            </div>
          </div>
          <div className="p-3 bg-slate-50 rounded border border-slate-100">
            <div className="text-[11px] uppercase tracking-wider text-slate-400 font-semibold">Execution Duration</div>
            <div className="text-xl font-bold font-mono text-slate-900 mt-1">
              {lastResult.durationMs} ms
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
