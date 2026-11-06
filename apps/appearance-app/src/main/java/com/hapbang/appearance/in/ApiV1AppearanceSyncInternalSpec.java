package com.hapbang.appearance.in;

import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Tag(name = "출연 동기화 (내부)", description = "모듈 간 내부 API (#713). AI 추천이 출연 이벤트 누락을 주 1회 바로잡을 때 처음부터 끝까지 "
        + "읽는다(docs/api/internal-appearance-sync.md). 회원 로그인으로는 호출할 수 없고, 내부 포트(8081)로만 받고, "
        + "Gateway·외부 포트로 오면 404다.")
public interface ApiV1AppearanceSyncInternalSpec {

    String AFTER_ID_DESCRIPTION = "이 ID보다 큰 항목부터 (생략하면 처음부터). 음수면 400";
    String SIZE_DESCRIPTION = "페이지 크기 (1~500, 기본 100). 범위 밖이면 400";

    @Operation(summary = "출연 동기화 목록 (키셋)",
            description = "모든 출연(모든 상태, 탈퇴 회원의 출연 포함)을 ID 오름차순으로 size개씩 준다. 다음 페이지는 응답의 "
                    + "nextAfterId를 afterId로 넘기고, nextAfterId가 null이면 마지막 페이지다. items는 출연 변경 이벤트"
                    + "(AppearanceChangedEvent)와 같은 AppearanceSyncDto(version 포함)다. result는 SCHEDULED·COMPLETED·"
                    + "CANCELED·NO_SHOW. 시각은 offset 없는 KST다. "
                    + "오류: size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<AppearanceSyncPageDto> findAppearances(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(AppearanceSyncPageDto.MAX_SIZE) int size);

    @Operation(summary = "공개된 출연 리뷰 동기화 목록 (키셋)",
            description = "공개된 리뷰만 리뷰 ID 오름차순으로 size개씩 준다(블라인드 공개 전 리뷰는 빠지고 키셋은 끊기지 않는다). "
                    + "nextAfterId는 그 페이지 마지막 리뷰 ID이고 null이면 마지막 페이지다. 본문·쓴 사람은 없고, 필드 값은 리뷰 "
                    + "공개 이벤트(AppearanceReviewPublishedEvent)의 같은 이름 필드와 같다. "
                    + "오류: size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<AppearanceReviewSyncPageDto> findPublishedReviews(
            @Parameter(description = AFTER_ID_DESCRIPTION) @PositiveOrZero Long afterId,
            @Parameter(description = SIZE_DESCRIPTION) @Min(1) @Max(AppearanceReviewSyncPageDto.MAX_SIZE) int size);
}
