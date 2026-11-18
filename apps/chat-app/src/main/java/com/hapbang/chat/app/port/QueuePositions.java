package com.hapbang.chat.app.port;

import java.time.Instant;

/**
 * 작업 큐 항목 위치({@code <ms>-<seq>}) 규칙. 회수 cursor·그룹 위치·정리 경계를 이 형식으로 주고받는다
 * ({@link ChatDeliveryQueue}).
 */
public final class QueuePositions {

    /** 회수를 처음부터 시작하는 위치, 그룹을 처음부터 읽는 위치 */
    public static final String START = "0-0";

    private QueuePositions() {
    }

    /** 항목 위치({@code <ms>-<seq>}) 비교 */
    public static int compare(String left, String right) {
        long[] a = parse(left);
        long[] b = parse(right);
        int byTime = Long.compare(a[0], b[0]);
        return byTime != 0 ? byTime : Long.compare(a[1], b[1]);
    }

    public static String min(String left, String right) {
        return compare(left, right) <= 0 ? left : right;
    }

    /** 이 시각보다 앞서 적재된 항목을 가르는 위치 */
    public static String positionAt(Instant instant) {
        return instant.toEpochMilli() + "-0";
    }

    private static long[] parse(String entryId) {
        int dash = entryId.indexOf('-');
        if (dash < 0) {
            return new long[] {Long.parseLong(entryId), 0};
        }
        return new long[] {Long.parseLong(entryId.substring(0, dash)), Long.parseLong(entryId.substring(dash + 1))};
    }
}
