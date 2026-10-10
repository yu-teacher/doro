import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { apiClient } from '../api/client';
import { clearAccounts } from '../test-support/seedAccounts';
import { useAuthStore } from '../store/authStore';
import { clearConsentReturn } from '../utils/consentReturn';
import type { AuthAccount } from '../types/auth';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let root: Root | undefined;
let host: HTMLElement | undefined;
let where = '';

function Probe() {
  const location = useLocation();
  where = location.pathname + location.search;
  return null;
}

const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

async function open(entry: string | { pathname: string; state?: unknown }) {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(createElement(MemoryRouter, { initialEntries: [entry as never] }, createElement(Probe), createElement(AppRoutes)));
  });
  await flush();
}

function account(role: AuthAccount['role'] = 'USER'): AuthAccount {
  return { userId: 'u1', email: 'a@doro.test', fullName: '사용자', role, accessToken: 'token', slot: 0, userIndex: 0 };
}

/** 로그인에 성공한 것처럼 계정을 심는다(실제 로그인 화면이 하는 일과 같다). */
async function loggedIn(role: AuthAccount['role'] = 'USER') {
  await act(async () => { useAuthStore.getState().addAccount(account(role)); });
  await flush();
}

const CONSENT = '/oauth2/consent?client_id=doro-blog&redirect_uri=' + encodeURIComponent('https://varen05.asuscomm.com/blog/bff/callback')
  + '&code_challenge=' + 'x'.repeat(43) + '&code_challenge_method=S256&state=abc';

beforeEach(() => {
  clearAccounts();
  clearConsentReturn();
  vi.spyOn(apiClient, 'get').mockResolvedValue({ data: {} } as never);
});

afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  vi.restoreAllMocks();
  clearAccounts();
  clearConsentReturn();
  sessionStorage.clear();
});

describe('로그인 뒤 이동: 어디서 로그인하러 왔는지에 따라 자연스럽게 이어진다', () => {
  it('허브에서 로그인하면 허브로 돌아온다', async () => {
    await open('/');
    const link = [...host!.querySelectorAll('a')].find((a) => a.textContent?.includes('로그인')) as HTMLAnchorElement;
    await act(async () => { link.click(); });
    await flush();
    expect(where).toBe('/login');

    await loggedIn();

    expect(where).toBe('/');
  });

  it('로그인 없이 /logs 를 열었다가 로그인하면 /logs 로 이어진다', async () => {
    await open('/logs');
    expect(where).toBe('/login');

    await loggedIn('ADMIN');

    expect(where).toBe('/logs');
  });

  it('주소를 직접 입력해 /login 으로 온 경우는 내 계정으로 간다', async () => {
    await open('/login');

    await loggedIn();

    expect(where).toBe('/account');
  });

  it('상태에 외부 주소가 실려 있어도 따라가지 않고 내 계정으로 간다', async () => {
    await open({ pathname: '/login', state: { from: 'https://evil.example/' } });

    await loggedIn();

    expect(where).toBe('/account');
  });

  it('서비스(블로그 등)에서 로그인하러 온 동의 화면은 로그인 화면으로 바로 보내고, 로그인하면 그 동의 요청으로 돌아온다', async () => {
    await open(CONSENT);
    expect(where).toBe('/login');

    await loggedIn();

    expect(where.startsWith('/oauth2/consent?')).toBe(true);
    expect(where).toContain('client_id=doro-blog');
    expect(where).toContain('state=abc');
  });

  it('동의 화면에서 가입 화면을 거쳐도(계정 만들기) 가입 직후 그 동의 요청으로 돌아온다', async () => {
    await open(CONSENT);
    expect(where).toBe('/login');
    const signup = [...host!.querySelectorAll('a')].find((a) => a.getAttribute('href') === '/signup') as HTMLAnchorElement;
    await act(async () => { signup.click(); });
    await flush();
    expect(where).toBe('/signup');

    await loggedIn();

    expect(where.startsWith('/oauth2/consent?')).toBe(true);
  });

  it('허브에서 가입하러 갔다가 가입하면 허브로 돌아온다', async () => {
    await open('/');
    const signup = [...host!.querySelectorAll('a')].find((a) => a.getAttribute('href') === '/signup') as HTMLAnchorElement;
    await act(async () => { signup.click(); });
    await flush();
    expect(where).toBe('/signup');

    await loggedIn();

    expect(where).toBe('/');
  });

  it('세션이 끝나 로그인으로 보내진 경우(저장된 안내) 로그인 화면이 이유를 한 번만 보여 준다', async () => {
    sessionStorage.setItem('doro_login_notice', '로그인 유지 기간이 지나 세션이 끝났습니다. 다시 로그인해 주세요.');

    await open('/login');

    expect(host!.textContent).toContain('세션이 끝났습니다');
    expect(sessionStorage.getItem('doro_login_notice')).toBeNull();
  });

  it('이미 로그인한 상태로 /login 을 열면 보던 곳(없으면 내 계정)으로 바로 보낸다', async () => {
    await act(async () => { useAuthStore.getState().addAccount(account()); });
    await open('/login');

    expect(where).toBe('/account');
  });
});
