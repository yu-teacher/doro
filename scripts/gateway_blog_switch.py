#!/usr/bin/env python3
"""게이트웨이(nginx.conf)에서 블로그를 /blog/ 아래로 옮기는 변환기. 입력 파일을 읽어 변환한 결과를 출력 파일에 쓴다(입력은 바꾸지 않는다).

  gateway_blog_switch.py <입력 nginx.conf> <출력 파일> <stage>

stage 1 (추가만): /blog, /blog/api/ 경로를 새로 만든다. 기존 경로(/ 가 블로그, /api/v1/ 가 블로그 API)는 그대로 둔다. 사용자에게 보이는 변화가 없다.
stage 2 (전환):  / 를 허브(포털)로, 나머지 알 수 없는 경로를 /blog 로 보내고(임시 이동 302), Referer 추측 두 곳의 기본값을 포털/IAM 으로 바꾼다.
stage 5 (영구 이동): 옛 블로그 주소의 임시 이동(302)을 영구 이동(301)으로 바꾼다. 안정된 것을 확인한 뒤에만 적용한다(브라우저가 301 을 오래 기억해서 되돌리기 어렵다).
stage 4 (허브 PWA 보정): stage 3 에 /index.html 을 추가한다. 허브 서비스 워커가 앱 셸 /index.html 을 미리 캐시하는데 stage 2 의 /blog 이동에 걸려 블로그 화면이 캐시되던 문제를 막는다.
stage 3 (허브 PWA): 허브의 서비스 워커·매니페스트·아이콘 같은 루트 파일(/sw.js, /manifest.webmanifest ...)을 포털로 보낸다(안 그러면 stage 2 의 /blog 이동에 걸린다).

멱등이다: 이미 적용된 stage 는 SAME 을 출력하고 그대로 둔다. 앵커(바꿀 자리)는 정확히 1개여야 하며 아니면 실패한다(서버 설정이 예상과 다르면 건드리지 않는다).
성공하면 표준 출력에 CHANGE <stage> 또는 SAME <stage> 를 한 줄 출력한다.
"""
import re
import sys

PROXY_HEADERS = """            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
"""

# ---- stage 1: /blog 경로 추가 (기존 경로는 건드리지 않는다)
BLOG_LOCATIONS = """        # 4-1. DORO Blog (/blog/): 접두사(/blog)를 떼고 블로그 컨테이너/백엔드로 넘긴다(메뉴·게임·파티 API 와 같은 방식).
        #      웹은 VITE_BASE_PATH=/blog 로 빌드되어 에셋을 /blog/assets/ 로 부르고, 이 location 이 /assets/ 로 바꿔 컨테이너에 전달한다.
        #      보안 헤더/CSP 는 서버 공통 값을 그대로 상속한다(이 location 에는 add_header 를 두지 않는다).
        location = /blog {
            return 302 /blog/;
        }

        location /blog/api/ {
            limit_req zone=blog_writes burst=20 nodelay;
            proxy_pass http://blog_api_upstream/api/;
""" + PROXY_HEADERS + """        }

        location /blog/ {
            proxy_pass http://blog_web_upstream/;
""" + PROXY_HEADERS + """        }

"""

# ---- stage 2: / 를 허브로, 알 수 없는 경로는 /blog 로
FINAL_LOCATIONS = """        # 9. 메인 주소: Doro 허브(포털 SPA). 블로그는 /blog/ 로 옮겼다.
        location = / {
            proxy_pass http://portal_upstream;
""" + PROXY_HEADERS + """        }

        # 9-1. 루트에 두는 공용 정적 파일. robots.txt 는 크롤러가 루트에서만 읽으므로 포털이 준다(/blog/ 경로의 비공개 영역 Disallow 포함).
        location = /robots.txt {
            proxy_pass http://portal_upstream;
            proxy_set_header Host $host;
        }

        # 블로그 컨테이너에 있던 루트 파일(로고, 파비콘): 이미 공유된 글의 og:image 가 이 주소를 가리킨다.
        location ~ ^/(doro-logo\\.png|favicon\\.png)$ {
            proxy_pass http://blog_web_upstream;
            proxy_set_header Host $host;
        }

        # 9-2. 옛 블로그 주소(/@사용자/글, /tags, /search ...)는 /blog 아래로 보낸다. 블로그가 루트를 차지하던 때의 링크를 살린다.
        #      처음에는 임시 이동(302)으로 둔다: 301 은 브라우저가 영구 기억해서, 문제가 생겨 되돌려도 이미 방문한 사람은 계속 /blog/ 로 간다.
        #      안정되면 아래 302 를 301 로 바꾼다.
        location / {
            return 302 /blog$request_uri;
        }
"""

# ---- stage 3: 허브 PWA 루트 파일. 서비스 워커 범위가 / 라서 루트에 있어야 한다. 포털 nginx 가 no-cache 로 내보낸다.
HUB_PWA_LOCATION = """        # 9-1b. 허브 PWA 파일(서비스 워커·매니페스트·아이콘): 서비스 워커 범위가 / 여야 해서 루트에 둔다. 블로그 PWA 파일은 /blog/ 아래에 있다.
        location ~ ^/(sw\\.js|registerSW\\.js|workbox-[A-Za-z0-9_-]+\\.js|manifest\\.webmanifest|icon-192\\.png|icon-512\\.png|apple-touch-icon\\.png|favicon\\.svg|index\\.html)$ {
            proxy_pass http://portal_upstream;
            proxy_set_header Host $host;
        }

"""
STAGE3_ANCHOR = "        # 블로그 컨테이너에 있던 루트 파일(로고, 파비콘)"

ASSETS_MAP_OLD = """    map $http_referer $assets_upstream {
        default http://blog_web_upstream;
        ~*(logs|account|login|signup|oauth2|portal) http://portal_upstream;
    }"""
ASSETS_MAP_NEW = """    # 전환 후: /assets/ 는 포털 에셋만 준다. 블로그 에셋은 /blog/assets/ 로 오므로 Referer 로 추측할 필요가 없다.
    map $http_referer $assets_upstream {
        default http://portal_upstream;
    }"""
USERS_ME_MAP_OLD = """    map "$http_referer:$request_method" $users_me_upstream {
        default http://blog_api_upstream;
        ~*(logs|account|login|signup|oauth2|portal): http://iam_upstream;
        ~:PATCH$ http://iam_upstream;
    }"""
USERS_ME_MAP_NEW = """    # 전환 후: 루트의 /api/v1/users/me 는 IAM(포털) 것이다. 블로그는 /blog/api/v1/users/me 를 쓴다.
    map "$http_referer:$request_method" $users_me_upstream {
        default http://iam_upstream;
    }"""

TRAILING_SLASH_ANCHOR = "        rewrite ^(/api/v1/.*?)/+$ $1;\n"
TRAILING_SLASH_ADD = "        rewrite ^(/blog/api/v1/.*?)/+$ $1;\n"
STAGE1_ANCHOR = "        # 4. Blog Backend API"
FINAL_BLOCK_RE = re.compile(
    r"        # 9\. DORO Blog Web Frontend[^\n]*\n        location / \{\n            proxy_pass http://blog_web_upstream;\n(?:            proxy_set_header [^\n]*\n)+        \}\n"
)


def count(text, needle):
    return text.count(needle)


def stage1(conf):
    if "location /blog/ {" in conf:
        return conf, "SAME"
    if count(conf, STAGE1_ANCHOR) != 1:
        sys.exit(f"stage 1 앵커('# 4. Blog Backend API')를 정확히 1개 찾지 못했다(찾은 수: {count(conf, STAGE1_ANCHOR)})")
    if count(conf, TRAILING_SLASH_ANCHOR) != 1:
        sys.exit(f"끝 슬래시 제거 rewrite 줄을 정확히 1개 찾지 못했다(찾은 수: {count(conf, TRAILING_SLASH_ANCHOR)})")
    conf = conf.replace(TRAILING_SLASH_ANCHOR, TRAILING_SLASH_ANCHOR + TRAILING_SLASH_ADD, 1)
    conf = conf.replace(STAGE1_ANCHOR, BLOG_LOCATIONS + STAGE1_ANCHOR, 1)
    return conf, "CHANGE"


def stage2(conf):
    if "return 302 /blog$request_uri;" in conf or "return 301 /blog$request_uri;" in conf:
        return conf, "SAME"
    if "location /blog/ {" not in conf:
        sys.exit("stage 2 는 stage 1(/blog/ 경로 추가)이 먼저 적용돼 있어야 한다")
    matches = FINAL_BLOCK_RE.findall(conf)
    if len(matches) != 1:
        sys.exit(f"마지막 '/' location(블로그 웹) 블록을 정확히 1개 찾지 못했다(찾은 수: {len(matches)})")
    for old in (ASSETS_MAP_OLD, USERS_ME_MAP_OLD):
        if count(conf, old) != 1:
            sys.exit(f"Referer map 블록이 예상과 다르다: {old.splitlines()[0].strip()}")
    conf = FINAL_BLOCK_RE.sub(lambda _m: FINAL_LOCATIONS, conf, count=1)
    conf = conf.replace(ASSETS_MAP_OLD, ASSETS_MAP_NEW, 1).replace(USERS_ME_MAP_OLD, USERS_ME_MAP_NEW, 1)
    return conf, "CHANGE"


def stage3(conf):
    if "# 9-1b. 허브 PWA 파일" in conf:
        return conf, "SAME"
    if "return 302 /blog$request_uri;" not in conf and "return 301 /blog$request_uri;" not in conf:
        sys.exit("stage 3 는 stage 2(허브 전환)가 먼저 적용돼 있어야 한다")
    if count(conf, STAGE3_ANCHOR) != 1:
        sys.exit(f"stage 3 앵커('블로그 컨테이너에 있던 루트 파일')를 정확히 1개 찾지 못했다(찾은 수: {count(conf, STAGE3_ANCHOR)})")
    return conf.replace(STAGE3_ANCHOR, HUB_PWA_LOCATION + STAGE3_ANCHOR, 1), "CHANGE"


OLD_HUB_RE = "apple-touch-icon\\.png|favicon\\.svg)$"
NEW_HUB_RE = "apple-touch-icon\\.png|favicon\\.svg|index\\.html)$"


def stage4(conf):
    if "favicon\\.svg|index\\.html)$" in conf:
        return conf, "SAME"
    if count(conf, OLD_HUB_RE) != 1:
        sys.exit(f"stage 4 는 stage 3(허브 PWA 파일 규칙)이 먼저 적용돼 있어야 한다(찾은 수: {count(conf, OLD_HUB_RE)})")
    return conf.replace(OLD_HUB_RE, NEW_HUB_RE, 1), "CHANGE"


def stage5(conf):
    if "return 301 /blog$request_uri;" in conf:
        return conf, "SAME"
    if count(conf, "return 302 /blog$request_uri;") != 1:
        sys.exit(f"stage 5 는 stage 2(옛 주소 이동 302)가 먼저 적용돼 있어야 한다(찾은 수: {count(conf, 'return 302 /blog$request_uri;')})")
    conf = conf.replace("return 302 /blog$request_uri;", "return 301 /blog$request_uri;", 1)
    conf = conf.replace("#      처음에는 임시 이동(302)으로 둔다: 301 은 브라우저가 영구 기억해서, 문제가 생겨 되돌려도 이미 방문한 사람은 계속 /blog/ 로 간다.\n        #      안정되면 아래 302 를 301 로 바꾼다.", "#      안정된 것을 확인하고 영구 이동(301)으로 바꿨다. 301 은 브라우저가 기억하므로 되돌려도 이미 방문한 사람은 계속 /blog/ 로 간다.", 1)
    return conf, "CHANGE"


def main():
    if len(sys.argv) != 4 or sys.argv[3] not in ("1", "2", "3", "4", "5"):
        sys.exit("사용법: gateway_blog_switch.py <입력> <출력> <1|2|3|4|5>")
    src, out, stage = sys.argv[1], sys.argv[2], sys.argv[3]
    conf = open(src, encoding="utf-8").read()
    new, state = {"1": stage1, "2": stage2, "3": stage3, "4": stage4, "5": stage5}[stage](conf)
    open(out, "w", encoding="utf-8").write(new)
    print(f"{state} {stage}")


if __name__ == "__main__":
    main()
