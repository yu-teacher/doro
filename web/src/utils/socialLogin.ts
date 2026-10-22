/**
 * 소셜 로그인(GitHub, Google ...). 로그인 화면이 공급자로 이동했다가 /login?social=... 로 돌아온다.
 * 서버는 성공하면 리프레시 토큰을 HttpOnly 쿠키(슬롯)로 심고, 2FA 가 필요하면 임시 티켓을 준다. 토큰은 주소에 실리지 않는다.
 */

export interface SocialProvider {
  id: string;
  name: string;
}

export type SocialReturn =
  | { kind: 'ok'; slot: number }
  | { kind: '2fa'; slot: number; ticket: string }
  | { kind: 'error'; code: string };

/** 서버(RefreshTokenCookies.MAX_SLOT)와 맞춘 슬롯 범위(0~4)인지. */
function parseSlot(raw: string | null): number | null {
  if (raw === null || !/^\d$/.test(raw)) return null;
  const slot = Number(raw);
  return slot >= 0 && slot <= 4 ? slot : null;
}

/** 로그인 화면 주소의 쿼리에서 소셜 로그인 결과를 읽는다. 소셜 로그인으로 돌아온 것이 아니면 null. */
export function parseSocialReturn(params: URLSearchParams): SocialReturn | null {
  const result = params.get('social');
  if (result === null) return null;

  if (result === 'ok') {
    const slot = parseSlot(params.get('slot'));
    return slot === null ? { kind: 'error', code: 'provider_error' } : { kind: 'ok', slot };
  }
  if (result === '2fa') {
    const slot = parseSlot(params.get('slot'));
    const ticket = params.get('ticket');
    return slot === null || !ticket ? { kind: 'error', code: 'provider_error' } : { kind: '2fa', slot, ticket };
  }
  return { kind: 'error', code: params.get('code') ?? 'provider_error' };
}

const ERROR_MESSAGES: Record<string, string> = {
  invalid_state: '로그인 요청이 만료되었거나 올바르지 않습니다. 다시 시도해 주세요.',
  email_not_verified: '인증된 이메일이 없어 로그인할 수 없습니다. 해당 서비스에서 이메일을 인증한 뒤 다시 시도해 주세요.',
  account_suspended: '정지되었거나 탈퇴한 계정입니다.',
  denied: '로그인이 취소되었습니다.',
  unavailable: '지금은 사용할 수 없는 로그인 방식입니다.',
  too_many: '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
  provider_error: '소셜 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.',
};

export function socialErrorMessage(code: string): string {
  return ERROR_MESSAGES[code] ?? ERROR_MESSAGES.provider_error;
}

/** 공급자 로그인을 시작하는 주소(브라우저 이동). 슬롯은 돌아왔을 때 리프레시 쿠키를 둘 자리다. */
export function socialStartUrl(providerId: string, slot: number): string {
  return `/api/v1/auth/social/${encodeURIComponent(providerId)}/start?slot=${slot}`;
}
