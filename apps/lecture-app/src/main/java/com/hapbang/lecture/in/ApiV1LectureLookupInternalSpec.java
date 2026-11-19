package com.hapbang.lecture.in;

import java.util.List;
import java.util.Map;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.LectureLookupDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Tag(name = "강의 조회 (내부)", description = "모듈 간 내부 API. 쿠폰이 판매자 소유 확인·강의 가격 snapshot과 쿠폰 받기 화면의 "
        + "강의 제목·판매 중 거르기에 쓴다. 회원 로그인으로는 호출할 수 없고, 내부 포트로만 받는다(외부 포트로 오면 404).")
public interface ApiV1LectureLookupInternalSpec {

    String FIELDS = """
            판매자 회원 ID(`sellerId`), 지금 가격(`price`, HP), 판매 상태(`saleStatus`), 지금 제목(`title`). 읽기만 하고 아무것도 바꾸지 않는다.
            `saleStatus`는 강의 검수 상태 이름이다.
            - `PENDING_INSPECTION`: 검수 대기
            - `REJECTED`: 반려
            - `ON_SALE`: 판매 중. 장바구니 담기·주문은 이 상태에서만 받는다
            """;

    String DESCRIPTION = "강의 하나의 " + FIELDS + """

            오류: 강의가 없거나 삭제됨 404 LECTURE_NOT_FOUND, lectureId가 숫자가 아님 400 INVALID_REQUEST.""";

    String BATCH_DESCRIPTION = "강의 여러 개의 " + FIELDS + """

            data는 {강의 ID: 강의}이고 요청한 ID마다 키가 있다(중복은 한 번). 없거나 삭제된 강의는 null이다. \
            판매 상태로 거르지 않는다(검수 대기·반려도 준다).

            오류: ids가 0개이거나 100개를 넘거나(중복 포함) 숫자가 아님 400 INVALID_REQUEST.""";

    @Operation(summary = "강의 조회", description = DESCRIPTION)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1LectureEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<LectureLookupDto> getLecture(@Parameter(description = "강의 ID", required = true) Long lectureId);

    @Operation(summary = "강의 일괄 조회", description = BATCH_DESCRIPTION)
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1LectureEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<Map<Long, LectureLookupDto>> getLectures(
            @Parameter(description = "강의 ID 목록, 쉼표로 구분하거나 ids를 여러 번 (1~100개)", example = "11,12")
            @Size(min = 1, max = LectureLookupDto.MAX_BATCH_SIZE, message = "ids는 1~100개여야 합니다.")
            List<@NotNull Long> ids);
}
