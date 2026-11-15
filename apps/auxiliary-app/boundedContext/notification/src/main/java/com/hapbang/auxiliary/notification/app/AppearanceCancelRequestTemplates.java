package com.hapbang.auxiliary.notification.app;

import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationPayload;

/**
 * 출연 취소 요청 3종의 문구(N6, ND-085, 알림 종류 문서 0.6). 값({@link AppearanceCancelRequestAlerts}가 만든다)만으로 문구를
 * 만든다. 게스트 닉네임은 값의 {@code nicknames}에서 읽어 파기 뒤 바뀐 닉네임으로 다시 만들 수 있다(ND-051). 형식 버전 {@code v}는
 * 1이다.
 * <p>
 * {@link NotificationTemplates}가 N5 템플릿과 함께 등록한다.
 */
public enum AppearanceCancelRequestTemplates implements NotificationTemplate {
    /** {게스트 닉네임}님이 '{제목}' 출연 취소를 요청했습니다. */
    APPEARANCE_CANCEL_REQUESTED {
        @Override
        public String render(Map<String, Object> payload) {
            String guestId = String.valueOf(payload.get(Keys.GUEST_ID));
            return NotificationPayload.nicknames(payload).get(guestId) + "님이 '" + title(payload)
                    + "' 출연 취소를 요청했습니다.";
        }
    },
    /** '{제목}' 출연 취소 요청이 수락되어 출연이 취소되었습니다. */
    APPEARANCE_CANCEL_REQUEST_ACCEPTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 출연 취소 요청이 수락되어 출연이 취소되었습니다.";
        }
    },
    /** 거절: '{제목}' 출연 취소 요청이 거절되었습니다. … / 자동 거절: 호스트가 응답하지 않아 … 자동 거절되었습니다. … */
    APPEARANCE_CANCEL_REQUEST_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            if (Boolean.TRUE.equals(payload.get(Keys.AUTO_REJECTED))) {
                return "호스트가 응답하지 않아 '" + title(payload) + "' 출연 취소 요청이 자동 거절되었습니다. 출연은 예정대로 진행됩니다.";
            }
            return "'" + title(payload) + "' 출연 취소 요청이 거절되었습니다. 출연은 예정대로 진행됩니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    @Override
    public String kind() {
        return name();
    }

    private static String title(Map<String, Object> payload) {
        return String.valueOf(payload.get(Keys.TITLE));
    }

    /** 값의 키 */
    static final class Keys {
        static final String GUEST_ID = "guestId";
        static final String TITLE = "title";
        static final String AUTO_REJECTED = "autoRejected";

        private Keys() {
        }
    }
}
