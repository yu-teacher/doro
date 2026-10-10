import { defineConfig } from '@playwright/test';

/**
 * 모바일 레이아웃 회귀 테스트(실제 브라우저). `npm run test:mobile`.
 * - 개발 서버를 띄우고 API 응답은 테스트가 가로채(page.route) 가짜 데이터를 준다. 서버·DB 가 필요 없다.
 * - 폰 세로 3종(320·375·414)과 가로 모드(812x375)에서 같은 화면을 측정한다. 터치 기기로 에뮬레이션해 pointer: coarse 규칙도 적용된다.
 * - 로컬은 PW_CHANNEL=chrome 으로 설치된 Chrome 을 쓸 수 있고, CI(scripts/ci-test.sh mobile)는 Playwright 공식 이미지의 Chromium 을 쓴다.
 */
const PORT = Number(process.env.E2E_PORT ?? 4173);
const channel = process.env.PW_CHANNEL || undefined;

// CI 컨테이너는 비루트 사용자로 도는데 Chromium 샌드박스가 거기서는 못 뜬다. 컨테이너가 이미 격리돼 있으니 CI 에서만 끈다.
const launchOptions = process.env.CI ? { args: ['--no-sandbox'] } : {};

const touch = { isMobile: true, hasTouch: true, deviceScaleFactor: 2, channel } as const;

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: `http://localhost:${PORT}`, trace: 'off', launchOptions },
  projects: [
    { name: 'phone-320', use: { ...touch, viewport: { width: 320, height: 568 } } },
    { name: 'phone-375', use: { ...touch, viewport: { width: 375, height: 812 } } },
    { name: 'phone-414', use: { ...touch, viewport: { width: 414, height: 896 } } },
    { name: 'phone-landscape', use: { ...touch, viewport: { width: 812, height: 375 } } },
  ],
  webServer: {
    command: `npx vite --port ${PORT} --strictPort --host 127.0.0.1`,
    url: `http://localhost:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
