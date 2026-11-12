package com.hapbang.auxiliary.notification.app;

import java.util.Map;

/**
 * N7 회원 3종의 문구(#915, ND-121, 알림 종류 문서 0.10). 값({@link MemberReviewAlerts}가 만든다)만으로 문구를 만든다. 다른 회원의
 * 닉네임과 채널 이름은 넣지 않는다. 자동·관리자 승인은 같은 문구이고, 이미 인플루언서인 회원의 승인에도 쓰므로 역할이 바뀌었다고 쓰지
 * 않는다. 형식 버전 {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 다른 템플릿과 함께 등록한다.
 */
public enum MemberReviewTemplates implements NotificationTemplate {
    /** 채널 인증이 승인되었습니다. */
    INFLUENCER_CHANNEL_APPROVED {
        @Override
        public String render(Map<String, Object> payload) {
            return "채널 인증이 승인되었습니다.";
        }
    },
    /** 채널 인증이 반려되었습니다. 사유: {반려 사유} */
    INFLUENCER_CHANNEL_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "채널 인증이 반려되었습니다. 사유: " + payload.get(Keys.REJECTION_REASON);
        }
    },
    /** 인증된 인플루언서 승급 신청이 반려되었습니다. 사유: {반려 사유} */
    INFLUENCER_UPGRADE_REQUEST_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "인증된 인플루언서 승급 신청이 반려되었습니다. 사유: " + payload.get(Keys.REJECTION_REASON);
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
        static final String REJECTION_REASON = "rejectionReason";

        private Keys() {
        }
    }
}
