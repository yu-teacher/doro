import axios from 'axios';
import type { NotifyDevice } from '../api/notifyApi';
import { getBlogUrl, getGamesUrl, getMenuUrl, getPartyUrl } from './urlUtils';

/**
 * 같은 출처의 localStorage 에 앱들이 함께 쓰는 기기 표식(UUID). 설계 8장: 허브가 앱별 구독을 한 기기로 묶는 표시용 값이다.
 * 허브는 읽기만 한다(만드는 쪽은 각 앱의 구독 코드). 키 이름은 앱 쪽 구독 코드와 맞춰야 한다.
 */
export const NOTIFY_DEVICE_ID_STORAGE_KEY = 'doro.notify.deviceId';

export type BrowserPermission = 'granted' | 'denied' | 'default' | 'unsupported';

/** 이 브라우저의 알림 권한. 권한은 출처 단위라 모든 앱에 같이 적용된다. Notification API 가 없는 브라우저는 unsupported. */
export function readBrowserPermission(notification: { permission: NotificationPermission } | undefined = globalThis.Notification): BrowserPermission {
  return notification ? notification.permission : 'unsupported';
}

export interface PermissionView {
  label: string;
  tone: 'ok' | 'warn' | 'neutral';
  hint: string;
}

export function describePermission(permission: BrowserPermission): PermissionView {
  switch (permission) {
    case 'granted':
      return { label: '허용됨', tone: 'ok', hint: '이 브라우저는 알림을 받을 수 있어요.' };
    case 'denied':
      return {
        label: '차단됨',
        tone: 'warn',
        hint: '이 브라우저에서 알림이 차단돼 있어요. 주소창 왼쪽의 사이트 설정(자물쇠 아이콘)에서 알림을 허용으로 바꾼 뒤 다시 켜 주세요.',
      };
    case 'default':
      return { label: '아직 묻지 않음', tone: 'neutral', hint: '앱에서 알림을 켤 때 브라우저가 허용 여부를 물어봐요.' };
    default:
      return { label: '지원하지 않음', tone: 'neutral', hint: '이 브라우저는 웹 알림을 지원하지 않아요. 설정은 바꿀 수 있지만 이 기기로는 받지 못해요.' };
  }
}

/** 이 브라우저의 기기 표식. localStorage 를 쓸 수 없거나 값이 없으면 null(이 기기가 목록에서 어느 것인지 알 수 없다). */
export function readLocalDeviceId(storage: Pick<Storage, 'getItem'> | null = safeLocalStorage()): string | null {
  if (!storage) return null;
  try {
    const value = storage.getItem(NOTIFY_DEVICE_ID_STORAGE_KEY);
    return value && value.trim() ? value : null;
  } catch {
    // 개인정보 보호 모드 등에서 저장소 접근 자체가 던질 수 있다. 기기를 못 알아볼 뿐 화면은 그대로 동작한다.
    return null;
  }
}

function safeLocalStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

/** 지금 이 브라우저(기기 표식이 같은 항목)에서 해당 앱의 알림이 켜져 있는지. 기기를 못 알아보면 false. */
export function isAppOnThisDevice(devices: NotifyDevice[], localDeviceId: string | null, app: string): boolean {
  if (!localDeviceId) return false;
  return devices.some((device) => device.deviceId === localDeviceId && device.apps.includes(app));
}

/** 서비스가 아직 없거나(404) 내려가 있을 때(502·503·504) 섹션을 조용히 "사용할 수 없음"으로 처리하기 위한 판별. */
const UNAVAILABLE_STATUSES = new Set([404, 502, 503, 504]);

export function isNotifyUnavailable(err: unknown): boolean {
  return axios.isAxiosError(err) && err.response !== undefined && UNAVAILABLE_STATUSES.has(err.response.status);
}

/** 앱 식별자 → 그 앱 화면 주소(앱에서 켜기 링크). 모르는 앱이나 허브 자신은 null. */
export function appUrlFor(app: string): string | null {
  switch (app) {
    case 'party':
      return getPartyUrl();
    case 'blog':
      return getBlogUrl();
    case 'games':
      return getGamesUrl();
    case 'menu':
      return getMenuUrl();
    default:
      return null;
  }
}

/** 마지막 성공 시각 표시. 한 번도 성공하지 않았거나 시각을 읽을 수 없으면 안내 문구. */
export function formatLastSuccess(iso: string | null, locale = 'ko-KR'): string {
  if (!iso) return '아직 받은 적 없음';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '아직 받은 적 없음';
  return date.toLocaleString(locale, { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function describeTestResult(sent: number): string {
  return sent > 0 ? `${sent}개 기기로 시험 알림을 보냈어요. 잠시 뒤 도착하는지 확인해 보세요.` : '시험 알림을 받을 기기가 없어요. 앱에서 알림을 먼저 켜 주세요.';
}
