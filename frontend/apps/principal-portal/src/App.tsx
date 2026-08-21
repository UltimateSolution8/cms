import React, { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PortalHeader } from './components/PortalHeader';
import { NoticeReaderPage } from './pages/NoticeReaderPage';
import { RightsIntakePage } from './pages/RightsIntakePage';
import { VerifyOtpPage } from './pages/VerifyOtpPage';
import { StatusTrackerPage } from './pages/StatusTrackerPage';

const queryClient = new QueryClient();

export const App: React.FC = () => {
  const [currentLang, setCurrentLang] = useState('en');

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <div className="min-h-screen flex flex-col bg-[#fcf8ff] text-[#1b1b22] font-['Inter']">
          <PortalHeader currentLang={currentLang} onLanguageChange={setCurrentLang} />
          <main className="flex-1">
            <Routes>
              <Route path="/" element={<NoticeReaderPage currentLang={currentLang} />} />
              <Route path="/rights" element={<RightsIntakePage />} />
              <Route path="/verify" element={<VerifyOtpPage />} />
              <Route path="/status" element={<StatusTrackerPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>
          <footer className="py-6 border-t border-[#dcd8e3] bg-white text-center text-xs text-slate-400">
            <div className="max-w-6xl mx-auto px-4 flex flex-col sm:flex-row items-center justify-between gap-2">
              <div>© 2026 Updater Services Limited (UDS). All Rights Reserved.</div>
              <div className="font-mono text-[11px]">DPDP Act 2023 · ISO/IEC TS 27560:2023 Compliant</div>
            </div>
          </footer>
        </div>
      </BrowserRouter>
    </QueryClientProvider>
  );
};
