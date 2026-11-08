package com.hapbang.lecture.domain;

import java.util.Locale;

/**
 * 회원 탈퇴가 막힌 강의 쪽 이유 (#625, D75). 상수 이름이 응답의 {@code code}이고, 선언 순서가 응답 순서다.
 * 주문 항목 하나는 이유 하나로만 센다(이의 제기 접수 중이 먼저다).
 */
public enum LectureWithdrawalBlockCode {

    /** 구매자: 결제 처리 중·결제 취소 처리 중·구매 취소 처리 중·반환 처리 중 */
    LECTURE_ORDER_PROCESSING("처리 중인 주문 %s건이 있어요. 잠시 뒤 다시 시도해 주세요.", false),
    /** 구매자: 구매·구매 확정·지급 처리 중. 구매 HP가 아직 보류 중이다 (지급 예정일까지 막는다) */
    LECTURE_ORDER_HOLDING("구매한 강의 %s건의 이의 제기 기간이 끝나지 않았어요. 기간이 끝난 뒤 탈퇴할 수 있어요.", true),
    /** 구매자: 이의 제기 접수 중 */
    LECTURE_OBJECTION_FILED("처리 중인 이의 제기 %s건이 있어요. 결과가 나온 뒤 탈퇴할 수 있어요.", false),
    /** 판매자: 아직 지급하지 않은 판매 수익 (수익 분류의 미지급 다섯 상태) */
    LECTURE_SALE_PAYOUT_PENDING("아직 지급되지 않은 판매 수익 %s건이 있어요. 지급이 끝난 뒤 탈퇴할 수 있어요.", true),
    /** 판매자: 삭제하지 않은 판매 중·검수 대기 강의. 삭제하면 신규 판매만 멈추고 시청·지급은 그대로다 (D5) */
    LECTURE_ON_SALE("판매 중이거나 검수 대기 중인 강의 %s개가 있어요. 강의를 삭제한 뒤 탈퇴할 수 있어요.", false);

    private final String messageTemplate;
    private final boolean releasedByPayout;

    LectureWithdrawalBlockCode(String messageTemplate, boolean releasedByPayout) {
        this.messageTemplate = messageTemplate;
        this.releasedByPayout = releasedByPayout;
    }

    /** 사용자에게 그대로 보여 줄 문장. 건수는 천 단위 쉼표로 쓴다 */
    public String message(long count) {
        return messageTemplate.formatted(String.format(Locale.ROOT, "%,d", count));
    }

    /** 지급이 끝나면 풀리는 이유인지. 이 이유만 풀리는 날짜({@code availableAt})를 줄 수 있다 */
    public boolean isReleasedByPayout() {
        return releasedByPayout;
    }
}
