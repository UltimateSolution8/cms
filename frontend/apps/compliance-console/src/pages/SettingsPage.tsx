import React from 'react';
import { Settings, Shield, Globe, Key, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { Badge } from '@uds/ui-core';

export const SettingsPage: React.FC = () => {
  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
            System & Security Configuration
          </h1>
          <p className="text-xs text-slate-500 mt-1">
            CORS allowlists, OIDC identity provider claims, rate limiters, and cryptographic key rotation SPI.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Card 1: Identity & OIDC */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center gap-2 border-b border-slate-100 pb-3">
            <Shield className="w-4 h-4 text-indigo-600" />
            <h3 className="text-sm font-bold text-slate-900">OIDC Resource Server Configuration</h3>
          </div>

          <div className="space-y-3 text-xs">
            <div>
              <div className="text-slate-400 font-medium">Issuer URI:</div>
              <div className="font-mono text-slate-800 bg-slate-50 p-2 rounded border mt-0.5">
                http://localhost:8081/realms/uds
              </div>
            </div>
            <div>
              <div className="text-slate-400 font-medium">Audience (aud claim):</div>
              <div className="font-mono text-slate-800 bg-slate-50 p-2 rounded border mt-0.5">
                uds-consent-api
              </div>
            </div>
            <div>
              <div className="text-slate-400 font-medium">Actor Attribution Precedence:</div>
              <div className="font-mono text-indigo-700 bg-indigo-50 p-2 rounded border mt-0.5">
                preferred_username → email → sub
              </div>
            </div>
          </div>
        </div>

        {/* Card 2: CORS & Front Door */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center gap-2 border-b border-slate-100 pb-3">
            <Globe className="w-4 h-4 text-indigo-600" />
            <h3 className="text-sm font-bold text-slate-900">CORS Allowed Origins</h3>
          </div>

          <div className="space-y-3 text-xs">
            <div>
              <div className="text-slate-400 font-medium">Configured Allowed Origins:</div>
              <div className="space-y-1 mt-1">
                <div className="font-mono text-slate-800 bg-slate-50 p-2 rounded border flex items-center justify-between">
                  <span>http://localhost:3000 (Compliance Console)</span>
                  <Badge variant="success" size="sm">ACTIVE</Badge>
                </div>
                <div className="font-mono text-slate-800 bg-slate-50 p-2 rounded border flex items-center justify-between">
                  <span>http://localhost:3001 (Data Principal Portal)</span>
                  <Badge variant="success" size="sm">ACTIVE</Badge>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Card 3: Rate Limiting */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center gap-2 border-b border-slate-100 pb-3">
            <AlertTriangle className="w-4 h-4 text-amber-600" />
            <h3 className="text-sm font-bold text-slate-900">Dual Rate Limit Thresholds</h3>
          </div>

          <div className="space-y-2 text-xs">
            <div className="flex items-center justify-between p-2 bg-slate-50 rounded border">
              <span className="font-semibold text-slate-700">Pre-Auth Flood Limiter</span>
              <span className="font-mono font-bold text-slate-900">400 req/s per IP</span>
            </div>
            <div className="flex items-center justify-between p-2 bg-slate-50 rounded border">
              <span className="font-semibold text-slate-700">Administrative Surface (/v1/admin/**)</span>
              <span className="font-mono font-bold text-slate-900">50 req/s (Burst 100)</span>
            </div>
            <div className="flex items-center justify-between p-2 bg-slate-50 rounded border">
              <span className="font-semibold text-slate-700">Decision Engine (/v1/evaluate)</span>
              <span className="font-mono font-bold text-slate-900">200 req/s (Burst 400)</span>
            </div>
          </div>
        </div>

        {/* Card 4: Cryptography & Key Custody */}
        <div className="bg-white p-5 rounded border border-slate-200 shadow-sm space-y-4">
          <div className="flex items-center gap-2 border-b border-slate-100 pb-3">
            <Key className="w-4 h-4 text-emerald-600" />
            <h3 className="text-sm font-bold text-slate-900">SigningKeyProvider SPI</h3>
          </div>

          <div className="space-y-3 text-xs text-slate-600">
            <div className="p-3 bg-emerald-50 border border-emerald-200 rounded text-emerald-800">
              <div className="font-bold flex items-center gap-1.5">
                <CheckCircle2 className="w-4 h-4 text-emerald-600" />
                Ed25519 Local Snapshot Signing Active
              </div>
              <div className="text-[11px] mt-1">
                Public verification key served at <code className="font-mono font-bold">GET /v1/keys</code> for offline field SDKs.
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
