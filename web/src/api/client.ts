import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';
import { refreshAccessToken } from './tokenRefresh';

export const apiClient = axios.create({
  baseURL: '',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10000,
});

// 토큰 갱신 대상이 아닌(자격 증명을 다루는) 공개 인증 엔드포인트
const PUBLIC_AUTH_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/2fa/login',
  '/api/v1/auth/signup',
  '/api/v1/auth/lookup',
  '/api/v1/auth/token/refresh',
  // 비밀번호 재확인이 필요한 요청. 실패가 서버의 잠금 횟수에 합산되므로 401 에서 갱신 후 재전송하지 않는다.
  // (호출하는 쪽이 먼저 authApi 의 토큰 갱신 단계를 거친다.)
  '/api/v1/users/me/deletion',
  '/api/v1/users/me/password',
  '/api/v1/auth/2fa/setup',
];

/** 로그인 전에 부르는 경로: 액세스 토큰이 없어도 되므로 요청 전에 갱신하지 않는다. */
const NO_TOKEN_PATHS = [
  '/api/v1/auth/login',
  '/api/v1/auth/2fa/login',
  '/api/v1/auth/signup',
  '/api/v1/auth/lookup',
  '/api/v1/auth/token/refresh',
];

const LOGIN_PATH = '/login';

type RetriableRequest = InternalAxiosRequestConfig & { _retry?: boolean };

function setBearer(config: InternalAxiosRequestConfig, accessToken: string): void {
  if (config.headers.set) {
    config.headers.set('Authorization', `Bearer ${accessToken}`);
  } else {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
}

function bearerOf(config: InternalAxiosRequestConfig): string | null {
  const header = config.headers.get ? config.headers.get('Authorization') : config.headers.Authorization;
  return typeof header === 'string' && header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null;
}

function isPublicAuthPath(url: string | undefined): boolean {
  return !!url && PUBLIC_AUTH_PATHS.some((path) => url.startsWith(path));
}

function endSession(email: string): void {
  useAuthStore.getState().removeAccountByEmail(email);
  window.location.href = LOGIN_PATH;
}

apiClient.interceptors.request.use(async (config) => {
  const activeAccount = useAuthStore.getState().getActiveAccount();
  if (!activeAccount) {
    return config;
  }
  if (activeAccount.accessToken) {
    setBearer(config, activeAccount.accessToken);
    return config;
  }
  // 새로고침 직후에는 액세스 토큰이 메모리에 없다. HttpOnly 쿠키로 새로 받은 뒤에 요청을 보낸다.
  if (NO_TOKEN_PATHS.some((path) => config.url?.startsWith(path))) {
    return config;
  }
  const result = await refreshAccessToken(activeAccount.email, null);
  if (result.kind === 'refreshed') {
    setBearer(config, result.accessToken);
  } else if (result.kind === 'rejected') {
    endSession(activeAccount.email);
    throw new AxiosError('세션이 만료되었습니다. 다시 로그인해 주세요.', 'ERR_SESSION_EXPIRED', config);
  }
  // unavailable(네트워크/5xx): 토큰 없이 보내 서버의 401 로 원래 오류를 전달한다.
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const originalRequest = error.config as RetriableRequest | undefined;
    if (error.response?.status !== 401 || !originalRequest || originalRequest._retry || isPublicAuthPath(originalRequest.url)) {
      return Promise.reject(error);
    }
    originalRequest._retry = true;

    const account = useAuthStore.getState().getActiveAccount();
    if (!account) {
      return Promise.reject(error);
    }

    const result = await refreshAccessToken(account.email, bearerOf(originalRequest));
    if (result.kind === 'refreshed') {
      setBearer(originalRequest, result.accessToken);
      return apiClient(originalRequest);
    }
    if (result.kind === 'rejected') {
      // 서버가 리프레시 토큰을 거부한 경우에만 세션을 정리한다.
      endSession(account.email);
    }
    // unavailable(네트워크/5xx): 로그인 상태를 유지한 채 원래 오류를 전달한다.
    return Promise.reject(error);
  }
);
