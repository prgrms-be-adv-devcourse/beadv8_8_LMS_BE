package com.hapbang.auxiliary.notification.domain;

/**
 * 예약 알림 5종(알림 종류 7장). 종류 코드는 이름 그대로다(ND-137). 이동 대상 종류는 ND-139이고 대상 ID는 예약의 대상 ID와 같다.
 */
public enum ScheduleKind {
    /** 출연 요청 만료 임박: 게스트, 요청서 ID */
    APPEARANCE_REQUEST_EXPIRING("APPEARANCE_REQUEST"),
    /** 모집 마감 임박: 호스트, 모집글 ID */
    APPEARANCE_RECRUIT_CLOSING("APPEARANCE_RECRUIT"),
    /** 방송 임박: 호스트·게스트, 출연 ID */
    APPEARANCE_STARTING("APPEARANCE"),
    /** 출연 확인 부탁: 호스트, 출연 ID */
    APPEARANCE_CONFIRM_PERIOD_ENDING("APPEARANCE"),
    /** 구매 취소 마감 임박: 구매자, 주문 항목 ID */
    LECTURE_ORDER_ITEM_CONFIRMING("LECTURE_ORDER_ITEM");

    private final String targetType;

    ScheduleKind(String targetType) {
        this.targetType = targetType;
    }

    /** 종류 코드 */
    public String kind() {
        return name();
    }

    /** 알림의 이동 대상 종류(ND-139) */
    public String targetType() {
        return targetType;
    }
}
