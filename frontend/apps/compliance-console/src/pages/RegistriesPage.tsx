import React, { useState } from 'react';
import { Layers, ShieldAlert, Radio, FileText, Plus, CheckCircle, AlertTriangle } from 'lucide-react';
import { DataTable, Column, Badge, Modal } from '@uds/ui-core';
import {
  MOCK_VENDORS,
  MOCK_PROCESSING_ACTIVITIES,
  MOCK_PROPAGATION_GAPS,
  Vendor,
  ProcessingActivity,
  PropagationGapReport
} from '@uds/api-client';
import { useAuth } from '../auth/AuthProvider';

export const RegistriesPage: React.FC = () => {
  const { selectedEntityId } = useAuth();
  const [activeTab, setActiveTab] = useState<'vendors' | 'ropa' | 'propagation'>('vendors');
  const [vendors, setVendors] = useState<Vendor[]>(MOCK_VENDORS);
  const [isNewVendorModalOpen, setIsNewVendorModalOpen] = useState(false);
  const [newVendorName, setNewVendorName] = useState('');
  const [newDpaRef, setNewDpaRef] = useState('');

  const filteredVendors = vendors.filter((v) => !selectedEntityId || v.entityId === selectedEntityId);
  const filteredRopa = MOCK_PROCESSING_ACTIVITIES.filter((p) => !selectedEntityId || p.entityId === selectedEntityId);
  const filteredGaps = MOCK_PROPAGATION_GAPS.filter((g) => !selectedEntityId || g.entityId === selectedEntityId);

  const vendorColumns: Column<Vendor>[] = [
    {
      header: 'Vendor Name & Reference',
      accessorKey: 'vendorName',
      cell: (v) => (
        <div>
          <div className="font-semibold text-slate-900 flex items-center gap-1.5">
            {v.vendorName}
            <span className="text-[10px] font-mono bg-slate-100 px-1 py-0.2 rounded text-slate-600">
              {v.entityId}
            </span>
          </div>
          <div className="text-[11px] text-slate-500 font-mono">DPA: {v.dpaReference}</div>
        </div>
      )
    },
    {
      header: 'Category',
      accessorKey: 'category',
      cell: (v) => <span className="text-xs text-slate-700">{v.category}</span>
    },
    {
      header: 'Authorized Purposes',
      accessorKey: 'authorizedPurposes',
      cell: (v) => (
        <div className="flex flex-wrap gap-1">
          {v.authorizedPurposes.map((p) => (
            <span key={p} className="text-[10px] font-mono bg-indigo-50 text-indigo-700 border border-indigo-200 px-1.5 py-0.5 rounded">
              {p}
            </span>
          ))}
        </div>
      )
    },
    {
      header: 'DPA Validity',
      accessorKey: 'dpaExpiresAt',
      cell: (v) => (
        <div className="text-xs font-mono text-slate-600">
          Expires: {v.dpaExpiresAt}
        </div>
      )
    },
    {
      header: 'Status',
      accessorKey: 'active',
      cell: (v) => <Badge variant={v.active ? 'success' : 'critical'}>{v.active ? 'ACTIVE DPA' : 'EXPIRED'}</Badge>
    }
  ];

  const ropaColumns: Column<ProcessingActivity>[] = [
    {
      header: 'Activity Name',
      accessorKey: 'activityName',
      cell: (r) => (
        <div>
          <div className="font-semibold text-slate-900">{r.activityName}</div>
          <div className="text-[11px] font-mono text-indigo-700">{r.purposeCode}</div>
        </div>
      )
    },
    {
      header: 'System',
      accessorKey: 'systemName',
      cell: (r) => <span className="font-semibold text-slate-800 text-xs">{r.systemName}</span>
    },
    {
      header: 'Legal Basis',
      accessorKey: 'legalBasis',
      cell: (r) => <span className="text-xs text-slate-600">{r.legalBasis}</span>
    },
    {
      header: 'Retention',
      accessorKey: 'retentionPeriodMonths',
      cell: (r) => <span className="font-mono text-xs text-slate-700">{r.retentionPeriodMonths} Months</span>
    }
  ];

  const gapColumns: Column<PropagationGapReport>[] = [
    {
      header: 'System Code',
      accessorKey: 'systemCode',
      cell: (g) => <span className="font-mono font-bold text-slate-900">{g.systemCode}</span>
    },
    {
      header: 'Entity',
      accessorKey: 'entityId',
      cell: (g) => <Badge variant="primary">{g.entityId}</Badge>
    },
    {
      header: 'Unmet Propagation Days',
      accessorKey: 'unmetDays',
      cell: (g) => (
        <Badge variant={g.unmetDays === 0 ? 'success' : 'critical'}>
          {g.unmetDays === 0 ? '0 Days (Clean Sync)' : `${g.unmetDays} Days Out of Sync`}
        </Badge>
      )
    },
    {
      header: 'Last Delivery',
      accessorKey: 'lastSuccessfulDelivery',
      cell: (g) => <span className="font-mono text-xs text-slate-600">{g.lastSuccessfulDelivery || 'Never'}</span>
    }
  ];

  const handleAddVendor = () => {
    if (!newVendorName.trim()) return;
    const newV: Vendor = {
      id: `VND-${Date.now()}`,
      entityId: selectedEntityId || 'DENAVE_IN',
      vendorName: newVendorName,
      category: 'ENTERPRISE_SOFTWARE',
      dpaReference: newDpaRef || 'DPA-2026-NEW',
      dpaSignedAt: '2026-08-21',
      dpaExpiresAt: '2028-08-21',
      active: true,
      authorizedPurposes: ['DIRECT_MARKETING_EMAIL'],
      dataCategories: ['CONTACT_INFO'],
      hostingCountry: 'India (ap-south-1)'
    };
    setVendors([newV, ...vendors]);
    setIsNewVendorModalOpen(false);
    setNewVendorName('');
    setNewDpaRef('');
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            Compliance & Vendor Registries
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            Data Processing Agreements (DPAs), RoPA records, and downstream propagation registers.
          </p>
        </div>
        {activeTab === 'vendors' && (
          <button
            onClick={() => setIsNewVendorModalOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900 transition-colors shadow-sm"
          >
            <Plus className="w-4 h-4" />
            Register New Vendor / DPA
          </button>
        )}
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-4 border-b border-slate-200 text-xs font-semibold">
        <button
          onClick={() => setActiveTab('vendors')}
          className={`pb-2.5 border-b-2 transition-colors flex items-center gap-1.5 ${
            activeTab === 'vendors'
              ? 'border-indigo-600 text-indigo-700'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <Layers className="w-4 h-4" />
          Vendor & Processor Registry ({filteredVendors.length})
        </button>
        <button
          onClick={() => setActiveTab('ropa')}
          className={`pb-2.5 border-b-2 transition-colors flex items-center gap-1.5 ${
            activeTab === 'ropa'
              ? 'border-indigo-600 text-indigo-700'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <FileText className="w-4 h-4" />
          Processing Activities (RoPA) ({filteredRopa.length})
        </button>
        <button
          onClick={() => setActiveTab('propagation')}
          className={`pb-2.5 border-b-2 transition-colors flex items-center gap-1.5 ${
            activeTab === 'propagation'
              ? 'border-indigo-600 text-indigo-700'
              : 'border-transparent text-slate-500 hover:text-slate-800'
          }`}
        >
          <Radio className="w-4 h-4" />
          Propagation Health & Gaps ({filteredGaps.length})
        </button>
      </div>

      {/* Tab Content */}
      {activeTab === 'vendors' && (
        <DataTable
          columns={vendorColumns}
          data={filteredVendors}
          keyExtractor={(item) => item.id}
          searchPlaceholder="Search vendors or DPA references..."
          searchFilter={(item, q) =>
            item.vendorName.toLowerCase().includes(q) ||
            item.dpaReference.toLowerCase().includes(q) ||
            item.category.toLowerCase().includes(q)
          }
          pageSize={10}
        />
      )}

      {activeTab === 'ropa' && (
        <DataTable
          columns={ropaColumns}
          data={filteredRopa}
          keyExtractor={(item) => item.id}
          searchPlaceholder="Search processing activities..."
          searchFilter={(item, q) =>
            item.activityName.toLowerCase().includes(q) ||
            item.systemName.toLowerCase().includes(q) ||
            item.purposeCode.toLowerCase().includes(q)
          }
          pageSize={10}
        />
      )}

      {activeTab === 'propagation' && (
        <DataTable
          columns={gapColumns}
          data={filteredGaps}
          keyExtractor={(item) => `${item.entityId}-${item.systemCode}`}
          searchPlaceholder="Search system codes..."
          searchFilter={(item, q) => item.systemCode.toLowerCase().includes(q)}
          pageSize={10}
        />
      )}

      {/* Add Vendor Modal */}
      {isNewVendorModalOpen && (
        <Modal
          isOpen={isNewVendorModalOpen}
          onClose={() => setIsNewVendorModalOpen(false)}
          title="Register Third-Party Vendor / Processor"
          subtitle="Enforces Data Processing Agreement (DPA) validity before API decision path allows sharing"
          footer={
            <>
              <button
                onClick={() => setIsNewVendorModalOpen(false)}
                className="px-3 py-1.5 bg-white border border-slate-300 rounded text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                onClick={handleAddVendor}
                className="px-3 py-1.5 bg-[#1f108e] text-white rounded text-xs font-semibold hover:bg-indigo-900"
              >
                Save & Authorize
              </button>
            </>
          }
        >
          <div className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">Vendor / Entity Name</label>
              <input
                type="text"
                value={newVendorName}
                onChange={(e) => setNewVendorName(e.target.value)}
                placeholder="e.g. AWS Cloud Services India Pvt Ltd"
                className="w-full p-2 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              />
            </div>
            <div>
              <label className="block text-xs font-semibold text-slate-700 mb-1">DPA / Contract Reference</label>
              <input
                type="text"
                value={newDpaRef}
                onChange={(e) => setNewDpaRef(e.target.value)}
                placeholder="e.g. DPA-2026-AWS-IND"
                className="w-full p-2 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
              />
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
};
