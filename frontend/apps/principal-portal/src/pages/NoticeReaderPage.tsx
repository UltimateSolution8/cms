import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ScrollText, ExternalLink, ArrowRight, ShieldCheck, Mail, Globe } from 'lucide-react';
import { MOCK_NOTICES, PrivacyNotice } from '@uds/api-client';

const LOCALIZED_NOTICES: Record<string, Record<string, string>> = {
  NOTICE_DENAVE_B2B: {
    en: 'Denave India Private Limited processes your name, job title, employer and business contact details to contact you about products and services relevant to your professional role, on behalf of itself and its clients. You can withdraw your consent at any time, as easily as you gave it, using the link below.',
    hi: 'देनाव इंडिया प्राइवेट लिमिटेड अपने और अपने ग्राहकों की ओर से आपकी व्यावसायिक भूमिका से संबंधित उत्पादों और सेवाओं के बारे में आपसे संपर्क करने के लिए आपके नाम, पदनाम, नियोक्ता और व्यावसायिक संपर्क विवरण को संसाधित करता है। आप नीचे दिए गए लिंक का उपयोग करके किसी भी समय अपनी सहमति वापस ले सकते हैं।',
    ta: 'டினேவ் இந்தியா பிரைவேட் லிமிடெட், தனது மற்றும் அதன் வாடிக்கையாளர்களின் சார்பாக உங்கள் தொழில்முறை பொறுப்புக்கு தொடர்புடைய தயாரிப்புகள் மற்றும் சேவைகள் குறித்து உங்களைத் தொடர்பு கொள்ள உங்கள் பெயர், பதவி, நிறுவனம் மற்றும் வணிக தொடர்பு விவரங்களை செயலாக்குகிறது.',
    te: 'దేనవే ఇండియా ప్రైవేట్ లిమిటెడ్ తన తరపున మరియు తన ఖాతాదారుల తరపున మీ వృత్తిపరమైన బాధ్యతకు సంబంధించిన ఉత్పత్తులు మరియు సేవల గురించి మిమ్మల్ని సంప్రదించడానికి మీ పేరు, ఉద్యోగ హోదా, యజమాని మరియు వ్యాపార సంప్రదింపు వివరాలను ప్రాసెస్ చేస్తుంది.'
  }
};

export const NoticeReaderPage: React.FC<{ currentLang: string }> = ({ currentLang }) => {
  const navigate = useNavigate();
  const [selectedNoticeId, setSelectedNoticeId] = useState('NOTICE_DENAVE_B2B');

  const activeNotice = MOCK_NOTICES.find((n) => n.noticeId === selectedNoticeId) || MOCK_NOTICES[0];
  const localizedBody =
    LOCALIZED_NOTICES[selectedNoticeId]?.[currentLang] || activeNotice.body;

  return (
    <div className="max-w-4xl mx-auto py-8 px-4 space-y-8">
      {/* Hero Header */}
      <div className="text-center space-y-2">
        <span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold bg-indigo-50 text-indigo-800 border border-indigo-200">
          <ShieldCheck className="w-3.5 h-3.5" />
          DPDP Act 2023 Section 5 Notice Repository
        </span>
        <h1 className="text-3xl font-bold tracking-tight text-slate-900 font-['Inter']">
          Transparency & Privacy Notices
        </h1>
        <p className="text-xs text-slate-500 max-w-xl mx-auto">
          Read transparent, unbundled privacy notices for each UDS Group subsidiary in your preferred official Indian language.
        </p>
      </div>

      {/* Notice Selector Pills */}
      <div className="flex flex-wrap items-center justify-center gap-2">
        {MOCK_NOTICES.map((n) => (
          <button
            key={n.noticeId}
            onClick={() => setSelectedNoticeId(n.noticeId)}
            className={`px-4 py-2 rounded text-xs font-semibold transition-all ${
              selectedNoticeId === n.noticeId
                ? 'bg-[#1f108e] text-white shadow-sm'
                : 'bg-white text-slate-700 border border-slate-200 hover:bg-slate-50'
            }`}
          >
            {n.title}
          </button>
        ))}
      </div>

      {/* Main Notice Document Card */}
      <div className="bg-white border border-[#dcd8e3] rounded-[4px] shadow-sm p-6 sm:p-8 space-y-6">
        <div className="border-b border-slate-100 pb-4 flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="text-lg font-bold text-slate-900">{activeNotice.title}</h2>
            <div className="text-xs text-slate-400 font-mono mt-1">
              Notice ID: {activeNotice.noticeId} · Version: v{activeNotice.version} · Published: 17 Aug 2026
            </div>
          </div>
          <span className="text-xs font-mono bg-slate-100 text-slate-700 px-2 py-0.5 rounded">
            Language: {currentLang.toUpperCase()}
          </span>
        </div>

        {/* Notice Body */}
        <div className="text-sm text-slate-800 leading-relaxed font-['Inter'] bg-slate-50/50 p-5 rounded border border-slate-100">
          {localizedBody}
        </div>

        {/* DPDP Statutory Links (Rule 3) */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 pt-4 border-t border-slate-100">
          <div className="p-4 bg-slate-50 rounded border border-slate-200 space-y-1.5">
            <div className="text-xs font-bold text-slate-900">Withdraw Consent</div>
            <p className="text-[11px] text-slate-500 leading-normal">
              Withdraw previously given consent easily and instantly.
            </p>
            <button
              onClick={() => navigate('/rights?type=CONSENT_WITHDRAWAL')}
              className="text-xs font-semibold text-indigo-700 hover:text-indigo-900 inline-flex items-center gap-1 mt-1"
            >
              Withdraw Now <ArrowRight className="w-3 h-3" />
            </button>
          </div>

          <div className="p-4 bg-slate-50 rounded border border-slate-200 space-y-1.5">
            <div className="text-xs font-bold text-slate-900">Exercise Rights (DSR)</div>
            <p className="text-[11px] text-slate-500 leading-normal">
              Access your data summary or request correction/erasure.
            </p>
            <button
              onClick={() => navigate('/rights')}
              className="text-xs font-semibold text-indigo-700 hover:text-indigo-900 inline-flex items-center gap-1 mt-1"
            >
              File Request <ArrowRight className="w-3 h-3" />
            </button>
          </div>

          <div className="p-4 bg-slate-50 rounded border border-slate-200 space-y-1.5">
            <div className="text-xs font-bold text-slate-900">Data Protection Officer</div>
            <p className="text-[11px] text-slate-500 leading-normal">
              Contact group DPO for unresolved privacy grievances.
            </p>
            <a
              href="mailto:dpo@uds.co.in"
              className="text-xs font-semibold text-indigo-700 hover:text-indigo-900 inline-flex items-center gap-1 mt-1"
            >
              <Mail className="w-3 h-3" /> dpo@uds.co.in
            </a>
          </div>
        </div>
      </div>
    </div>
  );
};
