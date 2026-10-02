const MAX_CONTROL_CODE = 0x1f;
const DEL_CODE = 0x7f;

/** 제어 문자(탭/개행 포함)와 DEL 을 제거한다. */
function stripControlChars(input: string): string {
  let result = '';
  for (const ch of input) {
    const code = ch.codePointAt(0) ?? 0;
    if (code > MAX_CONTROL_CODE && code !== DEL_CODE) {
      result += ch;
    }
  }
  return result;
}

/**
 * LogQL 의 큰따옴표 문자열 리터럴(`|= "..."`, `{label="..."}`)에 안전하게 넣기 위해 이스케이프한다.
 * 제어 문자를 제거하고, 역슬래시와 큰따옴표를 이스케이프하여 사용자가 문자열을 탈출해
 * 스트림 셀렉터/파이프라인을 주입하지 못하게 한다.
 */
export function escapeLogQlString(input: string): string {
  return stripControlChars(input).replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}
