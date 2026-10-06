export interface ConsentRequest {
  clientId: string;
  redirectUri: string;
  redirectHost: string;
  codeChallenge: string;
  /** 서버가 지원하는 값은 S256 뿐이며, 쿼리에 없으면 S256 으로 간주한다. */
  codeChallengeMethod: 'S256';
  /** 요청된 스코프(중복 제거, 요청 순서 유지). 요청에 scope 가 없으면 빈 배열. */
  scopes: string[];
  state: string | null;
  nonce: string | null;
}

export type ConsentParseResult =
  | { ok: true; request: ConsentRequest }
  | { ok: false; reason: string };

export interface ScopeDescription {
  scope: string;
  label: string;
}

const HTTPS_PROTOCOL = 'https:';
const HTTP_PROTOCOL = 'http:';
// 서버(IAM)의 redirect_uri 등록 규칙과 같다: https, 그리고 loopback 호스트에 한해 http
const LOOPBACK_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]']);
// RFC 7636 S256 code_challenge: base64url 43자
const CODE_CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{43}$/;
// RFC 6749 §3.3 scope-token
const SCOPE_TOKEN_PATTERN = /^[\x21\x23-\x5B\x5D-\x7E]+$/;
const PKCE_METHOD_S256 = 'S256';
// 서버 제한과 동일(OAuth2Constants)
const MAX_STATE_LENGTH = 512;
const MAX_NONCE_LENGTH = 256;
const MAX_SCOPE_LENGTH = 200;
/** 서버 오류 메시지를 화면에 보여줄 때의 최대 길이 */
const MAX_SERVER_MESSAGE_LENGTH = 200;

const SCOPE_LABELS: Readonly<Record<string, string>> = {
  openid: 'Doro 계정으로 본인 확인 (사용자 ID)',
  profile: '기본 프로필 정보 (이름, 프로필 사진)',
  email: '이메일 주소',
};
const KNOWN_SCOPES: ReadonlySet<string> = new Set(Object.keys(SCOPE_LABELS));
/** scope 를 요청하지 않은 클라이언트에게 보여줄 설명 */
const NO_SCOPE_LABEL = 'Doro 계정 로그인 (사용자 식별)';
const GENERIC_AUTHORIZE_ERROR = '승인 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.';
const REJECTED_AUTHORIZE_ERROR = '허용되지 않은 요청입니다. 앱 관리자에게 문의해 주세요.';
const HTTP_BAD_REQUEST = 400;

function isAllowedRedirectProtocol(url: URL): boolean {
  if (url.protocol === HTTPS_PROTOCOL) return true;
  return url.protocol === HTTP_PROTOCOL && LOOPBACK_HOSTNAMES.has(url.hostname);
}

function parseScopes(raw: string | null): string[] | null {
  if (raw === null || raw.trim() === '') return [];
  if (raw.length > MAX_SCOPE_LENGTH) return null;
  const tokens = raw.trim().split(/ +/);
  if (!tokens.every((token) => SCOPE_TOKEN_PATTERN.test(token) && KNOWN_SCOPES.has(token))) return null;
  return Array.from(new Set(tokens));
}

/**
 * 동의 화면 쿼리 파라미터를 검증한다. 화면에서 하는 검증은 사용자에게 잘못된 요청을 일찍 알려주기 위한 것이며,
 * 실제 client/redirect_uri/scope 허용 여부는 인가 서버(IAM)가 최종 판정한다.
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
  const method = params.get('code_challenge_method');
  if (method !== null && method !== PKCE_METHOD_S256) {
    return { ok: false, reason: 'code_challenge_method 는 S256 만 지원합니다.' };
  }

  let url: URL;
  try {
    url = new URL(redirectUri);
  } catch {
    return { ok: false, reason: 'redirect_uri 형식이 올바르지 않습니다.' };
  }
  if (!isAllowedRedirectProtocol(url) || url.username || url.password || url.hash) {
    return { ok: false, reason: '허용되지 않는 redirect_uri 입니다.' };
  }

  const scopes = parseScopes(params.get('scope'));
  if (scopes === null) {
    return { ok: false, reason: '지원하지 않는 scope 가 요청되었습니다.' };
  }
  const state = params.get('state');
  const nonce = params.get('nonce');
  if ((state !== null && state.length > MAX_STATE_LENGTH) || (nonce !== null && nonce.length > MAX_NONCE_LENGTH)) {
    return { ok: false, reason: 'state 또는 nonce 가 너무 깁니다.' };
  }

  return {
    ok: true,
    request: {
      clientId,
      redirectUri,
      redirectHost: url.host,
      codeChallenge,
      codeChallengeMethod: PKCE_METHOD_S256,
      scopes,
      state,
      nonce,
    },
  };
}

/** 요청된 스코프를 사용자에게 보여줄 한국어 설명으로 바꾼다. 스코프가 없으면 기본 로그인 설명 한 줄. */
export function describeScopes(scopes: readonly string[]): ScopeDescription[] {
  if (scopes.length === 0) {
    return [{ scope: '', label: NO_SCOPE_LABEL }];
  }
  return scopes.map((scope) => ({ scope, label: SCOPE_LABELS[scope] ?? scope }));
}

/** 인가 엔드포인트(Bearer JSON 모드)에 보낼 쿼리 파라미터. 값이 없는 선택 파라미터는 포함하지 않는다. */
export function buildAuthorizeParams(request: ConsentRequest): Record<string, string> {
  const params: Record<string, string> = {
    client_id: request.clientId,
    redirect_uri: request.redirectUri,
    response_type: 'code',
    code_challenge: request.codeChallenge,
    code_challenge_method: request.codeChallengeMethod,
  };
  if (request.scopes.length > 0) {
    params.scope = request.scopes.join(' ');
  }
  if (request.state) {
    params.state = request.state;
  }
  if (request.nonce) {
    params.nonce = request.nonce;
  }
  return params;
}

/** 서버가 보낸 값을 화면에 안전하게 보여줄 수 있는 짧은 한 줄 문자열로 정리한다. 쓸 수 없으면 null. */
export function sanitizeServerMessage(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  // eslint 규칙 없이도 제어 문자(줄바꿈 포함)를 공백으로 치환해 한 줄로 만든다.
  const singleLine = Array.from(value, (char) => (char.charCodeAt(0) < 0x20 || char.charCodeAt(0) === 0x7f ? ' ' : char))
    .join('')
    .replace(/\s+/g, ' ')
    .trim();
  if (singleLine === '') return null;
  return singleLine.length > MAX_SERVER_MESSAGE_LENGTH ? `${singleLine.slice(0, MAX_SERVER_MESSAGE_LENGTH)}…` : singleLine;
}

/**
 * 인가 요청 실패를 사용자 메시지로 바꾼다. 400 이면 서버가 알려 준 사유(정리한 문자열)를 함께 보여 주고,
 * 그 외에는 일반 안내만 보여 준다. 메시지는 React 텍스트로만 렌더링해야 하며 HTML 로 해석하지 않는다.
 */
export function resolveAuthorizeError(status: number | undefined, serverMessage: unknown): string {
  if (status !== HTTP_BAD_REQUEST) return GENERIC_AUTHORIZE_ERROR;
  const reason = sanitizeServerMessage(serverMessage);
  return reason ? `${REJECTED_AUTHORIZE_ERROR} (${reason})` : REJECTED_AUTHORIZE_ERROR;
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

/**
 * /oauth2/client-info 응답에서 자사(first-party) 앱 여부를 읽는다.
 * true 가 확실할 때만 동의 화면을 건너뛴다: 응답 형식이 다르거나 값이 없으면 false 로 보고 동의 화면을 그대로 보여 준다.
 */
export function isFirstPartyClient(body: unknown): boolean {
  if (typeof body !== 'object' || body === null) return false;
  const data = (body as { data?: unknown }).data;
  if (typeof data !== 'object' || data === null) return false;
  return (data as { firstParty?: unknown }).firstParty === true;
}
