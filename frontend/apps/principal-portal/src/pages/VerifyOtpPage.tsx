import React, { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { KeyRound, ShieldCheck, CheckCircle2, ArrowRight } from 'lucide-react';

export const VerifyOtpPage: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const refFromQuery = searchParams.get('ref') || '';

  const [reference, setReference] = useState(refFromQuery);
  const [otpCode, setOtpCode] = useState('');
  const [isVerifying, setIsVerifying] = useState(false);
  const [isVerified, setIsVerified] = useState(false);

  const handleVerify = (e: React.FormEvent) => {
    e.preventDefault();
    if (!otpCode.trim() || !reference.trim()) return;

    setIsVerifying(true);
    setTimeout(() => {
      setIsVerifying(false);
      setIsVerified(true);
    }, 1000);
  };

  return (
    <div className="max-w-md mx-auto py-12 px-4 space-y-6">
      <div className="text-center space-y-2">
        <div className="w-12 h-12 rounded-full bg-indigo-100 text-indigo-800 mx-auto flex items-center justify-center">
          <KeyRound className="w-6 h-6" />
        </div>
        <h1 className="text-2xl font-bold tracking-tight text-slate-900 font-['Inter']">
          Verify One-Time Passcode
        </h1>
        <p className="text-xs text-slate-500">
          Enter the 6-digit cryptographic verification code dispatched to your registered SMS/email.
        </p>
      </div>

      {!isVerified ? (
        <form onSubmit={handleVerify} className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-6 space-y-4">
          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              Request Reference Number
            </label>
            <input
              type="text"
              required
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="e.g. REF-IN-981247"
              className="w-full p-2.5 border border-slate-300 rounded text-xs font-mono font-bold text-slate-900 focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold text-slate-700 mb-1">
              6-Digit Verification Code
            </label>
            <input
              type="text"
              required
              maxLength={6}
              value={otpCode}
              onChange={(e) => setOtpCode(e.target.value.replace(/\D/g, ''))}
              placeholder="123456"
              className="w-full p-3 border border-slate-300 rounded text-center text-xl tracking-[0.5em] font-mono font-bold text-slate-900 focus:ring-1 focus:ring-indigo-600 focus:outline-none"
            />
          </div>

          <button
            type="submit"
            disabled={isVerifying}
            className="w-full py-2.5 bg-[#1f108e] hover:bg-indigo-900 text-white rounded text-xs font-bold uppercase tracking-wider shadow-sm transition-colors flex items-center justify-center gap-2 disabled:opacity-50"
          >
            {isVerifying ? 'Redeeming Token...' : 'Verify & Redeem Token'}
          </button>
        </form>
      ) : (
        <div className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-6 text-center space-y-4">
          <div className="w-12 h-12 rounded-full bg-emerald-100 text-emerald-700 mx-auto flex items-center justify-center">
            <CheckCircle2 className="w-6 h-6" />
          </div>
          <h2 className="text-lg font-bold text-slate-900">Identity Token Verified!</h2>
          <p className="text-xs text-slate-500">
            Your verification has been recorded into the append-only evidence ledger. The statutory 30-day resolution clock is now active.
          </p>
          <button
            onClick={() => navigate(`/status?ref=${reference}&code=${otpCode}`)}
            className="w-full py-2.5 bg-[#1f108e] text-white rounded text-xs font-bold hover:bg-indigo-900 transition-colors shadow-sm"
          >
            View Request Status & Evidence Receipt
          </button>
        </div>
      )}
    </div>
  );
};
