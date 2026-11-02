package com.hapbang.lecture.in;

import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.lecture.dto.LectureObjectionFileRequest;
import com.hapbang.shared.lecture.dto.LectureObjectionFiledDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "강의 이의 제기 (내부)", description = "모듈 간 내부 API. 고객센터가 강의 내용 다름 이의 제기 문의를 저장한 뒤 호출한다 "
        + "(docs/api/internal-objections.md). 회원 로그인으로는 호출할 수 없고, 서버 밖에서는 Envoy에 route가 없어 닿지 않는다. "
        + "판단·철회는 HTTP가 아니라 고객센터 이벤트(customersupport.events.v1)로 받는다.")
public interface ApiV1LectureObjectionInternalSpec {

    String API_KEY_DESCRIPTION = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)";

    @Operation(summary = "이의 제기 접수",
            description = """
                    주문 항목을 이의 제기 접수(FILED)로 바꿔 판단·철회 전까지 지급·구매 취소를 멈춘다.
                    같은 commandId(FILE:<문의 ID>)로 다시 보내면 처음 결과를 그대로 돌려준다(성공은 200, 거절은 같은 오류).
                    같은 commandId로 다른 내용을 보내면 409 OBJECTION_COMMAND_CONFLICT다(처음 결과는 그대로).
                    오류(code): 404 OBJECTION_NOT_BUYER(구매자 본인 항목이 아님, 없는 항목 포함), 409 OBJECTION_NOT_OBJECTIONABLE
                    (취소·반환됨), 409 OBJECTION_ALREADY_PAID_OUT, 409 OBJECTION_ALREADY_FILED, 409 OBJECTION_PERIOD_EXPIRED
                    (지급 예정 시각 지남), 400 INVALID_REQUEST(빠진 값·빈 사유). 5xx·시간 초과는 결과를 알 수 없으므로 같은 요청을 다시 보낸다.""")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = API_KEY_DESCRIPTION)
    RsData<LectureObjectionFiledDto> file(LectureObjectionFileRequest request);
}
