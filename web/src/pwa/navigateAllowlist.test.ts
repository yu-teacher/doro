import { describe, expect, it } from 'vitest';
import { HUB_NAVIGATE_ALLOWLIST } from './navigateAllowlist';

const allowed = (url: string): boolean => HUB_NAVIGATE_ALLOWLIST.some((re) => re.test(url));

describe('허브 서비스 워커 이동 허용 목록', () => {
  it('허브 자신의 라우트는 허용한다', () => {
    for (const url of ['/', '/portal', '/login', '/signup', '/account', '/logs', '/login?returnTo=%2Fparty%2F', '/account/', '/?x=1']) {
      expect(allowed(url), url).toBe(true);
    }
  });

  it('다른 서비스·OAuth·API·옛 블로그 주소는 허용하지 않는다(네트워크로 보낸다)', () => {
    for (const url of ['/blog/', '/blog/@doro', '/party/', '/games/', '/menu/', '/oauth2/consent', '/oauth2/authorize?x=1', '/api/v1/auth/login', '/loki/api/v1/labels', '/.well-known/openid-configuration', '/@doro', '/tags?tag=java', '/loginx', '/logs/extra']) {
      expect(allowed(url), url).toBe(false);
    }
  });
});
