import React from 'react';
import { History, Shield, User, FileSpreadsheet } from 'lucide-react';
import { DataTable, Column, Badge, formatDate } from '@uds/ui-core';
import { AdminAuditEvent } from '@uds/api-client';

const MOCK_AUDIT_LOGS: AdminAuditEvent[] = [
  {
    id: 'AUD-001',
    actorId: 'dpo.officer@uds.co.in',
    action: 'EVIDENCE_BUNDLE_ASSEMBLED',
    resourceType: 'SUBJECT_EVIDENCE',
    resourceId: '5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8',
    timestamp: '2026-08-21T04:30:00Z',
    entityId: 'MATRIX',
    ipAddress: '10.240.12.8'
  },
  {
    id: 'AUD-002',
    actorId: 'compliance.denave@uds.co.in',
    action: 'RIGHTS_VERIFICATION_RECORDED',
    resourceType: 'RIGHTS_REQUEST',
    resourceId: 'REQ-2026-0852',
    timestamp: '2026-08-21T03:15:00Z',
    entityId: 'DENAVE_IN',
    ipAddress: '10.240.14.22'
  },
  {
    id: 'AUD-003',
    actorId: 'dpo.officer@uds.co.in',
    action: 'INTEGRITY_SWEEP_EXECUTED',
    resourceType: 'LEDGER_INTEGRITY',
    resourceId: 'SWEEP-20260821-001',
    timestamp: '2026-08-21T02:00:00Z',
    ipAddress: '10.240.12.8'
  },
  {
    id: 'AUD-004',
    actorId: 'compliance.matrix@uds.co.in',
    action: 'VENDOR_DPA_REGISTERED',
    resourceType: 'VENDOR',
    resourceId: 'VND-002',
    timestamp: '2026-08-20T16:40:00Z',
    entityId: 'MATRIX',
    ipAddress: '10.240.18.91'
  }
];

export const AuditPage: React.FC = () => {
  const columns: Column<AdminAuditEvent>[] = [
    {
      header: 'Timestamp',
      accessorKey: 'timestamp',
      cell: (a) => <span className="font-mono text-xs text-slate-700">{formatDate(a.timestamp)}</span>
    },
    {
      header: 'Actor (Human Attributed)',
      accessorKey: 'actorId',
      cell: (a) => (
        <div className="flex items-center gap-2">
          <User className="w-3.5 h-3.5 text-slate-400" />
          <span className="font-semibold text-slate-900 text-xs">{a.actorId}</span>
        </div>
      )
    },
    {
      header: 'Administrative Action',
      accessorKey: 'action',
      cell: (a) => (
        <Badge variant="primary" size="sm">
          {a.action}
        </Badge>
      )
    },
    {
      header: 'Resource & Scope',
      accessorKey: 'resourceType',
      cell: (a) => (
        <div>
          <div className="font-medium text-slate-800 text-xs">{a.resourceType}</div>
          <div className="font-mono text-[11px] text-slate-400 truncate max-w-xs">{a.resourceId}</div>
        </div>
      )
    },
    {
      header: 'Entity',
      accessorKey: 'entityId',
      cell: (a) => a.entityId ? <Badge variant="neutral">{a.entityId}</Badge> : <span className="text-slate-400 font-mono text-xs">GROUP</span>
    }
  ];

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Administrative Audit Trail
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Append-only record of all administrative actions and mutations under OIDC and Basic auth.
          </p>
        </div>
      </div>

      <DataTable
        columns={columns}
        data={MOCK_AUDIT_LOGS}
        keyExtractor={(item) => item.id}
        searchPlaceholder="Filter audit events by actor or action..."
        searchFilter={(item, q) =>
          item.actorId.toLowerCase().includes(q) ||
          item.action.toLowerCase().includes(q) ||
          item.resourceType.toLowerCase().includes(q)
        }
        pageSize={10}
      />
    </div>
  );
};
