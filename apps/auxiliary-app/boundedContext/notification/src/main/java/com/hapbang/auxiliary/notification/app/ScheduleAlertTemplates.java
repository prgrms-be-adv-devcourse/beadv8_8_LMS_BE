package com.hapbang.auxiliary.notification.app;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 예약 5종의 문구(#928, ND-140, 알림 종류 문서 0.13). 남은 시간이 아니라 기한 시각을 적어, 늦게 나가도 틀린 말이 되지 않는다. 값
 * ({@link ScheduleInputs}가 만든다)의 제목과 기한(KST)만 쓰고 다른 회원의 닉네임은 넣지 않는다. 형식 버전 {@code v}는 1이다.
 * {@link NotificationTemplates}가 다른 템플릿과 함께 등록한다.
 */
public enum ScheduleAlertTemplates implements NotificationTemplate {
    /** '{제목}' 출연 요청의 수락 기한은 {수락 기한}입니다. */
    APPEARANCE_REQUEST_EXPIRING {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 출연 요청의 수락 기한은 " + deadline(payload) + "입니다.";
        }
    },
    /** '{제목}' 모집이 {모집 마감 일시}에 마감됩니다. */
    APPEARANCE_RECRUIT_CLOSING {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 모집이 " + deadline(payload) + "에 마감됩니다.";
        }
    },
    /** '{제목}' 방송이 {방송 예정 일시}에 시작됩니다. */
    APPEARANCE_STARTING {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 방송이 " + deadline(payload) + "에 시작됩니다.";
        }
    },
    /** '{제목}' 출연을 확인해 주세요. {자동 확인 시각}에 자동으로 확인됩니다. */
    APPEARANCE_CONFIRM_PERIOD_ENDING {
        @Override
        public String render(Map<String, Object> payload) {
            return quoted(payload) + " 출연을 확인해 주세요. " + deadline(payload) + "에 자동으로 확인됩니다.";
        }
    },
    /** '{강의 제목}' 구매 취소는 {구매일 + 7일}까지 할 수 있습니다. */
    LECTURE_ORDER_ITEM_CONFIRMING {
        @Override
        public String render(Map<String, Object> payload) {
            LocalDate until = LocalDate.parse(String.valueOf(payload.get(Keys.CANCEL_UNTIL)));
            return quoted(payload) + " 구매 취소는 " + until.format(DATE) + "까지 할 수 있습니다.";
        }
    };

    /** 값의 형식 버전 */
    public static final int PAYLOAD_VERSION = 1;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    @Override
    public String kind() {
        return name();
    }

    private static String quoted(Map<String, Object> payload) {
        return "'" + payload.get(Keys.TITLE) + "'";
    }

    private static String deadline(Map<String, Object> payload) {
        return LocalDateTime.parse(String.valueOf(payload.get(Keys.DEADLINE))).format(DATE_TIME);
    }

    /** 값의 키 */
    static final class Keys {
        /** 요청서·모집글·강의 제목(예약을 만든 이벤트의 값) */
        static final String TITLE = "title";
        /** 출연 4종의 기한(KST {@code yyyy-MM-ddTHH:mm}) */
        static final String DEADLINE = "deadline";
        /** 구매 취소를 할 수 있는 마지막 날(KST {@code yyyy-MM-dd}) */
        static final String CANCEL_UNTIL = "cancelUntil";

        private Keys() {
        }
    }
}
