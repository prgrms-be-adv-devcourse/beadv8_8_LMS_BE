package com.hapbang.coupon.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 발급 접수 요청. 형식이 틀리면 400 {@code INVALID_REQUEST}(errors에 issueRequestId)다 */
public record IssueRequestCreateRequest(
        @Schema(description = "클라이언트가 만든 발급 요청 ID. 1~64자의 영문·숫자·'_'·'-'(예: UUID). "
                + "응답을 받지 못해 다시 보낼 때는 같은 값을 보낸다(같은 결과를 받는다)",
                example = "3f1c9a52-7d4b-4e7a-9a51-0c2f6d1e8b77")
        @NotNull
        @Pattern(regexp = ISSUE_REQUEST_ID_PATTERN, message = "발급 요청 ID는 1~64자의 영문·숫자·'_'·'-'여야 합니다.")
        String issueRequestId
) {

    /** 발급 요청 ID 형식: 1~64자의 영문·숫자·'_'·'-' */
    public static final String ISSUE_REQUEST_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
}
