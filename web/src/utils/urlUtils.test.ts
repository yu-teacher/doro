import { afterEach, describe, expect, it, vi } from 'vitest';
import { getGuardDocsUrl, getIamDocsUrl, resolveOptionalHttpUrl } from './urlUtils';

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
