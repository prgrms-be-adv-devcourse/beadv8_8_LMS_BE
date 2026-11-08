package com.hapbang.lecture.in;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.WithdrawalBlocksDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "강의 회원 탈퇴 확인 (내부)", description = "모듈 간 내부 API. 회원이 탈퇴해도 되는지 확인할 때 호출한다 "
        + "(docs/api/internal-withdrawals.md 3장). 회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다(외부 포트로 오면 404).")
public interface ApiV1LectureWithdrawalInternalSpec {

    String DESCRIPTION = """
            회원(userId)의 탈퇴가 막힌 강의 쪽 이유. 읽기만 하고 아무것도 바꾸지 않는다. 막힌 이유가 없으면 `reasons`가 빈 목록이다.
            주문·강의가 없는 회원(복제 회원이 없는 회원 포함)도 빈 목록이다. 주문 항목 하나는 이유 하나로만 센다.
            - `LECTURE_ORDER_PROCESSING`: 구매자. 결제·결제 취소·구매 취소·반환 처리 중인 주문
            - `LECTURE_ORDER_HOLDING`: 구매자. 구매 HP가 보류 중(구매·구매 확정·지급 처리 중, 지급 예정일까지)
            - `LECTURE_OBJECTION_FILED`: 구매자. 이의 제기 접수 중
            - `LECTURE_SALE_PAYOUT_PENDING`: 판매자. 아직 지급하지 않은 판매 수익(이의 제기로 지급 중단된 건 포함)
            - `LECTURE_ON_SALE`: 판매자. 삭제하지 않은 판매 중·검수 대기 강의(강의를 삭제하면 풀린다)

            `availableAt`(KST 날짜)은 보류·지급 전 판매 건에만 있고, 가장 늦은 지급 예정 시각의 날짜다.
            지급 중단·지급 실패 건이 있거나 지급 예정 시각이 지나 처리 중이면 정할 수 없어 빠진다.
            오류: userId가 숫자가 아님 400 INVALID_REQUEST.""";

    @Operation(summary = "회원 탈퇴 막힌 이유", description = DESCRIPTION)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1LectureEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<WithdrawalBlocksDto> getWithdrawalBlocks(
            @Parameter(description = "탈퇴하려는 회원 ID (호출한 모듈이 인증한 회원)", required = true) Long userId);
}
