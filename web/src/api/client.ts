import axios from 'axios';
import { useAuthStore } from '../store/authStore';

export const apiClient = axios.create({
  baseURL: '',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10000,
});

apiClient.interceptors.request.use((config) => {
  const activeAccount = useAuthStore.getState().getActiveAccount();
  if (activeAccount && activeAccount.accessToken) {
    if (config.headers.set) {
      config.headers.set('Authorization', `Bearer ${activeAccount.accessToken}`);
    } else {
      config.headers.Authorization = `Bearer ${activeAccount.accessToken}`;
    }
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    if ((error.response?.status === 401 || error.response?.status === 403) && !originalRequest._retry) {
      originalRequest._retry = true;
      const activeAccount = useAuthStore.getState().getActiveAccount();

      if (activeAccount && activeAccount.refreshToken) {
        try {
          const res = await axios.post('/api/v1/auth/token/refresh', {
            refreshToken: activeAccount.refreshToken,
          });

          const tokenData = res.data?.data || res.data;
          if (tokenData && tokenData.accessToken) {
            useAuthStore.getState().updateActiveToken(tokenData.accessToken, tokenData.refreshToken);
            if (originalRequest.headers.set) {
              originalRequest.headers.set('Authorization', `Bearer ${tokenData.accessToken}`);
            } else {
              originalRequest.headers.Authorization = `Bearer ${tokenData.accessToken}`;
            }
            return apiClient(originalRequest);
          }
        } catch {
          // 토큰 갱신 실패 시 만료된 계정 세션 정리 후 로그인 페이지로 안내
          const activeIndex = useAuthStore.getState().activeAccountIndex;
          useAuthStore.getState().removeAccount(activeIndex);
          window.location.href = '/login';
        }
      } else if (activeAccount) {
        const activeIndex = useAuthStore.getState().activeAccountIndex;
        useAuthStore.getState().removeAccount(activeIndex);
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);
