"""N15 본 부하 시험 판정(test-spec NT-LOAD-L3·L4·L5S·L5B·L5M·L5F, ND-189~ND-197). n15.sh가 모은 산출물을 판정한다.

사용:
  python -I verify_n15.py l4 <L4 manifest.json> <회차 폴더>
  python -I verify_n15.py l5 <L5 manifest.json> <회차 폴더>
  python -I verify_n15.py l3 <L3 manifest.json> <회차 폴더>
종료 코드: 0 = 모두 통과, 1 = 기준 실패, 3 = 투입 검증(V0) 실패로 회차 무효.
기대값(manifest·시험 명세의 식)을 실측에 맞춰 고치지 않는다. 다르면 원인을 분석해 보고한다.
"""
import datetime
import importlib.util
import json
import math
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ALERT_GROUPS = ["auxiliary-app.notification-appearance-alert", "auxiliary-app.notification-billing-alert",
                "auxiliary-app.notification-chat-alert", "auxiliary-app.notification-customersupport-alert",
                "auxiliary-app.notification-lecture-alert"]
L4_RECOVERY_LIMIT_SECONDS = 300
BATCH_SIZE = 100
MAX_FAILURES = 3
RUN_LIMIT_SECONDS = 60
CRON_SECOND = 35


def load_verify_b0():
    """verify_b0.py(B0 판정)를 고치지 않고 함수만 빌려 쓴다. -I 실행이라 경로로 불러온다"""
    spec = importlib.util.spec_from_file_location("verify_b0", HERE / "verify_b0.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_time(text):
    return datetime.datetime.fromisoformat(text).timestamp()


def read_lines(path):
    path = Path(path)
    return path.read_text(encoding="utf-8").splitlines() if path.exists() else []


def log_records(lines):
    for line in lines:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if isinstance(record, dict) and "@timestamp" in record:
            yield record


# L4

ASSIGNED = re.compile(r"^(?P<group>[^:]+): partitions assigned: \[(?P<partitions>[^\]]*)\]")


def assignment_times(lines, groups):
    """group마다 partition을 하나 이상 받은 첫 로그 시각(앱 시계). 빈 할당은 세지 않는다"""
    times = {}
    for record in log_records(lines):
        match = ASSIGNED.match(record.get("message", ""))
        if not match or match.group("group") not in groups or not match.group("partitions").strip():
            continue
        times.setdefault(match.group("group"), parse_time(record["@timestamp"]))
    return times


def lag_zero_at(rows, after, groups):
    """lag 표본(시각 TAB 합계 lag TAB 보인 group 수) 중 after 뒤에 모든 group이 보이고 합계가 0인 첫 시각"""
    for row in rows:
        parts = row.split("\t")
        if len(parts) < 3:
            continue
        t, lag, seen = float(parts[0]), int(parts[1]), int(parts[2])
        if t >= after and lag == 0 and seen == groups:
            return t
    return None


def l4_recovery(assigned, lag_zero, groups):
    """판정 시작점은 다섯 group partition 할당 완료(가장 늦은 할당) 시각이다(ND-190)"""
    if len(assigned) != groups or lag_zero is None:
        return {"ok": False, "detail": {"assignedGroups": len(assigned), "lagZeroAt": lag_zero}}
    start = max(assigned.values())
    seconds = lag_zero - start
    return {"ok": seconds <= L4_RECOVERY_LIMIT_SECONDS,
            "detail": {"assignedAt": start, "lagZeroAt": lag_zero, "recoverySeconds": round(seconds, 3),
                       "limitSeconds": L4_RECOVERY_LIMIT_SECONDS}}


def add_metrics(a, b):
    total = dict(a)
    for key, value in b.items():
        total[key] = total.get(key, 0.0) + value
    return total


def log_starts_before(lines, start):
    for record in log_records(lines):
        return parse_time(record["@timestamp"]) <= start
    return False


def dropped_by_reason(metrics):
    found = {}
    for (name, label_text), value in metrics.items():
        if name == "hapbang_notification_realtime_dropped_total":
            reason = re.search(r'reason="([^"]*)"', label_text or "")
            found[reason.group(1) if reason else "?"] = found.get(reason.group(1) if reason else "?", 0) + value
    return found


def verify_l4(manifest_path, run):
    b0 = load_verify_b0()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["_fixtureDir"] = str(manifest_path.parent)
    results = {}
    b0.verify_v0(manifest, run, results)
    output = {"valid": False, "checks": results}
    # 앱을 한 번 내렸다 다시 띄운다. 지표는 pod마다 0부터라 장벽 pod 끝 값과 재개 pod 값을 더해 회차 전체로 본다
    barrier = b0.parse_prometheus(run / "metrics-barrier.prom")
    normal = add_metrics(barrier, b0.parse_prometheus(run / "metrics-normal.prom"))
    after = add_metrics(barrier, b0.parse_prometheus(run / "metrics-after.prom"))
    before = {}
    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    b0.verify_phases(results, timing, before, normal, after, sum(manifest["phaseRecordsByTopic"]["normal"].values()))
    v0_ok = all(r["ok"] for name, r in results.items() if name.startswith("V0"))
    if v0_ok:
        b0.verify_counts(manifest, run, results, before, after)
        # V8의 로그 범위: 장벽 pod 로그(app-barrier.log)와 재개 pod 로그(app.log)를 함께 보고, 재개 pod 로그는 첫 할당보다 앞서야 한다
        app_lines = read_lines(run / "app.log")
        all_lines = read_lines(run / "app-barrier.log") + app_lines
        assigned = assignment_times(app_lines, ALERT_GROUPS)
        v8 = results["V8 errors"]
        unexpected = b0.unexpected_error_lines(all_lines)
        covered = bool(assigned) and log_starts_before(app_lines, min(assigned.values()))
        detail = dict(v8["detail"])
        detail.update({"unexpectedErrorLines": unexpected[:20], "logCoversRound": covered,
                       "logRule": "N15 L4: 장벽 pod + 재개 pod 로그, 재개 pod 첫 줄 <= 첫 할당"})
        # 실시간 전달 버림은 L4에서 판정하지 않고 따로 기록한다(ND-196)
        dropped = detail.pop("droppedDelta")
        ok = detail["expectedFailures"] == detail["actualFailures"] and not unexpected and covered
        results["V8 errors"] = {"ok": ok, "detail": detail}
        output["records"] = {"realtimeDropped": {"delta": dropped, "byReason": dropped_by_reason(after),
                                                 "note": "판정 아님, 결과 문서·N17·U13 자료(ND-196)"}}
        lag_rows = read_lines(run / "lag.tsv")
        recovery = l4_recovery(assigned, lag_zero_at(lag_rows, max(assigned.values()) if assigned else 0,
                                                     len(ALERT_GROUPS)), len(ALERT_GROUPS))
        recovery["detail"].update({k: timing.get(k) for k in ("normalInjectEndAt", "replicasOneAt",
                                                               "normalCompletedAt")})
        recovery["detail"]["processedAt"] = timing.get("processedAt")
        recovery["detail"]["hostMinusClusterSeconds"] = timing.get("clusterClock", {}).get("hostMinusClusterSeconds")
        recovery["detail"]["assignedByGroup"] = assigned
        results["L4 recovery"] = recovery
        output["valid"] = all(r["ok"] for r in results.values())
    return output, v0_ok


# L5

def l5_expectation(normal, failing):
    """정상 예약 완료 상한 회차 = ⌈(정상 + 3 × 실패)/100⌉, 최대 지연 = 그 회차 + 1분(ND-192·ND-194)"""
    runs = math.ceil((normal + MAX_FAILURES * failing) / BATCH_SIZE)
    expectation = {"runs": runs, "maxDelayMinutes": runs + 1}
    if failing == 0 and BATCH_SIZE < normal < 2 * BATCH_SIZE:
        # 배치 경계 이월(L5-B): 1회차 100건·2회차 나머지(ND-192). 다른 시나리오는 회차별 분포를 정하지 않는다
        expectation["byRun"] = {"1": BATCH_SIZE, "2": normal - BATCH_SIZE}
    return expectation


def first_fire(send_at):
    """보낼 시각 뒤 첫 회차 발화 시각(매분 35초, KST는 정시 단위라 epoch 분 경계와 같다)"""
    minute = math.floor(send_at / 60) * 60
    fire = minute + CRON_SECOND
    return fire if fire >= send_at else fire + 60


def run_index(t, first):
    """회차 로그(회차 끝에 찍힘)의 회차 번호. 회차 실행은 60초 미만이어야 하므로(ND-192) 발화 뒤 60초 안이다"""
    return int((t - first) // 60) + 1


def row_run(t, run_logs):
    """예약 행이 처리된 회차: 처리 시각 뒤 처음 찍힌 회차 로그(회차 끝)의 회차. 스케줄러가 계산상 발화(:35.000)보다 몇 ms 일찍
    시작해도 바르게 센다(L5-M 1회차에서 1ms 일찍 시작한 회차가 있었다). 로그가 없으면 None"""
    for log in sorted(run_logs, key=lambda x: x["end"]):
        if t <= log["end"]:
            return log["run"]
    return None


def judge_l5(data):
    rows = data["rows"]
    by_target = {r["target"]: r for r in rows}
    normal = [by_target[t] for t in data["normalTargets"] if t in by_target]
    failing = [by_target[t] for t in data["failingTargets"] if t in by_target]
    expired = [by_target[t] for t in data["expiredTargets"] if t in by_target]
    expect = l5_expectation(len(data["normalTargets"]), len(data["failingTargets"]))
    send_at = min(r["sendAt"] for r in normal) if normal else 0
    first = first_fire(send_at)
    results = {}

    sent = [r for r in normal if r["status"] == "SENT" and r["processedAt"] is not None]
    runs = [row_run(r["processedAt"], data["runLogs"]) for r in sent]
    last_run = None if not runs or None in runs else max(runs)
    by_run = {str(k): runs.count(k) for k in sorted(set(runs), key=lambda x: (x is None, x or 0))}
    expected_by_run = expect.get("byRun")
    results["L5 runs"] = {"ok": len(sent) == len(data["normalTargets"]) and last_run is not None
                          and last_run <= expect["runs"] and (expected_by_run is None or by_run == expected_by_run),
                          "detail": {"sent": len(sent), "expected": len(data["normalTargets"]), "lastRun": last_run,
                                     "expectedRuns": expect["runs"], "sentByRun": by_run,
                                     "expectedByRun": expected_by_run}}
    delays = [r["processedAt"] - r["sendAt"] for r in sent]
    max_delay = max(delays) if delays else None
    results["L5 max delay"] = {"ok": max_delay is not None and max_delay <= expect["maxDelayMinutes"] * 60,
                               "detail": {"maxDelaySeconds": None if max_delay is None else round(max_delay, 3),
                                          "limitSeconds": expect["maxDelayMinutes"] * 60,
                                          "definition": "processed_at - send_at"}}
    durations = [log["duration"] for log in data["runLogs"]]
    results["L5 run duration"] = {"ok": bool(durations) and max(durations) < RUN_LIMIT_SECONDS,
                                  "detail": {"maxSeconds": max(durations) if durations else None,
                                             "limitSeconds": RUN_LIMIT_SECONDS}}
    # 보낼 시각 전 회차(L5-F의 기한 지남 처리 등)는 측정 창 밖이라 회차 누락 판정에서 빼고 따로 남긴다
    pre_window = [log["run"] for log in data["runLogs"] if log["run"] < 1]
    logged = [log["run"] for log in data["runLogs"] if log["run"] >= 1]
    needed = list(range(1, (last_run or 0) + 1))
    results["L5 missed runs"] = {"ok": logged[:len(needed)] == needed and len(set(logged)) == len(logged)
                                 and data["runsFailed"] == 0,
                                 "detail": {"loggedRuns": logged, "neededRuns": needed, "preWindowRuns": pre_window,
                                            "runsFailed": data["runsFailed"]}}
    notifications = data["notifications"]
    duplicated = [t for t in data["normalTargets"] if notifications.get(t, 0) > 1]
    missing = [t for t in data["normalTargets"] if notifications.get(t, 0) == 0]
    unexpected = [t for t in data["failingTargets"] + data["expiredTargets"] if notifications.get(t, 0) > 0]
    results["L5 duplicates and missing"] = {"ok": not duplicated and not missing and not unexpected,
                                            "detail": {"duplicated": duplicated[:20], "missing": missing[:20],
                                                       "unexpectedForFailingOrExpired": unexpected[:20]}}

    failing_problems = []
    for r in failing:
        first_failure = data["failureLogs"].get(r["id"])
        first_run = None if first_failure is None else row_run(first_failure, data["runLogs"])
        expected_run = None if first_run is None else first_run + MAX_FAILURES - 1
        actual_run = None if r["processedAt"] is None else row_run(r["processedAt"], data["runLogs"])
        # 첫 시도 회차와 끝난 회차를 모두 알아야 비교한다. 하나라도 모르면 실패다
        if (r["status"] != "SKIPPED" or r["failureCount"] != MAX_FAILURES or expected_run is None or actual_run is None
                or expected_run != actual_run):
            failing_problems.append({"id": r["id"], "status": r["status"], "failureCount": r["failureCount"],
                                     "firstAttemptRun": None if expected_run is None else expected_run - 2,
                                     "skippedRun": actual_run})
    n = len(data["failingTargets"])
    results["L5F failing schedules"] = {
        "ok": len(failing) == n and not failing_problems and data["errorLines"] == n and data["abandoned"] == n,
        # retry 수는 판정 기준이 아니다(NT-LOAD-L5F는 ERROR·abandoned). 기록만 한다
        "detail": {"failing": n, "problems": failing_problems, "errorLines": data["errorLines"],
                   "abandoned": data["abandoned"], "retry": data["retry"], "retryNote": "기록, 판정 아님"}}
    # 실패 대상이 있으면 복제본 확인 기록이 하나 이상 있어야 한다(확인 안 함과 0건 확인을 구분)
    results["L5F failing replicas absent"] = {"ok": (n == 0 or bool(data["replicaChecks"]))
                                                    and all(c == 0 for c in data["replicaChecks"]),
                                              "detail": {"checks": len(data["replicaChecks"]),
                                                         "max": max(data["replicaChecks"] or [0])}}
    deadline = data["expiredDeadline"]
    expired_problems = [r["id"] for r in expired
                        if r["status"] != "SKIPPED" or r["failureCount"] != 0 or r["processedAt"] is None
                        or deadline is None or r["processedAt"] < deadline]
    results["L5F expired schedules"] = {"ok": len(expired) == len(data["expiredTargets"]) and not expired_problems,
                                        "detail": {"expired": len(data["expiredTargets"]),
                                                   "problems": expired_problems[:20]}}
    return results


# L3

L3_RECV = re.compile(r"L3RECV (\d+) (\d+) (\d+)")
L3_P95_LIMIT_SECONDS = 1.0
L3_LOSS_LIMIT = 0.001


def percentile(sorted_values, p):
    """가장 가까운 순위 방식(보간 없음)"""
    if not sorted_values:
        return None
    return sorted_values[max(0, math.ceil(p * len(sorted_values)) - 1)]


def parse_l3_receives(rows, received_lines):
    """수신 표지(L3RECV)를 알림마다 첫 수신 시각으로 모은다. 중복 수신과 저장에 없는 ID(측정 제외)는 따로 센다"""
    first, duplicates, unknown = {}, 0, set()
    for line in received_lines:
        # k6 콘솔 로그는 time="…" level=info msg="L3RECV …" source=console 형식이라 줄 안에서 찾는다
        match = L3_RECV.search(line)
        if not match:
            continue
        notification_id, at = int(match.group(1)), int(match.group(2)) / 1000
        if notification_id not in rows:
            unknown.add(notification_id)
            continue
        if notification_id in first:
            duplicates += 1
            continue
        first[notification_id] = at
    return first, duplicates, unknown


def l3_receive_counts(rows, received_lines):
    """판정이 아닌 기록: 중복 수신, 측정 제외(저장된 시험 대상이 아닌 알림 ID, 하루 묶음 갱신 등)(NT-LOAD-L3)"""
    _, duplicates, unknown = parse_l3_receives(rows, received_lines)
    return {"duplicateReceives": duplicates, "excludedIds": sorted(unknown)[:20], "excludedCount": len(unknown),
            "note": "따로 센다, 판정 아님(NT-LOAD-L3)"}


def judge_l3(rows, received_lines):
    """rows: 알림 ID → created_at(epoch 초). received_lines: k6 로그 "L3RECV <알림 ID> <수신 epoch ms> <VU>"(ND-193)"""
    first, _, _ = parse_l3_receives(rows, received_lines)
    latencies = sorted(first[i] - rows[i] for i in first)
    p95, p99 = percentile(latencies, 0.95), percentile(latencies, 0.99)
    results = {"L3 latency": {"ok": p95 is not None and p95 <= L3_P95_LIMIT_SECONDS,
                              "detail": {"samples": len(latencies),
                                         "p50Seconds": None if not latencies else round(percentile(latencies, 0.5), 4),
                                         "p95Seconds": None if p95 is None else round(p95, 4),
                                         "p99Seconds": None if p99 is None else round(p99, 4),
                                         "maxSeconds": None if not latencies else round(latencies[-1], 4),
                                         "minSeconds": None if not latencies else round(latencies[0], 4),
                                         "limitSeconds": L3_P95_LIMIT_SECONDS,
                                         "definition": "STOMP 프레임 수신 - notification.created_at, 알림마다 첫 수신"}}}
    missing = len(rows) - len(first)
    loss = missing / len(rows) if rows else 1.0
    results["L3 loss"] = {"ok": bool(rows) and loss < L3_LOSS_LIMIT,
                          "detail": {"saved": len(rows), "receivedUnique": len(first), "missing": missing,
                                     "lossRate": round(loss, 6), "limit": L3_LOSS_LIMIT}}
    return results


def read_schedule_rows(path):
    """db-l5-schedules.tsv: id, target, receiver, status, send_at(epoch), processed_at(epoch 또는 빈 값), failure_count"""
    rows = []
    for line in read_lines(path):
        if not line.strip():
            continue
        i, target, receiver, status, send_at, processed, failures = line.split("\t")
        rows.append({"id": int(i), "target": int(target), "receiver": int(receiver), "status": status,
                     "sendAt": float(send_at), "processedAt": float(processed) if processed else None,
                     "failureCount": int(failures)})
    return rows


RUN_LOG = re.compile(r"^예약 배치를 실행했다 ")
FAILURE_LOG = re.compile(r"^예약 알림을 보내지 못했다.*scheduleId=(?P<id>\d+), failureCount=(?P<count>\d+)")


def run_logs(lines, first):
    """회차 로그(예약 배치를 실행했다) 시각을 회차 번호와 그 회차 발화(매분 35초)부터 걸린 초로 바꾼다"""
    logs = []
    for record in log_records(lines):
        if RUN_LOG.match(record.get("message", "")):
            t = parse_time(record["@timestamp"])
            # 회차 끝 로그. 발화가 몇 ms 일찍이어도 번호가 밀리지 않게 1초 여유를 둔다(회차 실행은 60초 미만, ND-192)
            index = run_index(t + 1, first)
            duration = t - (first + 60 * (index - 1))
            if duration < 0:
                # 다음 발화 1초 전 안에 끝난 긴 회차다(회차 실행이 1ms보다 짧을 수는 없다). 앞 회차로 센다
                index -= 1
                duration += 60
            logs.append({"run": index, "duration": round(duration, 3), "end": t,
                         "message": record["message"]})
    return logs


def first_failures(lines):
    found = {}
    for record in log_records(lines):
        match = FAILURE_LOG.match(record.get("message", ""))
        if match and match.group("count") == "1":
            found.setdefault(int(match.group("id")), parse_time(record["@timestamp"]))
    return found


def verify_l5(manifest_path, run):
    b0 = load_verify_b0()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rows = read_schedule_rows(run / "db-l5-schedules.tsv")
    lines = read_lines(run / "app.log")
    normal_rows = [r for r in rows if r["target"] in set(manifest["normalTargets"])]
    first = first_fire(min(r["sendAt"] for r in normal_rows)) if normal_rows else 0
    after = b0.parse_prometheus(run / "metrics-after.prom")
    notifications = {}
    for line in read_lines(run / "db-l5-notifications.tsv"):
        if line.strip():
            target, count = line.split("\t")
            notifications[int(target)] = int(count)
    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    data = {"normalTargets": manifest["normalTargets"], "failingTargets": manifest["failingTargets"],
            "expiredTargets": manifest["expiredTargets"], "expiredDeadline": timing.get("expiredDeadline"),
            "rows": rows, "runLogs": run_logs(lines, first), "failureLogs": first_failures(lines),
            "errorLines": sum(1 for r in log_records(lines) if r.get("level") == "ERROR"
                              and "[NOTIFICATION_SCHEDULE_FAILED]" in r.get("message", "")),
            "abandoned": int(b0.metric_sum(after, "hapbang_notification_schedule_outcomes_total", result="abandoned")),
            "retry": int(b0.metric_sum(after, "hapbang_notification_schedule_outcomes_total", result="retry")),
            "notifications": notifications,
            "replicaChecks": [int(x) for x in read_lines(run / "replica-checks.txt") if x.strip()],
            "runsFailed": int(b0.metric_sum(after, "hapbang_notification_schedule_runs_total", outcome="failed"))}
    results = judge_l5(data)
    # L5-F는 앱을 한 번 내렸다 띄운다. 앞 pod 로그(app-pre.log)도 예상 밖 오류를 본다
    all_lines = read_lines(run / "app-pre.log") + lines
    unexpected = [l[:300] for l in b0.unexpected_error_lines(all_lines) if "[NOTIFICATION_SCHEDULE_FAILED]" not in l]
    results["L5 unexpected errors"] = {"ok": not unexpected, "detail": {"lines": unexpected[:20]}}
    return {"valid": all(r["ok"] for r in results.values()), "checks": results,
            "runLogs": data["runLogs"], "firstFire": first}, True


def histogram_quantile(before, after, name, q):
    """라벨 없는 histogram의 분위수(Prometheus histogram_quantile과 같은 구간 내 선형 보간). 보조 지표 기록용이다"""
    buckets = []
    for (metric, label_text), value in after.items():
        if metric != name + "_bucket":
            continue
        le = re.search(r'le="([^"]*)"', label_text)
        if le:
            bound = math.inf if le.group(1) == "+Inf" else float(le.group(1))
            buckets.append((bound, value - before.get((metric, label_text), 0.0)))
    buckets.sort()
    total = buckets[-1][1] if buckets else 0.0
    if total <= 0:
        return {"seconds": None, "samples": 0}
    rank, lower, previous = q * total, 0.0, 0.0
    for bound, cumulative in buckets:
        if cumulative >= rank:
            if math.isinf(bound):
                return {"seconds": None, "aboveTopBucket": True, "samples": int(total)}
            count = cumulative - previous
            seconds = lower + (bound - lower) * ((rank - previous) / count if count else 0)
            return {"seconds": round(seconds, 6), "bucket": [lower, bound], "samples": int(total)}
        lower, previous = bound, cumulative
    return {"seconds": None, "samples": int(total)}


L3_CLOSE = re.compile(r"L3CLOSE (\d+) (-?\d+|undefined) (\d+)")


def verify_l3(receivers_path, run):
    b0 = load_verify_b0()
    rows = {}
    for line in read_lines(run / "db-l3-rows.tsv"):
        if line.strip():
            notification_id, created = line.split("\t")
            rows[int(notification_id)] = float(created)
    k6 = read_lines(run / "k6.log")
    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    results = judge_l3(rows, k6)
    connected = sum(1 for l in k6 if "L3CONN" in l)
    ticket_failures = sum(1 for l in k6 if "L3TICKETFAIL" in l)
    # 수집은 시험 클라이언트가 연결을 닫기 전에 한다. 그 전에 닫힌 연결은 모두 비정상이다(만료 4401은 15분 뒤라 이 시험 안에 없다)
    closes = [m.groups() for m in (L3_CLOSE.search(l) for l in k6) if m]
    results["L3 connections"] = {"ok": connected == timing["receivers"] and ticket_failures == 0 and not closes,
                                 "detail": {"connected": connected, "expected": timing["receivers"],
                                            "ticketFailures": ticket_failures, "closedBeforeCollection": closes[:20],
                                            "auth": "Gateway 사용자 헤더 직접, 앱 티켓 발급·검증 그대로, JWT 검증 없음(ND-195)"}}
    before = b0.parse_prometheus(run / "metrics-before.prom")
    after = b0.parse_prometheus(run / "metrics-after.prom")
    dropped = b0.delta(before, after, "hapbang_notification_realtime_dropped_total")
    sections = {}
    for name in ("hapbang_notification_realtime_publish_latency_seconds", "hapbang_notification_realtime_send_latency_seconds"):
        sections[name] = {q: histogram_quantile(before, after, name, q) for q in (0.95, 0.99)}
    # 실시간 전달 버림·구간 지표는 판정이 아니라 기록이다(NT-LOAD-L3 보조 지표, ND-196과 같은 처리)
    records = {"receives": l3_receive_counts(rows, k6),
               "relay": {"droppedDelta": dropped, "byReason": dropped_by_reason(after), "sectionQuantiles": sections,
                         "note": "구간 분석용 보조 지표, 합산하지 않음, 판정 아님(ND-193)"}}
    return {"valid": all(r["ok"] for r in results.values()), "checks": results, "records": records,
            "timing": timing}, True


def main():
    mode, manifest_path, run = sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3])
    if mode == "l4":
        output, v0_ok = verify_l4(manifest_path, run)
    elif mode == "l5":
        output, v0_ok = verify_l5(manifest_path, run)
    elif mode == "l3":
        output, v0_ok = verify_l3(manifest_path, run)
    else:
        raise SystemExit("모드는 l4·l5·l3 중 하나다")
    (run / "result.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, r in output["checks"].items():
        print(("PASS " if r["ok"] else "FAIL ") + name)
    sys.exit(0 if output["valid"] else (3 if not v0_ok else 1))


if __name__ == "__main__":
    main()
