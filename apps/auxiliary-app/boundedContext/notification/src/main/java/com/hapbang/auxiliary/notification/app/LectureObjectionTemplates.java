package com.hapbang.auxiliary.notification.app;

import java.util.Map;

/**
 * 이의 제기 결과 4종의 문구(N6, ND-085, 알림 종류 문서 0.6). 값({@link LectureObjectionAlerts}가 만든다)만으로 문구를 만든다.
 * 다른 회원 닉네임은 넣지 않는다. 형식 버전 {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 N5 템플릿과 함께 등록한다.
 */
public enum LectureObjectionTemplates implements NotificationTemplate {
    /**
     * 인정: 구매자 '{강의 제목}' 이의 제기가 인정되어 HP를 반환합니다. / 판매자 '{강의 제목}' 이의 제기가 인정되었습니다.
     * 기각: 구매자·판매자 '{강의 제목}' 이의 제기가 기각되었습니다.
     */
    LECTURE_OBJECTION_RESOLVED {
        @Override
        public String render(Map<String, Object> payload) {
            if (!Boolean.TRUE.equals(payload.get(Keys.ACCEPTED))) {
                return "'" + title(payload) + "' 이의 제기가 기각되었습니다.";
            }
            if (Keys.ROLE_BUYER.equals(payload.get(Keys.RECEIVER_ROLE))) {
                return "'" + title(payload) + "' 이의 제기가 인정되어 HP를 반환합니다.";
            }
            return "'" + title(payload) + "' 이의 제기가 인정되었습니다.";
        }
    },
    /** '{강의 제목}' 이의 제기 HP 반환이 완료되었습니다. */
    LECTURE_OBJECTION_RETURN_COMPLETED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 이의 제기 HP 반환이 완료되었습니다.";
        }
    },
    /** '{강의 제목}' 이의 제기가 철회되었습니다. */
    LECTURE_OBJECTION_WITHDRAWN {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 이의 제기가 철회되었습니다.";
        }
    },
    /** '{강의 제목}' HP 반환이 늦어지고 있습니다. 완료되면 다시 알려 드립니다. */
    LECTURE_OBJECTION_RETURN_ACTION_REQUIRED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' HP 반환이 늦어지고 있습니다. 완료되면 다시 알려 드립니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    @Override
    public String kind() {
        return name();
    }

    private static String title(Map<String, Object> payload) {
        return String.valueOf(payload.get(Keys.LECTURE_TITLE));
    }

    /** 값의 키 */
    static final class Keys {
        static final String LECTURE_TITLE = "lectureTitle";
        static final String ACCEPTED = "accepted";
        /** 판단 알림의 받는 사람 역할({@value #ROLE_BUYER}·{@value #ROLE_SELLER}). 문구를 나눈다 */
        static final String RECEIVER_ROLE = "receiverRole";
        static final String ROLE_BUYER = "BUYER";
        static final String ROLE_SELLER = "SELLER";
        static final String RETURN_DELAY_SEQUENCE = "returnDelaySequence";

        private Keys() {
        }
    }
}
