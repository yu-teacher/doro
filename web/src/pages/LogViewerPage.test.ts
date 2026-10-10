import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logApi, type ParsedLogEntry } from '../api/logApi';
import { useAuthStore } from '../store/authStore';
import { LogViewerPage } from './LogViewerPage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const entry = (id: string, level: ParsedLogEntry['level'], message: string, traceId?: string): ParsedLogEntry => ({
  id, rawTimestamp: '1', formattedTime: '12:00:00', service: 'doro-auth-api', level, traceId, clientIp: '203.0.113.1', message, raw: `RAW ${message}`,
});

let root: Root | undefined;
let host: HTMLElement | undefined;

async function flush() {
  for (let i = 0; i < 3; i++) await act(async () => { await Promise.resolve(); });
}

async function render() {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root!.render(createElement(MemoryRouter, null, createElement(LogViewerPage)));
  });
  await flush();
}

const buttonByText = (text: string) => [...host!.querySelectorAll('button')].find((b) => b.textContent?.includes(text)) as HTMLButtonElement;

beforeEach(() => {
  vi.restoreAllMocks();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  useAuthStore.setState({
    accounts: [{ userId: 'u1', email: 'admin@example.com', fullName: '관리자', role: 'ADMIN', accessToken: 'a', slot: 0, userIndex: 0 }],
    activeAccountIndex: 0,
  });
});

afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  vi.useRealTimers();
  useAuthStore.setState({ accounts: [], activeAccountIndex: 0 });
});

describe('LogViewerPage', () => {
  it('관리자가 아니면 로그를 조회하지 않고 안내만 보인다', async () => {
    const getLogs = vi.spyOn(logApi, 'getLogs').mockResolvedValue([]);
    useAuthStore.setState({
      accounts: [{ userId: 'u2', email: 'user@example.com', fullName: '일반', role: 'USER', accessToken: 'a', slot: 0, userIndex: 0 }],
    });
    await render();

    expect(host!.textContent).toContain('관리자 전용');
    expect(getLogs).not.toHaveBeenCalled();
  });

  it('등급 필터를 누르면 그 등급으로 다시 조회한다', async () => {
    const getLogs = vi.spyOn(logApi, 'getLogs').mockResolvedValue([entry('1', 'ERROR', '실패')]);
    await render();

    await act(async () => { buttonByText('오류만 보기').click(); });
    await flush();

    expect(getLogs).toHaveBeenLastCalledWith(expect.objectContaining({ level: 'ERROR' }));
  });

  it('trace 버튼은 그 trace 로 검색하고 서비스·등급 필터를 풀어 준다', async () => {
    const getLogs = vi.spyOn(logApi, 'getLogs').mockResolvedValue([entry('1', 'INFO', '요청', 'abcdef1234567890')]);
    await render();
    await act(async () => { buttonByText('경고').click(); });
    await flush();

    await act(async () => { buttonByText('trace:').click(); });
    await flush();

    expect(getLogs).toHaveBeenLastCalledWith(expect.objectContaining({ search: 'abcdef1234567890', level: 'all', service: 'all' }));
  });

  it('원문 펼치기 버튼은 aria-expanded 로 상태를 알리고 원문을 보여 준다', async () => {
    vi.spyOn(logApi, 'getLogs').mockResolvedValue([entry('1', 'INFO', '메시지')]);
    await render();
    const toggle = () => host!.querySelector('button[aria-label$="원문 펼치기"], button[aria-label="원문 접기"]') as HTMLButtonElement;

    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(host!.textContent).not.toContain('RAW 메시지');

    await act(async () => { toggle().click(); });

    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(host!.textContent).toContain('RAW 메시지');
  });

  it('조회가 실패해도 화면이 깨지지 않고 빈 상태를 보여 준다', async () => {
    vi.spyOn(logApi, 'getLogs').mockRejectedValue(new Error('loki down'));
    await render();

    expect(host!.textContent).toContain('수집된 로그가 없거나');
  });
});
