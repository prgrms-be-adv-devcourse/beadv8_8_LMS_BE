"""normalize_dump.py·verify_b0.py 단위 시험(N13, test-spec NT-B0-10). 실행: python -I -m unittest discover -s loadtest/notification/tests"""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import verify_b0  # noqa: E402


def normalize(topic, raw):
    result = subprocess.run([sys.executable, "-I", str(HERE / "normalize_dump.py"), topic], input=raw.encode("utf-8"),
                            capture_output=True, check=True)
    return result.stdout.decode("utf-8").splitlines()


class NormalizeDumpTest(unittest.TestCase):

    def test_원본_레코드는_한_줄로_헤더_key_본문을_남긴다(self):
        raw = "Partition:2<<B0SEP>>Offset:7<<B0SEP>>__TypeId__:com.x.Ev<<B0SEP>>point-charge:1<<B0SEP>>{\"a\":1}<<B0EOR>>"
        self.assertEqual(normalize("billing.events.v1", raw),
                         ["billing.events.v1\t2\t7\t__TypeId__:com.x.Ev\tpoint-charge:1\t{\"a\":1}"])

    def test_DLT_헤더의_줄바꿈_탭_쉼표가_든_stacktrace는_키만_남기고_예외_클래스는_값을_남긴다(self):
        raw = ("Partition:0<<B0SEP>>Offset:0<<B0SEP>>__TypeId__:com.x.Ev<<B0HDR>>"
               "kafka_dlt-exception-fqcn:org.springframework.kafka.listener.ListenerExecutionFailedException<<B0HDR>>"
               "kafka_dlt-exception-cause-fqcn:com.h.NotificationException<<B0HDR>>"
               "kafka_dlt-exception-stacktrace:a,b\n\tat x(y:1)<<B0SEP>>k<<B0SEP>>v<<B0EOR>>")
        lines = normalize("t.DLT", raw)
        self.assertEqual(len(lines), 1)
        headers = lines[0].split("\t")[3]
        self.assertIn("kafka_dlt-exception-cause-fqcn:com.h.NotificationException", headers)
        self.assertIn("kafka_dlt-exception-stacktrace:", headers)
        self.assertNotIn("at x", headers)

    def test_출력은_콘솔_기본_인코딩과_무관하게_UTF8과_LF다(self):
        # Windows 콘솔 기본(cp949, CRLF)을 강제로 흉내 낸다. -I는 PYTHONIOENCODING을 무시하므로 여기서만 빼고 실행한다
        raw = "Partition:0<<B0SEP>>Offset:0<<B0SEP>>__TypeId__:com.x.Ev<<B0SEP>>9000001<<B0SEP>>{\"nickname\":\"회원1\"}<<B0EOR>>"
        result = subprocess.run([sys.executable, str(HERE / "normalize_dump.py"), "member.events.v1"],
                                input=raw.encode("utf-8"), capture_output=True, check=True,
                                env={"PYTHONIOENCODING": "cp949", "SYSTEMROOT": __import__("os").environ.get("SYSTEMROOT", "")})
        self.assertEqual(result.stdout,
                         "member.events.v1\t0\t0\t__TypeId__:com.x.Ev\t9000001\t{\"nickname\":\"회원1\"}\n".encode("utf-8"))


class VerifyV0Test(unittest.TestCase):

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.fixture = self.dir / "fixture"
        self.run = self.dir / "run"
        self.fixture.mkdir()
        self.run.mkdir()
        self.d1 = verify_b0.digest("k1", "T", "v1")
        self.d2 = verify_b0.digest("k1", "T", "v2")
        (self.fixture / "alert-order.json").write_text(json.dumps([self.d1, self.d2]), encoding="utf-8")
        self.manifest = {"_fixtureDir": str(self.fixture), "replicas": 1,
                         "partitionSequences": {"billing.events.v1": {"1": [self.d1, self.d2]}},
                         "orderedPairs": [{"firstTopic": "billing.events.v1", "firstIndex": 0,
                                           "secondTopic": "billing.events.v1", "secondIndex": 1}]}
        stderr = "ProducerConfig values:\n" + "\n".join(verify_b0.REQUIRED_CONFIG)
        (self.run / "producer-billing.events.v1.stderr").write_text(stderr, encoding="utf-8")
        (self.run / "timing.json").write_text(json.dumps(
            {"replicas": 1, "memberLag": 0, "barrierAt": 1.0, "alertInjectStartAt": 2.0}), encoding="utf-8")

    def write_topics(self, rows):
        (self.run / "topics.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")

    def test_순서와_위치가_같으면_통과한다(self):
        self.write_topics(["billing.events.v1\t1\t0\t__TypeId__:T\tk1\tv1", "billing.events.v1\t1\t1\t__TypeId__:T\tk1\tv2"])
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertTrue(all(r["ok"] for r in results.values()), results)

    def test_같은_key_쌍의_순서가_뒤바뀌면_실패한다(self):
        self.write_topics(["billing.events.v1\t1\t0\t__TypeId__:T\tk1\tv2", "billing.events.v1\t1\t1\t__TypeId__:T\tk1\tv1"])
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertFalse(results["V0-1/2 records"]["ok"])
        self.assertFalse(results["V0-3 ordered pairs"]["ok"])

    def test_다른_파티션에_들어가면_실패한다(self):
        self.write_topics(["billing.events.v1\t0\t0\t__TypeId__:T\tk1\tv1", "billing.events.v1\t0\t1\t__TypeId__:T\tk1\tv2"])
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertFalse(results["V0-1/2 records"]["ok"])

    def test_헤더가_더_있으면_실패한다(self):
        self.write_topics(["billing.events.v1\t1\t0\t__TypeId__:T,extra:\tk1\tv1",
                           "billing.events.v1\t1\t1\t__TypeId__:T\tk1\tv2"])
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertFalse(results["V0-4 headers"]["ok"])

    def test_producer_설정이_빠지거나_오류가_있으면_실패한다(self):
        self.write_topics(["billing.events.v1\t1\t0\t__TypeId__:T\tk1\tv1", "billing.events.v1\t1\t1\t__TypeId__:T\tk1\tv2"])
        (self.run / "producer-billing.events.v1.stderr").write_text(
            "ProducerConfig values:\n acks = 1\n[x] ERROR boom\n", encoding="utf-8")
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertFalse(results["V0-5 producer"]["ok"])

    def test_단계_장벽보다_알림_투입이_먼저면_실패한다(self):
        self.write_topics(["billing.events.v1\t1\t0\t__TypeId__:T\tk1\tv1", "billing.events.v1\t1\t1\t__TypeId__:T\tk1\tv2"])
        (self.run / "timing.json").write_text(json.dumps(
            {"replicas": 1, "memberLag": 0, "barrierAt": 3.0, "alertInjectStartAt": 2.0}), encoding="utf-8")
        results = {}
        verify_b0.verify_v0(self.manifest, self.run, results)
        self.assertFalse(results["V0-6 barrier"]["ok"])


class VerifyV2Test(unittest.TestCase):
    """V2: manifest의 토픽·결과별 기대값과 같아야 하고, manifest에 없는 토픽·결과가 늘어도 실패한다"""

    METRIC = "hapbang_notification_consume_outcomes_total"

    def series(self, values):
        return {(self.METRIC, f'{{result="{r}",topic="{t}"}}'): v for (t, r), v in values.items()}

    def test_기대값과_같으면_차이가_없다(self):
        after = self.series({("billing.events.v1", "created"): 3.0})
        self.assertEqual(verify_b0.outcome_differences({"billing.events.v1": {"created": 3}}, {}, after), {})

    def test_manifest에_없는_토픽의_결과가_늘면_차이로_잡는다(self):
        after = self.series({("billing.events.v1", "created"): 3.0, ("member.events.v1", "created"): 950.0})
        diff = verify_b0.outcome_differences({"billing.events.v1": {"created": 3}}, {}, after)
        self.assertEqual(diff, {"member.events.v1/created": {"expected": 0, "actual": 950.0}})

    def test_manifest_토픽이라도_기대에_없는_결과가_늘면_차이로_잡는다(self):
        after = self.series({("billing.events.v1", "created"): 3.0, ("billing.events.v1", "updated"): 1.0})
        diff = verify_b0.outcome_differences({"billing.events.v1": {"created": 3}}, {}, after)
        self.assertEqual(diff, {"billing.events.v1/updated": {"expected": 0, "actual": 1.0}})

    def test_회원_단계_결과는_스냅샷_1_전에_쌓여도_회차_전체로_비교한다(self):
        # 회원 투입·단계 장벽은 스냅샷 ①보다 먼저다. 앱은 회차마다 새로 떠서 지표가 0부터라 회차 전체 값으로 본다(ND-163)
        before = self.series({("member.events.v1", "created"): 950.0})
        after = self.series({("member.events.v1", "created"): 950.0, ("billing.events.v1", "created"): 3.0})
        expected = {"member.events.v1": {"created": 950}, "billing.events.v1": {"created": 3}}
        self.assertEqual(verify_b0.outcome_differences(expected, before, after), {})


if __name__ == "__main__":
    unittest.main()


class PhaseTest(unittest.TestCase):
    """두 단계 투입(ND-166): 정상 단계에 실패가 섞이지 않았는지(V0-7), 단계별 성능, histogram 최상단(+Inf) 처리"""

    LAT = "hapbang_notification_consume_latency_seconds_bucket"

    def counter(self, name, value, **label):
        text = ",".join(f'{k}="{v}"' for k, v in sorted(label.items()))
        return {(name, "{" + text + "}"): value}

    def buckets(self, counts):
        series = {}
        for le, value in counts.items():
            series[(self.LAT, f'{{le="{le}",topic="billing.events.v1"}}')] = value
        return series

    def timing(self, **override):
        base = {"alertInjectStartAt": 100.0, "normalInjectEndAt": 110.0, "normalCompletedAt": 150.0,
                "failureInjectStartAt": 160.0, "failureInjectEndAt": 161.0, "completedAt": 400.0}
        base.update(override)
        return base

    def test_정상_단계에_처리_실패나_DLT가_없으면_통과한다(self):
        results = {}
        verify_b0.verify_phases(results, self.timing(), {}, self.count(4925), self.count(4925), 4925)
        self.assertTrue(results["V0-7 phases"]["ok"])

    def test_정상_단계에_처리_실패가_있으면_실패한다(self):
        results = {}
        normal = self.counter("hapbang_notification_consume_failures_total", 1.0, topic="billing.events.v1",
                              exception="X")
        normal.update(self.count(4925))
        verify_b0.verify_phases(results, self.timing(), {}, normal, normal, 4925)
        self.assertFalse(results["V0-7 phases"]["ok"])

    def test_정상_단계가_끝나기_전에_실패_단계를_넣으면_실패한다(self):
        results = {}
        verify_b0.verify_phases(results, self.timing(failureInjectStartAt=140.0), {}, self.count(4925),
                                self.count(4925), 4925)
        self.assertFalse(results["V0-7 phases"]["ok"])

    def test_정상_단계_성공_건수가_정상_레코드_수와_다르면_실패한다(self):
        # LEGACY·중복·알림 대상 아님을 포함해 정상 레코드마다 정확히 한 번 늘어야 한다(ND-167)
        results = {}
        verify_b0.verify_phases(results, self.timing(), {}, self.count(4924), self.count(4924), 4925)
        self.assertFalse(results["V0-7 phases"]["ok"])
        self.assertEqual(results["V0-7 phases"]["detail"]["normalPhaseSucceeded"], 4924)

    def test_실패_단계에서_성공_건수가_늘면_실패한다(self):
        results = {}
        verify_b0.verify_phases(results, self.timing(), {}, self.count(4925), self.count(4926), 4925)
        self.assertFalse(results["V0-7 phases"]["ok"])

    def count(self, value):
        return {("hapbang_notification_consume_latency_seconds_count", '{topic="billing.events.v1"}'): float(value)}

    def test_분위수가_최상단_경계를_넘으면_값_대신_넘었다고_적는다(self):
        after = self.buckets({"60.0": 50, "300.0": 90, "+Inf": 100})
        report = verify_b0.latency_quantile({}, after, self.LAT, 0.95)
        self.assertIsNone(report["seconds"])
        self.assertTrue(report["aboveTopBucket"])
        self.assertEqual(report["countAboveTopBucket"], 10.0)
        self.assertEqual(report["bucket"], {"lower": 300.0, "upper": None})
        self.assertEqual(report["samples"], 100.0)

    def test_분위수가_경계_안이면_그_경계_값을_적는다(self):
        after = self.buckets({"1.0": 96, "300.0": 100, "+Inf": 100})
        report = verify_b0.latency_quantile({}, after, self.LAT, 0.95)
        # 첫 구간(0~1초)에 96건: 순위 95 → 0 + 1 × 95/96 (Prometheus histogram_quantile과 같은 선형 보간)
        self.assertAlmostEqual(report["seconds"], 95 / 96, places=6)
        self.assertEqual(report["bucket"], {"lower": 0.0, "upper": 1.0})
        self.assertFalse(report["aboveTopBucket"])

    def test_표본이_없으면_값_없이_표본_0으로_적는다(self):
        # 실패 단계 레코드는 성공한 시도가 없어 consume.latency 표본이 없다(ND-161: 성공한 시도만 잼)
        same = self.buckets({"1.0": 5, "300.0": 5, "+Inf": 5})
        report = verify_b0.latency_quantile(same, same, self.LAT, 0.95)
        self.assertEqual(report["samples"], 0.0)
        self.assertIsNone(report["seconds"])

    def test_정상_단계_성능은_스냅샷_1과_2_사이만_본다(self):
        before = self.buckets({"1.0": 0, "300.0": 0, "+Inf": 0})
        normal = self.buckets({"1.0": 100, "300.0": 100, "+Inf": 100})
        after = self.buckets({"1.0": 100, "300.0": 120, "+Inf": 175})
        manifest = {"phaseRecordsByTopic": {"normal": {"billing.events.v1": 100}, "failure": {"billing.events.v1": 75}}}
        perf = verify_b0.phase_performance(manifest, self.timing(), [], before, normal, after)
        self.assertEqual(perf["normal"]["records"], 100)
        self.assertEqual(perf["normal"]["elapsedSeconds"], 50.0)
        self.assertEqual(perf["normal"]["throughputRecordsPerSecond"], 2.0)
        self.assertEqual(perf["normal"]["injectRecordsPerSecond"], 10.0)
        self.assertAlmostEqual(perf["normal"]["consumeLatencyP95"]["seconds"], 0.95, places=6)
        self.assertEqual(perf["failure"]["recoverySeconds"], 240.0)
        self.assertTrue(perf["failure"]["consumeLatencyP95"]["aboveTopBucket"])

    def test_클러스터_시계가_있으면_정상_단계_소요_시간은_클러스터_시계끼리만_잰다(self):
        # 투입 시작·끝(producer exec 안 date)과 완료(도구 pod polling)가 같은 시계다(ND-169). 호스트 시계 값은 섞지 않는다
        manifest = {"phaseRecordsByTopic": {"normal": {"billing.events.v1": 100}, "failure": {"billing.events.v1": 75}}}
        timing = self.timing(clusterClock={"normalInjectStartAt": 1000.0, "normalInjectEndAt": 1004.0,
                                           "normalCompletedAt": 1020.0, "hostMinusClusterSeconds": 0.03})
        perf = verify_b0.phase_performance(manifest, timing, [], {}, {}, {})
        self.assertEqual(perf["normal"]["clock"], "cluster")
        self.assertEqual(perf["normal"]["elapsedSeconds"], 20.0)
        self.assertEqual(perf["normal"]["injectSeconds"], 4.0)
        self.assertEqual(perf["normal"]["throughputRecordsPerSecond"], 5.0)
        self.assertEqual(perf["normal"]["hostMinusClusterSeconds"], 0.03)

    def test_구간_안에서_선형_보간하고_구간별_건수를_적는다(self):
        # 140번째 회차 지갑 토픽 분포: 순위 1902.85가 10~30초 구간(1089→2003)에 든다
        after = self.buckets({"0.1": 0, "0.25": 14, "0.5": 49, "1.0": 106, "2.5": 262, "5.0": 541, "10.0": 1089,
                              "30.0": 2003, "60.0": 2003, "300.0": 2003, "+Inf": 2003})
        report = verify_b0.latency_quantile({}, after, self.LAT, 0.95)
        self.assertAlmostEqual(report["seconds"], 10 + 20 * (0.95 * 2003 - 1089) / (2003 - 1089), places=6)
        self.assertEqual(report["bucket"], {"lower": 10.0, "upper": 30.0})
        self.assertEqual(report["bucketCounts"]["30.0"], 914.0)
        self.assertEqual(report["bucketCounts"]["+Inf"], 0.0)
        self.assertEqual(report["samples"], 2003.0)


class LatencyCountTest(unittest.TestCase):
    """정상 단계 완료 판정(ND-167): 알림 토픽 5개의 성공 처리 건수(consume.latency count)만 더한다. 회원 토픽은 뺀다"""

    def test_알림_토픽만_더하고_회원_토픽은_뺀다(self):
        text = chr(10).join([
            '# TYPE hapbang_notification_consume_latency_seconds histogram',
            'hapbang_notification_consume_latency_seconds_count{topic="member.events.v1"} 1000',
            'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 2003',
            'hapbang_notification_consume_latency_seconds_count{topic="chat.events.v1"} 256',
            'hapbang_notification_consume_latency_seconds_sum{topic="chat.events.v1"} 99.5',
            'hapbang_notification_consume_outcomes_total{result="created",topic="chat.events.v1"} 434',
        ])
        self.assertEqual(verify_b0.alert_latency_count(text), 2259)

    def test_지표가_없으면_0이다(self):
        self.assertEqual(verify_b0.alert_latency_count(""), 0)

    def test_완료_관측은_도달까지의_최대_평균_polling_간격과_scrape_시간을_적는다(self):
        # progress.tsv: 관측 시각 TAB 정상 단계 성공 건수 증가량 TAB scrape 걸린 초. 도달 뒤의 줄은 보지 않는다
        lines = ["100.0\t0\t0.1", "100.6\t50\t0.2", "101.4\t100\t0.3", "102.0\t100\t0.1"]
        report = verify_b0.completion_observation(lines, 100)
        self.assertEqual(report["method"], "consume.latency count polling")
        self.assertEqual(report["configuredIntervalSeconds"], 0.5)
        self.assertEqual(report["reachedAt"], 101.4)
        self.assertEqual(report["polls"], 3)
        self.assertAlmostEqual(report["maxPollGapSeconds"], 0.8, places=6)
        self.assertAlmostEqual(report["meanPollGapSeconds"], 0.7, places=6)
        self.assertAlmostEqual(report["scrapeMaxSeconds"], 0.3, places=6)
        self.assertAlmostEqual(report["scrapeMeanSeconds"], 0.2, places=6)

    def test_도달하지_않았으면_도달_시각이_없다(self):
        report = verify_b0.completion_observation(["100.0\t0\t0.1", "100.5\t99\t0.1"], 100)
        self.assertIsNone(report["reachedAt"])

    def test_처리량_오차는_완료_관측_최대_간격만큼이다(self):
        # 완료는 앞 관측 뒤 ~ 도달 관측 사이 어딘가다. 소요 시간이 최대 간격만큼 짧았을 수 있다
        report = verify_b0.throughput_error(records=100, elapsed=20.0, max_gap=0.8)
        self.assertEqual(report, {"elapsedRangeSeconds": [19.2, 20.0], "throughputRange": [5.0, 5.21],
                                  "relativeError": 0.04})


class AwkCountTest(unittest.TestCase):
    """0.5초 polling용 awk 집계(ND-168)는 Python 집계(alert_latency_count)와 같은 값을 낸다"""

    def test_awk_집계는_알림_토픽만_더하고_Python_집계와_같다(self):
        text = "\n".join([
            '# TYPE hapbang_notification_consume_latency_seconds histogram',
            'hapbang_notification_consume_latency_seconds_count{topic="member.events.v1"} 1000',
            'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 2003',
            'hapbang_notification_consume_latency_seconds_count{topic="chat.events.v1"} 256.0',
            'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1.x"} 7',
            'hapbang_notification_consume_latency_seconds_sum{topic="chat.events.v1"} 99.5',
        ]) + "\n"
        result = subprocess.run(["awk", "-f", str(HERE / "queries" / "alert_latency_count.awk")],
                                input=text.encode("utf-8"), capture_output=True, check=True)
        self.assertEqual(result.stdout.decode("utf-8").strip(), "2259")
        self.assertEqual(verify_b0.alert_latency_count(text), 2259)


class ClusterPollerTest(unittest.TestCase):
    """도구 pod 안 polling 스크립트(ND-169): "클러스터 시각 TAB 기준 대비 증가량 TAB 조회 초"를 내고, 기대에 닿으면 몇 번 더 본 뒤 끝난다"""

    def run_poller(self, metrics_text, base, expected, extra):
        work = Path(tempfile.mkdtemp())
        (work / "metrics.txt").write_text(metrics_text, encoding="utf-8")
        env = dict(__import__("os").environ)
        env.update({"B0_FETCH": f"cat {work.as_posix()}/metrics.txt",
               "B0_AWK": (HERE / "queries" / "alert_latency_count.awk").as_posix(), "B0_INTERVAL": "0",
               "B0_EXTRA_POLLS": str(extra), "B0_DEADLINE_SECONDS": "5"})
        script = (HERE / "k3d" / "tools" / "poll_latency_count.sh").as_posix()
        # Windows에서 맨 이름 "bash"는 WSL bash로 갈 수 있어 PATH의 bash(Git Bash·Linux bash)를 찾아 쓴다
        bash = __import__("shutil").which("bash")
        return subprocess.run([bash, script, str(base), str(expected)], env=env, capture_output=True,
                              timeout=30)

    def test_기대에_닿으면_정한_횟수만큼_더_보고_끝난다(self):
        text = 'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 150' + "\n"
        result = self.run_poller(text, base=50, expected=100, extra=2)
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.decode("utf-8").splitlines()
        self.assertEqual(len(lines), 3)
        at, count, scrape = lines[0].split("\t")
        self.assertEqual(count, "100")
        self.assertGreater(float(at), 1_700_000_000)
        self.assertGreaterEqual(float(scrape), 0.0)

    def test_기한까지_닿지_않으면_0이_아닌_코드로_끝난다(self):
        text = 'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 60' + "\n"
        result = self.run_poller(text, base=50, expected=100, extra=0)
        self.assertNotEqual(result.returncode, 0)


class ReviewFixTest(unittest.TestCase):
    """S6 리뷰 반영: V8 면제 범위, V8 로그 범위, V7 회차 전체, polling 조회 실패"""

    def entry(self, logger, stack):
        return json.dumps({"@timestamp": "2026-11-15T19:21:00+09:00", "level": "ERROR", "logger_name": logger,
                           "message": "x", "stack_trace": stack}, separators=(",", ":"))  # 앱 JSON 로그와 같은 모양

    def test_V8_감싼_예외만_있고_의도한_원인이_없으면_예상_밖_오류다(self):
        lines = [
            self.entry("org.springframework.kafka.listener.DefaultErrorHandler",
                       "ListenerExecutionFailedException: x\nCaused by: java.lang.IllegalStateException: boom"),
            self.entry("org.springframework.kafka.listener.DefaultErrorHandler",
                       "ListenerExecutionFailedException: x\nCaused by: com.h.NotificationException: RECEIVER"),
            self.entry("com.hapbang.auxiliary.chatbot.app.ChatbotSyncUseCase", "IllegalStateException"),
        ]
        unexpected = verify_b0.unexpected_error_lines(lines)
        self.assertEqual(len(unexpected), 1)
        self.assertIn("IllegalStateException: boom", unexpected[0])

    def test_V8_로그가_정상_단계_시작_전부터_남아_있어야_한다(self):
        lines = ["not json", json.dumps({"@timestamp": "2026-11-15T19:21:00+09:00"})]
        start = verify_b0.parse_time("2026-11-15T19:21:00+09:00")
        self.assertTrue(verify_b0.log_covers(lines, start + 1))
        self.assertFalse(verify_b0.log_covers(lines, start - 1))
        self.assertFalse(verify_b0.log_covers([], start))

    def test_V7_예약_발송과_실패는_회차_전체로_센다(self):
        name = "hapbang_notification_schedule_outcomes_total"
        after = {(name, '{result="sent"}'): 2.0, (name, '{result="failed"}'): 1.0, (name, '{result="skipped"}'): 5.0}
        self.assertEqual(verify_b0.schedule_attempts(after), {"sent": 2.0, "failed": 1.0})


class PollerFetchFailureTest(unittest.TestCase):

    def test_닿은_뒤_조회가_실패하면_추가_확인으로_세지_않고_기한에_끝난다(self):
        work = Path(tempfile.mkdtemp())
        (work / "metrics.txt").write_text(
            'hapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 150\n', encoding="utf-8")
        fetch = work / "fetch.sh"
        # 처음 한 번만 성공하고 그 뒤로는 실패한다
        fetch.write_text(f'if [ -e "{work.as_posix()}/done" ]; then exit 1; fi\ntouch "{work.as_posix()}/done"\n'
                         f'cat "{work.as_posix()}/metrics.txt"\n', encoding="utf-8")
        env = dict(__import__("os").environ)
        env.update({"B0_FETCH": f"bash {fetch.as_posix()}",
                    "B0_AWK": (HERE / "queries" / "alert_latency_count.awk").as_posix(), "B0_INTERVAL": "0.2",
                    "B0_EXTRA_POLLS": "2", "B0_DEADLINE_SECONDS": "2"})
        bash = __import__("shutil").which("bash")
        result = subprocess.run([bash, (HERE / "k3d" / "tools" / "poll_latency_count.sh").as_posix(), "50", "100"],
                                env=env, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 3)
        self.assertEqual(len(result.stdout.decode("utf-8").splitlines()), 1)
