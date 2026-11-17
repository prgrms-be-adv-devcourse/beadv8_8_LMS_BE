package com.hapbang.auxiliary.chatbot.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 회원 이벤트 다시 처리 결과 한 건 (#976) */
public record MemberEventRetryResDto(
        @Schema(description = "실패 기록 ID") Long id,
        @Schema(description = "SUCCEEDED(반영함), FAILED(또 실패함, 기록은 PENDING으로 남음), ALREADY_RESOLVED(이미 처리됨), "
                + "IN_PROGRESS(다른 요청이 처리 중), NOT_FOUND(없는 기록), NOT_REPLAYABLE(다시 처리할 수 없는 메시지)")
        MemberEventRetryResult result,
        @Schema(description = "FAILED일 때 예외 클래스") String exceptionClass,
        @Schema(description = "FAILED일 때 메시지(이메일·전화를 지우고 200자로 자름. DB 예외는 SQLState만)") String message
) {

    public static MemberEventRetryResDto of(Long id, MemberEventRetryResult result) {
        return new MemberEventRetryResDto(id, result, null, null);
    }
}
