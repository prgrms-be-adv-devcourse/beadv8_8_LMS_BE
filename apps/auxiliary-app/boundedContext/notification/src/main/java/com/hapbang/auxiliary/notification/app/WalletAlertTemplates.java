package com.hapbang.auxiliary.notification.app;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * N7 지갑 4종의 문구(#907, ND-108, 알림 종류 문서 0.7). 값({@link WalletAlerts}가 만든다)만으로 문구를 만든다. 값은 jsonb로
 * 저장했다 읽으면 숫자 타입이 바뀌므로 숫자는 {@link Number}로 읽는다(ND-020). 다른 회원의 닉네임은 넣지 않는다. 형식 버전
 * {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 다른 템플릿과 함께 등록한다.
 */
public enum WalletAlertTemplates implements NotificationTemplate {
    /** {충전 HP} HP 충전에 실패했습니다. */
    CHARGE_FAILED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.AMOUNT_HP) + " HP 충전에 실패했습니다.";
        }
    },
    /** {요청 HP} HP 환불에 실패했습니다. 신청한 HP는 지갑으로 돌아왔습니다. */
    REFUND_FAILED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP 환불에 실패했습니다. 신청한 HP는 지갑으로 돌아왔습니다.";
        }
    },
    /** {요청 HP} HP 환불은 운영자 확인이 필요해 처리가 늦어지고 있습니다. */
    REFUND_MANUAL_REVIEW_REQUIRED {
        @Override
        public String render(Map<String, Object> payload) {
            return amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP 환불은 운영자 확인이 필요해 처리가 늦어지고 있습니다.";
        }
    },
    /** {신청 HP} HP 환전 신청이 승인되었습니다. {M월 d일}에 송금됩니다. (송금 예정일은 KST 날짜) */
    EXCHANGE_APPROVED {
        @Override
        public String render(Map<String, Object> payload) {
            String date = Instant.parse(String.valueOf(payload.get(Keys.PAYOUT_SCHEDULED_AT))).atZone(KST)
                    .format(MONTH_DAY);
            return amount(payload, Keys.REQUESTED_AMOUNT_HP) + " HP 환전 신청이 승인되었습니다. " + date + "에 송금됩니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

    @Override
    public String kind() {
        return name();
    }

    private static String amount(Map<String, Object> payload, String key) {
        return String.format(Locale.KOREA, "%,d", ((Number) payload.get(key)).longValue());
    }

    /** 값의 키. 문구에 쓰는 값만 둔다 */
    static final class Keys {
        static final String AMOUNT_HP = "amountHp";
        static final String REQUESTED_AMOUNT_HP = "requestedAmountHp";
        /** 송금 예정 시각. ISO-8601 UTC 문자열({@link Instant#toString()}) */
        static final String PAYOUT_SCHEDULED_AT = "payoutScheduledAt";

        private Keys() {
        }
    }
}
