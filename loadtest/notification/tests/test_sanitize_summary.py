"""sanitize_summary.py 시험(N13, test-spec NT-B0-11, S2). 실행: python -I -m unittest discover -s loadtest/notification/tests"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "k6" / "notification" / "sanitize_summary.py"

RAW = {
    "setup_data": {"token": "eyJhbGciOiJSUzI1NiJ9.payload.sig"},
    "options": {"headers": {"Authorization": "Bearer eyJabc", "Cookie": "refresh=abc"}},
    "metrics": {
        "http_reqs": {"count": 60, "rate": 2.0},
        "http_req_duration{scenario:main}": {"avg": 20.1, "p(95)": 40.2, "thresholds": {"p(95)<500": False}},
        "http_req_duration{name:https://hapbang.example/api?ticket=abc}": {"avg": 1.0},
        "checks": {"passes": 120, "fails": 0, "value": 1.0},
    },
    "root_group": {"checks": {"notification_list 200": {"passes": 30, "fails": 0}},
                   "groups": {}},
}


def run(raw):
    directory = Path(tempfile.mkdtemp())
    source, target = directory / "raw.json", directory / "summary.json"
    source.write_text(json.dumps(raw), encoding="utf-8")
    result = subprocess.run([sys.executable, "-I", str(SCRIPT), str(source), str(target)], capture_output=True)
    return result.returncode, target


class SanitizeSummaryTest(unittest.TestCase):

    def test_허용_필드만_남기고_토큰_헤더_쿠키_URL은_뺀다(self):
        code, target = run(RAW)
        self.assertEqual(code, 0)
        text = target.read_text(encoding="utf-8")
        for forbidden in ("eyJ", "Bearer", "Authorization", "Cookie", "refresh", "ticket=", "setup_data", "options"):
            self.assertNotIn(forbidden, text)
        summary = json.loads(text)
        self.assertEqual(summary["metrics"]["http_reqs"], {"count": 60, "rate": 2.0})
        self.assertEqual(summary["metrics"]["http_req_duration{scenario:main}"]["thresholds"], {"p(95)<500": False})
        self.assertEqual(summary["checks"]["notification_list 200"], {"passes": 30, "fails": 0})

    def test_허용한_필드에_민감_패턴이_남으면_저장하지_않고_실패한다(self):
        raw = dict(RAW)
        raw["root_group"] = {"checks": {"login tester@example.com 200": {"passes": 1, "fails": 0}}}
        code, target = run(raw)
        self.assertEqual(code, 1)
        self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
