"""verify_n15.py 단위 시험(N15, test-spec NT-LOAD-L4·L5S·L5B·L5M·L5F). 실행: python -I -m unittest discover -s loadtest/notification/tests"""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import verify_n15  # noqa: E402

KST = "+09:00"


def datetime_iso(epoch):
    import datetime
    return datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).isoformat()


def log(ts, message, level="INFO", logger="org.springframework.kafka.listener.KafkaMessageListenerContainer"):
    return json.dumps({"@timestamp": ts, "message": message, "logger_name": logger, "level": level},
                      ensure_ascii=False)


class L4AssignmentTest(unittest.TestCase):

    def test_다섯_group의_첫_할당_시각을_읽고_빈_할당과_다른_group은_뺀다(self):
        lines = [
            log("2026-11-16T12:00:01.5+09:00", "auxiliary-app.chatbot-appearance-copy: partitions assigned: [a-0]"),
            log("2026-11-16T12:00:02+09:00", "auxiliary-app.notification-billing-alert: partitions assigned: []"),
            log("2026-11-16T12:00:03+09:00",
                "auxiliary-app.notification-billing-alert: partitions assigned: [billing.events.v1-0]"),
            log("2026-11-16T12:00:04+09:00",
                "auxiliary-app.notification-billing-alert: partitions assigned: [billing.events.v1-1]"),
        ]
        times = verify_n15.assignment_times(lines, ["auxiliary-app.notification-billing-alert",
                                                    "auxiliary-app.notification-chat-alert"])
        self.assertEqual(times["auxiliary-app.notification-billing-alert"],
                         verify_n15.parse_time("2026-11-16T12:00:03+09:00"))
        self.assertNotIn("auxiliary-app.notification-chat-alert", times)

    def test_lag_0_첫_관측은_기준_시각_뒤이고_모든_group이_보인_줄만_센다(self):
        rows = ["100.0\t5\t5", "101.0\t0\t4", "102.0\t0\t5", "103.0\t0\t5"]
        self.assertEqual(verify_n15.lag_zero_at(rows, 101.5, groups=5), 102.0)
        self.assertIsNone(verify_n15.lag_zero_at(rows[:2], 99.0, groups=5))

    def test_회복_판정은_할당_완료부터_lag_0까지_300초_이하다(self):
        ok = verify_n15.l4_recovery({"g1": 10.0, "g2": 12.0}, lag_zero=311.9, groups=2)
        self.assertTrue(ok["ok"])
        self.assertAlmostEqual(ok["detail"]["recoverySeconds"], 299.9)
        late = verify_n15.l4_recovery({"g1": 10.0, "g2": 12.0}, lag_zero=312.1, groups=2)
        self.assertFalse(late["ok"])
        missing = verify_n15.l4_recovery({"g1": 10.0}, lag_zero=20.0, groups=2)
        self.assertFalse(missing["ok"])

    def test_두_pod의_지표를_더한다(self):
        a = {("m", '{topic="t"}'): 2.0, ("n", ""): 1.0}
        b = {("m", '{topic="t"}'): 3.0}
        self.assertEqual(verify_n15.add_metrics(a, b), {("m", '{topic="t"}'): 5.0, ("n", ""): 1.0})

    def test_로그_범위는_재개_pod의_첫_줄이_첫_할당보다_앞서야_한다(self):
        lines = ["not json", log("2026-11-16T12:00:00+09:00", "Starting")]
        self.assertTrue(verify_n15.log_starts_before(lines, verify_n15.parse_time("2026-11-16T12:00:01+09:00")))
        self.assertFalse(verify_n15.log_starts_before(lines, verify_n15.parse_time("2026-11-16T11:59:59+09:00")))


class L5ExpectationTest(unittest.TestCase):

    def test_시나리오별_기대_회차와_최대_지연(self):
        self.assertEqual(verify_n15.l5_expectation(100, 0), {"runs": 1, "maxDelayMinutes": 2})
        self.assertEqual(verify_n15.l5_expectation(101, 0), {"runs": 2, "maxDelayMinutes": 3, "byRun": {"1": 100, "2": 1}})
        self.assertEqual(verify_n15.l5_expectation(1_000, 0), {"runs": 10, "maxDelayMinutes": 11})
        self.assertEqual(verify_n15.l5_expectation(990, 10), {"runs": 11, "maxDelayMinutes": 12})

    def test_회차_번호는_첫_회차_발화_시각부터_분_단위다(self):
        first = 1_000.0
        self.assertEqual(verify_n15.run_index(1_000.2, first), 1)
        self.assertEqual(verify_n15.run_index(1_059.9, first), 1)
        self.assertEqual(verify_n15.run_index(1_060.1, first), 2)

    def test_행은_처리_뒤_처음_찍힌_회차_로그의_회차에_속한다(self):
        # L5-M 1회차: 8회차 첫 행이 계산상 발화(:35.000)보다 1ms 일찍 처리됐다(스케줄러 발화 오차). 회차 끝 로그로 나눈다
        logs = [{"run": 7, "end": 360.4}, {"run": 8, "end": 420.3}]
        self.assertEqual(verify_n15.row_run(419.999, logs), 8)
        self.assertEqual(verify_n15.row_run(360.4, logs), 7)
        self.assertEqual(verify_n15.row_run(300.0, logs), 7)
        self.assertIsNone(verify_n15.row_run(421.0, logs))

    def test_다음_회차_경계_1초_안에_끝난_긴_회차는_그_회차로_세고_실행_시간을_바르게_낸다(self):
        first = 1_000.0
        lines = [json.dumps({"@timestamp": datetime_iso(first + 0.3), "message": "예약 배치를 실행했다 due=1"}),
                 # 2회차가 59.5초 걸려 3회차 발화 0.5초 전에 끝났다
                 json.dumps({"@timestamp": datetime_iso(first + 60 + 59.5), "message": "예약 배치를 실행했다 due=1"})]
        logs = verify_n15.run_logs(lines, first)
        self.assertEqual([l["run"] for l in logs], [1, 2])
        self.assertAlmostEqual(logs[1]["duration"], 59.5, places=3)

    def test_첫_회차는_보낼_시각_뒤_첫_35초다(self):
        # 2026-11-16T18:00:00+09:00
        send_at = verify_n15.parse_time("2026-11-16T18:00:00+09:00")
        self.assertEqual(verify_n15.first_fire(send_at), send_at + 35)
        self.assertEqual(verify_n15.first_fire(send_at + 40), send_at + 95)


class L5JudgeTest(unittest.TestCase):

    T = verify_n15.parse_time("2026-11-16T18:00:00+09:00")

    def row(self, target, status, processed_offset, failures=0, receiver=1, schedule_id=None):
        return {"id": schedule_id or target, "target": target, "receiver": receiver, "status": status,
                "sendAt": self.T, "processedAt": None if processed_offset is None else self.T + processed_offset,
                "failureCount": failures}

    def base(self, rows, **kw):
        data = {"normalTargets": [r["target"] for r in rows if r["status"] == "SENT"], "failingTargets": [],
                "expiredTargets": [], "expiredDeadline": None, "rows": rows,
                "runLogs": [], "failureLogs": {}, "errorLines": 0, "abandoned": 0, "retry": 0,
                "notifications": {r["target"]: 1 for r in rows if r["status"] == "SENT"},
                "replicaChecks": [0], "runsFailed": 0}
        data.update(kw)
        return data

    def run_log(self, run, duration=1.0):
        # 회차 로그는 회차가 끝날 때 찍힌다(발화 = 보낼 시각 + 35초 + 60 × (회차 - 1))
        return {"run": run, "duration": duration, "end": self.T + 35 + 60 * (run - 1) + duration}

    def test_S_100건이_1회차에_끝나면_통과(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        result = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1)]))
        self.assertTrue(all(c["ok"] for c in result.values()), result)

    def test_B_101번째가_2회차에_가면_통과하고_3회차면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)] + [self.row(101, "SENT", 95.5)]
        ok = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1), self.run_log(2)]))
        self.assertTrue(all(c["ok"] for c in ok.values()), ok)
        rows[-1] = self.row(101, "SENT", 155.5)
        late = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1), self.run_log(2), self.run_log(3)]))
        self.assertFalse(late["L5 runs"]["ok"])

    def test_B_1회차가_100건이_아니면_실패한다(self):
        # NT-LOAD-L5B: 1회차 100건·2회차 1건(ND-192). 마지막 회차가 2여도 분포가 99/2면 실패
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 100)] + [self.row(i, "SENT", 95.5) for i in (100, 101)]
        result = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1), self.run_log(2)]))
        self.assertFalse(result["L5 runs"]["ok"], result["L5 runs"])
        self.assertEqual(result["L5 runs"]["detail"]["expectedByRun"], {"1": 100, "2": 1})

    def test_M은_회차별_분포를_판정하지_않는다(self):
        # NT-LOAD-L5M은 "10회차 안에 모두"만 정한다
        rows = [self.row(i, "SENT", 35.5 + 60 * ((i - 1) // 125)) for i in range(1, 1_001)]
        result = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(r) for r in range(1, 11)]))
        self.assertTrue(result["L5 runs"]["ok"], result["L5 runs"])

    def test_회차가_빠지거나_60초를_넘으면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)] + [self.row(101, "SENT", 95.5)]
        gap = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(2)]))
        self.assertFalse(gap["L5 missed runs"]["ok"])
        slow = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1, 61.0), self.run_log(2)]))
        self.assertFalse(slow["L5 run duration"]["ok"])

    def test_보낼_시각_전_회차_로그는_회차_누락_판정에서_빼고_따로_남긴다(self):
        # L5-F: 앱을 다시 띄운 뒤 보낼 시각 전 회차가 기한 지남 예약을 건너뜀으로 끝내며 로그를 남겼다(회차 번호 -3)
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        logs = [{"run": -3, "duration": 0.01, "end": self.T - 205}, self.run_log(1)]
        result = verify_n15.judge_l5(self.base(rows, runLogs=logs))
        self.assertTrue(result["L5 missed runs"]["ok"], result["L5 missed runs"])
        self.assertEqual(result["L5 missed runs"]["detail"]["preWindowRuns"], [-3])

    def test_중복_누락이_있으면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        data = self.base(rows, runLogs=[self.run_log(1)])
        data["notifications"][5] = 2
        del data["notifications"][6]
        result = verify_n15.judge_l5(data)
        self.assertFalse(result["L5 duplicates and missing"]["ok"])

    def test_F_실패_예약은_첫_시도_회차_2회차_뒤에_건너뜀이고_ERROR와_abandoned가_각_10(self):
        normal = [self.row(i, "SENT", 35.5 + 60 * ((i - 1) // 93)) for i in range(1, 991)]
        failing = [self.row(2_000 + k, "SKIPPED", 35.5 + 60 * (k + 2), failures=3, receiver=9, schedule_id=5_000 + k)
                   for k in range(10)]
        failure_logs = {5_000 + k: self.T + 35.5 + 60 * k for k in range(10)}
        # 정상은 11회차에 끝나고, 마지막 실패 예약(10회차 첫 시도)은 12회차에 건너뜀으로 끝난다
        runs = [self.run_log(r) for r in range(1, 13)]
        data = self.base(normal + failing, normalTargets=[r["target"] for r in normal],
                         failingTargets=[r["target"] for r in failing], runLogs=runs, failureLogs=failure_logs,
                         errorLines=10, abandoned=10, retry=20)
        result = verify_n15.judge_l5(data)
        self.assertTrue(all(c["ok"] for c in result.values()), result)
        # retry 수는 판정하지 않고 기록만 한다(NT-LOAD-L5F 기준은 ERROR·abandoned)
        data["retry"] = 19
        self.assertTrue(verify_n15.judge_l5(data)["L5F failing schedules"]["ok"])
        self.assertEqual(verify_n15.judge_l5(data)["L5F failing schedules"]["detail"]["retry"], 19)
        # 한 건이 4번째 시도 회차에 끝나면 실패
        data["rows"][-1] = self.row(2_009, "SKIPPED", 35.5 + 60 * 12, failures=3, receiver=9, schedule_id=5_009)
        self.assertFalse(verify_n15.judge_l5(data)["L5F failing schedules"]["ok"])

    def test_F_복제본이_다시_생기면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        result = verify_n15.judge_l5(self.base(rows, runLogs=[self.run_log(1)], replicaChecks=[0, 0, 1]))
        self.assertFalse(result["L5F failing replicas absent"]["ok"])

    def test_F_실패_대상이_있는데_복제본_확인_기록이_없으면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        failing = [self.row(2_000, "SKIPPED", 155.5, failures=3, receiver=9, schedule_id=5_000)]
        data = self.base(rows + failing, normalTargets=list(range(1, 101)), failingTargets=[2_000],
                         runLogs=[self.run_log(r) for r in range(1, 4)], failureLogs={5_000: self.T + 35.5},
                         errorLines=1, abandoned=1, replicaChecks=[])
        result = verify_n15.judge_l5(data)
        self.assertFalse(result["L5F failing replicas absent"]["ok"])
        self.assertEqual(result["L5F failing replicas absent"]["detail"]["checks"], 0)

    def test_F_실패_예약의_첫_시도나_끝난_시각을_모르면_실패(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        failing = [self.row(2_000, "SKIPPED", None, failures=3, receiver=9, schedule_id=5_000)]
        data = self.base(rows + failing, normalTargets=list(range(1, 101)), failingTargets=[2_000],
                         runLogs=[self.run_log(r) for r in range(1, 4)], failureLogs={},
                         errorLines=1, abandoned=1)
        self.assertFalse(verify_n15.judge_l5(data)["L5F failing schedules"]["ok"])

    def test_F_기한_지남_예약은_기한_뒤_건너뜀이고_알림이_없어야_한다(self):
        rows = [self.row(i, "SENT", 35.5) for i in range(1, 101)]
        deadline = self.T - 600
        expired = [{"id": 7_000, "target": 7_000, "receiver": 1, "status": "SKIPPED", "sendAt": deadline - 43_200,
                    "processedAt": deadline + 30, "failureCount": 0}]
        data = self.base(rows + expired, runLogs=[self.run_log(1)], expiredTargets=[7_000], expiredDeadline=deadline)
        self.assertTrue(verify_n15.judge_l5(data)["L5F expired schedules"]["ok"])
        expired[0]["processedAt"] = deadline - 1
        self.assertFalse(verify_n15.judge_l5(data)["L5F expired schedules"]["ok"])


class GroupLagPollerTest(unittest.TestCase):
    """도구 pod 안 lag 관측(k3d/tools/poll_group_lag.sh, ND-190): 커밋 전 partition은 LOG-END 전체를 lag로 센다"""

    HEADER = "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n"

    def run_poller(self, describe_text, groups, extra=1, deadline=2):
        import os
        import shutil
        import subprocess
        import tempfile
        work = Path(tempfile.mkdtemp())
        (work / "describe.txt").write_text(describe_text, encoding="utf-8")
        fake = work / "kafka-consumer-groups.sh"
        fake.write_text(f"#!/usr/bin/env bash\ncat '{(work / 'describe.txt').as_posix()}'\n", encoding="utf-8")
        fake.chmod(0o755)
        env = dict(os.environ)
        env.update({"KAFKA_BIN": work.as_posix(), "N15_EXTRA_POLLS": str(extra),
                    "N15_DEADLINE_SECONDS": str(deadline)})
        script = (HERE / "k3d" / "tools" / "poll_group_lag.sh").as_posix()
        bash = shutil.which("bash")
        return subprocess.run([bash, script, "b:9092", *groups], env=env, capture_output=True, timeout=30)

    def test_모든_group이_커밋해_lag_0이면_끝난다(self):
        text = self.HEADER + "g1 t 0 5 5 0 - - -\ng1 t 1 3 3 0 - - -\ng2 u 0 7 7 0 - - -\n"
        result = self.run_poller(text, ["g1", "g2"])
        self.assertEqual(result.returncode, 0, result.stderr)
        _, lag, seen, _ = result.stdout.decode("utf-8").splitlines()[0].split("\t")
        self.assertEqual((lag, seen), ("0", "2"))

    def test_커밋_전_partition은_LOG_END_전체를_lag로_세어_0으로_보지_않는다(self):
        text = self.HEADER + "g1 t 0 5 5 0 - - -\ng1 t 1 - 4 - - - -\ng2 u 0 7 7 0 - - -\n"
        result = self.run_poller(text, ["g1", "g2"], deadline=0)
        self.assertEqual(result.returncode, 3)
        _, lag, seen, _ = result.stdout.decode("utf-8").splitlines()[0].split("\t")
        self.assertEqual((lag, seen), ("4", "2"))

    def test_다른_group_행과_보이지_않는_group은_세지_않는다(self):
        text = self.HEADER + "g1 t 0 5 5 0 - - -\nother t 0 0 9 9 - - -\n"
        result = self.run_poller(text, ["g1", "g2"], deadline=0)
        self.assertEqual(result.returncode, 3)
        _, lag, seen, _ = result.stdout.decode("utf-8").splitlines()[0].split("\t")
        self.assertEqual((lag, seen), ("0", "1"))


class L3JudgeTest(unittest.TestCase):
    """L3 저장→전달 판정(ND-193): 수신 시각 − created_at, 알림 ID로 맞춤, 유실률 < 0.1%, 중복·모르는 ID 따로"""

    @staticmethod
    def rows(n, created=100.0):
        return {i: created for i in range(1, n + 1)}

    def test_지연은_수신_ms를_초로_바꿔_created_at을_빼고_p95_p99를_낸다(self):
        rows = self.rows(100)
        received = [f"L3RECV {i} {int((100.0 + i / 100) * 1000)} 1" for i in range(1, 101)]
        result = verify_n15.judge_l3(rows, received)
        self.assertTrue(result["L3 latency"]["ok"], result)
        self.assertAlmostEqual(result["L3 latency"]["detail"]["p95Seconds"], 0.95, places=3)
        self.assertAlmostEqual(result["L3 latency"]["detail"]["p99Seconds"], 0.99, places=3)

    def test_p95가_1초를_넘으면_실패(self):
        rows = self.rows(100)
        received = [f"L3RECV {i} {int((100.0 + (2.0 if i > 90 else 0.1)) * 1000)} 1" for i in range(1, 101)]
        self.assertFalse(verify_n15.judge_l3(rows, received)["L3 latency"]["ok"])

    def test_유실률은_저장된_알림_중_받지_못한_고유_알림이고_0_1퍼센트_이상이면_실패(self):
        rows = self.rows(2_000)
        received = [f"L3RECV {i} 100100 1" for i in range(1, 1_999)]  # 2건 못 받음 = 0.1%
        result = verify_n15.judge_l3(rows, received)
        self.assertFalse(result["L3 loss"]["ok"])
        self.assertEqual(result["L3 loss"]["detail"]["missing"], 2)
        received.append("L3RECV 1999 100100 1")
        self.assertTrue(verify_n15.judge_l3(rows, received)["L3 loss"]["ok"])

    def test_중복_수신과_저장에_없는_ID는_따로_세고_지연에는_첫_수신만_쓴다(self):
        rows = self.rows(3)
        received = ["L3RECV 1 100100 1", "L3RECV 1 109000 2", "L3RECV 2 100200 1", "L3RECV 3 100300 1",
                    "L3RECV 99 100300 1", "noise"]
        result = verify_n15.judge_l3(rows, received)
        # 중복 수신·저장에 없는 ID(측정 제외)는 판정이 아니라 따로 센다(NT-LOAD-L3)
        self.assertEqual(set(result), {"L3 latency", "L3 loss"})
        counts = verify_n15.l3_receive_counts(rows, received)
        self.assertEqual(counts["duplicateReceives"], 1)
        self.assertEqual(counts["excludedIds"], [99])
        self.assertLess(result["L3 latency"]["detail"]["maxSeconds"], 1.0)

    def test_k6_콘솔_로그_형식에서도_표지를_찾는다(self):
        rows = self.rows(1)
        line = 'time="2026-11-16T07:00:00Z" level=info msg="L3RECV 1 100250 7" source=console'
        result = verify_n15.judge_l3(rows, [line])
        self.assertEqual(result["L3 loss"]["detail"]["receivedUnique"], 1)
        self.assertAlmostEqual(result["L3 latency"]["detail"]["p95Seconds"], 0.25, places=3)


class HistogramQuantileTest(unittest.TestCase):
    """라벨 없는 histogram(실시간 전달 구간 지표, ND-162)의 분위수: 구간 증가량으로 선형 보간"""

    def test_전후_차이로_구간_안을_선형_보간한다(self):
        name = "hapbang_notification_realtime_send_latency_seconds"
        before = {(name + "_bucket", '{le="0.01"}'): 0.0, (name + "_bucket", '{le="0.025"}'): 0.0,
                  (name + "_bucket", '{le="+Inf"}'): 0.0}
        after = {(name + "_bucket", '{le="0.01"}'): 90.0, (name + "_bucket", '{le="0.025"}'): 100.0,
                 (name + "_bucket", '{le="+Inf"}'): 100.0}
        result = verify_n15.histogram_quantile(before, after, name, 0.95)
        self.assertEqual(result["samples"], 100)
        self.assertAlmostEqual(result["seconds"], 0.0175, places=4)


class PaceTest(unittest.TestCase):
    """투입 속도 조절(k3d/tools/pace.sh): 줄을 모두 순서대로 내고, 초당 rate줄을 넘지 않는다"""

    def test_줄을_빠짐없이_순서대로_내고_정한_속도보다_빠르지_않다(self):
        import shutil
        import subprocess
        import time
        lines = "".join(f"L{i}\n" for i in range(1, 31))
        start = time.monotonic()
        result = subprocess.run([shutil.which("bash"), (HERE / "k3d" / "tools" / "pace.sh").as_posix(), "10"],
                                input=lines.encode("utf-8"), capture_output=True, timeout=30)
        elapsed = time.monotonic() - start
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode("utf-8"), lines)
        # 30줄을 초당 10줄이면 첫 10줄은 바로, 나머지는 2초 뒤까지 → 2초 이상 3.5초 미만
        self.assertGreaterEqual(elapsed, 2.0)
        self.assertLess(elapsed, 3.5)


class ClockMarkTest(unittest.TestCase):
    """투입 시계 표지 읽기(n15.sh phase_clock): pipefail·errexit 아래에서 큰 stderr여도 SIGPIPE로 끝나지 않는다(L4 1회차 141 종료)"""

    def run_phase_clock(self, files):
        import os
        import shutil
        import subprocess
        import tempfile
        work = Path(tempfile.mkdtemp())
        for name, lines in files.items():
            (work / name).write_text("".join(l + "\n" for l in lines), encoding="utf-8")
        script = (f"set -euo pipefail; source '{(HERE / 'n15.sh').as_posix()}'; "
                  f"s=$(phase_clock '{work.as_posix()}' normal start); e=$(phase_clock '{work.as_posix()}' normal end); "
                  "echo \"$s $e\"")
        return subprocess.run([shutil.which("bash"), "-c", script], capture_output=True, timeout=60,
                              env=dict(os.environ))

    def test_큰_stderr에서도_첫_토픽의_첫_시작과_마지막_토픽의_마지막_끝을_낸다(self):
        many = [f"B0_CLOCK_START {i}" for i in range(1, 30_000)]
        result = self.run_phase_clock({
            "producer-appearance.events.v1.normal.stderr": ["B0_CLOCK_START 10.5", *many, "B0_CLOCK_END 11.0"],
            "producer-billing.events.v1.normal.stderr": ["B0_CLOCK_START 12.0", "B0_CLOCK_END 13.0"],
            "producer-lecture.events.v1.normal.stderr": ["B0_CLOCK_START 14.0", "B0_CLOCK_END 15.5"],
        })
        self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", "replace"))
        self.assertEqual(result.stdout.decode("utf-8").split(), ["10.5", "15.5"])


class L3K6PodTest(unittest.TestCase):
    """L3 k6 pod(L3 1회차): k3d image import는 Docker Desktop containerd 저장소의 다중 플랫폼 이미지에서 노드 가져오기가 실패해도
    성공으로 끝난다. 노드가 버전 고정 이미지를 직접 받게 하고, pod가 실패하면 지우기 전에 상태·로그를 남긴다"""

    def test_k6_이미지는_노드가_직접_받고_k3d_가져오기를_쓰지_않는다(self):
        text = (HERE / "n15.sh").read_text(encoding="utf-8")
        run_l3 = text[text.index("run_l3() {"):text.index("main() {")]
        self.assertIn("imagePullPolicy: IfNotPresent", run_l3)
        self.assertNotIn("k3d image import", run_l3)
        self.assertNotIn("imagePullPolicy: Never", run_l3)

    def test_k6_pod_실패는_지우기_전에_상태와_로그를_남긴다(self):
        text = (HERE / "n15.sh").read_text(encoding="utf-8")
        run_l3 = text[text.index("run_l3() {"):text.index("main() {")]
        self.assertIn("k6-describe.txt", run_l3)


class N15GuardTest(unittest.TestCase):
    """n15.sh는 B0 cluster 말고는 거절한다(운영 hapbang-k3s에 쓰지 않음, ND-155)"""

    def test_운영_context는_아무것도_하지_않고_거절한다(self):
        import os
        import shutil
        import subprocess
        env = dict(os.environ)
        env["N15_CONTEXT"] = "hapbang-k3s"
        for args in (["--dry-run", "l4", "1"], ["l4", "1"], ["l5", "S", "1"]):
            result = subprocess.run([shutil.which("bash"), (HERE / "n15.sh").as_posix(), *args], env=env,
                                    capture_output=True, timeout=60)
            self.assertEqual(result.returncode, 2, args)
            self.assertIn("k3d-hapbang-b0만 허용", result.stderr.decode("utf-8"))
            self.assertNotIn("kubectl --context hapbang-k3s", result.stderr.decode("utf-8"))


class SyncCommitArgTest(unittest.TestCase):
    """N17-3 커밋 설정 인자(ND-217, test-spec NT-B0-12): n15.sh도 b0.sh와 같은 규칙이다"""

    SETTING_ON = "(모든 역할)@auxiliary=synchronous_commit=on"

    def bash(self, code, **env_extra):
        import os
        import shutil
        import subprocess
        env = dict(os.environ)
        env["MSYS_NO_PATHCONV"] = "1"
        env.pop("B0_SYNC_COMMIT", None)
        env.update(env_extra)
        return subprocess.run([shutil.which("bash"), "-c", code], env=env, capture_output=True, timeout=60)

    def source(self):
        # bash -c로 불러오면 $0이 bash라 DIR이 스크립트 위치가 아니다. 직접 실행할 때와 같은 DIR로 맞춘다
        return f'source "{(HERE / "n15.sh").as_posix()}"\nDIR="{HERE.as_posix()}"\n'

    def test_허용하지_않는_값은_context_검사보다_먼저_거절한다(self):
        result = self.bash(f'bash "{(HERE / "n15.sh").as_posix()}" --dry-run l4 1', B0_SYNC_COMMIT="yes",
                           N15_CONTEXT="hapbang-k3s")
        self.assertEqual(result.returncode, 2)
        stderr = result.stderr.decode("utf-8")
        self.assertIn("B0_SYNC_COMMIT", stderr)
        self.assertNotIn("k3d-hapbang-b0만 허용", stderr)

    def test_확인_함수는_앱_역할_연결_값까지_본다(self):
        ok = self.bash(self.source() + 'check_commit_setting on on on "$SETTING"', SETTING=self.SETTING_ON)
        self.assertEqual(ok.returncode, 0, ok.stderr.decode("utf-8"))
        bad = self.bash(self.source() + 'check_commit_setting on on off "$SETTING"', SETTING=self.SETTING_ON)
        self.assertEqual(bad.returncode, 2)

    def test_dry_run_적용은_지정한_값을_psql_변수로_넘기고_기본은_off다(self):
        on = self.bash(self.source() + "DRY=1; apply_commit_setting", B0_SYNC_COMMIT="on")
        self.assertIn("sync_commit=on", on.stderr.decode("utf-8"))
        default = self.bash(self.source() + "DRY=1; apply_commit_setting")
        self.assertIn("sync_commit=off", default.stderr.decode("utf-8"))


if __name__ == "__main__":
    unittest.main()
