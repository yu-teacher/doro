/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BLOG_URL?: string;
  readonly VITE_DORO_PORTAL_URL?: string;
  readonly VITE_MENU_URL?: string;
  /** 관리자 앱 런처의 Doro Guard OpenAPI 문서 URL (미설정 시 항목 숨김) */
  readonly VITE_GUARD_DOCS_URL?: string;
  /** 관리자 앱 런처의 Doro IAM OpenAPI 문서 URL (미설정 시 항목 숨김) */
  readonly VITE_IAM_DOCS_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
