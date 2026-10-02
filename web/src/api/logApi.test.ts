import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { logApi } from './logApi';

describe('logApi.getLogs LogQL 구성', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  function mockLoki() {
    return vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { data: { result: [] } } });
  }

  function queryOf(spy: ReturnType<typeof mockLoki>): string {
    const config = spy.mock.calls[0][1];
    return String((config?.params as { query: string }).query);
  }

  it('검색어의 따옴표를 이스케이프해 스트림 셀렉터를 주입할 수 없다', async () => {
    const spy = mockLoki();
    await logApi.getLogs({ search: '" } or {service=~".+"' });
    expect(queryOf(spy)).toBe('{service=~".+"} |= "\\" } or {service=~\\".+\\""');
  });

  it('서비스 이름도 이스케이프한다', async () => {
    const spy = mockLoki();
    await logApi.getLogs({ service: 'a"}|="' });
    expect(queryOf(spy)).toBe('{service="a\\"}|=\\""}');
  });

  it('정상 검색어는 그대로 필터로 붙는다', async () => {
    const spy = mockLoki();
    await logApi.getLogs({ service: 'doro-auth-api', search: 'abc-123' });
    expect(queryOf(spy)).toBe('{service="doro-auth-api"} |= "abc-123"');
  });
});
