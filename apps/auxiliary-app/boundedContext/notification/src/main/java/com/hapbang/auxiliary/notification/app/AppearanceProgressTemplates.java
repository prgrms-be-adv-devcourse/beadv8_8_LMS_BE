package com.hapbang.auxiliary.notification.app;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationPayload;

/**
 * 출연 진행 알림 10종의 문구(N7 출연, ND-113, 알림 종류 문서 0.8). 값({@link AppearanceProgressAlerts}가 만든다)만으로 문구를
 * 만든다. 행동한 상대의 닉네임은 값의 {@code nicknames}에서 읽어 파기 뒤 바뀐 닉네임으로 다시 만들 수 있다(ND-051). 형식 버전
 * {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 다른 내장 템플릿과 함께 등록한다.
 */
public enum AppearanceProgressTemplates implements NotificationTemplate {
    /** {게스트 닉네임}님이 '{제목}' 출연 요청을 수락했습니다. {M월 d일 HH:mm} 방송 예정입니다. */
    APPEARANCE_REQUEST_ACCEPTED {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.GUEST_ID) + "님이 '" + title(payload) + "' 출연 요청을 수락했습니다. "
                    + scheduledAt(payload) + " 방송 예정입니다.";
        }
    },
    /** {게스트 닉네임}님이 '{제목}' 출연 요청을 거절했습니다. */
    APPEARANCE_REQUEST_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.GUEST_ID) + "님이 '" + title(payload) + "' 출연 요청을 거절했습니다.";
        }
    },
    /** {호스트 닉네임}님이 '{제목}' 출연 요청을 취소했습니다. */
    APPEARANCE_REQUEST_CANCELED {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.HOST_ID) + "님이 '" + title(payload) + "' 출연 요청을 취소했습니다.";
        }
    },
    /** 호스트: {게스트 닉네임}님이 응답하지 않아 … 만료되었습니다. / 게스트: 응답 기한이 지나 … 만료되었습니다. */
    APPEARANCE_REQUEST_EXPIRED {
        @Override
        public String render(Map<String, Object> payload) {
            if (Keys.RECEIVER_HOST.equals(payload.get(Keys.RECEIVER_ROLE))) {
                return nickname(payload, Keys.GUEST_ID) + "님이 응답하지 않아 '" + title(payload)
                        + "' 출연 요청이 만료되었습니다.";
            }
            return "응답 기한이 지나 '" + title(payload) + "' 출연 요청이 만료되었습니다.";
        }
    },
    /** {호스트 닉네임}님의 '{모집글 제목}' 모집에 선정되었습니다. {M월 d일 HH:mm} 방송 예정입니다. */
    APPEARANCE_APPLICATION_SELECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.HOST_ID) + "님의 '" + title(payload) + "' 모집에 선정되었습니다. "
                    + scheduledAt(payload) + " 방송 예정입니다.";
        }
    },
    /** '{모집글 제목}' 모집에 선정되지 않았습니다. */
    APPEARANCE_APPLICATION_NOT_SELECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 모집에 선정되지 않았습니다.";
        }
    },
    /** '{모집글 제목}' 모집이 마감 일시가 되어 마감되었습니다. */
    APPEARANCE_RECRUIT_CLOSED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 모집이 마감 일시가 되어 마감되었습니다.";
        }
    },
    /** {호스트 닉네임}님이 '{제목}' 출연을 취소했습니다. 위약금은 넣지 않는다(ND-113) */
    APPEARANCE_CANCELED_BY_HOST {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.HOST_ID) + "님이 '" + title(payload) + "' 출연을 취소했습니다.";
        }
    },
    /** 출연 확인 기간이 지나 '{제목}' 출연이 자동 확인되었습니다. */
    APPEARANCE_CONFIRMED {
        @Override
        public String render(Map<String, Object> payload) {
            return "출연 확인 기간이 지나 '" + title(payload) + "' 출연이 자동 확인되었습니다.";
        }
    },
    /** '{제목}' 출연이 불참 처리되었습니다. */
    APPEARANCE_ABSENT {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 출연이 불참 처리되었습니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    private static final DateTimeFormatter MONTH_DAY_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN);

    @Override
    public String kind() {
        return name();
    }

    private static String title(Map<String, Object> payload) {
        return String.valueOf(payload.get(Keys.TITLE));
    }

    private static String nickname(Map<String, Object> payload, String memberIdKey) {
        return NotificationPayload.nicknames(payload).get(String.valueOf(payload.get(memberIdKey)));
    }

    private static String scheduledAt(Map<String, Object> payload) {
        return LocalDateTime.parse(String.valueOf(payload.get(Keys.SCHEDULED_AT))).format(MONTH_DAY_TIME);
    }

    /** 값의 키 */
    static final class Keys {
        static final String HOST_ID = "hostId";
        static final String GUEST_ID = "guestId";
        /** 출연 제목 또는 모집글 제목 */
        static final String TITLE = "title";
        /** 방송 예정 일시. KST {@code LocalDateTime} ISO 문자열 */
        static final String SCHEDULED_AT = "scheduledAt";
        /** 요청 만료의 받는 사람 구분({@value #RECEIVER_HOST}·{@value #RECEIVER_GUEST}) */
        static final String RECEIVER_ROLE = "receiverRole";
        static final String RECEIVER_HOST = "HOST";
        static final String RECEIVER_GUEST = "GUEST";

        private Keys() {
        }
    }
}
