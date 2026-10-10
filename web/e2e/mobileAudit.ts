import type { Page } from '@playwright/test';

/**
 * 모바일 화면 감사: 실제 브라우저에서 레이아웃을 측정해 "모바일에서 깨지는" 전형적인 문제를 찾는다.
 * jsdom 은 레이아웃을 계산하지 못해 이런 문제를 못 잡는다. 이 파일은 Doro(포털)·블로그·파티·게임 저장소가 같은 내용을 쓴다.
 * 한 곳을 고치면 나머지 저장소의 e2e/mobileAudit.ts 도 같이 고친다.
 *
 * 찾는 것
 *  - page-overflow    페이지 전체가 화면보다 넓어 가로 스크롤이 생김
 *  - element-overflow 화면 밖으로 나간 요소(스크롤·잘림 영역 안에 담긴 것은 제외)
 *  - text-overflow    글자가 자기 상자(요소)를 벗어나 옆으로 삐져나감(줄바꿈되지 않는 긴 단어). 페이지 가로 스크롤의 가장 흔한 원인이다
 *  - clipped-content  글자가 있는 요소가 overflow:hidden 인 부모 밖으로 삐져나와 잘림(긴 단어·식별자가 줄바꿈되지 않을 때)
 *  - small-tap-target 눌러야 하는 요소가 너무 작음(본문 속 인라인 링크는 제외)
 *  - small-input-font 입력창 글꼴이 16px 미만이라 iOS Safari 가 포커스 때 화면을 확대함
 */
export type ViolationKind = 'page-overflow' | 'element-overflow' | 'text-overflow' | 'clipped-content' | 'small-tap-target' | 'small-input-font';

export interface Violation {
  kind: ViolationKind;
  selector: string;
  detail: string;
}

export interface AuditOptions {
  /** 눌러야 하는 요소의 최소 변(px). 애플 44·머티리얼 48 이 권장이고, 이 값은 우리가 허용하는 하한이다. */
  minTapTarget?: number;
  /** 검사에서 뺄 요소의 CSS 선택자. 뺄 때는 이유를 테스트 코드 주석으로 남긴다. */
  ignore?: string[];
}

export const DEFAULT_MIN_TAP_TARGET = 32;

export async function auditMobile(page: Page, options: AuditOptions = {}): Promise<Violation[]> {
  const minTap = options.minTapTarget ?? DEFAULT_MIN_TAP_TARGET;
  const ignore = options.ignore ?? [];
  // 모바일 브라우저는 내용이 기기 너비보다 넓으면 레이아웃 뷰포트를 넓혀 버리므로(innerWidth 가 늘어남) 기기 너비는 설정값으로 기준 삼는다.
  const deviceWidth = page.viewportSize()?.width;
  if (!deviceWidth) throw new Error('viewport 크기를 알 수 없습니다(테스트 설정의 viewport 가 필요합니다).');
  return page.evaluate(
    ({ minTap: min, ignore: ignored, deviceWidth: vw }) => {
      const out: Array<{ kind: string; selector: string; detail: string }> = [];
      const TOL = 1;

      const describe = (el: Element): string => {
        const tag = el.tagName.toLowerCase();
        const id = el.id ? `#${el.id}` : '';
        const cls = typeof (el as HTMLElement).className === 'string'
          ? (el as HTMLElement).className.split(/\s+/).filter(Boolean).slice(0, 3).map((c) => `.${c}`).join('')
          : '';
        const text = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 24);
        return `${tag}${id}${cls}${text ? ` "${text}"` : ''}`;
      };

      const isIgnored = (el: Element) => ignored.some((selector) => el.closest(selector) !== null);

      const isVisible = (el: Element): boolean => {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return false;
        const style = getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) return false;
        return el.closest('[aria-hidden="true"], [hidden]') === null;
      };

      // 가로로 잘리거나 스크롤되는 조상 안에 담겨 있으면 화면 밖으로 나가도 문제가 아니다(탭 줄처럼 의도한 가로 스크롤)
      const containedByAncestor = (el: Element): boolean => {
        for (let p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
          const style = getComputedStyle(p);
          const clips = ['auto', 'scroll', 'hidden', 'clip'].includes(style.overflowX);
          if (clips && p.getBoundingClientRect().right <= vw + TOL) return true;
        }
        return false;
      };

      // 1) 페이지 전체
      const scrollWidth = document.documentElement.scrollWidth;
      if (scrollWidth > vw + TOL) {
        out.push({ kind: 'page-overflow', selector: 'html', detail: `scrollWidth ${scrollWidth}px > 화면 ${vw}px` });
      }

      // 2) 화면 밖으로 나간 요소: 가장 안쪽 원인만 보고한다(부모가 이미 보고됐으면 자식은 생략)
      const overflowing = new Set<Element>();
      for (const el of Array.from(document.body.querySelectorAll('*'))) {
        if (isIgnored(el) || !isVisible(el)) continue;
        const rect = el.getBoundingClientRect();
        if ((rect.right > vw + TOL || rect.left < -TOL) && !containedByAncestor(el)) overflowing.add(el);
      }
      for (const el of overflowing) {
        if (el.parentElement && overflowing.has(el.parentElement)) continue;
        const rect = el.getBoundingClientRect();
        out.push({ kind: 'element-overflow', selector: describe(el), detail: `left ${Math.round(rect.left)} right ${Math.round(rect.right)} (화면 ${vw}px)` });
      }

      // 2-0) 글자가 자기 상자를 벗어나는 경우: 요소의 박스는 화면 안인데 줄바꿈되지 않는 긴 단어가 박스 밖으로 흘러나간다(요소 위치만 재면 놓친다).
      const hasText = (el: Element): boolean =>
        Array.from(el.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent || '').trim().length > 0);
      for (const el of Array.from(document.body.querySelectorAll('*'))) {
        const box = el as HTMLElement;
        if (isIgnored(el) || !isVisible(el) || !hasText(el)) continue;
        const style = getComputedStyle(box);
        if (style.display === 'inline' || style.overflowX !== 'visible') continue;
        if (box.scrollWidth > box.clientWidth + TOL && box.clientWidth > 0 && !containedByAncestor(el)) {
          out.push({ kind: 'text-overflow', selector: describe(el), detail: `글자가 ${box.scrollWidth}px 로 상자(${box.clientWidth}px)를 넘칩니다` });
        }
      }

      // 2-1) 글자가 잘리는 경우: 글자가 있는 요소가 overflow:hidden 부모보다 넓으면 화면 안이어도 글자가 잘려 보인다.
      //      부모가 text-overflow:ellipsis(truncate)거나 스크롤 영역이면 의도한 것이라 제외한다. 글자 없는 장식 요소도 제외한다.
      const hasOwnText = (el: Element): boolean =>
        Array.from(el.childNodes).some((n) => n.nodeType === Node.TEXT_NODE && (n.textContent || '').trim().length > 0);
      for (const el of Array.from(document.body.querySelectorAll('*'))) {
        if (isIgnored(el) || !isVisible(el) || !hasOwnText(el)) continue;
        const rect = el.getBoundingClientRect();
        let cursor: Element | null = el.parentElement;
        while (cursor && cursor !== document.documentElement) {
          const p: Element = cursor;
          const style = getComputedStyle(p);
          // 가로로 스크롤되는 조상(코드 블록·표 래퍼)이 먼저 나오면 사용자가 끝까지 볼 수 있으니 문제가 아니다
          if (style.overflowX === 'auto' || style.overflowX === 'scroll') break;
          if (style.overflowX === 'hidden' || style.overflowX === 'clip') {
            if (style.textOverflow === 'ellipsis' || style.display === '-webkit-box') break;
            const box = p.getBoundingClientRect();
            if (rect.right > box.right + TOL || rect.left < box.left - TOL) {
              out.push({ kind: 'clipped-content', selector: describe(el), detail: `글자 영역이 ${describe(p)} 의 ${rect.right > box.right + TOL ? '오른쪽' : '왼쪽'}으로 ${Math.round(Math.max(rect.right - box.right, box.left - rect.left))}px 넘쳐 잘립니다` });
            }
            break;
          }
          cursor = p.parentElement;
        }
      }

      // 3) 눌러야 하는 요소의 크기. 본문 속 인라인 링크는 WCAG 도 예외로 둔다.
      const interactive = 'a[href], button, [role="button"], [role="tab"], select, summary, input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]), textarea';
      for (const el of Array.from(document.querySelectorAll(interactive))) {
        if (isIgnored(el) || !isVisible(el) || (el as HTMLButtonElement).disabled) continue;
        // 스위치는 모양(트랙 44x24)이 정해져 있어 크기를 키우지 않는다. 가로가 충분히 넓어 오터치 위험이 작다.
        if (el.matches('[role="switch"]')) continue;
        const style = getComputedStyle(el);
        if (style.display === 'inline') continue;
        const rect = el.getBoundingClientRect();
        if (rect.width < min || rect.height < min) {
          out.push({ kind: 'small-tap-target', selector: describe(el), detail: `${Math.round(rect.width)}x${Math.round(rect.height)}px < ${min}px` });
        }
      }

      // 4) 입력창 글꼴 16px 미만(iOS 확대)
      for (const el of Array.from(document.querySelectorAll('input:not([type="hidden"]):not([type="checkbox"]):not([type="radio"]), textarea, select'))) {
        if (isIgnored(el) || !isVisible(el)) continue;
        const size = parseFloat(getComputedStyle(el).fontSize);
        if (size < 16) {
          out.push({ kind: 'small-input-font', selector: describe(el), detail: `글꼴 ${size}px < 16px` });
        }
      }
      return out;
    },
    { minTap, ignore, deviceWidth },
  ) as Promise<Violation[]>;
}

export function formatViolations(violations: Violation[]): string {
  return violations.map((v) => `  [${v.kind}] ${v.selector} — ${v.detail}`).join('\n');
}
