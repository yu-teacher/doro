declare global {
  interface Window {
    gtag?: (...args: unknown[]) => void;
    dataLayer?: unknown[];
  }
}

/**
 * Doro 통합 GA4 커스텀 이벤트 추적 유틸리티.
 */
export const trackEvent = (
  eventName: string,
  eventParams?: Record<string, string | number | boolean | undefined>
) => {
  if (typeof window !== 'undefined' && typeof window.gtag === 'function') {
    const sanitizedParams: Record<string, string | number | boolean> = {};
    if (eventParams) {
      Object.entries(eventParams).forEach(([key, value]) => {
        if (value !== undefined) {
          sanitizedParams[key] = value;
        }
      });
    }
    window.gtag('event', eventName, sanitizedParams);
  }
};
