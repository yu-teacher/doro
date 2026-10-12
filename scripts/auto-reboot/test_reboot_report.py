import unittest

import importlib.util
import os

_spec = importlib.util.spec_from_file_location("reboot_report", os.path.join(os.path.dirname(__file__), "reboot-report.py"))
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
build_message, parse_events, summarize_host, build_query = _mod.build_message, _mod.parse_events, _mod.summarize_host, _mod.build_query


def ev(*lines):
    return parse_events(list(lines))


class RebootReportTest(unittest.TestCase):
    def test_all_not_needed_sends_nothing(self):
        per = {h: ev(f"doro-autoreboot: CHECK host={h} result=not-needed") for h in ("mini", "notebook", "pi")}
        self.assertIsNone(build_message(per))

    def test_reboot_ok(self):
        per = {
            "mini": ev("CHECK host=mini result=rebooting kernel=7.0.0-31 pkgs=libc6", "BOOT-OK host=mini kernel=7.0.0-38 took=112s failed_units=0"),
            "notebook": ev("CHECK host=notebook result=not-needed"),
        }
        msg = build_message(per)
        self.assertIn("✅ mini: 재부팅 완료(커널 7.0.0-38, 112s)", msg)
        self.assertIn("재부팅 필요 없음", msg)
        self.assertTrue(msg.startswith("🔄"))

    def test_skipped_is_warning(self):
        sev, text = summarize_host("mini", ev("CHECK host=mini result=skipped reason=CI작업진행중"))
        self.assertEqual(sev, "warn")
        self.assertIn("CI작업진행중", text)

    def test_rebooted_but_no_boot_result_is_failure(self):
        sev, _ = summarize_host("mini", ev("CHECK host=mini result=rebooting kernel=1 pkgs=x"))
        self.assertEqual(sev, "fail")
        self.assertTrue(build_message({"mini": ev("CHECK host=mini result=rebooting kernel=1 pkgs=x")}).startswith("🔴"))

    def test_boot_fail(self):
        sev, text = summarize_host("mini", ev("CHECK host=mini result=rebooting kernel=1 pkgs=x", "BOOT-FAIL host=mini kernel=2 took=430s reason=127.0.0.1/login:502(기대200)"))
        self.assertEqual(sev, "fail")
        self.assertIn("502", text)

    def test_missing_logs_are_reported(self):
        per = {"mini": ev("CHECK host=mini result=not-needed"), "pi": []}
        msg = build_message(per)
        self.assertIsNotNone(msg)
        self.assertIn("pi: 점검 로그가 없다", msg)

    def test_failed_units_noted_on_success(self):
        _, text = summarize_host("pi", ev("CHECK host=pi result=rebooting kernel=1 pkgs=x", "BOOT-OK host=pi kernel=2 took=60s failed_units=2"))
        self.assertIn("실패한 서비스 2개", text)

    def test_last_check_wins(self):
        sev, _ = summarize_host("mini", ev("CHECK host=mini result=skipped reason=x", "CHECK host=mini result=not-needed"))
        self.assertEqual(sev, "quiet")


class QueryTest(unittest.TestCase):
    def test_query_matches_line_body_markers_not_the_syslog_tag(self):
        q = build_query("mini")
        self.assertIn('host="mini"', q)
        self.assertIn("CHECK host=", q)
        self.assertIn("BOOT-OK", q)
        self.assertIn("BOOT-FAIL", q)
        # 태그(doro-autoreboot)는 줄 본문에 없어서 찾는 데 쓰면 안 된다(2026-10-12 오탐 사고)
        self.assertNotIn("doro-autoreboot", q)

    def test_query_rejects_unsafe_host_names(self):
        for bad in ('mini"}', "a b", "x;y", ""):
            with self.assertRaises(ValueError):
                build_query(bad)

    def test_logger_line_without_tag_is_parsed(self):
        # Loki 에 실제로 들어 있던 두 가지 줄(태그 없는 본문, 스크립트 출력)을 모두 읽는다.
        events = parse_events(["CHECK host=mini result=not-needed", "[05:10:01] CHECK host=mini result=not-needed"])
        self.assertEqual([e["fields"]["result"] for e in events], ["not-needed", "not-needed"])
        self.assertIsNone(build_message({"mini": events}))


if __name__ == "__main__":
    unittest.main()
