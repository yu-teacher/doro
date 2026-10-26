import { expect, test, type Page } from '@playwright/test';
import { auditMobile, formatViolations, type AuditOptions } from './mobileAudit';
import { ADMIN_ACCOUNT, gotoInApp, LONG_VALUE, mockApi, seedLoggedIn, USER_ACCOUNT } from './fixtures';

/** 화면을 불러온 뒤 레이아웃이 자리 잡을 때까지 기다리고(폰트·이미지·비동기 데이터) 모바일 문제를 모두 모아 한 번에 보여 준다. */
async function expectMobileFriendly(page: Page, options: AuditOptions = {}) {
  await page.waitForLoadState('networkidle');
  await page.evaluate(() => document.fonts.ready);
  const violations = await auditMobile(page, options);
  expect(violations, `모바일에서 깨지는 곳이 있습니다:\n${formatViolations(violations)}`).toEqual([]);
}

test.describe('포털: 비로그인 화면', () => {
  test.beforeEach(async ({ page }) => mockApi(page));

  test('허브', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('하나의 계정으로')).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('개발자 입구(Doro Dev): 개발자 서비스만 보이고 일반 서비스는 없다', async ({ page }) => {
    await page.goto('/dev/');
    await expect(page.getByRole('heading', { name: 'Doro Dev' })).toBeVisible();
    await expect(page.getByText('DORO.log')).toBeVisible();
    await expect(page.getByText('도로 면접')).toBeVisible();
    for (const hidden of ['도로 파티', '도로 게임 천국', '도로메뉴']) {
      await expect(page.getByText(hidden)).toHaveCount(0);
    }
    await expectMobileFriendly(page);
  });

  test('개발자 서비스에서 시작한 로그인 화면은 허브(일반 서비스)를 보여 주지 않는다', async ({ page }) => {
    const consent = new URLSearchParams({
      client_id: 'doro-blog',
      redirect_uri: 'https://doro.example.com/dev/blog/api/v1/bff/callback',
      code_challenge: 'c'.repeat(43),
      code_challenge_method: 'S256',
      state: 'abc',
    });
    await page.addInitScript(
      ([key, value]) => sessionStorage.setItem(key, value),
      ['doro_oauth_consent_return', JSON.stringify({ query: consent.toString(), savedAt: Date.now() })],
    );
    await page.goto('/login');
    await expect(page.getByText('Doro Dev').first()).toBeVisible();
    await page.getByTitle('Doro Dev 앱').click();
    await expect(page.getByText('Doro Dev 홈')).toBeVisible();
    for (const hidden of ['도로 파티', '도로 게임 천국', '도로메뉴', '모든 서비스 한눈에']) {
      await expect(page.getByText(hidden)).toHaveCount(0);
    }
  });

  test('일반 서비스에서 시작한 로그인 화면은 그대로 허브 모양이다', async ({ page }) => {
    const consent = new URLSearchParams({
      client_id: 'doro-party',
      redirect_uri: 'https://doro.example.com/party/api/v1/bff/callback',
      code_challenge: 'c'.repeat(43),
      code_challenge_method: 'S256',
      state: 'abc',
    });
    await page.addInitScript(
      ([key, value]) => sessionStorage.setItem(key, value),
      ['doro_oauth_consent_return', JSON.stringify({ query: consent.toString(), savedAt: Date.now() })],
    );
    await page.goto('/login');
    await page.getByTitle('Doro 앱').click();
    await expect(page.getByText('도로 파티')).toBeVisible();
    await expect(page.getByText('DORO.log')).toHaveCount(0);
    await expect(page.getByText('도로 면접')).toHaveCount(0);
  });

  test('로그인(이메일 단계)', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '다음' })).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('로그인(비밀번호 단계, 긴 이메일)', async ({ page }) => {
    await page.goto('/login');
    await page.locator('input[type=email]').fill(`${LONG_VALUE}@doro.test`);
    await page.getByRole('button', { name: '다음' }).click();
    await expect(page.locator('input[type=password]')).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('가입', async ({ page }) => {
    await page.goto('/signup');
    await expect(page.locator('input[type=password]').first()).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('동의 화면(긴 앱 ID·이동 주소)', async ({ page }) => {
    await seedLoggedIn(page, USER_ACCOUNT);
    const query = new URLSearchParams({
      client_id: LONG_VALUE,
      redirect_uri: `https://${'a'.repeat(63)}.example.com/callback`,
      code_challenge: 'c'.repeat(43),
      code_challenge_method: 'S256',
      state: 'abc',
    });
    await gotoInApp(page, `/oauth2/consent?${query}`);
    await expect(page.getByRole('button', { name: /승인/ })).toBeVisible();
    await expectMobileFriendly(page);
  });
});

test.describe('포털: 로그인한 일반 사용자', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { account: USER_ACCOUNT });
    await seedLoggedIn(page, USER_ACCOUNT);
  });

  test('허브', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByText('하나의 계정으로')).toBeVisible();
    await expectMobileFriendly(page);
  });

  for (const tab of ['홈', '개인 정보', '보안 & 2FA 설정', '기기 및 세션 킬스위치']) {
    test(`내 계정 — ${tab}`, async ({ page }) => {
      await page.goto('/account');
      await page.getByRole('button', { name: tab, exact: true }).click();
      await expect(page.getByRole('button', { name: tab, exact: true })).toBeVisible();
      await expectMobileFriendly(page);
    });
  }
});

test.describe('포털: 알림 설정(내 계정 > 기기 및 세션)', () => {
  test('앱별 토글·내 기기 목록·긴 이름이 모바일에서 깨지지 않는다', async ({ page }) => {
    await mockApi(page, { account: USER_ACCOUNT, notify: 'ok' });
    await seedLoggedIn(page, USER_ACCOUNT);
    await page.goto('/account');
    await page.getByRole('button', { name: '기기 및 세션 킬스위치', exact: true }).click();
    const section = page.getByTestId('notify-settings');
    await expect(section.getByRole('switch', { name: '도로 파티 새 댓글·답글 알림' })).toBeVisible();
    await expect(section.getByRole('button', { name: /Chrome \(Android\) 기기 해제/ })).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('알림 서비스가 내려가 있으면 이 섹션만 사용할 수 없음으로 보인다', async ({ page }) => {
    await mockApi(page, { account: USER_ACCOUNT, notify: 'down' });
    await seedLoggedIn(page, USER_ACCOUNT);
    await page.goto('/account');
    await page.getByRole('button', { name: '기기 및 세션 킬스위치', exact: true }).click();
    await expect(page.getByText('알림 서비스를 사용할 수 없습니다')).toBeVisible();
    await expect(page.getByText('로그인된 기기 및 활성 세션')).toBeVisible();
    await expectMobileFriendly(page);
  });
});

test.describe('포털: 두 제품 사이의 노출', () => {
  test('일반 사용자: 허브에는 개발자 서비스가 없고 개발자 입구에는 일반 서비스가 없다', async ({ page }) => {
    await mockApi(page, { account: USER_ACCOUNT });
    await seedLoggedIn(page, USER_ACCOUNT);
    await gotoInApp(page, '/');
    await expect(page.getByText('도로 파티')).toBeVisible();
    for (const hidden of ['DORO.log', '도로 면접', '개발자 서비스']) await expect(page.getByText(hidden)).toHaveCount(0);
    await gotoInApp(page, '/dev/');
    await expect(page.getByText('DORO.log')).toBeVisible();
    for (const hidden of ['도로 파티', '도로 게임 천국', '도로메뉴', '일반 서비스']) await expect(page.getByText(hidden)).toHaveCount(0);
  });

  test('관리자: 양쪽 입구에서 두 제품의 서비스를 모두 본다', async ({ page }) => {
    await mockApi(page, { account: ADMIN_ACCOUNT });
    await seedLoggedIn(page, ADMIN_ACCOUNT);
    await gotoInApp(page, '/');
    await expect(page.getByText('개발자 서비스 · 관리자에게만 보여요')).toBeVisible();
    await expect(page.getByText('DORO.log').first()).toBeVisible();
    await gotoInApp(page, '/dev/');
    await expect(page.getByText('일반 서비스 · 관리자에게만 보여요')).toBeVisible();
    await expect(page.getByText('도로 파티').first()).toBeVisible();
  });
});

test.describe('포털: 관리자', () => {
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { account: ADMIN_ACCOUNT });
    await seedLoggedIn(page, ADMIN_ACCOUNT);
  });

  test('내 계정 — 사용자 및 권한 관리(긴 이름·정지 사유·잠김)', async ({ page }) => {
    await page.goto('/account');
    await page.getByRole('button', { name: '사용자 및 권한 관리' }).click();
    await expect(page.getByText('정지된회원')).toBeVisible();
    await expectMobileFriendly(page);
  });

  test('로그 조회', async ({ page }) => {
    await page.goto('/logs');
    await expect(page.getByText('Started AuthApplication')).toBeVisible();
    await expectMobileFriendly(page);
  });
});

test.describe('포털: 관리자 — 검색 대기 타이머', () => {
  // 검색어 입력 대기(300ms) 타이머가 화면을 연 직후 한 번 돌 때 쪽을 1페이지로 되돌리면, 느린 환경에서 방금 누른 '다음'이 취소된다.
  // 시계를 가짜로 바꿔 그 타이머가 돈 뒤에도 쪽이 유지되는지 정확히 확인한다.
  test('다음 쪽으로 간 뒤 검색 대기 타이머가 지나도 쪽이 유지된다', async ({ page }) => {
    await page.clock.install({ time: new Date('2026-01-01T00:00:00Z') });
    await page.clock.pauseAt(new Date('2026-01-01T00:00:01Z')); // 시계를 멈춰 두어 대기 타이머가 쪽 이동보다 먼저 돌지 않게 한다
    await mockApi(page, { account: ADMIN_ACCOUNT, adminExtraUsers: 45 });
    await seedLoggedIn(page, ADMIN_ACCOUNT);
    await page.goto('/account');
    await page.getByRole('button', { name: '사용자 및 권한 관리' }).click();
    const pager = page.getByTestId('admin-pager');
    await expect(pager.getByText('1–20 / 51')).toBeVisible();
    await pager.getByRole('button', { name: '다음' }).click();
    await expect(page.getByText('대량회원015')).toBeVisible();
    await page.clock.runFor(1000);
    await page.waitForTimeout(500); // 되돌려졌다면 1페이지를 다시 불러오는 시간을 준다
    await expect(pager.getByText('21–40 / 51')).toBeVisible();
    await expect(page.getByText('대량회원015')).toBeVisible();
  });
});

test.describe('포털: 관리자 — 사용자가 많을 때', () => {
  // 가짜 사용자 6명 + 대량 회원 45명 = 51명 → 20명씩 3페이지
  test.beforeEach(async ({ page }) => {
    await mockApi(page, { account: ADMIN_ACCOUNT, adminExtraUsers: 45 });
    await seedLoggedIn(page, ADMIN_ACCOUNT);
    await page.goto('/account');
    await page.getByRole('button', { name: '사용자 및 권한 관리' }).click();
  });

  test('한 페이지에 20명만 보이고 다음·이전으로 이동한다', async ({ page }) => {
    const pager = page.getByTestId('admin-pager');
    await expect(pager.getByText('1–20 / 51')).toBeVisible();
    // 1페이지는 기본 사용자 6명 + 대량회원 001~014. 015 부터는 2페이지에 있다.
    await expect(page.getByText('대량회원014')).toBeVisible();
    await expect(page.getByText('대량회원015')).toBeHidden();
    await pager.getByRole('button', { name: '다음' }).click();
    await expect(pager.getByText('21–40 / 51')).toBeVisible();
    await pager.getByRole('button', { name: '다음' }).click();
    await expect(pager.getByText('41–51 / 51')).toBeVisible();
    await expect(pager.getByRole('button', { name: '다음' })).toBeDisabled();
    await pager.getByRole('button', { name: '이전' }).click();
    await expect(pager.getByText('21–40 / 51')).toBeVisible();
  });

  test('탭 개수는 전체 사용자 기준이고, 필터를 고르면 첫 페이지로 돌아간다', async ({ page }) => {
    await expect(page.getByRole('button', { name: /^전체 \(51\)/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /일반 \(49\)/ })).toBeVisible();
    await page.getByTestId('admin-pager').getByRole('button', { name: '다음' }).click();
    await page.getByRole('button', { name: /2FA \(1\)/ }).click();
    await expect(page.getByTestId('admin-pager').getByText('1–1 / 1')).toBeVisible();
  });

  test('검색은 서버에서 처리해 전체 사용자 중에서 찾는다', async ({ page }) => {
    await page.getByPlaceholder('이름 또는 이메일로 검색...').fill('대량회원045');
    await expect(page.getByText('대량회원045')).toBeVisible();
    await expect(page.getByTestId('admin-pager').getByText('1–1 / 1')).toBeVisible();
    await page.getByPlaceholder('이름 또는 이메일로 검색...').fill('없는사용자zzz');
    await expect(page.getByText('검색 및 필터 조건과 일치하는 사용자가 없습니다.')).toBeVisible();
  });

  test('모바일에서도 목록과 페이지 이동이 깨지지 않는다', async ({ page }) => {
    await expect(page.getByTestId('admin-pager')).toBeVisible();
    await expectMobileFriendly(page);
  });
});
