package com.hapbang.billing.in;

import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

// 관리자 기존 회원 BillingUser Backfill API의 Swagger 명세이며, 매핑은 ApiV1SettlementMemberBackfillAdminController가 맡는다 (#324).
@Tag(name = "관리자 회원 정보 동기화", description = "회원 복제본(BillingUser) 도입 전에 가입한 회원을 회원 내부 API로 채운다")
public interface ApiV1SettlementMemberBackfillAdminSpec {

    @Operation(summary = "기존 회원 BillingUser Backfill (수동 실행)",
            description = "관리자. 회원 내부 API에서 afterId보다 큰 회원을 ID 오름차순으로 최대 limit명 읽어(한 번에 500명씩) 회원 "
                    + "이벤트와 같은 규칙으로 반영한다: 복제본이 없으면 만들고, 회원 버전이 더 높을 때만 갱신한다(같거나 낮은 버전·버전 "
                    + "없는 정보는 무시). 이벤트로 반영한 최신 정보를 덮지 않고, 다시 실행해도 결과가 같다. 지갑은 만들지 않는다. "
                    + "응답: scanned = created + updated + ignoredStale + ignoredVersionless + failed, failedIds(최대 100개), "
                    + "lastProcessedId, completed(회원 목록 끝까지 순회했는지, 성공 여부는 failed로 판단), completed가 false면 "
                    + "nextAfterId를 다음 afterId로 넘겨 이어서 실행한다. 회원 한 명의 반영 실패는 failed로 세고 계속한다. "
                    + "회원 내부 API 장애·응답 계약 위반은 실행을 멈추고 503(MEMBER_SOURCE_UNAVAILABLE: 일시 장애·잘못된 응답, "
                    + "MEMBER_SOURCE_REJECTED: 내부 API 거절)이며, 이미 반영한 회원은 유지하고 ProblemDetail에 멈추기 전까지의 "
                    + "집계와 resumeAfterId(이어서 실행할 afterId)를 담는다. 실행 중 다시 요청하면 409 BACKFILL_ALREADY_RUNNING. "
                    + "afterId 0 이상(기본 0), limit 1~5,000(기본 100, 생략하면 100명만 처리하고 nextAfterId로 이어서 실행), "
                    + "범위 밖은 400 INVALID_REQUEST. "
                    + "비로그인 401, 관리자가 아니면 403.")
    RsData<BillingUserBackfillResponse> backfill(
            @Parameter(description = "이 회원 ID보다 큰 회원부터 (기본 0 = 처음부터)") @PositiveOrZero long afterId,
            @Parameter(description = "이번 실행에서 처리할 최대 회원 수 (1~5,000, 기본 100)") @Min(1) @Max(5_000) int limit);
}
