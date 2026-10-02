import { describe, expect, it } from 'vitest';
import { escapeLogQlString } from './logqlUtils';

describe('escapeLogQlString', () => {
  it('큰따옴표와 역슬래시를 이스케이프한다', () => {
    expect(escapeLogQlString('say "hi" \\ there')).toBe('say \\"hi\\" \\\\ there');
  });

  it('스트림 셀렉터 주입 페이로드가 문자열 밖으로 탈출하지 못한다', () => {
    const escaped = escapeLogQlString('" } or {service=~".+"');
    expect(escaped).toBe('\\" } or {service=~\\".+\\"');
    // 이스케이프되지 않은 큰따옴표가 남아 있지 않아야 한다
    expect(escaped.replace(/\\\\/g, '').replace(/\\"/g, '')).not.toContain('"');
  });

  it('역슬래시로 이스케이프를 무력화하려는 입력도 안전하다', () => {
    expect(escapeLogQlString('\\" } ')).toBe('\\\\\\" } ');
  });

  it('제어 문자(개행, 탭, NUL, DEL)를 제거한다', () => {
    expect(escapeLogQlString('a\nb\tc\u0000d\u007Fe\r')).toBe('abcde');
  });

  it('일반 텍스트와 한글/이모지는 그대로 유지한다', () => {
    expect(escapeLogQlString('traceId=abc-123 오류 🚨')).toBe('traceId=abc-123 오류 🚨');
  });
});
