export interface ConsentRequest {
  clientId: string;
  redirectUri: string;
  redirectHost: string;
  codeChallenge: string;
  state: string | null;
}

export type ConsentParseResult =
  | { ok: true; request: ConsentRequest }
  | { ok: false; reason: string };

const ALLOWED_SCHEMES = new Set(['https:', 'http:']);
// RFC 7636 S256 code_challenge: base64url 43자
const CODE_CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{43}$/;

/**
 * 동의 화면 쿼리 파라미터를 검증한다. 화면에서 하는 검증은 사용자에게 잘못된 요청을 일찍 알려주기 위한 것이며,
 * 실제 redirect_uri 허용 여부는 인가 서버(IAM)의 허용 목록이 최종 판정한다.
 */
export function parseConsentRequest(params: URLSearchParams): ConsentParseResult {
  const clientId = params.get('client_id');
  const redirectUri = params.get('redirect_uri');
  const codeChallenge = params.get('code_challenge');
  if (!clientId || !redirectUri || !codeChallenge) {
    return { ok: false, reason: '필수 요청 정보(client_id, redirect_uri, code_challenge)가 없습니다.' };
  }
  if (!CODE_CHALLENGE_PATTERN.test(codeChallenge)) {
    return { ok: false, reason: 'code_challenge 형식이 올바르지 않습니다.' };
  }

  let url: URL;
  try {
    url = new URL(redirectUri);
  } catch {
    return { ok: false, reason: 'redirect_uri 형식이 올바르지 않습니다.' };
  }
  if (!ALLOWED_SCHEMES.has(url.protocol) || url.username || url.password || url.hash) {
    return { ok: false, reason: '허용되지 않는 redirect_uri 입니다.' };
  }

  return { ok: true, request: { clientId, redirectUri, redirectHost: url.host, codeChallenge, state: params.get('state') } };
}

/** 서버가 발급한 인가 코드를 redirect_uri 의 기존 쿼리를 보존한 채 붙인다. */
export function buildAuthorizationRedirect(redirectUri: string, code: string, state: string | null): string {
  const target = new URL(redirectUri);
  target.searchParams.set('code', code);
  if (state) {
    target.searchParams.set('state', state);
  }
  return target.toString();
}
