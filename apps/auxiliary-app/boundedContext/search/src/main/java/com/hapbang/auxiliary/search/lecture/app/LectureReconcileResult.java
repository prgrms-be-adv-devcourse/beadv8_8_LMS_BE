package com.hapbang.auxiliary.search.lecture.app;

import java.util.EnumMap;
import java.util.Map;

/**
 * 강의 검색 보정 한 회차의 결과(#884).
 *
 * @param read   읽은 원본 수(강의·리뷰·회원, 칸 맞추기는 읽은 문서 수)
 * @param counts 결과별 수
 */
public record LectureReconcileResult(long read, Map<LectureReconcileKind, Long> counts) {

    public LectureReconcileResult {
        counts = counts.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(counts));
    }

    public long count(LectureReconcileKind kind) {
        return counts.getOrDefault(kind, 0L);
    }

    /** 놓친 변경을 바로잡은 수 */
    public long fixed() {
        return counts.entrySet().stream().filter(e -> e.getKey().isFix()).mapToLong(Map.Entry::getValue).sum();
    }

    /** 두 결과를 더한다(리뷰 보정 = 사본 반영 + 칸 맞추기) */
    LectureReconcileResult plus(LectureReconcileResult other) {
        Map<LectureReconcileKind, Long> merged = new EnumMap<>(LectureReconcileKind.class);
        merged.putAll(counts);
        other.counts.forEach((kind, count) -> merged.merge(kind, count, Long::sum));
        return new LectureReconcileResult(read + other.read, merged);
    }
}
