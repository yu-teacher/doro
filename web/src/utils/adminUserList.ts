import type { AdminUserFilter } from '../types/auth';

/** 관리자 목록의 한 페이지 크기. 서버 상한(500)보다 훨씬 작게 둬서 화면이 가볍게 그려지게 한다. */
export const ADMIN_PAGE_SIZE = 20;

/** 전체 페이지 수(항목이 없어도 1). */
export function totalPages(total: number, size: number = ADMIN_PAGE_SIZE): number {
  if (size <= 0) return 1;
  return Math.max(1, Math.ceil(Math.max(0, total) / size));
}

/** 0부터 시작하는 페이지 번호를 존재하는 범위로 맞춘다(마지막 페이지의 항목이 사라진 뒤 등). */
export function clampPage(page: number, total: number, size: number = ADMIN_PAGE_SIZE): number {
  return Math.min(Math.max(0, page), totalPages(total, size) - 1);
}

/** "21–40 / 12,264" 형태의 표시. 항목이 없으면 "0명". */
export function pageRangeLabel(page: number, count: number, total: number, size: number = ADMIN_PAGE_SIZE): string {
  if (total <= 0 || count <= 0) return '0명';
  const from = page * size + 1;
  const to = page * size + count;
  return `${from.toLocaleString('ko-KR')}–${to.toLocaleString('ko-KR')} / ${total.toLocaleString('ko-KR')}`;
}

export interface AdminUserListParams {
  page: number;
  size?: number;
  q?: string;
  filter?: AdminUserFilter;
}

/** 서버가 받는 쿼리 파라미터로 바꾼다. 빈 검색어와 ALL 필터는 보내지 않는다. */
export function adminUserListParams({ page, size = ADMIN_PAGE_SIZE, q, filter }: AdminUserListParams): Record<string, string | number> {
  const params: Record<string, string | number> = { page, size };
  const query = q?.trim();
  if (query) params.q = query.slice(0, 100);
  if (filter && filter !== 'ALL') params.filter = filter;
  return params;
}

/** X-Total-Count 헤더를 숫자로 읽는다. 없거나 잘못되면 null. */
export function parseTotalCount(header: unknown): number | null {
  if (typeof header !== 'string' && typeof header !== 'number') return null;
  const n = Number(header);
  return Number.isInteger(n) && n >= 0 ? n : null;
}
