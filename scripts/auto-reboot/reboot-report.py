#!/usr/bin/env python3
"""월요일 자동 재부팅 결과를 모아 텔레그램으로 한 통 보낸다(파이에서 cron 으로 실행).

각 서버의 doro-auto-reboot 가 남긴 로그(태그 doro-autoreboot)를 중앙 Loki 에서 읽어 서버별 한 줄로 요약한다.
아무 서버도 재부팅하지 않았고(모두 not-needed) 이상도 없으면 아무것도 보내지 않는다. 점검 로그가 없는 서버가 있으면 그것도 알린다.

  reboot-report.py [--dry-run] [--hosts mini,notebook,pi] [--window-hours 3]
설정: ~/watchdog/.env 의 LOKI_URL, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID (값은 출력하지 않는다)
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

KV = re.compile(r"(\w+)=(\S+)")


def parse_events(lines):
    """로그 줄 목록 -> 이벤트 목록. 이벤트: {'kind': 'CHECK'|'BOOT-OK'|'BOOT-FAIL', 'fields': {...}}"""
    events = []
    for line in lines:
        m = re.search(r"\b(CHECK|BOOT-OK|BOOT-FAIL)\b(.*)", line)
        if not m:
            continue
        events.append({"kind": m.group(1), "fields": dict(KV.findall(m.group(2)))})
    return events


def summarize_host(host, events):
    """한 서버의 이벤트를 (심각도, 문장)으로 바꾼다. 심각도: quiet(조용히 지나감) | info | warn | fail"""
    checks = [e for e in events if e["kind"] == "CHECK"]
    if not checks:
        return "warn", f"❓ {host}: 점검 로그가 없다(타이머가 안 돌았거나 로그 수집이 끊겼는지 확인)"
    last = checks[-1]["fields"]
    result = last.get("result", "?")
    if result == "not-needed":
        return "quiet", f"– {host}: 재부팅 필요 없음"
    if result == "skipped":
        return "warn", f"⚠️ {host}: 건너뜀({last.get('reason', '?')}) - 다음 월요일에 다시 확인"
    if result == "rebooting":
        boots = [e for e in events if e["kind"] in ("BOOT-OK", "BOOT-FAIL")]
        if not boots:
            return "fail", f"🔴 {host}: 재부팅했지만 부팅 후 확인 결과가 없다(올라오지 않았거나 로그 수집 전)"
        b = boots[-1]
        f = b["fields"]
        if b["kind"] == "BOOT-OK":
            extra = ""
            if f.get("failed_units", "0") not in ("0", ""):
                extra = f", 실패한 서비스 {f['failed_units']}개"
            return "info", f"✅ {host}: 재부팅 완료(커널 {f.get('kernel', '?')}, {f.get('took', '?')}{extra})"
        return "fail", f"🔴 {host}: 재부팅 후 확인 실패 - {f.get('reason', '?')} ({f.get('took', '?')})"
    return "warn", f"❓ {host}: 알 수 없는 결과({result})"


def build_message(per_host):
    """{host: events} -> 보낼 메시지 문자열. 보낼 것이 없으면 None."""
    rows = [summarize_host(h, ev) for h, ev in per_host.items()]
    if all(sev == "quiet" for sev, _ in rows):
        return None
    head = "🔄 월요일 자동 재부팅 결과"
    if any(sev == "fail" for sev, _ in rows):
        head = "🔴 월요일 자동 재부팅: 확인 필요"
    elif any(sev == "warn" for sev, _ in rows):
        head = "🟡 월요일 자동 재부팅 결과(확인 필요 있음)"
    return head + "\n" + "\n".join(text for _, text in rows)


def load_env(path):
    env = {}
    if os.path.exists(path):
        for raw in open(path, encoding="utf-8"):
            raw = raw.strip()
            if not raw or raw.startswith("#") or "=" not in raw:
                continue
            k, v = raw.split("=", 1)
            env[k.strip()] = v.strip().strip("'\"")
    return env


def query_host_lines(loki_url, host, window_hours):
    end = int(time.time() * 1e9)
    start = end - int(window_hours * 3600 * 1e9)
    q = '{host="%s", job="journal"} |= "doro-autoreboot"' % host
    params = urllib.parse.urlencode({"query": q, "start": start, "end": end, "limit": 100, "direction": "forward"})
    with urllib.request.urlopen(f"{loki_url}/loki/api/v1/query_range?{params}", timeout=20) as resp:
        data = json.load(resp)
    lines = []
    for stream in data.get("data", {}).get("result", []):
        for _, line in stream.get("values", []):
            lines.append(line)
    return lines


def send_telegram(token, chat_id, text):
    url = f"https://api.telegram.org/bot{token}/sendMessage"  # 토큰은 로그·화면에 출력하지 않는다
    body = urllib.parse.urlencode({"chat_id": chat_id, "text": text}).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=body), timeout=20) as resp:
        return json.load(resp).get("ok", False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="보내지 않고 메시지만 출력")
    ap.add_argument("--hosts", default="mini,notebook,pi")
    ap.add_argument("--window-hours", type=float, default=3.0)
    ap.add_argument("--env", default=os.path.expanduser("~/watchdog/.env"))
    args = ap.parse_args()
    env = load_env(args.env)
    loki = env.get("LOKI_URL")
    if not loki:
        print("LOKI_URL 이 없다", file=sys.stderr)
        return 2
    per_host = {}
    for h in [x for x in args.hosts.split(",") if x]:
        try:
            per_host[h] = parse_events(query_host_lines(loki, h, args.window_hours))
        except Exception as exc:  # 조회 실패도 알려야 한다(빈 결과로 오해하지 않게)
            print(f"{h} 로그 조회 실패: {exc}", file=sys.stderr)
            per_host[h] = []
    msg = build_message(per_host)
    if msg is None:
        print("보낼 것이 없다(모든 서버가 재부팅 필요 없음).")
        return 0
    if args.dry_run:
        print(msg)
        return 0
    token, chat = env.get("TELEGRAM_BOT_TOKEN"), env.get("TELEGRAM_CHAT_ID")
    if not token or not chat:
        print("텔레그램 설정이 없어 보내지 못했다:\n" + msg, file=sys.stderr)
        return 1
    ok = send_telegram(token, chat, msg)
    print("전송 성공" if ok else "전송 실패")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
