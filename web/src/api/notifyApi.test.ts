import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from './client';
import { notifyApi } from './notifyApi';

describe('notifyApi (허브 경로 /notify/api/v1)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('설정 조회는 봉투의 data 를 벗겨 돌려준다', async () => {
    const spy = vi.spyOn(apiClient, 'get').mockResolvedValue({ data: { success: true, data: { enabled: true, publicKey: 'k' } } });
    await expect(notifyApi.getConfig()).resolves.toEqual({ available: true });
    expect(spy).toHaveBeenCalledWith('/notify/api/v1/config');
  });

  it('앱별 설정과 기기 목록은 각자의 경로를 부른다', async () => {
    const spy = vi
      .spyOn(apiClient, 'get')
      .mockResolvedValueOnce({ data: { success: true, data: { apps: [] } } })
      .mockResolvedValueOnce({ data: { success: true, data: { devices: [] } } });
    await expect(notifyApi.getPreferences()).resolves.toEqual([]);
    await expect(notifyApi.getDevices()).resolves.toEqual([]);
    expect(spy.mock.calls.map((c) => c[0])).toEqual(['/notify/api/v1/preferences', '/notify/api/v1/devices']);
  });

  it('기기 목록의 앱은 앱 식별자 배열로 줄여 돌려준다', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: {
        success: true,
        data: {
          devices: [
            {
              deviceId: 'd1',
              browser: 'Chrome',
              lastSeenAt: null,
              lastSuccessAt: '2026-10-20T01:00:00Z',
              apps: [{ app: 'party', displayName: '도로 파티', subscribedAt: null, lastSuccessAt: null }],
            },
          ],
        },
      },
    });
    await expect(notifyApi.getDevices()).resolves.toEqual([
      { deviceId: 'd1', browser: 'Chrome', lastSuccessAt: '2026-10-20T01:00:00Z', apps: ['party'] },
    ]);
  });

  it('설정 변경은 PUT /preferences 에 변경 목록(앱·종류·값)만 보내고 앱 식별 헤더는 붙이지 않는다', async () => {
    const spy = vi.spyOn(apiClient, 'put').mockResolvedValue({ data: {} });
    await notifyApi.setPreference('party', 'comment', false);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy.mock.calls[0]).toEqual(['/notify/api/v1/preferences', { changes: [{ app: 'party', type: 'comment', enabled: false }] }]);
  });

  it('기기 해제는 기기 표식을 주소에 안전하게 인코딩한다', async () => {
    const spy = vi.spyOn(apiClient, 'delete').mockResolvedValue({ data: {} });
    await notifyApi.removeDevice('a/b c');
    expect(spy).toHaveBeenCalledWith('/notify/api/v1/devices/a%2Fb%20c');
  });

  it('시험 알림은 POST /test 이고 대상 기기 수를 돌려준다', async () => {
    const spy = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { success: true, data: { devices: 2, delivered: 1 } } });
    await expect(notifyApi.sendTest()).resolves.toEqual({ sent: 2 });
    expect(spy).toHaveBeenCalledWith('/notify/api/v1/test');
  });
});
