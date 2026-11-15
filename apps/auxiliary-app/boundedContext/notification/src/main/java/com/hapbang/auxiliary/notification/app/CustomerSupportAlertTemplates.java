package com.hapbang.auxiliary.notification.app;

import java.util.Map;

/**
 * N7 고객센터 2종의 문구(#918, ND-126, 알림 종류 문서 0.11). 값({@link CustomerSupportAlerts}가 만든다)만으로 문구를 만든다.
 * 문의 유형과 강의 제목은 넣지 않고, 다른 회원의 닉네임도 넣지 않는다. 형식 버전 {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 다른 템플릿과 함께 등록한다.
 */
public enum CustomerSupportAlertTemplates implements NotificationTemplate {
    /** '{문의 제목}' 문의에 답변이 등록되었습니다. */
    INQUIRY_ANSWERED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + payload.get(Keys.TITLE) + "' 문의에 답변이 등록되었습니다.";
        }
    },
    /** 강의 내용 다름 이의 제기를 접수하지 못했습니다. 문의 내용을 확인해 주세요. */
    LECTURE_OBJECTION_FILE_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "강의 내용 다름 이의 제기를 접수하지 못했습니다. 문의 내용을 확인해 주세요.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    @Override
    public String kind() {
        return name();
    }

    /** 값의 키. 문구에 쓰는 값만 둔다 */
    static final class Keys {
        /** 문의 제목 */
        static final String TITLE = "title";

        private Keys() {
        }
    }
}
