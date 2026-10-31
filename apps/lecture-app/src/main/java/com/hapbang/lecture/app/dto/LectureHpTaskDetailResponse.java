package com.hapbang.lecture.app.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.hapbang.lecture.domain.LectureHpAttemptResult;
import com.hapbang.lecture.domain.LectureHpTask;
import com.hapbang.lecture.domain.LectureHpTaskAttempt;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "강의 HP 작업 상세와 시도 이력 (관리자, D57)")
public record LectureHpTaskDetailResponse(
        LectureHpTaskResponse task,
        @Schema(description = "시도 순")
        List<Attempt> attempts
) {

    public static LectureHpTaskDetailResponse of(LectureHpTask task, List<LectureHpTaskAttempt> attempts) {
        return new LectureHpTaskDetailResponse(LectureHpTaskResponse.of(task),
                attempts.stream().map(Attempt::of).toList());
    }

    @Schema(description = "선점 한 번의 시도. 원래 오류 코드·진단 정보는 관리자만 본다")
    public record Attempt(
            int attemptNo,
            @Schema(description = "실행 중이면 null", nullable = true)
            LectureHpAttemptResult result,
            @Schema(nullable = true)
            String errorCode,
            @Schema(description = "변환 전 지갑 오류 코드", nullable = true)
            String rawErrorCode,
            @Schema(description = "예외 종류·메시지 등 진단 정보", nullable = true)
            String errorDetail,
            LocalDateTime startedAt,
            LocalDateTime completedAt
    ) {

        static Attempt of(LectureHpTaskAttempt attempt) {
            return new Attempt(attempt.getAttemptNo(), attempt.getResult(), attempt.getErrorCode(),
                    attempt.getRawErrorCode(), attempt.getErrorDetail(), attempt.getStartedAt(),
                    attempt.getCompletedAt());
        }
    }
}
