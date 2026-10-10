import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/client';
import { useAuthStore } from '../store/authStore';
import { OAuthConsentPage } from './OAuthConsentPage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const CODE_CHALLENGE = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ';
// 하이픈·점이 없어 브라우저가 줄바꿈할 곳이 없는 긴 값: 모바일에서 카드 밖으로 넘치는 최악의 경우
const LONG_CLIENT_ID = 'A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v2W3x4Y5z6A7b8C9d0E1f2';
const LONG_HOST_LABEL = 'abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz';
const LONG_EMAIL = 'averyveryverylonglocalpartwithoutanybreaks0123456789@example-company-domain.com';

let root: Root | undefined;
let host: HTMLElement | undefined;

async function flush() {
  for (let i = 0; i < 4; i++) await act(async () => { await Promise.resolve(); });
}

async function render() {
  const query = new URLSearchParams({
    client_id: LONG_CLIENT_ID,
    redirect_uri: `https://${LONG_HOST_LABEL}.example.com/cb`,
    response_type: 'code',
    code_challenge: CODE_CHALLENGE,
    scope: 'openid profile email',
    state: 'x',
  });
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(createElement(MemoryRouter, { initialEntries: [`/oauth2/consent?${query}`] }, createElement(OAuthConsentPage)));
  });
  await flush();
}

const leaf = (text: string) => [...host!.querySelectorAll('*')].find((e) => e.children.length === 0 && e.textContent?.includes(text)) as HTMLElement;

beforeEach(() => {
  vi.restoreAllMocks();
  // 자사 앱이 아니라서(client-info 가 빈 응답) 동의 화면이 그려진다
  vi.spyOn(apiClient, 'get').mockResolvedValue({ data: {} } as never);
  useAuthStore.setState({
    accounts: [{ userId: 'u1', email: LONG_EMAIL, fullName: '이름이아주긴사용자의이름입니다홍길동김철수', role: 'USER', accessToken: 'a', slot: 0, userIndex: 0 }],
    activeAccountIndex: 0,
  });
});

afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  useAuthStore.setState({ accounts: [], activeAccountIndex: 0 });
});

describe('OAuthConsentPage 긴 값 (모바일)', () => {
  it('앱 ID·이동할 주소·계정 이메일은 어디서든 줄바꿈되도록 표시한다 (하이픈 없는 긴 값이 카드 밖으로 넘치지 않게)', async () => {
    await render();

    expect(host!.textContent).toContain(LONG_CLIENT_ID);
    for (const [label, element] of [
      ['앱 ID', leaf(LONG_CLIENT_ID)],
      ['이동할 주소', leaf(LONG_HOST_LABEL)],
      ['이메일', leaf(LONG_EMAIL)],
    ] as const) {
      expect(element, label).toBeDefined();
      expect(element.className, `${label} 은 break-all 이어야 한다`).toContain('break-all');
    }
  });

  it('계정 이름도 줄바꿈되고, 이름·이메일 영역이 줄어들 수 있다(min-w-0)', async () => {
    await render();

    const name = leaf('이름이아주긴사용자');
    expect(name.className).toContain('break-words');
    expect(name.parentElement!.className).toContain('min-w-0');
  });

  it('긴 값이 있어도 취소·승인 버튼은 그대로 나온다', async () => {
    await render();

    const labels = [...host!.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(labels).toContain('취소');
    expect(labels).toContain('계속 (승인)');
  });
});
