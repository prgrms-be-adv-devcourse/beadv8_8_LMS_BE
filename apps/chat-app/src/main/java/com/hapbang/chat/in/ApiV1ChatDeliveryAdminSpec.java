package com.hapbang.chat.in;

import java.util.UUID;

import com.hapbang.chat.app.ChatJobStream;
import com.hapbang.chat.app.dto.ChatIsolatedJobPage;
import com.hapbang.chat.app.dto.ChatReprocessResult;
import com.hapbang.chat.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 격리 전달 작업 API의 Swagger 명세. 매핑은 {@link ApiV1ChatDeliveryAdminController}
 */
@Tag(name = "관리자 채팅 전달 작업", description = "격리(DEAD)한 메시지 전달·연결 제어 작업을 조회하고 운영 재처리한다")
public interface ApiV1ChatDeliveryAdminSpec {

    @Operation(summary = "격리한 전달 작업 목록", description = """
            관리자. 발행 체크포인트가 격리(DEAD)됐거나 outbox가 적재 시도 상한으로 격리(DEAD)된 작업을 최신순(체크포인트 ID 내림차순)으로
            조회한다. stream은 DELIVERY(메시지 전달, 기본) 또는 CONTROL(연결 제어). 응답 항목: stream, consumerGroup, jobId,
            checkpointId, outboxStatus(outbox 원본이 없으면 null), publishStatus, generation(처리 세대), deadReason
            (PUBLISH_RETRY_EXHAUSTED·MALFORMED_ENTRY·SOURCE_MISSING·REQUEUE_EXHAUSTED, 적재 격리만 있으면 null),
            deadStreamEntryId, 이번 세대·누적 적재/발행 시도 횟수, enqueueError·publishError(마지막 오류), isolatedAt,
            reprocessable(outbox 원본이 있어야 재처리할 수 있다). nextCursor가 있으면 cursor로 넘겨 다음 페이지를 읽는다.
            size 1~100(기본 20), cursor 1 이상, 범위 밖은 400 INVALID_REQUEST. 비로그인 401, 관리자가 아니면 403.""")
    RsData<ChatIsolatedJobPage> getIsolatedJobs(
            @Parameter(description = "DELIVERY(기본) 또는 CONTROL") ChatJobStream stream,
            @Parameter(description = "이전 응답의 nextCursor. 처음이면 생략") Long cursor,
            @Parameter(description = "조회 크기 (기본 20, 최대 100)") int size);

    @Operation(summary = "격리한 전달 작업 운영 재처리", description = """
            관리자. 격리한 작업의 처리 세대를 올리고 같은 안정 ID로 다시 적재한다(적재 격리면 relay가, 발행 격리면 바로 적재).
            재처리 직전 상태와 요청한 관리자 ID를 감사 기록(chat_delivery_reprocess_audit.requested_by)에 남긴다.
            응답은 되돌렸다는 뜻이며 발행 완료가 아니다. outbox 원본이 없는 작업(형식 오류 등)은 404 DELIVERY_JOB_NOT_FOUND,
            격리 상태가 아니면 409 DELIVERY_JOB_NOT_ISOLATED. 비로그인 401, 관리자가 아니면 403.""")
    RsData<ChatReprocessResult> reprocess(@Parameter(hidden = true) Long adminId,
            @Parameter(description = "DELIVERY 또는 CONTROL") ChatJobStream stream,
            @Parameter(description = "작업 안정 ID(deliveryId 또는 controlId)") UUID jobId);
}
