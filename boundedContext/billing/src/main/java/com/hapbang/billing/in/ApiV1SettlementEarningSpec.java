package com.hapbang.billing.in;

import java.util.Set;

import org.springframework.data.domain.Page;

import com.hapbang.billing.domain.EarningStatus;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

// 지급 예정 수익 통합 조회 API의 Swagger 명세이며, 매핑은 ApiV1SettlementEarningController가 맡는다 (#295).
@Tag(name = "지급 예정 수익", description = "강의 판매·출연으로 받을 수익(아직 수익 HP로 지급되지 않은 건)을 합쳐 본다")
public interface ApiV1SettlementEarningSpec {

    @Operation(summary = "내 지급 예정 수익 요약",
            description = "유저·인플루언서·인증된 인플루언서. 강의 판매·출연의 아직 지급하지 않은 수익을 상태별(확인 대기·지급 예정·"
                    + "보류·처리 중·실패)로 합한 금액(HP)·건수와 전체 합계. 출처별 소계는 없다. 수익이 없으면 모두 0. "
                    + "asOf는 Billing이 조회를 시작한 시각(UTC)이며 두 출처의 같은 시점 스냅샷을 보장하지 않는다. "
                    + "강의·출연 중 한쪽이라도 조회하지 못하면 부분 결과 없이 503(EARNING_SOURCE_UNAVAILABLE: 일시 장애, "
                    + "EARNING_SOURCE_REJECTED: 내부 API 거절)이고 source에 실패한 출처(LECTURE·APPEARANCE)를 담는다. "
                    + "비로그인 401, 관리자 403(ProblemDetail).")
    RsData<EarningSummaryResponse> summary(Long userId);

    @Operation(summary = "내 지급 예정 수익 목록",
            description = "유저·인플루언서·인증된 인플루언서. 강의 판매·출연 수익을 상태 변경 최신순(같으면 sourceId 내림차순 → "
                    + "sourceType → earningType 오름차순)으로 합친 목록. status를 생략하거나 비우면 미지급 다섯 상태, "
                    + "여러 번 주면 그 상태들만. 시각은 UTC Instant, 지급 예정 시각을 정할 수 없으면 payoutScheduledAt은 null. "
                    + "page 0 이상, size 1~100(기본 20), (page + 1) × size ≤ 1,000. 범위 밖·없는 status는 400 INVALID_REQUEST, "
                    + "깊이 초과는 400 EARNING_PAGE_TOO_DEEP. 출처 장애는 요약과 같은 503. 비로그인 401, 관리자 403.")
    RsData<Page<EarningItemResponse>> earnings(Long userId,
            @Parameter(description = "수익 상태(여러 번 줄 수 있다). 생략하면 미지급 상태") Set<EarningStatus> statuses,
            @Parameter(description = "페이지 번호(0부터)") @Min(0) int page,
            @Parameter(description = "페이지 크기(1~100)") @Min(1) @Max(100) int size);
}
