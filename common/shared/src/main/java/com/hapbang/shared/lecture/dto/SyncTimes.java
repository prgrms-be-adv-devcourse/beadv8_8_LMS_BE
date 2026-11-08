package com.hapbang.shared.lecture.dto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** 동기화 DTO의 시각을 KST offset으로 맞춘다 (#712). 같은 시각이면 offset과 관계없이 같은 값이 되게 한다 */
final class SyncTimes {

    /** 한국은 일광 절약 시간이 없어 offset이 늘 같다 */
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private SyncTimes() {
    }

    static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(KST);
    }
}
