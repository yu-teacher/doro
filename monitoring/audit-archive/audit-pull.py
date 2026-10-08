#!/usr/bin/env python3
"""감사 로그 사본: 중앙 Loki 의 {log_type="audit"} (SSH·sudo 기록)를 파이로 가져와 날짜별 파일로 보관한다.

- 파이가 가져온다(pull). 노트북에는 파이에 쓸 수 있는 열쇠가 없으므로, 노트북이 침해돼도 이미 가져간 사본은 지우거나 바꿀 수 없다.
- 날짜별 파일(YYYY-MM-DD.log, UTC 기준)에 이어 쓰고(append-only), 쓴 구간마다 chain.log 에 한 줄을 남긴다:
  (날짜, 파일 안 위치, 길이, 줄 수, 그 구간의 sha256, 체인 해시). 체인 해시 = sha256(이전 체인 + 구간 정보).
  사본의 어느 바이트를 고치거나 지우거나 덧붙이면 `--verify` 가 찾아낸다(파일 전체 길이도 기록과 맞아야 한다).
- 늦게 도착하는 로그(수집기가 멈췄다가 따라잡는 경우)를 놓치지 않도록, 매 회차 직전 OVERLAP_HOURS(기본 6시간)를 다시 조회하고
  이미 가져온 줄(해시)만 걸러 낸다. 그보다 더 늦게 도착한 줄은 놓칠 수 있다(워치독이 로그 유입 중단을 따로 감시한다).
- 결과·오류는 syslog(태그 doro-audit-pull)로만 남긴다. 표준 라이브러리만 쓴다.
- 설정은 환경변수(기본값 있음): LOKI_URL, AUDIT_DIR, RETAIN_DAYS, LAG_SECONDS, PAGE_LIMIT, OVERLAP_HOURS

    audit-pull.py             # 새 줄을 가져온다 (cron 이 매시간 실행)
    audit-pull.py --verify    # 체인과 파일 내용이 맞는지 검증한다
"""
import gzip
import hashlib
import json
import os
import sys
import syslog
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

LOKI_URL = os.environ.get("LOKI_URL", "http://192.168.0.4:3100").rstrip("/")
AUDIT_DIR = os.environ.get("AUDIT_DIR", os.path.expanduser("~/audit-archive"))
RETAIN_DAYS = int(os.environ.get("RETAIN_DAYS", "400"))
LAG_SECONDS = int(os.environ.get("LAG_SECONDS", "90"))      # 수집기가 보내는 중인 최근 줄은 다음 회차로 미룬다
PAGE_LIMIT = int(os.environ.get("PAGE_LIMIT", "5000"))
FIRST_LOOKBACK_DAYS = int(os.environ.get("FIRST_LOOKBACK_DAYS", "8"))   # Loki 가 받는 가장 오래된 줄(7일)보다 조금 넓게
OVERLAP_NS = int(float(os.environ.get("OVERLAP_HOURS", "6")) * 3600 * 1e9)
QUERY = '{log_type="audit"}'

STATE_FILE = os.path.join(AUDIT_DIR, ".state.json")
CHAIN_FILE = os.path.join(AUDIT_DIR, "chain.log")
GENESIS = "0" * 64


def log(level, msg):
    syslog.syslog(level, msg)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        start = int((time.time() - FIRST_LOOKBACK_DAYS * 86400) * 1e9)
        return {"covered_until_ns": start, "seen": {}, "chain": GENESIS, "first_run": True}


def save_state(state):
    tmp = STATE_FILE + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(state, f)
    os.replace(tmp, STATE_FILE)


def hosts_in_range(start_ns, end_ns):
    params = urllib.parse.urlencode({"query": QUERY, "start": start_ns, "end": end_ns})
    with urllib.request.urlopen(f"{LOKI_URL}/loki/api/v1/label/host/values?{params}", timeout=60) as resp:
        return sorted(json.load(resp).get("data", []))


def fetch_stream(host, start_ns, end_ns):
    """한 호스트의 줄을 start_ns 이상, end_ns 미만으로 시간순으로 모두 가져온다(페이지 반복)."""
    entries, cursor = [], start_ns
    query = f'{{log_type="audit", host="{host}"}}'
    while True:
        params = urllib.parse.urlencode({
            "query": query, "start": cursor, "end": end_ns,
            "limit": PAGE_LIMIT, "direction": "forward",
        })
        with urllib.request.urlopen(f"{LOKI_URL}/loki/api/v1/query_range?{params}", timeout=60) as resp:
            body = json.load(resp)
        page = sorted((int(ts), host, line) for stream in body["data"]["result"] for ts, line in stream["values"])
        entries.extend(page)
        if len(page) < PAGE_LIMIT:
            return entries
        if page[-1][0] == cursor:          # 같은 시각에 PAGE_LIMIT 줄 이상(비정상): 무한 반복 방지
            return entries
        cursor = page[-1][0]               # 경계 줄 중복은 pull() 의 dedup 이 거른다


def fetch(start_ns, end_ns):
    """호스트(스트림)별로 따로 조회해서 합친다.
    여러 호스트를 한 번에 조회하면 Loki 가 limit 을 스트림에 나눠 적용해, 페이지 경계에서 일부 호스트 줄이 누락되는 것을 확인했다
    (미니 7,679줄 중 4,926줄만 반환). 그래서 호스트마다 독립적으로 페이지를 넘긴다."""
    entries = []
    for host in hosts_in_range(start_ns, end_ns):
        entries.extend(fetch_stream(host, start_ns, end_ns))
    entries.sort()
    return entries


def line_id(ts, host, line):
    return sha256(f"{ts}\t{host}\t{line}".encode())[:16]


def day_path(day):
    return os.path.join(AUDIT_DIR, f"{day}.log")


def pull():
    os.makedirs(AUDIT_DIR, mode=0o700, exist_ok=True)
    state = load_state()
    end_ns = int((time.time() - LAG_SECONDS) * 1e9)
    # 첫 실행은 조회 시작 지점부터, 이후에는 직전 조회 끝에서 OVERLAP 만큼 되돌아가 다시 조회한다.
    start_ns = state["covered_until_ns"] if state.get("first_run") else state["covered_until_ns"] - OVERLAP_NS
    if end_ns <= start_ns:
        return 0
    raw = fetch(start_ns, end_ns)
    seen = state["seen"]
    fresh = []
    for ts, host, line in raw:
        lid = line_id(ts, host, line)
        if lid in seen:
            continue
        seen[lid] = ts
        fresh.append((ts, host, line))
    fresh.sort()

    # 날짜(UTC)별로 묶어 파일에 이어 쓰고, 쓴 구간(위치·길이)의 해시를 체인에 남긴다.
    by_day = {}
    for ts, host, line in fresh:
        stamp = datetime.fromtimestamp(ts / 1e9, tz=timezone.utc)
        text = f"{stamp.strftime('%Y-%m-%dT%H:%M:%S.%fZ')}\t{host}\t{line}\n"
        by_day.setdefault(stamp.strftime("%Y-%m-%d"), []).append(text)
    chain = state["chain"]
    rows = []
    for day, lines in sorted(by_day.items()):
        data = "".join(lines).encode("utf-8")
        path = day_path(day)
        if os.path.exists(path + ".gz"):
            raise OSError(f"{day} 파일이 이미 압축되어 이어 쓸 수 없다(너무 늦게 도착한 로그): {len(lines)}줄")
        fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "ab") as f:
            offset = f.tell()
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        digest = sha256(data)
        chain = sha256(f"{chain}|{day}|{offset}|{len(data)}|{digest}".encode())
        rows.append(f"{datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}\t{day}\t{offset}\t{len(data)}\t{len(lines)}\t{digest}\t{chain}\n")
    if rows:
        fd = os.open(CHAIN_FILE, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as f:
            f.writelines(rows)
            f.flush()
            os.fsync(f.fileno())

    state["chain"] = chain
    state["covered_until_ns"] = end_ns
    state["first_run"] = False
    state["seen"] = {k: v for k, v in seen.items() if v >= end_ns - OVERLAP_NS}   # 다음 조회 범위 밖의 해시는 버린다
    save_state(state)
    return len(fresh)


def prune_and_compress():
    """오래된 날은 gzip 으로 줄이고, 보관 기간(RETAIN_DAYS)을 넘으면 지운다. 체인은 지우지 않는다."""
    today = datetime.now(timezone.utc).date()
    for name in sorted(os.listdir(AUDIT_DIR)):
        stem = name.split(".")[0]
        try:
            day = datetime.strptime(stem, "%Y-%m-%d").date()
        except ValueError:
            continue
        path = os.path.join(AUDIT_DIR, name)
        if (today - day).days > RETAIN_DAYS:
            os.remove(path)
            log(syslog.LOG_INFO, f"보관 기간 초과로 삭제: {name}")
        elif name.endswith(".log") and (today - day).days >= 2:
            with open(path, "rb") as src, gzip.open(path + ".gz", "wb") as dst:
                dst.write(src.read())
            os.chmod(path + ".gz", 0o600)
            os.remove(path)


def read_day_bytes(day):
    path = day_path(day)
    if os.path.exists(path):
        with open(path, "rb") as f:
            return f.read()
    if os.path.exists(path + ".gz"):
        with gzip.open(path + ".gz", "rb") as f:
            return f.read()
    return None


def verify():
    """체인을 처음부터 다시 계산하고, 각 구간의 파일 내용 해시와 날짜 파일의 전체 길이가 기록과 맞는지 확인한다."""
    try:
        with open(CHAIN_FILE, encoding="utf-8") as f:
            rows = [r.rstrip("\n").split("\t") for r in f if r.strip()]
    except FileNotFoundError:
        print("체인 파일이 없다")
        return 1
    prev, bad, lines_total = GENESIS, 0, 0
    expected_len = {}
    cache = {}
    today = datetime.now(timezone.utc).date()
    for i, row in enumerate(rows, 1):
        _, day, offset, length, count, digest, chain = row
        offset, length = int(offset), int(length)
        if sha256(f"{prev}|{day}|{offset}|{length}|{digest}".encode()) != chain:
            print(f"체인 불일치: {i}번째 기록 ({day})")
            bad += 1
        prev = chain
        lines_total += int(count)
        expected_len[day] = max(expected_len.get(day, 0), offset + length)
        if day not in cache:
            cache[day] = read_day_bytes(day)
        data = cache[day]
        if data is None:
            age = (today - datetime.strptime(day, "%Y-%m-%d").date()).days
            if age > RETAIN_DAYS:
                continue                                    # 보관 기간으로 지운 날
            print(f"파일 없음: {day} ({i}번째 기록)")
            bad += 1
        elif sha256(data[offset:offset + length]) != digest:
            print(f"내용 불일치: {day} 의 {i}번째 구간(위치 {offset}, 길이 {length})")
            bad += 1
    for day, n in expected_len.items():
        data = cache.get(day)
        if data is not None and len(data) != n:
            print(f"파일 길이 불일치: {day} (기록 {n}바이트, 실제 {len(data)}바이트: 덧붙이거나 잘렸다)")
            bad += 1
    if prev != load_state()["chain"]:
        print("마지막 체인 해시가 상태 파일과 다르다(기록이 지워졌거나 상태가 바뀌었다)")
        bad += 1
    print(f"체인 {len(rows)}구간, 기록상 {lines_total}줄, 확인한 날 {len(cache)}일")
    print("검증 실패" if bad else "검증 통과")
    return 1 if bad else 0


def main():
    syslog.openlog("doro-audit-pull", syslog.LOG_PID)
    if "--verify" in sys.argv:
        sys.exit(verify())
    try:
        n = pull()
        prune_and_compress()
        log(syslog.LOG_INFO, f"감사 로그 {n}줄을 가져왔다")
    except (urllib.error.URLError, TimeoutError, OSError, KeyError, ValueError) as e:
        log(syslog.LOG_ERR, f"감사 로그 가져오기 실패: {type(e).__name__}: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
