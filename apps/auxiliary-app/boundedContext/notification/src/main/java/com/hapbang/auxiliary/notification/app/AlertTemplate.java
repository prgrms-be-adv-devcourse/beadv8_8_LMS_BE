package com.hapbang.auxiliary.notification.app;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationPayload;
import com.hapbang.shared.billing.domain.PayoutType;

/**
 * N5 11종의 문구(ND-075, 알림 종류 문서 0.5). 값({@link AlertDrafts}가 만든다)만으로 문구를 만든다. 값은 jsonb로 저장했다 읽으면
 * 숫자 타입이 바뀌므로 숫자는 {@link Number}로 읽는다(ND-020). 다른 회원 닉네임은 값의 {@code nicknames}에서 읽어, 파기 뒤
 * 바뀐 닉네임으로 다시 만들 수 있다(ND-051).
 * <p>
 * 값의 키는 {@link Keys}다. 형식 버전 {@code v}는 1이다. 지급 알림에 제목이 생기면(M5, ND-070) 버전을 올린다.
 */
public enum AlertTemplate implements NotificationTemplate {

    /** {호스트 닉네임}님이 '{제목}' 출연을 요청했습니다. {M월 d일 HH:mm}까지 응답해 주세요. */
    APPEARANCE_REQUEST_SENT {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.HOST_ID) + "님이 '" + text(payload, Keys.TITLE) + "' 출연을 요청했습니다. "
                    + LocalDateTime.parse(text(payload, Keys.ACCEPT_DEADLINE)).format(MONTH_DAY_TIME)
                    + "까지 응답해 주세요.";
        }
    },
    /** {게스트 닉네임}님이 '{모집글 제목}' 모집에 지원했습니다. */
    APPEARANCE_APPLICATION_SUBMITTED {
        @Override
        public String render(Map<String, Object> payload) {
            return nickname(payload, Keys.GUEST_ID) + "님이 '" + text(payload, Keys.RECRUIT_TITLE) + "' 모집에 지원했습니다.";
        }
    },
    /** {충전 HP} HP 충전이 완료되었습니다. */
    CHARGE_COMPLETED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.AMOUNT_HP) + " HP 충전이 완료되었습니다.";
        }
    },
    /** 전부: {환불 HP} HP 환불이 완료되었습니다. / 일부: 환불 신청 {요청 HP} HP 중 {환불 HP} HP가 환불되었습니다. */
    REFUND_COMPLETED {
        @Override
        public String render(Map<String, Object> payload) {
            long requested = number(payload, Keys.REQUESTED_AMOUNT_HP);
            long refunded = number(payload, Keys.REFUNDED_AMOUNT_HP);
            if (requested == refunded) {
                return format(refunded) + " HP 환불이 완료되었습니다.";
            }
            return "환불 신청 " + format(requested) + " HP 중 " + format(refunded) + " HP가 환불되었습니다.";
        }
    },
    /** NORMAL: 출연료 {지급 HP} HP가 지급되었습니다. / PENALTY: 위약금 {지급 HP} HP가 지급되었습니다. (제목 없음, ND-070) */
    PAYOUT_COMPLETED {
        @Override
        public String render(Map<String, Object> payload) {
            String what = PayoutType.PENALTY.name().equals(text(payload, Keys.PAYOUT_TYPE)) ? "위약금" : "출연료";
            return what + " " + amount(payload, Keys.PAID_AMOUNT_HP) + " HP가 지급되었습니다.";
        }
    },
    /** {신청 HP} HP 환전 신청이 거절되었습니다. */
    EXCHANGE_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP 환전 신청이 거절되었습니다.";
        }
    },
    /** {신청 HP} HP 환전 송금이 완료되었습니다. 입금액 {원}원 (수수료 {수수료 HP} HP) */
    EXCHANGE_COMPLETED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP 환전 송금이 완료되었습니다. 입금액 "
                    + amount(payload, Keys.PAYOUT_AMOUNT_KRW) + "원 (수수료 " + amount(payload, Keys.FEE_AMOUNT_HP)
                    + " HP)";
        }
    },
    /** 환전 송금({원}원)이 지연되고 있습니다. 다시 시도합니다. */
    EXCHANGE_PAYOUT_FAILED {
        @Override
        public String render(Map<String, Object> payload) {
            return "환전 송금(" + amount(payload, Keys.PAYOUT_AMOUNT_KRW) + "원)이 지연되고 있습니다. 다시 시도합니다.";
        }
    },
    /** 환전 송금에 실패해 {신청 HP} HP를 돌려드렸습니다. */
    EXCHANGE_FINAL_FAILED {
        @Override
        public String render(Map<String, Object> payload) {
            return "환전 송금에 실패해 " + amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP를 돌려드렸습니다.";
        }
    },
    /** 합방에 오신 것을 환영합니다. */
    USER_JOINED {
        @Override
        public String render(Map<String, Object> payload) {
            return "합방에 오신 것을 환영합니다.";
        }
    },
    /** 인증된 인플루언서로 승급되었습니다. (종류 코드 ND-071) */
    INFLUENCER_UPGRADE_REQUEST_APPROVED {
        @Override
        public String render(Map<String, Object> payload) {
            return "인증된 인플루언서로 승급되었습니다.";
        }
    };

    /** 값 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    private static final DateTimeFormatter MONTH_DAY_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN);

    @Override
    public String kind() {
        return name();
    }

    /** 값의 키 */
    static final class Keys {
        static final String HOST_ID = "hostId";
        static final String GUEST_ID = "guestId";
        static final String TITLE = "title";
        static final String RECRUIT_TITLE = "recruitTitle";
        static final String FEE = "fee";
        /** 응답 기한. KST {@code LocalDateTime} ISO 문자열 */
        static final String ACCEPT_DEADLINE = "acceptDeadline";
        static final String AMOUNT_HP = "amountHp";
        static final String PAYMENT_AMOUNT_KRW = "paymentAmountKrw";
        static final String REQUESTED_AMOUNT_HP = "requestedAmountHp";
        static final String REFUNDED_AMOUNT_HP = "refundedAmountHp";
        static final String PAYOUT_TYPE = "payoutType";
        static final String SOURCE_TYPE = "sourceType";
        static final String PAID_AMOUNT_HP = "paidAmountHp";
        static final String FEE_AMOUNT_HP = "feeAmountHp";
        static final String PAYOUT_AMOUNT_KRW = "payoutAmountKrw";
        static final String ATTEMPT_NO = "attemptNo";

        private Keys() {
        }
    }

    private static String nickname(Map<String, Object> payload, String idKey) {
        return NotificationPayload.nicknames(payload).get(text(payload, idKey));
    }

    private static String text(Map<String, Object> payload, String key) {
        return String.valueOf(payload.get(key));
    }

    private static long number(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).longValue();
    }

    private static String amount(Map<String, Object> payload, String key) {
        return format(number(payload, key));
    }

    private static String format(long value) {
        return String.format(Locale.KOREA, "%,d", value);
    }
}
