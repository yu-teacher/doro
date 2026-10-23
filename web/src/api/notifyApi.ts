import { apiClient } from './client';

/**
 * 플랫폼 알림 서비스(doro-notify) 클라이언트. 설계: docs/NOTIFICATION_SERVICE_DESIGN.md 7.1.
 * 허브는 게이트웨이의 `/notify/api/v1/` 를 Bearer 토큰으로 부른다(apiClient 가 토큰 첨부·401 갱신을 맡는다).
 * 앱 식별 헤더(X-Doro-Notify-App)는 보내지 않는다. 허브 경로는 설정 조회·변경과 기기 해제만 허용된다(구독 등록은 앱 경로에서만).
 *
 * 서버 응답(doro-notify 의 SubscriptionDtos·PreferenceDtos)은 `Raw*` 타입으로 받고, 화면이 쓰는 모양으로 바꿔 돌려준다.
 */
const NOTIFY_API_PREFIX = '/notify/api/v1';

export interface NotifyConfig {
  /** 서버가 푸시를 보낼 준비가 됐는지(VAPID 키 설정 등). false 면 설정은 볼 수 있어도 알림은 가지 않는다. */
  available: boolean;
}

export interface NotifyTypePreference {
  type: string;
  label: string;
  description?: string | null;
  enabled: boolean;
  /** 종류 등록부에서 폐기 표시된 종류. 화면에는 보이지 않는다. */
  deprecated?: boolean;
}

export interface NotifyAppPreferences {
  /** 앱 식별자(party, blog, games ...) */
  app: string;
  displayName: string;
  types: NotifyTypePreference[];
}

export interface NotifyDevice {
  deviceId: string;
  /** 브라우저 종류 표시명(서버가 user agent 에서 뽑는다). 모르면 null. endpoint·키는 받지 않는다. */
  browser: string | null;
  lastSuccessAt: string | null;
  /** 이 기기에서 알림이 켜진 앱 식별자 목록 */
  apps: string[];
}

export interface NotifyTestResult {
  /** 시험 알림을 보낸 기기 수. 0 이면 받을 기기가 없다. */
  sent: number;
}

interface Envelope<T> {
  success: boolean;
  data: T;
}

interface RawConfig {
  enabled: boolean;
  publicKey: string | null;
}

interface RawTypeView {
  type: string;
  label: string;
  description: string | null;
  defaultOn: boolean;
  enabled: boolean;
}

interface RawPreferences {
  apps: { app: string; displayName: string; types: RawTypeView[] }[];
}

interface RawDevices {
  devices: {
    deviceId: string;
    browser: string | null;
    lastSeenAt: string | null;
    lastSuccessAt: string | null;
    apps: { app: string; displayName: string; subscribedAt: string | null; lastSuccessAt: string | null }[];
  }[];
}

interface RawTestResult {
  devices: number;
  delivered: number;
}

const get = async <T>(path: string): Promise<T> => (await apiClient.get<Envelope<T>>(`${NOTIFY_API_PREFIX}${path}`)).data.data;

export const notifyApi = {
  getConfig: async (): Promise<NotifyConfig> => ({ available: (await get<RawConfig>('/config')).enabled }),

  getPreferences: async (): Promise<NotifyAppPreferences[]> => (await get<RawPreferences>('/preferences')).apps,

  setPreference: async (app: string, type: string, enabled: boolean): Promise<void> => {
    await apiClient.put(`${NOTIFY_API_PREFIX}/preferences`, { changes: [{ app, type, enabled }] });
  },

  getDevices: async (): Promise<NotifyDevice[]> =>
    (await get<RawDevices>('/devices')).devices.map((d) => ({
      deviceId: d.deviceId,
      browser: d.browser,
      lastSuccessAt: d.lastSuccessAt,
      apps: d.apps.map((a) => a.app),
    })),

  removeDevice: async (deviceId: string): Promise<void> => {
    await apiClient.delete(`${NOTIFY_API_PREFIX}/devices/${encodeURIComponent(deviceId)}`);
  },

  sendTest: async (): Promise<NotifyTestResult> => {
    const response = await apiClient.post<Envelope<RawTestResult>>(`${NOTIFY_API_PREFIX}/test`);
    return { sent: response.data.data.devices };
  },
};
