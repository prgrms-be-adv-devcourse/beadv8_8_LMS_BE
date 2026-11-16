"""k6 요약 JSON(--summary-export)을 커밋해도 되는 요약으로 줄인다(N13, ND-155·보완 ④).

허용 목록 필드만 남긴다: 지표 이름별 값(count·rate·avg·min·med·p(90)·p(95)·p(99)·max·passes·fails·thresholds 통과 여부)과
check 이름별 통과·실패 수. setup_data·options·헤더·쿠키·URL·태그 값은 남기지 않는다.
남긴 결과에 민감 패턴이 하나라도 있으면 저장하지 않고 실패한다(종료 코드 1).

사용: python -I sanitize_summary.py <원본 요약.json> <저장할 요약.json>
"""
import json
import re
import sys
from pathlib import Path

ALLOWED_METRIC_KEYS = {"count", "rate", "avg", "min", "med", "p(90)", "p(95)", "p(99)", "max", "passes", "fails",
                       "value"}
METRIC_NAME = re.compile(r"^[a-z_]+(\{[a-z_]+:[a-z_]+(,[a-z_]+:[a-z_]+)*\})?$")
SENSITIVE = [re.compile(p, re.IGNORECASE) for p in (
    r"eyJ[a-z0-9_-]{5,}", r"bearer", r"authorization", r"set-cookie", r"\bcookie", r"refresh", r"password",
    r"[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}", r"ticket=")]


def sanitize(raw):
    metrics = {}
    for name, values in raw.get("metrics", {}).items():
        if not METRIC_NAME.match(name):
            continue
        kept = {k: v for k, v in values.items() if k in ALLOWED_METRIC_KEYS and isinstance(v, (int, float))}
        thresholds = values.get("thresholds")
        if isinstance(thresholds, dict):
            # 식(예: p(95)<500)과 k6가 준 불리언을 그대로 옮긴다. 판정은 k6 종료 코드(0 합격, 99 threshold 실패)로 한다
            kept["thresholds"] = {expr: flag for expr, flag in thresholds.items() if isinstance(flag, bool)}
        metrics[name] = kept
    checks = {}

    def walk(group):
        for name, value in (group.get("checks") or {}).items():
            checks[name] = {"passes": value.get("passes", 0), "fails": value.get("fails", 0)}
        for child in (group.get("groups") or {}).values():
            walk(child)

    walk(raw.get("root_group") or {})
    return {"metrics": metrics, "checks": checks}


def find_sensitive(text):
    return [p.pattern for p in SENSITIVE if p.search(text)]


def main():
    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    summary = sanitize(json.loads(source.read_text(encoding="utf-8")))
    text = json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
    found = find_sensitive(text)
    if found:
        print("민감 패턴이 남아 저장하지 않는다: " + ", ".join(found), file=sys.stderr)
        sys.exit(1)
    target.write_text(text + "\n", encoding="utf-8")
    print(f"저장: {target}")


if __name__ == "__main__":
    main()
