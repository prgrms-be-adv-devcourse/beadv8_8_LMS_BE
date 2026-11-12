package com.hapbang.auxiliary.notification.app;

import java.util.Locale;
import java.util.Map;

/**
 * 강의 진행 알림 7종의 문구(N7 강의, ND-116, 알림 종류 문서 0.9). 값({@link LectureProgressAlerts}가 만든다)만으로 문구를 만든다.
 * 다른 회원 닉네임은 넣지 않는다. 형식 버전 {@code v}는 1이다.
 * <p>
 * {@link NotificationTemplates}가 다른 내장 템플릿과 함께 등록한다.
 */
public enum LectureProgressTemplates implements NotificationTemplate {
    /** '{강의 제목}' 강의 검수가 승인되어 판매가 시작되었습니다. */
    LECTURE_APPROVED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 강의 검수가 승인되어 판매가 시작되었습니다.";
        }
    },
    /** '{강의 제목}' 강의 검수가 반려되었습니다. 사유: {반려 사유} */
    LECTURE_REJECTED {
        @Override
        public String render(Map<String, Object> payload) {
            return "'" + title(payload) + "' 강의 검수가 반려되었습니다. 사유: " + payload.get(Keys.REJECTION_REASON);
        }
    },
    /** '{강의 제목}' 강의 구매가 완료되었습니다. / '{첫 항목 강의 제목}' 외 {n}개 강의 구매가 완료되었습니다. */
    LECTURE_ORDER_PAID {
        @Override
        public String render(Map<String, Object> payload) {
            return items(payload) + " 강의 구매가 완료되었습니다.";
        }
    },
    /** '{강의 제목}' 강의 결제에 실패했습니다. / '{첫 항목 강의 제목}' 외 {n}개 강의 결제에 실패했습니다. 원인은 말하지 않는다(ND-113) */
    LECTURE_ORDER_HOLD_FAILED {
        @Override
        public String render(Map<String, Object> payload) {
            return items(payload) + " 강의 결제에 실패했습니다.";
        }
    },
    /** '{강의 제목}' 구매가 취소되어 {반환 HP} HP를 돌려드렸습니다. 반환 HP가 0이면 '{강의 제목}' 구매가 취소되었습니다. */
    LECTURE_ORDER_ITEM_CANCELED {
        @Override
        public String render(Map<String, Object> payload) {
            long returned = number(payload, Keys.BUYER_AMOUNT_HP);
            if (returned == 0) {
                return "'" + title(payload) + "' 구매가 취소되었습니다.";
            }
            return "'" + title(payload) + "' 구매가 취소되어 " + String.format(Locale.KOREA, "%,d", returned)
                    + " HP를 돌려드렸습니다.";
        }
    },
    /** 구매 확정 기한이 지나 '{강의 제목}' 구매가 확정되었습니다. (자동 구매 확정만) */
    LECTURE_ORDER_ITEM_CONFIRMED {
        @Override
        public String render(Map<String, Object> payload) {
            return "구매 확정 기한이 지나 '" + title(payload) + "' 구매가 확정되었습니다.";
        }
    },
    /** 구매자 '{강의 제목}' 이의 제기가 접수되었습니다. / 판매자 '{강의 제목}' 이의 제기가 접수되어 지급이 멈췄습니다. */
    LECTURE_OBJECTION_FILED {
        @Override
        public String render(Map<String, Object> payload) {
            if (Keys.ROLE_SELLER.equals(payload.get(Keys.RECEIVER_ROLE))) {
                return "'" + title(payload) + "' 이의 제기가 접수되어 지급이 멈췄습니다.";
            }
            return "'" + title(payload) + "' 이의 제기가 접수되었습니다.";
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

    /** '{첫 항목 제목}' 또는 '{첫 항목 제목}' 외 {n}개 */
    private static String items(Map<String, Object> payload) {
        long others = number(payload, Keys.OTHER_ITEM_COUNT);
        String first = "'" + title(payload) + "'";
        return others == 0 ? first : first + " 외 " + others + "개";
    }

    private static long number(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).longValue();
    }

    /** 값의 키 */
    static final class Keys {
        /** 강의 제목. 주문 2종은 첫 항목의 제목이다 */
        static final String LECTURE_TITLE = "lectureTitle";
        static final String REJECTION_REASON = "rejectionReason";
        /** 주문 2종에서 첫 항목을 뺀 항목 수 */
        static final String OTHER_ITEM_COUNT = "otherItemCount";
        static final String BUYER_AMOUNT_HP = "buyerAmountHp";
        /** 이의 제기 접수의 받는 사람 역할({@value #ROLE_BUYER}·{@value #ROLE_SELLER}). 문구를 나눈다 */
        static final String RECEIVER_ROLE = "receiverRole";
        static final String ROLE_BUYER = "BUYER";
        static final String ROLE_SELLER = "SELLER";

        private Keys() {
        }
    }
}
