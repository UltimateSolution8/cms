import React from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  LayoutDashboard,
  FileCheck2,
  ShieldAlert,
  Layers,
  ScrollText,
  RotateCw,
  History,
  Settings,
  Shield,
  LogOut,
  User,
  AlertOctagon,
  ExternalLink
} from 'lucide-react';
import { EntitySelector } from '@uds/ui-core';
import { MOCK_FIDUCIARY_ENTITIES } from '@uds/api-client';
import { useAuth } from '../auth/AuthProvider';
import { CrossEntityErrorPage } from '../pages/CrossEntityErrorPage';

export const ConsoleShell: React.FC = () => {
  const { user, selectedEntityId, setSelectedEntityId, logout } = useAuth();
  const location = useLocation();

  if (user?.isOverAssigned) {
    return <CrossEntityErrorPage />;
  }

  const navItems = [
    { to: '/', label: 'Global Dashboard', icon: LayoutDashboard },
    { to: '/rights', label: 'Rights Request Queue', icon: FileCheck2 },
    { to: '/evidence', label: 'Subject Evidence', icon: ShieldAlert },
    { to: '/registries', label: 'Compliance Registries', icon: Layers },
    { to: '/notices', label: 'Notices & Blast Radius', icon: ScrollText },
    { to: '/sweepers', label: 'Integrity Sweepers', icon: RotateCw },
    { to: '/audit', label: 'Audit Trail', icon: History },
    { to: '/settings', label: 'Configuration', icon: Settings }
  ];

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#fcf8ff] text-[#1b1b22] font-['Inter']">
      {/* 260px Fixed Sidebar */}
      <aside className="w-[260px] shrink-0 bg-[#f0ecf6] border-r border-[#dcd8e3] flex flex-col justify-between select-none z-20">
        <div>
          {/* Brand Header */}
          <div className="p-5 border-b border-[#dcd8e3] flex items-center gap-3">
            <div className="w-8 h-8 rounded bg-[#1f108e] text-white flex items-center justify-center font-bold text-sm shadow-sm">
              <Shield className="w-5 h-5" />
            </div>
            <div>
              <div className="font-bold text-sm text-[#1f108e] tracking-tight leading-none">
                UDS Trust Logic
              </div>
              <div className="text-[10px] text-slate-500 font-semibold tracking-wider uppercase mt-1">
                Control Plane v2.3
              </div>
            </div>
          </div>

          {/* Navigation Links */}
          <nav className="p-3 space-y-1">
            <div className="px-3 py-1.5 text-[10px] font-bold text-slate-400 uppercase tracking-wider">
              Control Surface
            </div>
            {navItems.map((item) => {
              const Icon = item.icon;
              const isActive = location.pathname === item.to;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={`flex items-center gap-3 px-3 py-2 text-xs font-medium rounded transition-colors ${
                    isActive
                      ? 'bg-[#1f108e] text-white shadow-sm'
                      : 'text-slate-700 hover:bg-[#e4e1eb] hover:text-[#1f108e]'
                  }`}
                >
                  <Icon className={`w-4 h-4 ${isActive ? 'text-white' : 'text-slate-500'}`} />
                  <span>{item.label}</span>
                </NavLink>
              );
            })}
          </nav>
        </div>

        {/* Sidebar Footer / User Profile */}
        <div className="p-3 border-t border-[#dcd8e3] bg-[#eae6f1]/60">
          <div className="p-2.5 bg-white rounded border border-[#dcd8e3] flex items-center justify-between">
            <div className="flex items-center gap-2.5 overflow-hidden">
              <div className="w-7 h-7 rounded-full bg-indigo-100 text-indigo-800 flex items-center justify-center shrink-0">
                <User className="w-4 h-4" />
              </div>
              <div className="overflow-hidden">
                <div className="text-xs font-semibold text-slate-900 truncate leading-tight">
                  {user?.name || 'Compliance Officer'}
                </div>
                <div className="text-[10px] text-slate-500 truncate mt-0.5">
                  {user?.email || 'dpo@uds.co.in'}
                </div>
              </div>
            </div>
            <button
              onClick={logout}
              className="text-slate-400 hover:text-rose-600 p-1 rounded transition-colors"
              title="Sign Out"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
          <div className="mt-2 text-[10px] text-center text-slate-400 font-mono">
            DPDP 2023 · ISO 27560 · RLS
          </div>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col h-full overflow-hidden">
        {/* Top Header Bar */}
        <header className="h-14 shrink-0 bg-white border-b border-[#dcd8e3] px-6 flex items-center justify-between z-10">
          <div className="flex items-center gap-4">
            <EntitySelector
              entities={MOCK_FIDUCIARY_ENTITIES}
              selectedEntityId={selectedEntityId}
              onSelectEntity={setSelectedEntityId}
              allowAllEntities={user?.entityId === null}
            />
            {selectedEntityId && (
              <span className="hidden md:inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-medium bg-indigo-50 text-indigo-800 border border-indigo-200">
                <span className="w-1.5 h-1.5 rounded-full bg-indigo-600 animate-pulse" />
                Postgres RLS Scoped: {selectedEntityId}
              </span>
            )}
          </div>

          <div className="flex items-center gap-3">
            <a
              href="http://localhost:3001"
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-medium text-slate-600 hover:text-indigo-700 bg-slate-100 hover:bg-slate-200 rounded border border-slate-200 transition-colors"
            >
              <ExternalLink className="w-3.5 h-3.5" />
              Public Portal
            </a>
            <div className="h-4 w-px bg-slate-200" />
            <div className="flex items-center gap-2">
              <span className="text-[11px] font-semibold text-emerald-700 bg-emerald-50 border border-emerald-200 px-2 py-0.5 rounded">
                ● Keycloak OIDC Active
              </span>
            </div>
          </div>
        </header>

        {/* Page Content Body */}
        <main className="flex-1 overflow-y-auto p-6 bg-[#fcf8ff]">
          <div className="max-w-7xl mx-auto">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  );
};
