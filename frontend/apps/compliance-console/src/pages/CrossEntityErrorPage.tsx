import React from 'react';
import { ShieldAlert, AlertOctagon, ArrowLeft, LogOut } from 'lucide-react';
import { useAuth } from '../auth/AuthProvider';

export const CrossEntityErrorPage: React.FC = () => {
  const { user, logout } = useAuth();

  return (
    <div className="min-h-screen w-full bg-[#fcf8ff] flex items-center justify-center p-4 font-['Inter']">
      <div className="w-full max-w-lg bg-white border border-rose-200 rounded-[4px] shadow-lg overflow-hidden">
        {/* Header */}
        <div className="bg-rose-700 p-6 text-white text-center">
          <div className="w-12 h-12 rounded-full bg-white/20 mx-auto flex items-center justify-center mb-3">
            <AlertOctagon className="w-7 h-7 text-white" />
          </div>
          <h1 className="text-xl font-bold tracking-tight">403 Forbidden — Ambiguous Entity Scope</h1>
          <p className="text-xs text-rose-100 mt-1">
            RFC 7807 ProblemDetail: Cross-Entity Isolation Enforcement
          </p>
        </div>

        {/* Body */}
        <div className="p-6 space-y-5 text-xs text-slate-700">
          <div className="bg-rose-50 border border-rose-200 p-4 rounded text-rose-900 space-y-2">
            <div className="font-bold flex items-center gap-1.5">
              <ShieldAlert className="w-4 h-4 text-rose-700" />
              Two Entity Scopes Detected in Single Token
            </div>
            <p className="leading-relaxed">
              Your OpenID Connect token carries multiple contradictory entity claims (
              <span className="font-mono font-bold">entity.DENAVE_IN</span> and{' '}
              <span className="font-mono font-bold">entity.MATRIX</span>). Under UDS isolation policy (Phase 21/23),
              a token with ambiguous entity assignments is fail-closed by the API filter and PostgreSQL Row-Level Security.
            </p>
          </div>

          <div className="space-y-2 text-slate-600">
            <div className="font-semibold text-slate-800">What needs to be done:</div>
            <ul className="list-disc pl-5 space-y-1">
              <li>
                Ask your Entra ID / Keycloak administrator to assign <strong>exactly one</strong> <code className="font-mono">entity.&lt;ID&gt;</code> app role to your directory account.
              </li>
              <li>
                Sign in with a valid single-entity or group-level compliance credential.
              </li>
            </ul>
          </div>

          <div className="pt-4 border-t border-slate-100 flex items-center justify-between">
            <span className="text-[11px] text-slate-400 font-mono">
              Correlation ID: {Date.now().toString(36)}-sec-gate-403
            </span>
            <button
              onClick={logout}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-slate-900 hover:bg-black text-white rounded text-xs font-semibold transition-colors"
            >
              <LogOut className="w-3.5 h-3.5" />
              Sign Out & Switch Persona
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
