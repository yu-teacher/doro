import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { apiClient } from '../api/client';
import { clearAccounts } from '../test-support/seedAccounts';
import { useAuthStore } from '../store/authStore';
import { clearConsentReturn } from '../utils/consentReturn';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | undefined;
let host: HTMLElement | undefined;
let where = '';

function Probe() {
  const location = useLocation();
  where = location.pathname + location.search;
  return null;
}

const flush = () => act(async () => { for (let i = 0; i < 5; i += 1) await Promise.resolve(); });

async function open(entry: string) {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(createElement(MemoryRouter, { initialEntries: [entry] }, createElement(Probe), createElement(AppRoutes)));
  });
  await flush();
}

function jwt(payload: Record<string, unknown>): string {
  const b64 = (value: unknown) => btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

const ACCESS = jwt({ sub: 'user-1', email: 'social@doro.test', sid: 's1', uidx: 0, role: 'USER' });

function mockGet(providers: unknown) {
  vi.spyOn(apiClient, 'get').mockImplementation(async (url: string) => {
    if (url === '/api/v1/auth/social/providers') {
      if (providers instanceof Error) throw providers;
      return { data: { success: true, data: providers } } as never;
    }
    if (url === '/api/v1/users/me') {
      return { data: { success: true, data: { id: 'user-1', email: 'social@doro.test', name: '소셜 사용자', role: 'USER', hasTotp: false, status: 'ACTIVE', createdAt: '2026-10-22T00:00:00Z', passwordSet: false } } } as never;
    }
    return { data: {} } as never;
  });
}

beforeEach(() => {
  clearAccounts();
  clearConsentReturn();
  sessionStorage.clear();
});

afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  vi.restoreAllMocks();
  clearAccounts();
  clearConsentReturn();
  sessionStorage.clear();
});

describe('소셜 로그인 버튼', () => {
  it('서버가 알려 준 공급자마다 버튼이 나온다', async () => {
    mockGet([{ id: 'github', name: 'GitHub' }, { id: 'google', name: 'Google' }]);
    await open('/login');

    const labels = [...host!.querySelectorAll('button')].map((b) => b.textContent ?? '');
    expect(labels.some((l) => l.includes('GitHub'))).toBe(true);
    expect(labels.some((l) => l.includes('Google'))).toBe(true);
  });

  it('공급자가 없거나 목록을 못 받으면 버튼이 없고 이메일 로그인은 그대로 쓸 수 있다', async () => {
    mockGet([]);
    await open('/login');
    expect(host!.textContent).not.toContain('계속하기');
    expect(host!.querySelector('input[type="email"]')).not.toBeNull();

    act(() => root?.unmount());
    host?.remove();
    vi.restoreAllMocks();
    mockGet(new Error('network'));
    await open('/login');
    expect(host!.textContent).not.toContain('계속하기');
    expect(host!.querySelector('input[type="email"]')).not.toBeNull();
  });
});

describe('소셜 로그인에서 돌아왔을 때', () => {
  it('성공: 슬롯 쿠키로 액세스 토큰을 받아 로그인하고 내 계정으로 간다(주소의 쿼리는 지운다)', async () => {
    mockGet([]);
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { accessToken: ACCESS, sessionId: 's1', userIndex: 0 } } } as never);

    await open('/login?social=ok&slot=1');

    expect(post).toHaveBeenCalledWith('/api/v1/auth/token/refresh', {}, { headers: { 'X-Doro-Cookie-Session': '1', 'X-Doro-Account-Slot': '1' } });
    const account = useAuthStore.getState().accounts[0];
    expect(account).toMatchObject({ email: 'social@doro.test', userId: 'user-1', slot: 1, role: 'USER' });
    expect(account.accessToken).toBe(ACCESS);
    expect(where).toBe('/account');
  });

  it('성공 뒤 프로필에서 이름을 채운다', async () => {
    mockGet([]);
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { accessToken: ACCESS, sessionId: 's1', userIndex: 0 } } } as never);

    await open('/login?social=ok&slot=0');
    await flush();

    expect(useAuthStore.getState().accounts[0].fullName).toBe('소셜 사용자');
  });

  it('오류: 문구로 바꿔 보여 주고 쿼리를 지운다', async () => {
    mockGet([]);
    await open('/login?social=error&code=email_not_verified');

    expect(host!.textContent).toContain('인증된 이메일이 없어');
    expect(where).toBe('/login');
    expect(useAuthStore.getState().accounts).toHaveLength(0);
  });

  it('토큰을 못 받으면(쿠키가 없는 등) 로그인되지 않고 오류를 보여 준다', async () => {
    mockGet([]);
    vi.spyOn(apiClient, 'post').mockRejectedValue(new Error('401'));

    await open('/login?social=ok&slot=2');

    expect(useAuthStore.getState().accounts).toHaveLength(0);
    expect(host!.querySelector('[class*="text-red"]')).not.toBeNull();
  });

  it('2FA 가 필요하면 코드 입력 화면으로 가고, 코드를 내면 그 슬롯으로 로그인을 마친다', async () => {
    mockGet([]);
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { accessToken: ACCESS, sessionId: 's2', userIndex: 0 } } } as never);

    await open('/login?social=2fa&slot=3&ticket=t-123');
    expect(where).toBe('/login');
    expect(host!.textContent).toContain('2단계 인증');
    expect(post).not.toHaveBeenCalled();

    const input = host!.querySelector('input[maxlength="6"]') as HTMLInputElement;
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    await act(async () => {
      setValue.call(input, '123456');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const form = host!.querySelector('form') as HTMLFormElement;
    await act(async () => { form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); });
    await flush();

    expect(post).toHaveBeenCalledWith('/api/v1/auth/2fa/login', { tempTicket: 't-123', code: '123456' }, { headers: { 'X-Doro-Cookie-Session': '1', 'X-Doro-Account-Slot': '3' } });
    expect(useAuthStore.getState().accounts[0]).toMatchObject({ email: 'social@doro.test', slot: 3 });
  });

  it('소셜 쿼리가 없는 평소 로그인 화면은 아무것도 시도하지 않는다', async () => {
    mockGet([]);
    const post = vi.spyOn(apiClient, 'post');
    await open('/login');
    expect(post).not.toHaveBeenCalled();
    expect(host!.textContent).not.toContain('인증된 이메일이 없어');
  });
});
