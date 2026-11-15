package com.hapbang.customersupport.fixture;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.hapbang.shared.lecture.event.LectureObjectionResolvedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnActionRequiredEvent;
import com.hapbang.shared.lecture.event.LectureObjectionReturnCompletedEvent;
import com.hapbang.shared.lecture.event.LectureObjectionWithdrawnEvent;

/**
 * 강의 앱이 보내는 이의 제기 결과 이벤트 (#279). 강의 앱처럼 명령 ID는 {@code RESOLVE:<문의 ID>}·{@code WITHDRAW:<문의 ID>}이고,
 * 알림용 값(받는 사람 ID·강의 제목·사건 시각, #818)도 채운다. 고객센터는 알림용 값을 쓰지 않는다.
 */
public final class LectureObjectionResults {

    public static final Long BUYER_USER_ID = 20L;
    public static final Long SELLER_USER_ID = 10L;
    public static final String LECTURE_TITLE = "OBS 방송 세팅";
    public static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 10, 10, 30, 0, 0, ZoneOffset.ofHours(9));

    private LectureObjectionResults() {
    }

    public static LectureObjectionResolvedEvent resolved(Long inquiryId, Long orderItemId, boolean accepted) {
        return new LectureObjectionResolvedEvent("RESOLVE:" + inquiryId, inquiryId, orderItemId, accepted,
                BUYER_USER_ID, SELLER_USER_ID, LECTURE_TITLE, AT);
    }

    public static LectureObjectionWithdrawnEvent withdrawn(Long inquiryId, Long orderItemId) {
        return new LectureObjectionWithdrawnEvent("WITHDRAW:" + inquiryId, inquiryId, orderItemId, BUYER_USER_ID,
                SELLER_USER_ID, LECTURE_TITLE, AT);
    }

    public static LectureObjectionReturnCompletedEvent returnCompleted(Long inquiryId, Long orderItemId) {
        return new LectureObjectionReturnCompletedEvent(inquiryId, orderItemId, BUYER_USER_ID, SELLER_USER_ID,
                LECTURE_TITLE, AT);
    }

    /** 반환 지연 회차 1의 반환 지연 */
    public static LectureObjectionReturnActionRequiredEvent returnActionRequired(Long inquiryId, Long orderItemId) {
        return new LectureObjectionReturnActionRequiredEvent(inquiryId, orderItemId, BUYER_USER_ID, SELLER_USER_ID,
                LECTURE_TITLE, 1, AT);
    }
}
