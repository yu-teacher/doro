import { afterEach, describe, expect, it } from 'vitest';
import { consumeLoginNotice, rememberLoginNotice, SESSION_ENDED_NOTICE } from './loginNotice';

describe('로그인 화면 안내', () => {
  afterEach(() => sessionStorage.clear());

  it('저장한 안내를 한 번 읽을 수 있고, 읽으면 지워진다', () => {
    rememberLoginNotice(SESSION_ENDED_NOTICE);

    expect(consumeLoginNotice()).toBe(SESSION_ENDED_NOTICE);
    expect(consumeLoginNotice()).toBeNull();
  });

  it('저장한 것이 없으면 null', () => {
    expect(consumeLoginNotice()).toBeNull();
  });
});
