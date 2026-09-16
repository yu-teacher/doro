import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Header } from './components/layout/Header';
import { LoginPage } from './pages/LoginPage';
import { SignUpPage } from './pages/SignUpPage';
import { MyAccountPage } from './pages/MyAccountPage';
import { OAuthConsentPage } from './pages/OAuthConsentPage';
import { LogViewerPage } from './pages/LogViewerPage';
import { useAuthStore } from './store/authStore';

const RootRedirect: React.FC = () => {
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  return activeAccount ? <Navigate to="/account" replace /> : <Navigate to="/login" replace />;
};

const AdminRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { accounts, activeAccountIndex } = useAuthStore();
  const activeAccount = accounts[activeAccountIndex] || accounts[0];
  const isAdmin = activeAccount?.role === 'ADMIN' || activeAccount?.role === 'SUPER_ADMIN';

  if (!activeAccount) {
    return <Navigate to="/login" replace />;
  }
  if (!isAdmin) {
    return <Navigate to="/account" replace />;
  }
  return <>{children}</>;
};

export const App: React.FC = () => {
  return (
    <BrowserRouter>
      <div className="min-h-screen flex flex-col bg-slate-50/50">
        <Header />
        <main className="flex-1">
          <Routes>
            <Route path="/" element={<RootRedirect />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignUpPage />} />
            <Route path="/account" element={<MyAccountPage />} />
            <Route path="/oauth2/consent" element={<OAuthConsentPage />} />
            <Route
              path="/logs"
              element={
                <AdminRoute>
                  <LogViewerPage />
                </AdminRoute>
              }
            />
            <Route path="*" element={<RootRedirect />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
};
