import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'path';
import { HUB_NAVIGATE_ALLOWLIST } from './src/pwa/navigateAllowlist';

/** 허브는 게이트웨이의 루트(/)에 마운트된다. */
const BASE = '/';
const THEME_COLOR = '#4f46e5';
/** 개발 서버가 /api·/oauth2 를 넘겨 줄 IAM 주소. 로컬에 다른 포트로 띄운 IAM 을 쓸 때 VITE_DEV_API_TARGET 으로 바꾼다. */
const IAM_DEV_TARGET = process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
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
      '/oauth2': {
        target: IAM_DEV_TARGET,
        changeOrigin: true,
      },
      '/.well-known': {
        target: IAM_DEV_TARGET,
        changeOrigin: true,
      },
    },
  },
});
