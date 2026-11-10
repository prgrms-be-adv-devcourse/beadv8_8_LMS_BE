package com.hapbang.shared.lecture.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 강의 이벤트의 사건 시각 (#818). 강의의 시각은 KST이므로 offset을 {@code +09:00}으로 맞춘다.
 * Jackson은 읽을 때 offset을 UTC로 바꾸므로, 발행 기록(outbox)에서 읽어 다시 보내도 {@code +09:00}으로 나가게 레코드가 맞춘다.
 */
final class LectureEventTimes {

    /** 한국은 일광 절약 시간이 없어 offset이 늘 같다 */
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private LectureEventTimes() {
    }

    /** 같은 시각을 KST offset으로 나타낸다. {@code null}은 그대로 둔다(배포 전 발행 기록의 새 필드) */
    static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.withOffsetSameInstant(KST);
    }
}
