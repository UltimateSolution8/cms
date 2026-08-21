import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ShieldCheck,
  FileCheck2,
  AlertTriangle,
  Radio,
  Layers,
  ArrowUpRight,
  TrendingUp,
  RotateCw,
  Search,
  ExternalLink
} from 'lucide-react';
import { StatCard, Badge, DataTable, Column } from '@uds/ui-core';
import { MOCK_FIDUCIARY_ENTITIES, MOCK_RIGHTS_REQUESTS, FiduciaryEntity } from '@uds/api-client';
import { useAuth } from '../auth/AuthProvider';

export const DashboardPage: React.FC = () => {
  const navigate = useNavigate();
  const { selectedEntityId } = useAuth();

  const filteredEntities = selectedEntityId
    ? MOCK_FIDUCIARY_ENTITIES.filter((e) => e.id === selectedEntityId)
    : MOCK_FIDUCIARY_ENTITIES;

  const totalConsents = filteredEntities.reduce((acc, e) => acc + (e.activeConsentsCount || 0), 0);
  const totalOpenDsr = filteredEntities.reduce((acc, e) => acc + (e.openDsrCount || 0), 0);
  const avgCompliance = (
    filteredEntities.reduce((acc, e) => acc + (e.complianceRate || 0), 0) / filteredEntities.length
  ).toFixed(1);

  const urgentRequests = MOCK_RIGHTS_REQUESTS.filter(
    (r) => (!selectedEntityId || r.entityId === selectedEntityId) && r.daysRemaining <= 10 && r.status !== 'FULFILLED'
  );

  const columns: Column<FiduciaryEntity>[] = [
    {
      header: 'Fiduciary Entity',
      accessorKey: 'name',
      cell: (ent) => (
        <div>
          <div className="font-semibold text-slate-900 dark:text-slate-100 flex items-center gap-1.5">
            {ent.name}
            <span className="text-[10px] font-mono bg-slate-100 dark:bg-slate-800 px-1 py-0.5 rounded text-slate-600">
              {ent.id}
            </span>
          </div>
          <div className="text-[11px] text-slate-500">{ent.category.replace(/_/g, ' ')}</div>
        </div>
      )
    },
    {
      header: 'Residency Region',
      accessorKey: 'residencyRegion',
      cell: (ent) => <span className="font-mono text-xs text-slate-600">{ent.residencyRegion}</span>
    },
    {
      header: 'Active Consents',
      accessorKey: 'activeConsentsCount',
      cell: (ent) => (
        <span className="font-mono font-semibold text-slate-800">
          {(ent.activeConsentsCount || 0).toLocaleString()}
        </span>
      )
    },
    {
      header: 'Open DSRs',
      accessorKey: 'openDsrCount',
      cell: (ent) => (
        <Badge variant={ent.openDsrCount && ent.openDsrCount > 3 ? 'warning' : 'neutral'} size="sm">
          {ent.openDsrCount} Pending
        </Badge>
      )
    },
    {
      header: 'Compliance Rate',
      accessorKey: 'complianceRate',
      cell: (ent) => (
        <div className="flex items-center gap-2">
          <div className="w-16 bg-slate-100 h-1.5 rounded-full overflow-hidden">
            <div
              className="bg-emerald-600 h-full rounded-full"
              style={{ width: `${ent.complianceRate || 100}%` }}
            />
          </div>
          <span className="font-mono font-semibold text-emerald-700 text-xs">{ent.complianceRate}%</span>
        </div>
      )
    },
    {
      header: 'Action',
      cell: (ent) => (
        <button
          onClick={() => navigate(`/rights?entityId=${ent.id}`)}
          className="text-xs text-indigo-700 hover:text-indigo-900 font-medium inline-flex items-center gap-1"
        >
          View Queue <ArrowUpRight className="w-3 h-3" />
        </button>
      )
    }
  ];

  return (
    <div className="space-y-6">
      {/* Page Title & Context Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            {selectedEntityId ? `${selectedEntityId} Compliance Dashboard` : 'Global Compliance Overview'}
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Real-time consent enforcement, statutory DSR clocks, and cryptographic ledger health.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => navigate('/evidence')}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-white border border-slate-300 rounded text-xs font-semibold text-slate-700 hover:bg-slate-50 shadow-sm transition-colors"
          >
            <Search className="w-3.5 h-3.5" />
            Lookup Subject
          </button>
          <button
            onClick={() => navigate('/sweepers')}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900 shadow-sm transition-colors"
          >
            <RotateCw className="w-3.5 h-3.5" />
            Run Integrity Sweep
          </button>
        </div>
      </div>

      {/* KPI Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Overall Compliance"
          value={`${avgCompliance}%`}
          subtitle="DPDP 2023 / TRAI adherence"
          change="+0.2% vs last mo"
          changeType="positive"
          activeBorder
          icon={<ShieldCheck className="w-5 h-5 text-emerald-600" />}
        />
        <StatCard
          title="Active Consents"
          value={totalConsents.toLocaleString()}
          subtitle="ISO 27560 append-only records"
          change="Hash-chained"
          changeType="neutral"
          icon={<Layers className="w-5 h-5 text-indigo-600" />}
        />
        <StatCard
          title="Statutory DSR Queue"
          value={totalOpenDsr}
          subtitle="30-day clock active"
          change={urgentRequests.length > 0 ? `${urgentRequests.length} SLA warnings` : 'All on track'}
          changeType={urgentRequests.length > 0 ? 'warning' : 'positive'}
          icon={<FileCheck2 className="w-5 h-5 text-amber-600" />}
        />
        <StatCard
          title="Outbox Webhook Gaps"
          value="0"
          subtitle="Downstream sync verified"
          change="Real-time"
          changeType="positive"
          icon={<Radio className="w-5 h-5 text-indigo-600" />}
        />
      </div>

      {/* SLA Alert Banner if urgent requests exist */}
      {urgentRequests.length > 0 && (
        <div className="bg-amber-50 border border-amber-200 rounded p-4 flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-amber-600 shrink-0 mt-0.5" />
          <div className="flex-1">
            <h4 className="text-xs font-bold text-amber-900">
              Statutory 30-Day Resolution SLA Warning ({urgentRequests.length} Request{urgentRequests.length > 1 ? 's' : ''})
            </h4>
            <p className="text-xs text-amber-800 mt-0.5">
              DPDP Rule 14(3) mandates statutory response within the declared window. Request{' '}
              <span className="font-mono font-semibold">{urgentRequests[0].referenceNumber}</span> has only{' '}
              <span className="font-semibold">{urgentRequests[0].daysRemaining} days remaining</span>.
            </p>
          </div>
          <button
            onClick={() => navigate('/rights')}
            className="px-3 py-1 bg-amber-600 text-white rounded text-xs font-semibold hover:bg-amber-700 transition-colors shrink-0"
          >
            Open Queue
          </button>
        </div>
      )}

      {/* Fiduciary Entities Table */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold uppercase tracking-wider text-slate-700">
            Fiduciary Entities & Residency Status
          </h2>
          <span className="text-xs text-slate-500 font-mono">
            {filteredEntities.length} Registered Fiduciaries
          </span>
        </div>
        <DataTable
          columns={columns}
          data={filteredEntities}
          keyExtractor={(item) => item.id}
          searchPlaceholder="Filter entities by name or region..."
          searchFilter={(item, query) =>
            item.name.toLowerCase().includes(query) ||
            item.id.toLowerCase().includes(query) ||
            item.residencyRegion.toLowerCase().includes(query)
          }
          pageSize={10}
        />
      </div>
    </div>
  );
};
