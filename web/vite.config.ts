import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'path';
import { HUB_NAVIGATE_ALLOWLIST } from './src/pwa/navigateAllowlist';
import { buildServicesManifest } from './src/utils/servicesManifest';

/** 허브는 게이트웨이의 루트(/)에 마운트된다. */
const BASE = '/';
const THEME_COLOR = '#4f46e5';
/** 개발 서버가 /api·/oauth2 를 넘겨 줄 IAM 주소. 로컬에 다른 포트로 띄운 IAM 을 쓸 때 VITE_DEV_API_TARGET 으로 바꾼다. */
const IAM_DEV_TARGET = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

/** 개발 서버가 /notify 를 넘겨 줄 알림 서비스(doro-notify) 주소. 허브는 게이트웨이의 /notify/api/v1/ 를 /api/v1/notify/ 로 넘기는 것과 같게 고쳐 쓴다. */
const NOTIFY_DEV_TARGET = process.env.VITE_DEV_NOTIFY_TARGET ?? 'http://localhost:8089';

/**
 * 각 서비스 헤더의 "서비스 목록" 버튼이 읽는 /services.json 을 만든다(빌드 산출물과 개발 서버 모두).
 * 서비스 워커의 precache 에는 들어가지 않는다(workbox 기본 glob 에 json 이 없다): 낡은 목록이 남지 않게 항상 네트워크로 읽는다.
 */
function servicesManifestPlugin(): Plugin {
  const body = () => `${JSON.stringify(buildServicesManifest(), null, 2)}\n`;
  return {
    name: 'doro-services-manifest',
    configureServer(server) {
      server.middlewares.use('/services.json', (_req, res) => {
        res.setHeader('Content-Type', 'application/json; charset=utf-8');
        res.setHeader('Cache-Control', 'no-cache');
        res.end(body());
      });
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'services.json', source: body() });
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    servicesManifestPlugin(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg', 'apple-touch-icon.png'],
      manifest: {
        name: 'Doro',
        short_name: 'Doro',
        description: 'Doro 계정으로 쓰는 모든 서비스의 허브',
        lang: 'ko',
        start_url: BASE,
        scope: BASE,
        display: 'standalone',
        background_color: '#f8fafc',
        theme_color: THEME_COLOR,
        icons: [
          { src: `${BASE}icon-192.png`, sizes: '192x192', type: 'image/png', purpose: 'any maskable' },
          { src: `${BASE}icon-512.png`, sizes: '512x512', type: 'image/png', purpose: 'any maskable' },
        ],
      },
      workbox: {
        // 앱 셸(정적 파일)만 캐시한다. 로그인·OAuth 동의·API·로그 조회 응답은 절대 캐시하지 않는다(런타임 캐시 없음).
        // 허브의 라우트에만 index.html 로 대신 응답하고, 블로그·파티·게임·메뉴·OAuth 등 다른 주소로의 이동은 그대로 네트워크로 보낸다.
        navigateFallback: `${BASE}index.html`,
        navigateFallbackAllowlist: HUB_NAVIGATE_ALLOWLIST,
        runtimeCaching: [],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: IAM_DEV_TARGET,
        changeOrigin: true,
      },
      '/notify/api/v1': {
        target: NOTIFY_DEV_TARGET,
        changeOrigin: true,
        rewrite: (requestPath) => requestPath.replace(/^\/notify\/api\/v1/, '/api/v1/notify'),
      },
      '/oauth2': {
        target: IAM_DEV_TARGET,
        changeOrigin: true,
        // /oauth2/consent 는 IAM 이 아니라 포털의 화면(SPA 라우트)이다. 운영 게이트웨이도 이 경로만 포털로 보낸다.
        // IAM 이 로그인 전 인가 요청을 이 주소로 리다이렉트하므로, 개발 서버가 그대로 IAM 에 넘기면 404 JSON 이 뜬다.
        bypass: (req) => (req.url?.startsWith('/oauth2/consent') ? req.url : undefined),
      },
      '/.well-known': {
        target: IAM_DEV_TARGET,
        changeOrigin: true,
      },
    },
  },
});
