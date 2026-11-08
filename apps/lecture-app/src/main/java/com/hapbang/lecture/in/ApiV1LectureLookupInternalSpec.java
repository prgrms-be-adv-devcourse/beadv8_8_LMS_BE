package com.hapbang.lecture.in;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "강의 조회 (내부)", description = "모듈 간 내부 API. 쿠폰이 판매자 소유 확인과 강의 가격 snapshot에 쓴다. "
        + "회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다(외부 포트로 오면 404).")
public interface ApiV1LectureLookupInternalSpec {

    String DESCRIPTION = """
            강의 하나의 판매자 회원 ID(`sellerId`), 지금 가격(`price`, HP), 판매 상태(`saleStatus`). 읽기만 하고 아무것도 바꾸지 않는다.
            `saleStatus`는 강의 검수 상태 이름이다.
            - `PENDING_INSPECTION`: 검수 대기
            - `REJECTED`: 반려
            - `ON_SALE`: 판매 중. 장바구니 담기·주문은 이 상태에서만 받는다

            오류: 강의가 없거나 삭제됨 404 LECTURE_NOT_FOUND, lectureId가 숫자가 아님 400 INVALID_REQUEST.""";

    @Operation(summary = "강의 조회", description = DESCRIPTION)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1LectureEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<LectureLookupDto> getLecture(@Parameter(description = "강의 ID", required = true) Long lectureId);
}
