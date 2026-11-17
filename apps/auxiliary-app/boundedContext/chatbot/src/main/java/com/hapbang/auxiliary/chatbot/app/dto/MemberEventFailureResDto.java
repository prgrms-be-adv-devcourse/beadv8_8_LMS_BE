package com.hapbang.auxiliary.chatbot.app.dto;

import java.time.LocalDateTime;

import com.hapbang.auxiliary.chatbot.domain.MemberEventFailure;
import com.hapbang.auxiliary.chatbot.domain.MemberEventFailureStatus;
import com.hapbang.auxiliary.chatbot.domain.MemberEventType;

import io.swagger.v3.oas.annotations.media.Schema;

/** DLT로 보낸 회원 이벤트의 실패 기록 (#976). 원문은 내보내지 않는다 */
public record MemberEventFailureResDto(
        Long id,
        @Schema(description = "회원 ID. 읽지 못한 메시지는 key에서 읽고, 모르면 null") Long userId,
        @Schema(description = "JOINED(가입), MODIFIED(변경), MASKED(마스킹), UNREADABLE(읽지 못한 메시지)") MemberEventType eventType,
        @Schema(description = "다시 처리할 수 있는지(읽지 못함·필수 값 없음이면 false)") boolean replayable,
        @Schema(description = "PENDING(처리 대기), RESOLVED(처리됨)") MemberEventFailureStatus status,
        @Schema(description = "원래 메시지의 파티션") int sourcePartition,
        @Schema(description = "원래 메시지의 offset") long sourceOffset,
        @Schema(description = "마지막 실패의 예외 클래스") String exceptionClass,
        @Schema(description = "마지막 실패의 가장 안쪽 원인 클래스") String rootCauseClass,
        @Schema(description = "마지막 실패의 메시지(이메일·전화를 지우고 200자로 자름. DB 예외는 SQLState만, JSON 읽기 예외는 없음)")
        String message,
        @Schema(description = "DLT로 보낸 시각") LocalDateTime failedAt,
        @Schema(description = "관리자가 다시 처리한 횟수") int retryCount,
        LocalDateTime lastRetriedAt,
        LocalDateTime resolvedAt,
        @Schema(description = "다시 처리해 성공시킨 관리자 회원 ID") Long resolvedBy
) {

    public static MemberEventFailureResDto from(MemberEventFailure failure) {
        return new MemberEventFailureResDto(failure.getId(), failure.getUserId(), failure.getEventType(),
                failure.isReplayable(), failure.getStatus(), failure.getSourcePartition(), failure.getSourceOffset(),
                failure.getExceptionClass(), failure.getRootCauseClass(), failure.getMessage(), failure.getFailedAt(),
                failure.getRetryCount(), failure.getLastRetriedAt(), failure.getResolvedAt(), failure.getResolvedBy());
    }
}
