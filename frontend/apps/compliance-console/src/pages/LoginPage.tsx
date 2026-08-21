import React from 'react';
import { Shield, Lock, ArrowRight, UserCheck, AlertTriangle } from 'lucide-react';
import { useAuth, DEV_PERSONAS } from '../auth/AuthProvider';

export const LoginPage: React.FC = () => {
  const { loginWithPersona } = useAuth();

  return (
    <div className="min-h-screen w-full bg-[#fcf8ff] flex items-center justify-center p-4 font-['Inter']">
      <div className="w-full max-w-md bg-white border border-[#dcd8e3] rounded-[4px] shadow-[0_8px_30px_rgb(0,0,0,0.06)] overflow-hidden">
        {/* Header Banner */}
        <div className="bg-[#1f108e] p-8 text-white text-center">
          <div className="w-12 h-12 rounded bg-white/10 mx-auto flex items-center justify-center mb-3">
            <Shield className="w-7 h-7 text-white" />
          </div>
          <h1 className="text-xl font-bold tracking-tight">UDS Trust Logic</h1>
          <p className="text-xs text-indigo-200 mt-1 font-medium">
            Consent & Privacy Control Plane · Compliance Console
          </p>
        </div>

        {/* Login Body */}
        <div className="p-6 space-y-6">
          <div className="text-center">
            <h2 className="text-sm font-bold text-slate-900">Sign in with Enterprise SSO</h2>
            <p className="text-xs text-slate-500 mt-0.5">
              Role-Based Access Control under Microsoft Entra ID / Keycloak OIDC
            </p>
          </div>

          <button
            onClick={() => loginWithPersona(DEV_PERSONAS[0])}
            className="w-full py-2.5 px-4 bg-[#1f108e] hover:bg-indigo-900 text-white rounded text-xs font-semibold flex items-center justify-center gap-2 shadow-sm transition-colors"
          >
            <Lock className="w-4 h-4" />
            Sign in with Enterprise OIDC / Keycloak
          </button>

          <div className="relative flex items-center justify-center">
            <div className="border-t border-slate-200 w-full" />
            <span className="bg-white px-2 text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
              Or Select Dev Persona
            </span>
          </div>

          {/* Persona Switcher */}
          <div className="space-y-2">
            {DEV_PERSONAS.map((persona) => (
              <button
                key={persona.username}
                type="button"
                onClick={() => loginWithPersona(persona)}
                className={`w-full p-2.5 rounded border text-left flex items-center justify-between text-xs transition-colors ${
                  persona.isOverAssigned
                    ? 'bg-rose-50/60 border-rose-200 hover:bg-rose-100/80 text-rose-900'
                    : 'bg-slate-50 border-slate-200 hover:bg-indigo-50 hover:border-indigo-200 text-slate-800'
                }`}
              >
                <div>
                  <div className="font-semibold flex items-center gap-1.5">
                    {persona.name}
                    {persona.entityId && (
                      <span className="font-mono text-[10px] bg-white px-1.5 py-0.2 rounded border text-indigo-700">
                        {persona.entityId}
                      </span>
                    )}
                    {persona.isOverAssigned && (
                      <span className="font-mono text-[10px] bg-rose-200 text-rose-800 px-1 py-0.2 rounded">
                        403 Test
                      </span>
                    )}
                  </div>
                  <div className="text-[11px] text-slate-500 font-mono mt-0.5">{persona.email}</div>
                </div>
                <ArrowRight className="w-4 h-4 text-slate-400" />
              </button>
            ))}
          </div>

          <div className="pt-2 border-t border-slate-100 text-[11px] text-center text-slate-400">
            DPDP Act 2023 · ISO/IEC TS 27560 · PostgreSQL RLS Enforced
          </div>
        </div>
      </div>
    </div>
  );
};
