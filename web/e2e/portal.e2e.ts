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
