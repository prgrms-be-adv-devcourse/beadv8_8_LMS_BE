"""B0 처리량 변동 진단(N13). 정상 단계 동안 1초 간격으로 모은 표본을 같은 시간축으로 묶는다.

입력(회차 폴더의 diag/):
  tools.tsv        도구 pod 표본(diag/tools_sampler.sh): "vm TAB 시각 TAB 0 TAB 0 TAB 0 TAB /proc/stat cpu 필드"(VM 전체 CPU)와
                   "app TAB 시각 TAB 지표 줄"(앱 지표 이름 필터). 노드·앱·DB cgroup은 CPU 한도가 없어(limits.txt) throttling 열은 0이다
  pg.tsv           PostgreSQL 표본(diag/pg_sampler.sh): tick / act(활동·대기·막는 pid) / row(잠긴 회원) 줄
  app-normal.log   정상 단계 직후 보존한 앱 로그(JSON 줄, SIGQUIT 스레드 덤프 포함)
출력: diag/timeline.tsv, diag/summary.json

사용: python -I diag_b0.py <회차 폴더>
"""
import datetime as dt
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ALERT_TOPICS = ["appearance.events.v1", "billing.events.v1", "chat.events.v1", "customersupport.events.v1",
                "lecture.events.v1"]
SAMPLE_LINE = re.compile(r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([^\s]+)")
RECEIVER = re.compile(r"receiverUserId=(\d+)")


def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8", errors="replace").splitlines() if p.exists() else []


def node_samples(lines):
    """노드 표본 두 줄 사이의 VM CPU 사용률(1 - idle·iowait 비율)과 노드 throttling 증가량"""
    parsed = []
    for line in lines:
        f = line.split("\t")
        if len(f) < 5:
            continue
        cpu = [int(x) for x in f[4].split()]
        parsed.append((float(f[0]), int(f[2]), int(f[3]), cpu))
    rows = []
    for (t0, n0, u0, c0), (t1, n1, u1, c1) in zip(parsed, parsed[1:]):
        delta = [b - a for a, b in zip(c0, c1)]
        total = sum(delta)
        idle = delta[3] + (delta[4] if len(delta) > 4 else 0)
        rows.append({"t": t1, "vmCpuBusy": round((total - idle) / total, 4) if total else None,
                     "nodeThrottledDelta": n1 - n0, "nodeThrottledUsecDelta": u1 - u0})
    return rows


def app_samples(lines):
    """앱 지표 표본: 토픽별 초당 처리 건수(consume.latency count 증가), 스레드 상태, Hikari"""
    by_time = defaultdict(dict)
    for line in lines:
        at, _, sample = line.partition("\t")
        m = SAMPLE_LINE.match(sample)
        if not m:
            continue
        by_time[float(at)][(m.group(1), m.group(2) or "")] = float(m.group(3))
    times = sorted(by_time)
    rows = []
    for t0, t1 in zip(times, times[1:]):
        a, b = by_time[t0], by_time[t1]
        span = t1 - t0
        rate = {}
        for (name, label), value in b.items():
            if name == "hapbang_notification_consume_latency_seconds_count":
                topic = re.search(r'topic="([^"]*)"', label).group(1)
                if topic in ALERT_TOPICS:
                    rate[topic] = round((value - a.get((name, label), value)) / span, 2) if span else 0.0

        def pick(name, contains=""):
            return sum(v for (n, l), v in b.items() if n == name and contains in l)
        rows.append({"t": t1, "rate": rate, "total": round(sum(rate.values()), 2),
                     "threadsBlocked": pick("jvm_threads_states_threads", 'state="blocked"'),
                     "threadsRunnable": pick("jvm_threads_states_threads", 'state="runnable"'),
                     "threadsWaiting": pick("jvm_threads_states_threads", 'state="waiting"'),
                     "hikariActive": pick("hikaricp_connections_active"),
                     "hikariPending": pick("hikaricp_connections_pending"),
                     "processCpu": pick("process_cpu_usage")})
    return rows


def pg_samples(lines):
    """PostgreSQL 표본: 시각마다 Lock 대기 수, (대기 pid, 막는 pid들), 행 잠금이 걸린 회원"""
    by_time = {}
    for line in lines:
        f = line.split("\t")
        if len(f) < 3:
            continue
        t = float(f[1])
        row = by_time.setdefault(t, {"t": t, "active": 0, "lockWaits": 0, "blocking": [], "lockedUsers": []})
        if f[0] == "act" and len(f) >= 7:
            if f[3] == "active":
                row["active"] += 1
            if f[4] == "Lock":
                row["lockWaits"] += 1
            blockers = [int(x) for x in f[6].strip("{}").split(",") if x]
            if blockers:
                row["blocking"].append([int(f[2]), blockers])
        elif f[0] == "row":
            row["lockedUsers"].append(int(f[2]))
    return [by_time[t] for t in sorted(by_time)]


def app_log(lines):
    """앱 로그: 토픽별 초당 처리, 지갑·강의가 같은 초에 같은 받는 사람을 처리한 경우, 검색 재시도 시각, 스레드 덤프 수"""
    processed = {"billing": defaultdict(int), "lecture": defaultdict(int), "appearance": defaultdict(int),
                 "chat": defaultdict(int), "customersupport": defaultdict(int)}
    receivers = {"billing": defaultdict(set), "lecture": defaultdict(set)}
    search_retry, dumps = set(), 0
    for line in lines:
        if line.startswith("Full thread dump"):
            dumps += 1
            continue
        try:
            entry = json.loads(line)
        except ValueError:
            continue
        second = int(dt.datetime.fromisoformat(entry["@timestamp"]).timestamp())
        thread, message = entry.get("thread_name", ""), entry.get("message", "")
        for topic in processed:
            if thread.startswith(f"notification-{topic}-events"):
                processed[topic][second] += 1
                m = RECEIVER.search(message)
                if m and topic in receivers:
                    receivers[topic][second].add(int(m.group(1)))
        if thread.startswith("search-") and "retry" in message:
            search_retry.add(second)
    same = [{"second": s, "receivers": sorted(receivers["billing"][s] & receivers["lecture"][s])}
            for s in sorted(receivers["billing"]) if receivers["billing"][s] & receivers["lecture"].get(s, set())]
    return {"processedByTopicSecond": {k: dict(v) for k, v in processed.items()}, "sameReceiverSeconds": same,
            "searchRetrySeconds": sorted(search_retry), "threadDumps": dumps}


def slow_seconds(rates, done_at):
    """완료 전에 처리 건수가 그 회차 처리 중앙값의 30% 아래로 떨어진 초(대기열이 남아 있는 동안)"""
    active = [r for r in rates if r["t"] <= done_at and r["total"] > 0]
    if not active:
        return []
    median = statistics.median(r["total"] for r in active)
    first = min(r["t"] for r in active)
    return [r["t"] for r in rates if first < r["t"] < done_at and r["total"] < 0.3 * median]


def main():
    run = Path(sys.argv[1])
    diag = run / "diag"
    timing = json.loads((run / "timing.json").read_text(encoding="utf-8"))
    cluster = timing["clusterClock"]
    start, done = cluster["normalInjectStartAt"], cluster["normalCompletedAt"]
    tools = read(diag / "tools.tsv")
    node = node_samples([line.split("\t", 1)[1] for line in tools if line.startswith("vm\t")])
    app = app_samples([line.split("\t", 1)[1] for line in tools if line.startswith("app\t")])
    pg = pg_samples(read(diag / "pg.tsv"))
    log = app_log(read(diag / "app-normal.log"))
    slow = slow_seconds(app, done)

    def near(rows, t):
        return min(rows, key=lambda r: abs(r["t"] - t)) if rows else {}
    out = ["rel\ttotal\tbilling\tlecture\tappearance\tvmCpuBusy\tthrottled\tblocked\trunnable\thikariActive\t"
           "hikariPending\tprocessCpu\tpgActive\tpgLockWaits\tlockedUsers\tslow"]
    for r in app:
        if r["t"] < start - 2 or r["t"] > done + 3:
            continue
        n, p = near(node, r["t"]), near(pg, r["t"])
        out.append("\t".join(str(x) for x in [
            round(r["t"] - start, 1), r["total"], r["rate"].get("billing.events.v1"), r["rate"].get("lecture.events.v1"),
            r["rate"].get("appearance.events.v1"), n.get("vmCpuBusy"), n.get("nodeThrottledDelta"),
            r["threadsBlocked"], r["threadsRunnable"], r["hikariActive"], r["hikariPending"],
            round(r["processCpu"], 3), p.get("active"), p.get("lockWaits"),
            ",".join(str(u) for u in p.get("lockedUsers", [])), "Y" if r["t"] in slow else ""]))
    (diag / "timeline.tsv").write_text("\n".join(out) + "\n", encoding="utf-8")
    window = [r for r in pg if start <= r["t"] <= done]
    summary = {
        "elapsedSeconds": round(done - start, 3),
        "slowSecondsRelative": [round(t - start, 1) for t in slow],
        "pgLockWaitSamples": sum(1 for r in window if r["lockWaits"]),
        "pgMaxLockWaits": max((r["lockWaits"] for r in window), default=0),
        "pgLockedUsers": sorted({u for r in window for u in r["lockedUsers"]}),
        "sameReceiverSecondsRelative": [{"second": s["second"] - int(start), "receivers": s["receivers"]}
                                        for s in log["sameReceiverSeconds"] if start <= s["second"] <= done + 1],
        "searchRetrySecondsRelative": [s - int(start) for s in log["searchRetrySeconds"] if start - 5 <= s <= done + 5],
        "vmCpuBusyMax": max((r["vmCpuBusy"] or 0 for r in node if start <= r["t"] <= done), default=None),
        "nodeThrottled": sum(r["nodeThrottledDelta"] for r in node if start <= r["t"] <= done),
        "threadsBlockedMax": max((r["threadsBlocked"] for r in app if start <= r["t"] <= done), default=None),
        "hikariPendingMax": max((r["hikariPending"] for r in app if start <= r["t"] <= done), default=None),
        "threadDumps": log["threadDumps"],
    }
    (diag / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
