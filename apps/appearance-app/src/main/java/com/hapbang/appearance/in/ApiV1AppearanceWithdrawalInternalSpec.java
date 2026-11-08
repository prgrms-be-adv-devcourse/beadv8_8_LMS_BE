package com.hapbang.appearance.in;

import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "출연 탈퇴 확인 (내부)", description = "모듈 간 내부 API. 회원 모듈이 회원 탈퇴 전에 출연 쪽에 정리할 것이 남았는지 확인할 때 호출한다 "
        + "(docs/api/internal-withdrawals.md 3장, docs/appearance/module.md 26장). 회원 로그인으로는 호출할 수 없고, "
        + "내부 포트(8081)로만 받고, Gateway·외부 포트로 오면 404다.")
public interface ApiV1AppearanceWithdrawalInternalSpec {

    @Operation(summary = "회원 탈퇴가 막힌 출연 쪽 이유",
            description = "회원(userId)이 호스트·게스트로 관련된 출연 중 정리할 것이 남았으면 조건마다 이유 하나(건수 포함)를 준다. 읽기만 한다. "
                    + "code: APPEARANCE_REQUEST_SENT(응답 대기 중인 보낸 요청), APPEARANCE_REQUEST_RECEIVED(응답하지 않은 받은 요청), "
                    + "APPEARANCE_RECRUITING(모집 중인 모집글), APPEARANCE_APPLICATION_PENDING(결과 대기 지원), "
                    + "APPEARANCE_IN_PROGRESS(성립·출연 취소 요청 처리 중인 출연), APPEARANCE_HP_PROCESSING(HP 보류 확인 중·HP 작업 처리 중), "
                    + "APPEARANCE_HP_CHECK_REQUIRED(운영자 확인이 필요한 HP 작업). 순서는 이 순서다. "
                    + "message는 회원에게 그대로 보여 줄 문장이고, availableAt은 주지 않는다(항상 null). "
                    + "막힌 이유가 없거나 출연 기록·복제 회원 정보가 없는 회원은 reasons가 빈 목록이다. "
                    + "오류: userId 1 미만·숫자 아님 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<WithdrawalBlocksDto> getWithdrawalBlocks(
            @Parameter(description = "탈퇴하려는 회원 ID. 1 미만이면 400", required = true) Long userId);
}
