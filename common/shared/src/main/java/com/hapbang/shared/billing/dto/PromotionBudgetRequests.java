package com.hapbang.shared.billing.dto;

import java.util.regex.Pattern;

// 쿠폰 예산 요청·응답 DTO가 함께 쓰는 형식이다. 금액 같은 금융 값은 검사하지 않는다(지갑이 INVALID_POINT_AMOUNT 등으로 거절한다).
// 구조 오류는 호출한 쪽 코드의 오류라 IllegalArgumentException·NullPointerException으로 알린다(BillingRequests와 같다).
public final class PromotionBudgetRequests {

    /** 예산 예약 ID 형식 "PB-{숫자}". 지갑이 정하고 호출한 쪽은 받은 값을 그대로 보낸다 */
    public static final Pattern RESERVATION_ID = Pattern.compile("PB-[1-9][0-9]{0,17}");
    static final String RESERVATION_ID_PREFIX = "PB-";
    /** 해제 대상 최대 길이 */
    public static final int MAX_TARGET_LENGTH = 200;
    /** 해제 사유 최대 길이 */
    public static final int MAX_REASON_LENGTH = 500;

    private PromotionBudgetRequests() {
    }

    // 작업 ID가 있고 지갑 요청 ID 형식(영문·숫자·-·_ 1~200자, ':' 없음)인지 확인한다.
    public static void requireWorkId(String workId) {
        BillingRequests.requireRequestId(workId, "작업 ID");
    }

    // 예산 예약 ID가 있고 "PB-{숫자}" 형식인지 확인한다.
    public static void requireReservationId(String reservationId) {
        if (reservationId == null || !RESERVATION_ID.matcher(reservationId).matches()) {
            throw new IllegalArgumentException("예산 예약 ID 형식이 올바르지 않습니다: " + reservationId);
        }
    }

    /** 지갑의 예약 행 ID를 예산 예약 ID 문자열로 바꾼다 */
    public static String reservationId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("예산 예약 행 ID는 양수여야 합니다: " + id);
        }
        return RESERVATION_ID_PREFIX + id;
    }

    /** 예산 예약 ID 문자열에서 지갑의 예약 행 ID를 꺼낸다. 형식이 틀리면 IllegalArgumentException이다 */
    public static long reservationRowId(String reservationId) {
        requireReservationId(reservationId);
        return Long.parseLong(reservationId.substring(RESERVATION_ID_PREFIX.length()));
    }
}
