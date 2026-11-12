package com.hapbang.auxiliary.notification.app;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.hapbang.auxiliary.notification.domain.NotificationPayload;

/**
 * 강의 지급 하루 묶음의 문구(ND-098·ND-100·ND-104). 강의 제목은 넣지 않는다: 묶음은 여러 강의의 지급을 합쳐 제목 하나로 나타낼 수
 * 없다(지급 이벤트의 {@code sourceTitle}은 읽지 않는다). 나중에 제목을 넣으면 형식 버전 {@code v}를 올린다. 값은 jsonb로 저장했다 읽으면 숫자 타입이 바뀌므로 숫자는 {@link Number}로 읽는다(ND-020).
 */
public enum PayoutDailyTemplate implements NotificationTemplate {

    /** {M월 d일} 강의 판매 수익 {합계} HP가 지급되었습니다. ({건수}건) */
    PAYOUT_COMPLETED_DAILY {
        @Override
        public String render(Map<String, Object> payload) {
            return LocalDate.parse(String.valueOf(payload.get(Keys.DATE))).format(MONTH_DAY) + " 강의 판매 수익 "
                    + format(totalPaidAmountHp(payload)) + " HP가 지급되었습니다. (" + format(count(payload)) + "건)";
        }
    };

    /** 값 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN);

    @Override
    public String kind() {
        return name();
    }

    /** 묶음의 값. 날짜는 KST {@code yyyy-MM-dd} */
    public static Map<String, Object> payload(LocalDate date, long totalPaidAmountHp, long count) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(NotificationPayload.VERSION, PAYLOAD_VERSION);
        payload.put(Keys.DATE, date.toString());
        payload.put(Keys.TOTAL_PAID_AMOUNT_HP, totalPaidAmountHp);
        payload.put(Keys.COUNT, count);
        return payload;
    }

    /** 묶음의 지금 값에 지급 한 건을 더한 새 값(합계 + 지급 HP, 건수 + 1) */
    static Map<String, Object> added(Map<String, Object> current, DailyPayout payout) {
        return payload(payout.bundleDate(), totalPaidAmountHp(current) + payout.paidAmountHp(), count(current) + 1);
    }

    private static long totalPaidAmountHp(Map<String, Object> payload) {
        return ((Number) payload.get(Keys.TOTAL_PAID_AMOUNT_HP)).longValue();
    }

    private static long count(Map<String, Object> payload) {
        return ((Number) payload.get(Keys.COUNT)).longValue();
    }

    private static String format(long value) {
        return String.format(Locale.KOREA, "%,d", value);
    }

    /** 값의 키 */
    static final class Keys {
        static final String DATE = "date";
        static final String TOTAL_PAID_AMOUNT_HP = "totalPaidAmountHp";
        static final String COUNT = "count";

        private Keys() {
        }
    }
}
