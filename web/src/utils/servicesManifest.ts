/**
 * 각 서비스 헤더의 "서비스 목록" 버튼이 가져가는 서비스 목록(/services.json)의 원본.
 * 새 서비스는 여기와 serviceCatalog.ts(허브 카드) 두 곳에 같은 항목을 더하고, 두 목록이 어긋나지 않는지는
 * servicesManifest.test.ts 가 검사한다. 서비스 쪽 헤더는 이 파일을 코드로 가져오지 않고 런타임에 JSON 으로만 읽으므로
 * 새 서비스가 생겨도 다른 서비스의 코드를 고치거나 다시 배포할 필요가 없다.
 *
 * 브라우저 전용 코드(import.meta.env, window)를 쓰지 않는다: vite.config.ts 가 빌드할 때 이 파일을 직접 불러 JSON 을 만든다.
 */
export const SERVICES_MANIFEST_VERSION = 1;

export interface ManifestService {
  id: string;
  name: string;
  desc: string;
  icon: string;
  /** 게이트웨이 기준 경로(항상 /로 시작해 /로 끝난다). 3000 포트 직접 접속용 주소는 넣지 않는다. */
  href: string;
  /** 어느 제품의 서비스인지. 개발자 제품(블로그·면접)의 헤더는 developer 만, 일반 제품은 general 만 보여 준다. 없으면 모두 보인다(옛 소비자 호환). */
  audience: 'general' | 'developer';
}

export interface ServicesManifest {
  version: typeof SERVICES_MANIFEST_VERSION;
  services: ManifestService[];
}

export const PUBLIC_SERVICES: readonly ManifestService[] = [
  { id: 'blog', name: 'DORO.log', desc: '개발자 오픈 기술 블로그', icon: 'blog', href: '/dev/blog/', audience: 'developer' },
  { id: 'party', name: '도로 파티', desc: '친구들과 지도에 핀 모으기', icon: 'party', href: '/party/', audience: 'general' },
  { id: 'games', name: '도로 게임 천국', desc: '웹 미니게임', icon: 'games', href: '/games/', audience: 'general' },
  { id: 'interview', name: '도로 면접', desc: '면접 질문 은행', icon: 'interview', href: '/dev/interview/', audience: 'developer' },
  { id: 'menu', name: '도로메뉴', desc: '메뉴 정해주는 도로롱', icon: 'menu', href: '/menu/', audience: 'general' },
];

export function buildServicesManifest(): ServicesManifest {
  return { version: SERVICES_MANIFEST_VERSION, services: PUBLIC_SERVICES.map((service) => ({ ...service })) };
}
