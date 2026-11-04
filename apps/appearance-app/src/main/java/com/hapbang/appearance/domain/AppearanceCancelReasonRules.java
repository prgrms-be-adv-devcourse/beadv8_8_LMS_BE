package com.hapbang.appearance.domain;

/**
 * 호스트 일방 취소·모집 전체 취소 사유와 불참 사유 규칙 (선택 입력, 500자 이하, ISSUE.md APR-32 H1·APR-35 E2).
 */
public final class AppearanceCancelReasonRules {

    /** 사유 최대 길이 (DB 컬럼 VARCHAR(500)) */
    public static final int REASON_MAX = 500;

    private AppearanceCancelReasonRules() {
    }

    /**
     * 선택 사유를 다듬는다. 비었거나 공백뿐이면 null.
     *
     * @throws AppearanceException 500자 초과({@code INVALID_REQUEST})
     */
    public static String optional(String reason) {
        return optional(reason, "취소 사유");
    }

    /**
     * 선택 사유를 다듬는다. 비었거나 공백뿐이면 null.
     *
     * @param label 오류 메시지에 쓸 이름 (예: "불참 사유")
     * @throws AppearanceException 500자 초과({@code INVALID_REQUEST})
     */
    public static String optional(String reason, String label) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        if (trimmed.length() > REASON_MAX) {
            throw new AppearanceException(AppearanceErrorCode.INVALID_REQUEST,
                    "%s는 %d자 이하여야 합니다.".formatted(label, REASON_MAX));
        }
        return trimmed;
    }
}
