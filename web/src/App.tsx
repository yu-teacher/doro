import React from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { loginStateFrom } from './utils/afterLogin';
import { Header } from './components/layout/Header';
import { LoginPage } from './pages/LoginPage';
import { SignUpPage } from './pages/SignUpPage';
import { MyAccountPage } from './pages/MyAccountPage';
import { OAuthConsentPage } from './pages/OAuthConsentPage';
import { LogViewerPage } from './pages/LogViewerPage';
import { HubPage } from './pages/HubPage';
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
  const location = useLocation();

  if (!activeAccount) {
    // 로그인하면 원래 가려던 화면(로그 조회)으로 돌아온다
    return <Navigate to="/login" replace state={loginStateFrom(location)} />;
  }
  if (!isAdmin) {
    return <Navigate to="/account" replace />;
  }
  return <>{children}</>;
};

/** 라우트와 공통 화면 틀. BrowserRouter 밖에서도(테스트의 MemoryRouter) 쓸 수 있게 따로 둔다. */
export const AppRoutes: React.FC = () => (
  <div className="min-h-screen flex flex-col bg-slate-50/50">
    <Header />
    <main className="flex-1">
      <Routes>
        {/* 허브: 게이트웨이가 / 를 포털로 보내면 메인 주소, 그 전에는 /portal 로 미리 확인한다 */}
        <Route path="/" element={<HubPage />} />
        <Route path="/portal" element={<HubPage />} />
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
);

export const App: React.FC = () => (
  <BrowserRouter>
    <AppRoutes />
  </BrowserRouter>
);
