import React, { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ShieldCheck, Send, CheckCircle2, Lock, HelpCircle } from 'lucide-react';
import { MOCK_FIDUCIARY_ENTITIES, FiduciaryEntityId, RightsRequestType } from '@uds/api-client';

export const RightsIntakePage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const defaultType = (searchParams.get('type') as RightsRequestType) || 'ACCESS';

  const [entityId, setEntityId] = useState<FiduciaryEntityId>('DENAVE_IN');
  const [requestType, setRequestType] = useState<RightsRequestType>(defaultType);
  const [identifier, setIdentifier] = useState('');
  const [details, setDetails] = useState('');
  const [isSubmitted, setIsSubmitted] = useState(false);
  const [generatedRef, setGeneratedRef] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!identifier.trim()) return;

    const ref = `REF-IN-${Math.floor(100000 + Math.random() * 900000)}`;
    setGeneratedRef(ref);
    setIsSubmitted(true);
  };

  return (
    <div className="max-w-3xl mx-auto py-8 px-4 space-y-8">
      {/* Header */}
      <div className="text-center space-y-2">
        <h1 className="text-3xl font-bold tracking-tight text-slate-900 font-['Inter']">
          Exercise Your Data Privacy Rights
        </h1>
        <p className="text-xs text-slate-500 max-w-lg mx-auto">
          Under the Digital Personal Data Protection (DPDP) Act, you have the statutory right to access, correct, erase, or withdraw consent for your personal data.
        </p>
      </div>

      {!isSubmitted ? (
        <form onSubmit={handleSubmit} className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-6 sm:p-8 space-y-6">
          {/* Step 1: Select Type */}
          <div>
            <label className="block text-xs font-bold text-slate-900 uppercase tracking-wider mb-2">
              1. Select Request Type
            </label>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {[
                { id: 'ACCESS', title: 'Access My Data Summary', desc: 'Summary of personal data and consent history' },
                { id: 'ERASURE', title: 'Erase / Delete My Data', desc: 'Permanent deletion where retention law permits' },
                { id: 'CORRECTION', title: 'Correct / Update My Information', desc: 'Rectify inaccurate or outdated contact info' },
                { id: 'CONSENT_WITHDRAWAL', title: 'Withdraw Consent', desc: 'Cease marketing and non-essential outreach' },
                { id: 'GRIEVANCE', title: 'File DPO Grievance', desc: 'Formal grievance to Data Protection Officer' }
              ].map((item) => (
                <label
                  key={item.id}
                  className={`p-3.5 rounded border flex items-start gap-3 cursor-pointer transition-all ${
                    requestType === item.id
                      ? 'bg-indigo-50/70 border-indigo-600 ring-1 ring-indigo-600'
                      : 'bg-white border-slate-200 hover:bg-slate-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="requestType"
                    value={item.id}
                    checked={requestType === item.id}
                    onChange={() => setRequestType(item.id as RightsRequestType)}
                    className="mt-0.5 text-indigo-600 focus:ring-indigo-500"
                  />
                  <div>
                    <div className="text-xs font-bold text-slate-900">{item.title}</div>
                    <div className="text-[11px] text-slate-500 mt-0.5">{item.desc}</div>
                  </div>
                </label>
              ))}
            </div>
          </div>

          {/* Step 2: Select Entity */}
          <div>
            <label className="block text-xs font-bold text-slate-900 uppercase tracking-wider mb-2">
              2. Select UDS Operating Subsidiary
            </label>
            <select
              value={entityId}
              onChange={(e) => setEntityId(e.target.value as FiduciaryEntityId)}
              className="w-full p-2.5 bg-slate-50 border border-slate-300 rounded text-xs font-medium text-slate-900 focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            >
              {MOCK_FIDUCIARY_ENTITIES.map((ent) => (
                <option key={ent.id} value={ent.id}>
                  {ent.name} ({ent.category.replace(/_/g, ' ')})
                </option>
              ))}
            </select>
          </div>

          {/* Step 3: Identifier */}
          <div>
            <label className="block text-xs font-bold text-slate-900 uppercase tracking-wider mb-1">
              3. Enter Your Registered Identifier (Phone or Email)
            </label>
            <p className="text-[11px] text-slate-400 mb-2">
              We will transmit a one-time cryptographic verification token to this address.
            </p>
            <input
              type="text"
              required
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              placeholder="+91 98765 43210 or name@example.com"
              className="w-full p-2.5 border border-slate-300 rounded text-xs font-medium text-slate-900 focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            />
          </div>

          {/* Step 4: Details */}
          <div>
            <label className="block text-xs font-bold text-slate-900 uppercase tracking-wider mb-1">
              4. Additional Details / Reason (Optional)
            </label>
            <textarea
              rows={3}
              value={details}
              onChange={(e) => setDetails(e.target.value)}
              placeholder="Provide any relevant context (e.g. recent telesales call, candidate registration reference)..."
              className="w-full p-2.5 border border-slate-300 rounded text-xs focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            />
          </div>

          {/* Security Notice */}
          <div className="bg-slate-50 p-3.5 rounded border border-slate-200 flex items-start gap-2.5 text-[11px] text-slate-600">
            <Lock className="w-4 h-4 text-slate-500 shrink-0 mt-0.5" />
            <div>
              <strong>Anti-Enumeration Privacy Protection:</strong> For your security, personal files are never displayed openly on web pages. We will send a secure verification code to the contact details on file.
            </div>
          </div>

          <button
            type="submit"
            className="w-full py-3 bg-[#1f108e] hover:bg-indigo-900 text-white rounded text-xs font-bold uppercase tracking-wider shadow-sm transition-colors flex items-center justify-center gap-2"
          >
            <Send className="w-4 h-4" />
            Submit Privacy Request
          </button>
        </form>
      ) : (
        /* Confirmation State */
        <div className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-8 text-center space-y-6">
          <div className="w-14 h-14 rounded-full bg-emerald-100 text-emerald-700 mx-auto flex items-center justify-center">
            <CheckCircle2 className="w-8 h-8" />
          </div>

          <div className="space-y-1">
            <h2 className="text-xl font-bold text-slate-900">Request Dispatched Securely</h2>
            <p className="text-xs text-slate-500 max-w-md mx-auto">
              If the provided identifier matches records on file for <strong>{entityId}</strong>, a one-time 6-digit verification code has been dispatched.
            </p>
          </div>

          <div className="bg-slate-50 p-4 rounded border border-slate-200 inline-block text-center max-w-sm mx-auto">
            <div className="text-[11px] font-semibold uppercase tracking-wider text-slate-400">
              Your Reference Number
            </div>
            <div className="font-mono text-xl font-bold text-slate-900 mt-1">{generatedRef}</div>
          </div>

          <div className="pt-4 border-t border-slate-100 flex flex-col sm:flex-row items-center justify-center gap-3">
            <button
              onClick={() => navigate(`/verify?ref=${generatedRef}`)}
              className="px-5 py-2.5 bg-[#1f108e] text-white rounded text-xs font-bold hover:bg-indigo-900 shadow-sm"
            >
              Enter Verification Code Now
            </button>
            <button
              onClick={() => navigate(`/status?ref=${generatedRef}`)}
              className="px-5 py-2.5 bg-white border border-slate-300 text-slate-700 rounded text-xs font-bold hover:bg-slate-50"
            >
              Track Request Status
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
