import { defineConfig } from '@playwright/test';

// 외부(제3자) 앱 시점의 OIDC 시험. run-third-party.sh 가 스택을 띄운 뒤 실행한다.
export default defineConfig({
  testDir: './third-party',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: { trace: 'off' },
});
