import { parseConsentRequest } from './oauthConsent';

export const CONSENT_PATH = '/oauth2/consent';
export const CONSENT_RETURN_STORAGE_KEY = 'doro_oauth_consent_return';
/** 저장된 동의 요청의 유효 시간. 오래된 요청이 나중의 무관한 로그인 뒤에 되살아나는 것을 막는다. */
export const CONSENT_RETURN_TTL_MS = 10 * 60 * 1000;

// 로그인 후 복원하는 파라미터는 동의 화면이 쓰는 것만 허용한다.
const CONSENT_PARAM_NAMES = ['client_id', 'redirect_uri', 'code_challenge', 'code_challenge_method', 'scope', 'state', 'nonce'] as const;

interface StoredConsent {
  query: string;
  savedAt: number;
}

function isStoredConsent(value: unknown): value is StoredConsent {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.query === 'string' && typeof candidate.savedAt === 'number';
}

/** 검증을 통과한 동의 요청만 sessionStorage 에 저장한다. 저장했으면 true. */
export function saveConsentReturn(params: URLSearchParams, now: number = Date.now()): boolean {
  if (!parseConsentRequest(params).ok) {
    return false;
  }
  const filtered = new URLSearchParams();
  for (const name of CONSENT_PARAM_NAMES) {
    const value = params.get(name);
    if (value !== null) {
      filtered.set(name, value);
    }
  }
  const stored: StoredConsent = { query: filtered.toString(), savedAt: now };
  try {
    sessionStorage.setItem(CONSENT_RETURN_STORAGE_KEY, JSON.stringify(stored));
    return true;
  } catch (error: unknown) {
    console.warn('Failed to persist OAuth consent request', error);
    return false;
  }
}

export function clearConsentReturn(): void {
  try {
    sessionStorage.removeItem(CONSENT_RETURN_STORAGE_KEY);
  } catch (error: unknown) {
    console.warn('Failed to clear OAuth consent request', error);
  }
}

/**
 * 저장된 동의 요청이 유효하면 앱 내부 상대 경로(/oauth2/consent?...)를 반환한다. (삭제하지 않음)
 * 반환 경로는 항상 이 모듈이 상수와 검증된 파라미터로 직접 조립하며, 외부에서 받은 URL 은 사용하지 않는다.
 * 만료/손상/검증 실패 시 저장값을 지우고 null 을 반환한다.
 */
export function peekConsentReturnPath(now: number = Date.now()): string | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(CONSENT_RETURN_STORAGE_KEY);
  } catch (error: unknown) {
    console.warn('Failed to read OAuth consent request', error);
    return null;
  }
  if (raw === null) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (error: unknown) {
    console.warn('Discarding malformed OAuth consent request', error);
    clearConsentReturn();
    return null;
  }
  if (!isStoredConsent(parsed) || now - parsed.savedAt > CONSENT_RETURN_TTL_MS || now < parsed.savedAt) {
    clearConsentReturn();
    return null;
  }

  const params = new URLSearchParams(parsed.query);
  if (!parseConsentRequest(params).ok) {
    clearConsentReturn();
    return null;
  }
  const filtered = new URLSearchParams();
  for (const name of CONSENT_PARAM_NAMES) {
    const value = params.get(name);
    if (value !== null) {
      filtered.set(name, value);
    }
  }
  return `${CONSENT_PATH}?${filtered.toString()}`;
}
