package com.hapbang.chat.in;

import com.hapbang.chat.app.dto.ChatUserBackfillResult;
import com.hapbang.chat.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 관리자 기존 회원 백필 API의 Swagger 명세(설계 문서 5.4). 매핑은 {@link ApiV1ChatUserBackfillAdminController}
 */
@Tag(name = "관리자 채팅 회원 동기화", description = "Kafka 회원 이벤트 전환 이전 회원과 전환 중 놓친 변경을 회원 내부 API로 채팅 회원 복제본에 맞춘다")
public interface ApiV1ChatUserBackfillAdminSpec {

    @Operation(summary = "기존 회원 백필 (수동 실행)", description = """
            관리자. 회원 내부 API에서 afterId보다 큰 회원을 ID 오름차순으로 최대 limit명 읽어 반영한다:
            복제본이 없으면 만들고, 있으면 회원 버전이 더 높을 때만 갱신한다(같거나 낮은 버전은 skippedStale).
            Kafka 회원 이벤트로 반영한 최신 정보를 덮지 않고, 다시 실행해도 결과가 같다.
            기존 복제본을 갱신한 결과가 채팅 불가이면 연결 재검사 작업을 남긴다.
            응답: scanned = created + updated + skippedStale + failed, failedIds(이번 실행에서 실패한 회원 ID 전부),
            lastProcessedId, reachedEnd(회원 목록 끝까지 읽었는지), completed(끝까지 읽었고 실패가 없을 때만 true).
            reachedEnd가 false면 nextAfterId를 다음 afterId로 넘겨 이어서 실행한다. 실패한 회원은 그 ID보다 작은 afterId로 다시 실행한다.
            회원 내부 API 장애·응답 계약 위반은 실행을 멈추고 503(MEMBER_SOURCE_UNAVAILABLE, MEMBER_SOURCE_REJECTED)이며,
            이미 반영한 회원은 유지하고 ProblemDetail에 멈추기 전까지의 집계와 resumeAfterId(이어서 실행할 afterId)를 담는다.
            실행 중 다시 요청하면 409 CHAT_USER_BACKFILL_ALREADY_RUNNING.
            afterId 0 이상(기본 0), limit 1 이상 설정 상한(hapbang.chat.backfill.max-limit, 기본 5,000) 이하(기본 100), 범위 밖은 400 INVALID_REQUEST.
            비로그인 401, 관리자가 아니면 403.""")
    RsData<ChatUserBackfillResult> backfill(
            @Parameter(description = "이 회원 ID보다 큰 회원부터 (기본 0 = 처음부터)") @PositiveOrZero long afterId,
            @Parameter(description = "이번 실행에서 처리할 최대 회원 수 (기본 100)") @Min(1) int limit);
}
