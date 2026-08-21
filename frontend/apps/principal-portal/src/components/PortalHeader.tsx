import React from 'react';
import { NavLink } from 'react-router-dom';
import { Shield, Globe, FileText, Search, UserCheck } from 'lucide-react';

interface PortalHeaderProps {
  currentLang: string;
  onLanguageChange: (lang: string) => void;
}

export const INDIAN_LANGUAGES = [
  { code: 'en', name: 'English' },
  { code: 'hi', name: 'हिन्दी (Hindi)' },
  { code: 'ta', name: 'தமிழ் (Tamil)' },
  { code: 'te', name: 'తెలుగు (Telugu)' },
  { code: 'kn', name: 'ಕನ್ನಡ (Kannada)' },
  { code: 'bn', name: 'বাংলা (Bengali)' },
  { code: 'mr', name: 'मराठी (Marathi)' },
  { code: 'gu', name: 'ગુજરાતી (Gujarati)' }
];

export const PortalHeader: React.FC<PortalHeaderProps> = ({ currentLang, onLanguageChange }) => {
  return (
    <header className="bg-white border-b border-[#dcd8e3] sticky top-0 z-30 shadow-sm">
      <div className="max-w-6xl mx-auto px-4 h-16 flex items-center justify-between">
        {/* Brand */}
        <NavLink to="/" className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded bg-[#1f108e] text-white flex items-center justify-center font-bold text-sm shadow-sm">
            <Shield className="w-5 h-5" />
          </div>
          <div>
            <div className="font-bold text-sm text-[#1f108e] tracking-tight leading-none">
              Updater Services Limited
            </div>
            <div className="text-[10px] text-slate-500 font-semibold tracking-wider uppercase mt-1">
              Data Privacy & Rights Portal
            </div>
          </div>
        </NavLink>

        {/* Center Nav */}
        <nav className="hidden md:flex items-center gap-6 text-xs font-semibold text-slate-600">
          <NavLink
            to="/"
            className={({ isActive }) =>
              `hover:text-[#1f108e] transition-colors pb-1 border-b-2 ${
                isActive ? 'border-[#1f108e] text-[#1f108e]' : 'border-transparent'
              }`
            }
          >
            Privacy Notices
          </NavLink>
          <NavLink
            to="/rights"
            className={({ isActive }) =>
              `hover:text-[#1f108e] transition-colors pb-1 border-b-2 ${
                isActive ? 'border-[#1f108e] text-[#1f108e]' : 'border-transparent'
              }`
            }
          >
            Exercise Privacy Rights
          </NavLink>
          <NavLink
            to="/status"
            className={({ isActive }) =>
              `hover:text-[#1f108e] transition-colors pb-1 border-b-2 ${
                isActive ? 'border-[#1f108e] text-[#1f108e]' : 'border-transparent'
              }`
            }
          >
            Track Request Status
          </NavLink>
        </nav>

        {/* Language Switcher */}
        <div className="flex items-center gap-2">
          <Globe className="w-4 h-4 text-slate-400" />
          <select
            value={currentLang}
            onChange={(e) => onLanguageChange(e.target.value)}
            className="p-1.5 bg-slate-50 border border-slate-300 rounded text-xs font-semibold text-slate-700 focus:outline-none focus:ring-1 focus:ring-indigo-600"
          >
            {INDIAN_LANGUAGES.map((lang) => (
              <option key={lang.code} value={lang.code}>
                {lang.name}
              </option>
            ))}
          </select>
        </div>
      </div>
    </header>
  );
};
