package com.hapbang.appearance.in;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.appearance.dto.AppearanceEarningItemDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "출연 수익 (내부)", description = "모듈 간 내부 API. billing이 게스트의 받을 출연료·위약금을 보여 줄 때 호출한다 "
        + "(docs/appearance/module.md 18장, 공통 규칙 docs/api/internal-earnings.md). 회원 로그인으로는 호출할 수 없고, "
        + "서버 밖에서는 nginx가 404로 막는다.")
public interface ApiV1AppearanceEarningInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    String USER_ID_DESCRIPTION = "게스트 회원 ID (호출한 모듈이 인증한 회원). 1 미만이면 400";

    String EARNING_TYPE_DESCRIPTION = "수익 종류 (생략하면 둘 다). `APPEARANCE_FEE`: 출연료, `APPEARANCE_PENALTY`: 호스트 일방 취소 위약금";

    String STATUS_DESCRIPTION = """
            지급 상태 (여러 개 가능, 생략하면 아직 받지 못한 상태만). 이름은 강의와 같다
            - `CONFIRMATION_PENDING`: 성립, 방송 뒤 호스트 확인 대기
            - `SCHEDULED`: 출연에는 없다 (이 상태만 고르면 빈 결과)
            - `ON_HOLD`: 게스트 출연 취소 요청을 호스트가 처리 중
            - `PROCESSING`: 지급·위약금 처리 중 (자동 재시도 포함)
            - `FAILED`: 지급·위약금이 격리되었거나 처리 기록이 없음. 관리자 조치 필요
            - `COMPLETED`: 지급·위약금 완료
            - `CANCELED`: 불참·합의 취소로 받을 수익 없음""";

    @Operation(summary = "출연 수익 요약",
            description = "게스트(userId)의 아직 받지 못한 수익을 상태별 {amountHp, count}로 합한다. 페이지와 관계없이 전체 출연 "
                    + "기준이고 완료·취소는 빠진다. 출연 기록이 없으면 모두 0이다. scheduled는 항상 0이다. 강의의 asOf는 없다. "
                    + "오류: userId 없음·1 미만, 잘못된 earningType 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<AppearanceEarningSummaryDto> getSummary(
            @Parameter(description = USER_ID_DESCRIPTION, required = true) Long userId,
            @Parameter(description = EARNING_TYPE_DESCRIPTION) AppearanceEarningType earningType);

    @Operation(summary = "출연 수익 목록",
            description = "게스트(userId)의 수익을 출연 단위로 보여 준다(일방 취소는 위약금 한 건). 상태 변경 최신순"
                    + "(statusUpdatedAt desc, sourceId desc)으로 고정한다(sort 무시). page·size(최대 100). "
                    + "시각은 offset 없는 KST다. payoutScheduledAt은 항상 null이라 응답에서 빠진다. "
                    + "오류: userId 없음·1 미만, 잘못된 status·earningType 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<Page<AppearanceEarningItemDto>> getEarnings(
            @Parameter(description = USER_ID_DESCRIPTION, required = true) Long userId,
            @Parameter(description = STATUS_DESCRIPTION) Set<AppearanceEarningStatus> statuses,
            @Parameter(description = EARNING_TYPE_DESCRIPTION) AppearanceEarningType earningType,
            Pageable pageable);
}
