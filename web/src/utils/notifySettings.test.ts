import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import type { NotifyDevice } from '../api/notifyApi';
import {
  NOTIFY_DEVICE_ID_STORAGE_KEY,
  appUrlFor,
  describePermission,
  describeTestResult,
  formatLastSuccess,
  isAppOnThisDevice,
  isNotifyUnavailable,
  readBrowserPermission,
  readLocalDeviceId,
} from './notifySettings';
import { getBlogUrl, getGamesUrl, getMenuUrl, getPartyUrl } from './urlUtils';

const device = (deviceId: string, apps: string[]): NotifyDevice => ({ deviceId, browser: 'Chrome', lastSuccessAt: null, apps });

function httpError(status: number): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  return new AxiosError('fail', String(status), config, null, { status, statusText: '', headers: {}, config, data: {} });
}

describe('브라우저 알림 권한', () => {
  it('Notification API 가 없으면 unsupported', () => {
    expect(readBrowserPermission(undefined)).toBe('unsupported');
  });

  it('세 가지 권한 값을 그대로 읽는다', () => {
    expect(readBrowserPermission({ permission: 'granted' })).toBe('granted');
    expect(readBrowserPermission({ permission: 'denied' })).toBe('denied');
    expect(readBrowserPermission({ permission: 'default' })).toBe('default');
  });

  it('차단이면 브라우저 설정 안내를 준다', () => {
    const view = describePermission('denied');
    expect(view.tone).toBe('warn');
    expect(view.hint).toContain('사이트 설정');
  });

  it('허용은 ok 톤이다', () => {
    expect(describePermission('granted').tone).toBe('ok');
  });
});

describe('기기 표식과 이 기기에서 켜짐 판정', () => {
  it('저장된 기기 표식을 읽고, 없거나 비면 null', () => {
    expect(readLocalDeviceId({ getItem: (k) => (k === NOTIFY_DEVICE_ID_STORAGE_KEY ? 'dev-1' : null) })).toBe('dev-1');
    expect(readLocalDeviceId({ getItem: () => null })).toBeNull();
    expect(readLocalDeviceId({ getItem: () => '  ' })).toBeNull();
    expect(readLocalDeviceId(null)).toBeNull();
  });

  it('저장소 접근이 던져도 null 로 처리한다', () => {
    expect(readLocalDeviceId({ getItem: () => { throw new Error('denied'); } })).toBeNull();
  });

  it('같은 기기 표식의 목록 항목에 앱이 있어야 켜진 것이다', () => {
    const devices = [device('dev-1', ['party']), device('dev-2', ['blog'])];
    expect(isAppOnThisDevice(devices, 'dev-1', 'party')).toBe(true);
    expect(isAppOnThisDevice(devices, 'dev-1', 'blog')).toBe(false);
    expect(isAppOnThisDevice(devices, 'dev-3', 'party')).toBe(false);
  });

  it('기기 표식을 모르면 어떤 앱도 켜졌다고 하지 않는다', () => {
    expect(isAppOnThisDevice([device('dev-1', ['party'])], null, 'party')).toBe(false);
  });
});

describe('서비스 사용 불가 판별', () => {
  it.each([404, 502, 503, 504])('상태 %i 는 사용할 수 없음', (status) => {
    expect(isNotifyUnavailable(httpError(status))).toBe(true);
  });

  it.each([400, 401, 403, 500])('상태 %i 는 일반 오류', (status) => {
    expect(isNotifyUnavailable(httpError(status))).toBe(false);
  });

  it('응답이 없는 네트워크 오류와 일반 오류는 사용 불가로 보지 않는다', () => {
    expect(isNotifyUnavailable(new AxiosError('Network Error'))).toBe(false);
    expect(isNotifyUnavailable(new Error('x'))).toBe(false);
  });
});

describe('표시 도우미', () => {
  it('앱 주소는 urlUtils 의 서비스 주소를 재사용하고 허브·모르는 앱은 null', () => {
    expect(appUrlFor('party')).toBe(getPartyUrl());
    expect(appUrlFor('blog')).toBe(getBlogUrl());
    expect(appUrlFor('games')).toBe(getGamesUrl());
    expect(appUrlFor('menu')).toBe(getMenuUrl());
    expect(appUrlFor('hub')).toBeNull();
    expect(appUrlFor('unknown')).toBeNull();
  });

  it('마지막 성공 시각이 없거나 잘못되면 안내 문구', () => {
    expect(formatLastSuccess(null)).toBe('아직 받은 적 없음');
    expect(formatLastSuccess('not-a-date')).toBe('아직 받은 적 없음');
    expect(formatLastSuccess('2026-10-20T01:00:00Z')).toContain('2026');
  });

  it('시험 알림 결과 문구', () => {
    expect(describeTestResult(2)).toContain('2개 기기');
    expect(describeTestResult(0)).toContain('받을 기기가 없어요');
  });
});
