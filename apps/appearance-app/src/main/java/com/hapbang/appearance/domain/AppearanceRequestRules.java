package com.hapbang.appearance.domain;

/**
 * 출연 요청서에만 있는 입력 값의 제한. 도메인({@link AppearanceRequest})과 요청 DTO의 Bean Validation이 같이 쓴다.
 * 요청서·모집글 공통 제한(제목·URL·설명·예상 출연 시간)은 {@link AppearanceContentRules}에 있다.
 */
public final class AppearanceRequestRules {

    /** 거절 사유: 선택 입력, 500자 이하 (DB 컬럼 VARCHAR(500)) */
    public static final int REJECT_REASON_MAX = 500;

    private AppearanceRequestRules() {
    }

    static void validateRejectReason(String reason) {
        if (reason != null && reason.length() > REJECT_REASON_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "거절 사유는 %d자 이하여야 합니다.".formatted(REJECT_REASON_MAX));
        }
    }
}
