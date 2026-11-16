"""diag_b0.py 단위 시험(N13 처리량 변동 진단). 실행: python -I -m unittest discover -s loadtest/notification/tests"""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))
import diag_b0  # noqa: E402


class NodeSamplesTest(unittest.TestCase):

    def test_VM_CPU_사용률과_노드_throttling_증가량을_초마다_낸다(self):
        # 시각 TAB usage_usec TAB nr_throttled TAB throttled_usec TAB /proc/stat cpu 필드(user nice system idle iowait irq softirq steal)
        lines = ["100.0\t1000\t0\t0\t100 0 100 800 0 0 0 0",
                 "101.0\t2000\t1\t500\t150 0 150 900 0 0 0 0"]
        rows = diag_b0.node_samples(lines)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["t"], 101.0)
        self.assertAlmostEqual(rows[0]["vmCpuBusy"], 0.5)  # (50+50)/(50+50+100)
        self.assertEqual(rows[0]["nodeThrottledDelta"], 1)
        self.assertEqual(rows[0]["nodeThrottledUsecDelta"], 500)


class AppSamplesTest(unittest.TestCase):

    def test_토픽별_초당_처리_건수와_스레드_상태와_Hikari를_낸다(self):
        lines = [
            '100.0\thapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 10',
            '100.0\thapbang_notification_consume_latency_seconds_count{topic="lecture.events.v1"} 5',
            '100.0\tjvm_threads_states_threads{state="blocked"} 0.0',
            '101.0\thapbang_notification_consume_latency_seconds_count{topic="billing.events.v1"} 40',
            '101.0\thapbang_notification_consume_latency_seconds_count{topic="lecture.events.v1"} 5',
            '101.0\tjvm_threads_states_threads{state="blocked"} 2.0',
            '101.0\thikaricp_connections_pending{pool="HikariPool-1"} 1.0',
        ]
        rows = diag_b0.app_samples(lines)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["t"], 101.0)
        self.assertEqual(rows[0]["rate"], {"billing.events.v1": 30.0, "lecture.events.v1": 0.0})
        self.assertEqual(rows[0]["threadsBlocked"], 2.0)
        self.assertEqual(rows[0]["hikariPending"], 1.0)


class PgSamplesTest(unittest.TestCase):

    def test_잠금_대기와_막는_pid와_잠긴_회원을_초마다_묶는다(self):
        # 종류 TAB 시각 TAB …. act: pid, state, wait_event_type, wait_event, blocking pids, 쿼리 앞부분 / row: 잠긴 회원 ID
        lines = [
            "act\t100.2\t11\tactive\tLock\ttransactionid\t{12}\tselect … for update",
            "act\t100.2\t12\tidle in transaction\tClient\tClientRead\t{}\tupdate …",
            "row\t100.2\t9000123",
            "act\t101.2\t11\tactive\t\t\t{}\tinsert …",
        ]
        rows = diag_b0.pg_samples(lines)
        self.assertEqual([r["t"] for r in rows], [100.2, 101.2])
        self.assertEqual(rows[0]["lockWaits"], 1)
        self.assertEqual(rows[0]["blocking"], [[11, [12]]])
        self.assertEqual(rows[0]["lockedUsers"], [9000123])
        self.assertEqual(rows[1]["lockWaits"], 0)


class AppLogTest(unittest.TestCase):

    def log(self, at, thread, message):
        return json.dumps({"@timestamp": at, "thread_name": thread, "message": message}, ensure_ascii=False)

    def test_지갑과_강의가_같은_초에_같은_받는_사람을_처리한_경우를_찾는다(self):
        lines = [
            self.log("2026-11-15T17:00:00.100+09:00", "notification-billing-events-0-C-1",
                     "알림을 만들었다 kind=CHARGE_COMPLETED, receiverUserId=9000123, sourceTargetId=1, outcome=CREATED"),
            self.log("2026-11-15T17:00:00.600+09:00", "notification-lecture-events-0-C-1",
                     "알림을 만들었다 kind=LECTURE_ORDER_PAID, receiverUserId=9000123, sourceTargetId=2, outcome=CREATED"),
            self.log("2026-11-15T17:00:02.000+09:00", "notification-lecture-events-0-C-1",
                     "알림을 만들었다 kind=LECTURE_ORDER_PAID, receiverUserId=9000999, sourceTargetId=3, outcome=CREATED"),
            self.log("2026-11-15T17:00:02.500+09:00", "search-member-0-C-1", "Record in retry and not yet recovered"),
            "Full thread dump OpenJDK 64-Bit Server VM",
        ]
        result = diag_b0.app_log(lines)
        self.assertEqual(result["sameReceiverSeconds"], [{"second": 1794729600, "receivers": [9000123]}])
        self.assertEqual(result["searchRetrySeconds"], [1794729602])
        self.assertEqual(result["processedByTopicSecond"]["lecture"], {1794729600: 1, 1794729602: 1})
        self.assertEqual(result["threadDumps"], 1)


class SlowWindowTest(unittest.TestCase):

    def test_대기열이_남았는데_처리가_중앙값의_30퍼센트_아래인_초를_느린_구간으로_본다(self):
        rates = [{"t": 1.0, "total": 100.0}, {"t": 2.0, "total": 110.0}, {"t": 3.0, "total": 20.0},
                 {"t": 4.0, "total": 15.0}, {"t": 5.0, "total": 105.0}]
        self.assertEqual(diag_b0.slow_seconds(rates, done_at=5.0), [3.0, 4.0])


if __name__ == "__main__":
    unittest.main()
