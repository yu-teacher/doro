import { afterEach, describe, expect, it, vi } from 'vitest';
import { getBlogUrl, getGuardDocsUrl, getIamDocsUrl, resolveOptionalHttpUrl } from './urlUtils';

describe('resolveOptionalHttpUrl', () => {
  it('비어 있거나 공백이면 null', () => {
    expect(resolveOptionalHttpUrl(undefined)).toBeNull();
    expect(resolveOptionalHttpUrl('')).toBeNull();
    expect(resolveOptionalHttpUrl('   ')).toBeNull();
  });

  it('http(s) 절대 URL 만 허용하고 앞뒤 공백은 제거한다', () => {
    expect(resolveOptionalHttpUrl(' https://docs.example.com/swagger-ui.html ')).toBe('https://docs.example.com/swagger-ui.html');
    expect(resolveOptionalHttpUrl('http://localhost:28081/swagger-ui.html')).toBe('http://localhost:28081/swagger-ui.html');
  });

  it('javascript: 등 다른 스킴이나 상대/잘못된 URL 은 null', () => {
    expect(resolveOptionalHttpUrl('javascript:alert(1)')).toBeNull();
    expect(resolveOptionalHttpUrl('/swagger-ui.html')).toBeNull();
    expect(resolveOptionalHttpUrl('not a url')).toBeNull();
  });
});

describe('문서 URL 환경변수', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('미설정이면 null', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', '');
    vi.stubEnv('VITE_IAM_DOCS_URL', '');
    expect(getGuardDocsUrl()).toBeNull();
    expect(getIamDocsUrl()).toBeNull();
  });

  it('설정되면 각각의 값을 반환', () => {
    vi.stubEnv('VITE_GUARD_DOCS_URL', 'https://guard.example.com/docs');
    vi.stubEnv('VITE_IAM_DOCS_URL', 'https://iam.example.com/docs');
    expect(getGuardDocsUrl()).toBe('https://guard.example.com/docs');
    expect(getIamDocsUrl()).toBe('https://iam.example.com/docs');
  });
});

describe('getBlogUrl', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
  });

  it('게이트웨이 기준으로 블로그는 /blog/ 이다(루트는 허브)', () => {
    vi.stubEnv('VITE_BLOG_URL', '');
    vi.stubGlobal('window', { location: { port: '', protocol: 'https:', hostname: 'example.test' } });
    expect(getBlogUrl()).toBe('/blog/');
  });

  it('3000 포트 직접 접속이면 블로그 개발 서버(3002)로 간다', () => {
    vi.stubEnv('VITE_BLOG_URL', '');
    vi.stubGlobal('window', { location: { port: '3000', protocol: 'http:', hostname: 'localhost' } });
    expect(getBlogUrl()).toBe('http://localhost:3002/');
  });

  it('VITE_BLOG_URL 이 있으면 그 값을 쓴다', () => {
    vi.stubEnv('VITE_BLOG_URL', 'https://blog.example.test/');
    expect(getBlogUrl()).toBe('https://blog.example.test/');
  });
});
