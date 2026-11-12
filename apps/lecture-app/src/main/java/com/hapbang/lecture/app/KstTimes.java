package com.hapbang.lecture.app;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.hapbang.lecture.config.LectureConfig;

/**
 * 강의 시각을 이벤트의 사건 시각으로 바꾼다 (#818, #872). 강의는 시각을 KST {@link LocalDateTime}으로 저장하므로 offset {@code +09:00}을 붙인다.
 */
final class KstTimes {

    private KstTimes() {
    }

    static OffsetDateTime offset(LocalDateTime time) {
        return time.atZone(LectureConfig.ZONE).toOffsetDateTime();
    }
}
