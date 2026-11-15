"""B0 1회차 검증(N13, ND-156). b0.sh가 모은 산출물과 manifest를 비교해 V0~V8·결과 지문(F)·성능 값을 낸다.

사용: python -I verify_b0.py <manifest.json> <회차 폴더>
종료 코드: 0 = V0~V8·F 모두 통과, 3 = V0 실패(그 회차 무효, 측정하지 않음), 1 = V1~V8·F 중 실패(기준선으로 인정하지 않음).
manifest를 실측에 맞춰 고치지 않는다. 다르면 원인(카탈로그·계약·정책·구현)을 분석한다.
"""
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ALERT_TOPICS = ["appearance.events.v1", "billing.events.v1", "chat.events.v1", "customersupport.events.v1",
                "lecture.events.v1"]
REQUIRED_CONFIG = ["acks = -1", "enable.idempotence = true", "max.in.flight.requests.per.connection = 1",
                   "partitioner.ignore.keys = false", "partitioner.class = null"]
NOTIFICATION_LOGGER = re.compile(r"com\.hapbang\.auxiliary\.notification|org\.springframework\.kafka|org\.apache\.kafka")
# 의도한 실패(받는 사람 복제본 없음 50, 깨진 본문 25)의 원인. 감싸는 ListenerExecutionFailedException만으로는 면제하지 않는다
INTENDED_EXCEPTIONS = ("NotificationException", "DeserializationException", "RECEIVER_NOT_REPLICATED")


def digest(key, type_id, value):
    return hashlib.sha256(f"{key}\0{type_id}\0{value}".encode("utf-8")).hexdigest()[:16]


def read_lines(path):
    return Path(path).read_text(encoding="utf-8").splitlines() if Path(path).exists() else []


def parse_prometheus(path):
    """이름{라벨} → 값. 같은 이름의 여러 series를 라벨 문자열로 구분한다"""
    metrics = {}
    for line in read_lines(path):
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([^\s]+)", line)
        if match:
            metrics[(match.group(1), match.group(2) or "")] = float(match.group(3))
    return metrics


def labels(text):
    return dict(re.findall(r'(\w+)="([^"]*)"', text))


def metric_sum(metrics, name, **wanted):
    total = 0.0
    for (metric, label_text), value in metrics.items():
        if metric != name:
            continue
        found = labels(label_text)
        if all(found.get(k) == v for k, v in wanted.items()):
            total += value
    return total


def delta(before, after, name, **wanted):
    return metric_sum(after, name, **wanted) - metric_sum(before, name, **wanted)


OUTCOMES_METRIC = "hapbang_notification_consume_outcomes_total"


def outcome_differences(expected_outcomes, before, after):
    """토픽·결과별 회차 전체 값을 기대와 비교한다. manifest에 없는 토픽·결과가 있어도 기대 0과 다른 것으로 본다.

    회원 단계(투입·단계 장벽)는 스냅샷 ① 전에 끝나므로 ①부터의 증가량으로는 회원 토픽 결과를 볼 수 없다. 앱은 회차마다
    replicas 0에서 새로 떠 지표가 0부터 시작하므로, 끝 스냅샷 값이 곧 회차 전체 값이다(ND-163). before는 관측한 series를
    모으는 데만 쓴다.
    """
    observed = set()
    for metrics in (before, after):
        for (metric, label_text) in metrics:
            if metric == OUTCOMES_METRIC:
                found = labels(label_text)
                observed.add((found.get("topic"), found.get("result")))
    for topic, results_by_kind in expected_outcomes.items():
        for result in results_by_kind:
            observed.add((topic, result))
    diff = {}
    for topic, result in sorted(observed):
        expected = expected_outcomes.get(topic, {}).get(result, 0)
        actual = metric_sum(after, OUTCOMES_METRIC, topic=topic, result=result)
        if int(actual) != expected:
            diff[f"{topic}/{result}"] = {"expected": expected, "actual": actual}
    return diff


def check(results, name, ok, detail):
    results[name] = {"ok": bool(ok), "detail": detail}


def verify_v0(manifest, run, results):
    """투입 재현성: 레코드 단위 위치·순서, 순서 의존 쌍, 헤더·key, producer 설정·오류, 단계 장벽"""
    actual = defaultdict(lambda: defaultdict(list))
    positions = {}
    bad_headers = []
    for line in read_lines(run / "topics.tsv"):
        topic, partition, offset, headers, key, value = line.split("\t", 5)
        entries = [h for h in headers.split(",") if h]
        type_ids = [h.split(":", 1)[1] for h in entries if h.startswith("__TypeId__:")]
        if len(type_ids) != 1 or len(entries) != 1:
            bad_headers.append(f"{topic}-{partition}@{offset}")
            continue
        d = digest(key, type_ids[0], value)
        actual[topic][int(partition)].append(d)
        positions.setdefault((topic, d), []).append((int(partition), int(offset)))
    expected = manifest["partitionSequences"]
    mismatched = []
    for topic, by_partition in expected.items():
        for partition, sequence in by_partition.items():
            if actual[topic][int(partition)] != sequence:
                mismatched.append(f"{topic}-{partition}: expected {len(sequence)} got {len(actual[topic][int(partition)])}")
    extra = [f"{t}-{p}" for t, parts in actual.items() for p in parts if str(p) not in expected.get(t, {})]
    check(results, "V0-1/2 records", not mismatched and not extra,
          {"mismatchedPartitions": mismatched[:20], "unexpectedPartitions": extra})
    check(results, "V0-4 headers", not bad_headers, {"badHeaderRecords": bad_headers[:20]})

    # 같은 토픽·같은 key 쌍: 같은 파티션, 앞 offset < 뒤 offset. 재전송은 같은 digest가 두 번 나온다
    alerts = alert_lines(manifest)
    pair_failures = []
    for pair in manifest["orderedPairs"]:
        first = alerts[pair["firstIndex"]]
        second = alerts[pair["secondIndex"]]
        first_positions = positions.get((pair["firstTopic"], first), [])
        second_positions = positions.get((pair["secondTopic"], second), [])
        if not first_positions or not second_positions:
            pair_failures.append(pair)
            continue
        if first == second:
            ok = len(first_positions) >= 2 and first_positions[0][0] == first_positions[-1][0]
        else:
            ok = first_positions[0][0] == second_positions[0][0] and first_positions[0][1] < second_positions[0][1]
        if not ok:
            pair_failures.append(pair)
    check(results, "V0-3 ordered pairs", not pair_failures,
          {"pairs": len(manifest["orderedPairs"]), "failures": pair_failures[:10]})

    producer_problems = []
    for stderr in sorted(run.glob("producer-*.stderr")):
        text = stderr.read_text(encoding="utf-8")
        missing = [c for c in REQUIRED_CONFIG if c not in text]
        errors = [l for l in text.splitlines() if " ERROR " in l or "Exception" in l]
        if "ProducerConfig values" not in text or missing or errors:
            producer_problems.append({"file": stderr.name, "missingConfig": missing, "errors": errors[:5]})
    check(results, "V0-5 producer", not producer_problems and any(run.glob("producer-*.stderr")),
          {"problems": producer_problems})

    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    barrier_ok = (timing.get("replicas") == manifest["replicas"] and timing.get("memberLag") == 0
                  and timing["barrierAt"] <= timing["alertInjectStartAt"])
    check(results, "V0-6 barrier", barrier_ok,
          {k: timing.get(k) for k in ("replicas", "memberLag", "barrierAt", "alertInjectStartAt")})


def alert_lines(manifest):
    """manifest의 순서 쌍 index는 알림 레코드 목록 위치다. 파일 순서 digest 목록을 토픽 파일에서 다시 만든다"""
    order = json.loads((Path(manifest["_fixtureDir"]) / "alert-order.json").read_text(encoding="utf-8"))
    return order


def verify_counts(manifest, run, results, before, after):
    total_rows = int((run / "db-total.txt").read_text().strip())
    check(results, "V1 rows", total_rows == manifest["notificationRows"],
          {"expected": manifest["notificationRows"], "actual": total_rows})

    outcome_diff = outcome_differences(manifest["outcomes"], before, after)
    check(results, "V2 outcomes", not outcome_diff, outcome_diff)

    # DLT 원본 헤더의 원인 예외(감싼 예외면 cause, 아니면 예외 자체)의 짧은 이름으로 센다
    dlt = Counter()
    for line in read_lines(run / "dlt.tsv"):
        headers = line.split("\t", 5)[3]
        found = dict(h.split(":", 1) for h in headers.split(",") if ":" in h)
        fqcn = found.get("kafka_dlt-exception-cause-fqcn") or found.get("kafka_dlt-exception-fqcn") or "?"
        dlt[fqcn.rsplit(".", 1)[-1]] += 1
    published = sum(delta(before, after, "hapbang_notification_dead_letter_total", topic=t, result="published")
                    for t in ALERT_TOPICS)
    failed = sum(delta(before, after, "hapbang_notification_dead_letter_total", topic=t, result="failed")
                 for t in ALERT_TOPICS)
    expected_dlt = manifest["deadLetters"]
    check(results, "V3 dead letters",
          dict(dlt) == expected_dlt and int(published) == sum(expected_dlt.values()) and failed == 0,
          {"expected": expected_dlt, "dltTopicByException": dict(dlt), "published": published, "failed": failed})

    legacy = delta(before, after, "hapbang_notification_alert_legacy_skipped_total")
    check(results, "V4 legacy", int(legacy) == manifest["legacySkipped"],
          {"expected": manifest["legacySkipped"], "actual": legacy})

    duplicated_keys = [l for l in read_lines(run / "db-dup.tsv") if l.strip()]
    check(results, "V5 duplicates", not duplicated_keys, {"keysWithMoreThanOneRow": duplicated_keys[:20]})

    alive = {}
    deleted = {}
    for line in read_lines(run / "db-rows.tsv"):
        receiver, a, d = line.split("\t")
        alive[int(receiver)] = int(a)
        deleted[int(receiver)] = int(d)
    retention_problems = []
    for receiver, saved in manifest["rowsByReceiver"].items():
        r = int(receiver)
        a, d = alive.get(r, 0), deleted.get(r, 0)
        if a != min(saved, 20) or a + d != saved:
            retention_problems.append({"receiver": r, "saved": saved, "alive": a, "deleted": d})
    inactive_rows = [r for r in manifest["inactiveReceivers"] if alive.get(r, 0) + deleted.get(r, 0) > 0]
    unexpected_receivers = [r for r in alive if str(r) not in manifest["rowsByReceiver"]]
    check(results, "V6 retention",
          not retention_problems and not inactive_rows and not unexpected_receivers,
          {"problems": retention_problems[:20], "inactiveWithRows": inactive_rows,
           "unexpectedReceivers": unexpected_receivers[:20]})

    schedules = defaultdict(dict)
    for line in read_lines(run / "db-schedules.tsv"):
        kind, status, count = line.split("\t")
        schedules[kind][status] = int(count)
    attempts = schedule_attempts(after)
    check(results, "V7 schedules",
          dict(schedules) == manifest["schedules"] and attempts == {"sent": 0.0, "failed": 0.0},
          {"expected": manifest["schedules"], "actual": dict(schedules), "scheduleAttempts": attempts})

    failures = {}
    for (metric, label_text), value in after.items():
        if metric == "hapbang_notification_consume_failures_total":
            found = labels(label_text)
            if found.get("topic") in ALERT_TOPICS:
                failures[found["exception"]] = failures.get(found["exception"], 0) + value
    for (metric, label_text), value in before.items():
        if metric == "hapbang_notification_consume_failures_total":
            found = labels(label_text)
            if found.get("topic") in ALERT_TOPICS:
                failures[found["exception"]] = failures.get(found["exception"], 0) - value
    failures = {k: int(v) for k, v in failures.items() if v}
    log = read_lines(run / "app.log")
    unexpected_errors = unexpected_error_lines(log)
    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    covered = log_covers(log, timing["alertInjectStartAt"])
    dropped = delta(before, after, "hapbang_notification_realtime_dropped_total")
    check(results, "V8 errors",
          failures == manifest["failures"] and not unexpected_errors and covered and failed == 0 and dropped == 0,
          {"expectedFailures": manifest["failures"], "actualFailures": failures,
           "unexpectedErrorLines": unexpected_errors[:20], "logCoversRound": covered, "droppedDelta": dropped})


def schedule_attempts(after):
    """예약 발송·실패 시도(회차 전체). 앱은 회차마다 새로 떠 지표가 0부터라 끝 스냅샷 값이 회차 전체다(V7)"""
    return {result: metric_sum(after, "hapbang_notification_schedule_outcomes_total", result=result)
            for result in ("sent", "failed")}


def unexpected_error_lines(lines):
    """V8: 알림·Spring Kafka·Kafka 로거의 ERROR 중 의도한 실패 원인이 없는 줄. JSON 로그는 stack_trace가 같은 줄에 있다"""
    unexpected = []
    for line in lines:
        if " ERROR " not in line and '"level":"ERROR"' not in line:
            continue
        if not NOTIFICATION_LOGGER.search(line):
            continue
        if any(e in line for e in INTENDED_EXCEPTIONS):
            continue
        unexpected.append(line[:300])
    return unexpected


def parse_time(text):
    import datetime
    return datetime.datetime.fromisoformat(text).timestamp()


def log_covers(lines, start):
    """V8: 수집한 앱 로그의 첫 기록이 알림 투입 시작 전이어야 한다(로그 회전으로 앞부분을 잃으면 오류를 놓친다)"""
    for line in lines:
        try:
            return parse_time(json.loads(line)["@timestamp"]) <= start
        except (ValueError, KeyError, TypeError):
            continue
    return False


def fingerprint(run):
    rows = sorted(read_lines(run / "db-fingerprint.tsv"))
    return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest(), len(rows)


def latency_quantile(before, after, name, q):
    """histogram 분위수. Prometheus histogram_quantile과 같은 구간 내 선형 보간이다(ND-167).

    순위 q × 표본 수가 드는 구간 (lower, upper]에서 lower + (upper - lower) × (순위 - 앞 구간 누적) / (구간 건수)로
    추정한다. 첫 구간의 lower는 0이다. 레코드별 실제 지연의 정확한 분위수가 아니라 구간 안 분포를 고르다고 본 추정치다.
    분위수가 최상단 유한 경계를 넘어 +Inf 구간에 들면 값을 내지 않고 넘은 건수를 적는다.
    """
    cumulative = Counter()
    for (metric, label_text), value in after.items():
        if metric == name:
            cumulative[labels(label_text)["le"]] += value
    for (metric, label_text), value in before.items():
        if metric == name:
            cumulative[labels(label_text)["le"]] -= value
    ordered = sorted(((float("inf") if le == "+Inf" else float(le), le, count) for le, count in cumulative.items()))
    finite = [bound for bound, _, _ in ordered if bound != float("inf")]
    top = max(finite) if finite else None
    total = ordered[-1][2] if ordered else 0.0
    bucket_counts, previous = {}, 0.0
    for _, le, count in ordered:
        bucket_counts[le] = float(count - previous)
        previous = count
    at_top = next((count for bound, _, count in ordered if bound == top), 0.0)
    report = {"seconds": None, "bucket": None, "aboveTopBucket": False, "topBucketSeconds": top,
              "countAboveTopBucket": float(total - at_top) if top is not None else 0.0, "samples": float(total),
              "bucketCounts": bucket_counts}
    if total <= 0:
        return report
    rank = q * total
    lower, below = 0.0, 0.0
    for bound, _, count in ordered:
        if count >= rank:
            if bound == float("inf"):
                report["aboveTopBucket"] = True
                report["bucket"] = {"lower": lower, "upper": None}
            else:
                width = count - below
                report["seconds"] = lower + (bound - lower) * ((rank - below) / width if width > 0 else 1.0)
                report["bucket"] = {"lower": lower, "upper": bound}
            return report
        lower, below = bound, count
    return report


LATENCY_COUNT_METRIC = "hapbang_notification_consume_latency_seconds_count"


def alert_latency_count(text):
    """Prometheus 본문에서 알림 토픽 5개의 성공 처리 건수(consume.latency count) 합. 회원 토픽은 뺀다(ND-167)"""
    total = 0.0
    for line in text.splitlines():
        match = re.match(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([^\s]+)", line)
        if match and match.group(1) == LATENCY_COUNT_METRIC and labels(match.group(2) or "").get("topic") in ALERT_TOPICS:
            total += float(match.group(3))
    return int(total)


def latency_count(metrics):
    """지표 스냅샷에서 알림 토픽 5개의 성공 처리 건수 합(회원 토픽 제외)"""
    return int(sum(metric_sum(metrics, LATENCY_COUNT_METRIC, topic=t) for t in ALERT_TOPICS))


def verify_phases(results, timing, before, normal, after, expected_normal):
    """V0-7 두 단계 분리(ND-166·ND-167).

    정상 단계(스냅샷 ①~②)에 처리 실패·DLT가 없고, 정상 레코드마다 성공 처리 건수가 정확히 한 번 늘어 expected_normal과 같다.
    실패 단계(②~③)에서는 성공 처리 건수가 늘지 않는다(실패 레코드는 성공한 시도가 없다). 실패 단계는 정상 단계 완료 뒤에 넣었다.
    """
    failures = delta(before, normal, "hapbang_notification_consume_failures_total")
    dead_letters = delta(before, normal, "hapbang_notification_dead_letter_total")
    succeeded = latency_count(normal) - latency_count(before)
    succeeded_in_failure_phase = latency_count(after) - latency_count(normal)
    ordered = timing.get("normalCompletedAt", 0) <= timing.get("failureInjectStartAt", -1)
    check(results, "V0-7 phases",
          failures == 0 and dead_letters == 0 and succeeded == expected_normal and succeeded_in_failure_phase == 0
          and ordered,
          {"normalPhaseFailures": failures, "normalPhaseDeadLetters": dead_letters,
           "normalPhaseSucceeded": succeeded, "expectedNormal": expected_normal,
           "failurePhaseSucceeded": succeeded_in_failure_phase,
           "normalCompletedAt": timing.get("normalCompletedAt"),
           "failureInjectStartAt": timing.get("failureInjectStartAt")})


POLL_INTERVAL_SECONDS = 0.5


def completion_observation(lines, expected):
    """progress.tsv(관측 시각, 정상 단계 성공 건수 증가량, scrape 걸린 초)에서 처음 expected에 닿은 관측 시각과 그때까지의
    실제 polling 간격(최대·평균)과 scrape 시간(ND-167·ND-168). 관측 시각은 지표 응답을 받은 뒤의 시각이다"""
    times, scrapes, reached = [], [], None
    for line in lines:
        if not line.strip():
            continue
        fields = line.split("\t")
        times.append(float(fields[0]))
        if len(fields) > 2:
            scrapes.append(float(fields[2]))
        if int(fields[1]) >= expected:
            reached = float(fields[0])
            break
    gaps = [b - a for a, b in zip(times, times[1:])]
    return {"method": "consume.latency count polling", "configuredIntervalSeconds": POLL_INTERVAL_SECONDS,
            "reachedAt": reached, "polls": len(times),
            "maxPollGapSeconds": round(max(gaps), 3) if gaps else None,
            "meanPollGapSeconds": round(sum(gaps) / len(gaps), 3) if gaps else None,
            "scrapeMaxSeconds": round(max(scrapes), 3) if scrapes else None,
            "scrapeMeanSeconds": round(sum(scrapes) / len(scrapes), 3) if scrapes else None}


def throughput_error(records, elapsed, max_gap):
    """완료는 도달 직전 관측 뒤부터 도달 관측 사이 어딘가라, 소요 시간이 최대 관측 간격만큼 짧았을 수 있다(ND-168)"""
    if not elapsed or max_gap is None:
        return None
    shortest = elapsed - max_gap
    return {"elapsedRangeSeconds": [round(shortest, 3), round(elapsed, 3)],
            "throughputRange": [round(records / elapsed, 2), round(records / shortest, 2) if shortest > 0 else None],
            "relativeError": round(max_gap / elapsed, 4)}


def window(samples, start, end, key):
    values = [s.get(key, 0) for s in samples if start <= s.get("t", 0) <= end]
    return max(values, default=None)


def phase_performance(manifest, timing, samples, before, normal, after, progress=()):
    """단계별 성능. 정상 단계는 스냅샷 ①~②(성능 기준선), 실패 단계는 ②~③(재시도 대기·복구 시간, 기준선에서 뺌).

    consume.latency는 레코드 timestamp부터라 Kafka 대기를 포함한다. 그래서 투입 속도를 함께 적고, 회차끼리는 같은 투입
    도구·환경일 때만 비교한다.
    """
    lat = "hapbang_notification_consume_latency_seconds_bucket"
    phases = manifest["phaseRecordsByTopic"]
    normal_records = sum(phases["normal"].values())
    failure_records = sum(phases["failure"].values())
    cluster = timing.get("clusterClock")
    if cluster:
        # 투입 시작·끝(producer exec 안 date)과 완료(도구 pod polling)를 같은 클러스터 시계로 잰다(ND-169).
        # 호스트 시계 구간(sampler 표본)은 호스트 - 클러스터 차이로 옮겨 고른다
        clock, offset = "cluster", cluster.get("hostMinusClusterSeconds", 0.0)
        start, inject_end, done = (cluster["normalInjectStartAt"], cluster["normalInjectEndAt"],
                                   cluster["normalCompletedAt"])
    else:
        clock, offset = "host", 0.0
        start, inject_end, done = timing["alertInjectStartAt"], timing["normalInjectEndAt"], timing["normalCompletedAt"]
    elapsed = done - start
    inject = inject_end - start
    f_start, f_end = timing["failureInjectStartAt"], timing["completedAt"]
    completion = completion_observation(progress, normal_records)
    return {
        "normal": {
            "records": normal_records,
            "clock": clock,
            "hostMinusClusterSeconds": cluster.get("hostMinusClusterSeconds") if cluster else None,
            "elapsedSeconds": round(elapsed, 3),
            "throughputRecordsPerSecond": round(normal_records / elapsed, 2) if elapsed > 0 else None,
            "injectSeconds": round(inject, 3),
            "injectRecordsPerSecond": round(normal_records / inject, 2) if inject > 0 else None,
            "consumeLatencyP95": latency_quantile(before, normal, lat, 0.95),
            "consumeLatencyP99": latency_quantile(before, normal, lat, 0.99),
            "gcPauseSeconds": delta(before, normal, "jvm_gc_pause_seconds_sum"),
            "gcPauseCount": delta(before, normal, "jvm_gc_pause_seconds_count"),
            "hikariTimeouts": delta(before, normal, "hikaricp_connections_timeout_total"),
            "hikariActiveMax": window(samples, start + offset, done + offset, "hikariActive"),
            "hikariPendingMax": window(samples, start + offset, done + offset, "hikariPending"),
            "cpuMillicoresMax": window(samples, start + offset, done + offset, "cpuMillicores"),
            "memoryMiBMax": window(samples, start + offset, done + offset, "memoryMiB"),
            "completion": completion,
            "throughputError": throughput_error(normal_records, elapsed, completion["maxPollGapSeconds"]),
        },
        "failure": {
            "records": failure_records,
            "recoverySeconds": round(f_end - f_start, 3),
            "consumeFailures": delta(normal, after, "hapbang_notification_consume_failures_total"),
            "deadLettersPublished": delta(normal, after, "hapbang_notification_dead_letter_total", result="published"),
            "consumeLatencyP95": latency_quantile(normal, after, lat, 0.95),
            "consumeLatencyP99": latency_quantile(normal, after, lat, 0.99),
        },
    }


def main():
    manifest_path, run_dir = Path(sys.argv[1]), Path(sys.argv[2])
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["_fixtureDir"] = str(manifest_path.parent)
    results = {}
    verify_v0(manifest, run_dir, results)
    output = {"valid": False, "checks": results}
    before = parse_prometheus(run_dir / "metrics-before.prom")
    normal = parse_prometheus(run_dir / "metrics-normal.prom")
    after = parse_prometheus(run_dir / "metrics-after.prom")
    timing = json.loads((run_dir / "timing.json").read_text(encoding="utf-8"))
    verify_phases(results, timing, before, normal, after, sum(manifest["phaseRecordsByTopic"]["normal"].values()))
    v0_ok = all(r["ok"] for name, r in results.items() if name.startswith("V0"))
    if v0_ok:
        verify_counts(manifest, run_dir, results, before, after)
        fp, rows = fingerprint(run_dir)
        output["fingerprint"] = fp
        output["fingerprintRows"] = rows
        samples = [json.loads(l) for l in read_lines(run_dir / "samples.jsonl")]
        progress = read_lines(run_dir / "progress.tsv")
        output["performance"] = phase_performance(manifest, timing, samples, before, normal, after, progress)
        output["valid"] = all(r["ok"] for r in results.values())
    (run_dir / "result.json").write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    for name, r in results.items():
        print(("PASS " if r["ok"] else "FAIL ") + name)
    if "fingerprint" in output:
        print("F " + output["fingerprint"] + f" ({output['fingerprintRows']} rows)")
    sys.exit(0 if output["valid"] else (3 if not v0_ok else 1))


if __name__ == "__main__":
    main()
