import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { ConsoleShell } from './layouts/ConsoleShell';
import { DashboardPage } from './pages/DashboardPage';
import { RightsQueuePage } from './pages/RightsQueuePage';
import { SubjectEvidencePage } from './pages/SubjectEvidencePage';
import { RegistriesPage } from './pages/RegistriesPage';
import { NoticesPage } from './pages/NoticesPage';
import { SweepersPage } from './pages/SweepersPage';
import { AuditPage } from './pages/AuditPage';
import { SettingsPage } from './pages/SettingsPage';
import { LoginPage } from './pages/LoginPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      staleTime: 30000
    }
  }
});

const ProtectedRoutes: React.FC = () => {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return <ConsoleShell />;
};

export const App: React.FC = () => {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<ProtectedRoutes />}>
              <Route path="/" element={<DashboardPage />} />
              <Route path="/rights" element={<RightsQueuePage />} />
              <Route path="/evidence" element={<SubjectEvidencePage />} />
              <Route path="/registries" element={<RegistriesPage />} />
              <Route path="/notices" element={<NoticesPage />} />
              <Route path="/sweepers" element={<SweepersPage />} />
              <Route path="/audit" element={<AuditPage />} />
              <Route path="/settings" element={<SettingsPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
};
