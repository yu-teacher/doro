import { describe, expect, it } from 'vitest';
import { ADMIN_PAGE_SIZE, adminUserListParams, clampPage, pageRangeLabel, parseTotalCount, totalPages } from './adminUserList';

describe('totalPages', () => {
  it('항목이 없어도 1페이지', () => expect(totalPages(0)).toBe(1));
  it('경계: 정확히 한 페이지 / 한 항목 넘침', () => {
    expect(totalPages(ADMIN_PAGE_SIZE)).toBe(1);
    expect(totalPages(ADMIN_PAGE_SIZE + 1)).toBe(2);
  });
  it('큰 수', () => expect(totalPages(12264, 20)).toBe(614));
});

describe('clampPage', () => {
  it('범위 안이면 그대로', () => expect(clampPage(3, 100, 20)).toBe(3));
  it('마지막 페이지를 넘으면 마지막으로', () => expect(clampPage(9, 41, 20)).toBe(2));
  it('음수는 0', () => expect(clampPage(-2, 100, 20)).toBe(0));
  it('항목이 없으면 0', () => expect(clampPage(5, 0, 20)).toBe(0));
});

describe('pageRangeLabel', () => {
  it('첫 페이지', () => expect(pageRangeLabel(0, 20, 12264, 20)).toBe('1–20 / 12,264'));
  it('마지막 페이지는 실제 개수까지', () => expect(pageRangeLabel(2, 1, 41, 20)).toBe('41–41 / 41'));
  it('비어 있으면 0명', () => expect(pageRangeLabel(0, 0, 0)).toBe('0명'));
});

describe('adminUserListParams', () => {
  it('기본: page 와 size 만', () => expect(adminUserListParams({ page: 0 })).toEqual({ page: 0, size: ADMIN_PAGE_SIZE }));
  it('검색어는 앞뒤 공백을 지우고, 비어 있으면 보내지 않는다', () => {
    expect(adminUserListParams({ page: 1, q: '  kim ' })).toEqual({ page: 1, size: ADMIN_PAGE_SIZE, q: 'kim' });
    expect(adminUserListParams({ page: 0, q: '   ' })).toEqual({ page: 0, size: ADMIN_PAGE_SIZE });
  });
  it('ALL 필터는 보내지 않고 나머지는 보낸다', () => {
    expect(adminUserListParams({ page: 0, filter: 'ALL' })).not.toHaveProperty('filter');
    expect(adminUserListParams({ page: 0, filter: 'TOTP' })).toMatchObject({ filter: 'TOTP' });
  });
  it('서버 상한(100자)을 넘는 검색어는 자른다', () => {
    expect((adminUserListParams({ page: 0, q: 'x'.repeat(300) }).q as string).length).toBe(100);
  });
});

describe('parseTotalCount', () => {
  it('숫자 문자열', () => expect(parseTotalCount('12264')).toBe(12264));
  it('없거나 잘못된 값은 null', () => {
    expect(parseTotalCount(undefined)).toBeNull();
    expect(parseTotalCount('abc')).toBeNull();
    expect(parseTotalCount('-1')).toBeNull();
    expect(parseTotalCount('1.5')).toBeNull();
  });
});
