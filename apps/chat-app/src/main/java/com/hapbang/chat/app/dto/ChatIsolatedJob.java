package com.hapbang.chat.app.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.domain.ChatOutboxStatus;
import com.hapbang.chat.domain.ChatPublishStatus;

/**
 * 격리한 전달·제어 작업(관리자 조회).
 *
 * @param checkpointId             목록 커서(체크포인트 ID)
 * @param outboxStatus             outbox 적재 상태. outbox 행이 없는 격리(형식 오류 등)면 null
 * @param publishStatus            발행 체크포인트 상태. 적재 격리면 보통 PENDING
 * @param generation               처리 세대
 * @param deadReason               체크포인트 격리 원인. 적재 격리만 있으면 null
 * @param deadStreamEntryId        격리할 때의 원본 항목 위치
 * @param enqueueAttemptCount      이번 세대의 적재 시도 횟수. outbox 행이 없으면 null
 * @param totalEnqueueAttemptCount 누적 적재 시도 횟수. outbox 행이 없으면 null
 * @param publishAttemptCount      이번 세대의 발행 실패 횟수
 * @param totalPublishAttemptCount 누적 발행 실패 횟수
 * @param enqueueError             마지막 적재 오류
 * @param publishError             마지막 발행 오류·격리 사유
 * @param isolatedAt               격리 시각(체크포인트 격리 시각, 없으면 outbox 격리 시각)
 * @param reprocessable            운영 재처리 가능 여부(outbox 원본이 있어야 한다)
 */
public record ChatIsolatedJob(
        ChatJobStream stream,
        String consumerGroup,
        UUID jobId,
        long checkpointId,
        ChatOutboxStatus outboxStatus,
        ChatPublishStatus publishStatus,
        int generation,
        String deadReason,
        String deadStreamEntryId,
        Integer enqueueAttemptCount,
        Integer totalEnqueueAttemptCount,
        int publishAttemptCount,
        int totalPublishAttemptCount,
        String enqueueError,
        String publishError,
        LocalDateTime isolatedAt,
        boolean reprocessable
) {
}
