/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BLOG_URL?: string;
  readonly VITE_DORO_PORTAL_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
