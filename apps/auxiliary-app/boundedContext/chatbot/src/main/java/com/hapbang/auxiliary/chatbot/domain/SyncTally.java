package com.hapbang.auxiliary.chatbot.domain;

import java.util.EnumMap;
import java.util.Map;

/**
 * 싱크 회차가 센 건수 (#716). 한 회차 안에서 한 스레드만 쓴다.
 * <ul>
 *     <li>{@code inserted}: 원본에만 있어 넣은 건수</li>
 *     <li>{@code fixed}: 원본 새것 + 같은 version 다른 값으로 고친 건수. version만 오른 정상 건은 넣지 않는다</li>
 *     <li>{@code oursNewer}: 우리 version이 커서 무시한 건수</li>
 * </ul>
 */
public final class SyncTally {

    private final Map<CopyOutcome, Long> counts = new EnumMap<>(CopyOutcome.class);
    private long scanned;

    public void add(CopyOutcome outcome) {
        scanned++;
        counts.merge(outcome, 1L, Long::sum);
    }

    /**
     * 경고 여부. {@code (inserted + fixed) / scanned}가 비율 이상이고 {@code inserted + fixed}가 최소 건수 이상이면 경고다.
     * 첫 실행은 원본 전체를 넣으므로 부르는 쪽이 빼고 판단한다.
     */
    public boolean warns(double ratio, long minChanged) {
        long changed = inserted() + fixed();
        return scanned > 0 && changed >= minChanged && (double) changed / scanned >= ratio;
    }

    public long scanned() {
        return scanned;
    }

    public long inserted() {
        return counts.getOrDefault(CopyOutcome.INSERTED, 0L);
    }

    public long fixed() {
        return counts.entrySet().stream().filter(entry -> entry.getKey().fixed()).mapToLong(Map.Entry::getValue).sum();
    }

    public long oursNewer() {
        return counts.getOrDefault(CopyOutcome.OURS_NEWER, 0L);
    }
}
