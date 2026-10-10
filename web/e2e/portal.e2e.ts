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
