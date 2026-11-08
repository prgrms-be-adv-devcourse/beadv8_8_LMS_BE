package com.hapbang.lecture.domain;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 동기화 DTO(#712)의 시각. 강의의 시각은 모두 KST이고(설계 5장), 내부 API 공통 규칙대로 offset을 붙여 보낸다 */
final class SyncTimes {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private SyncTimes() {
    }

    static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
