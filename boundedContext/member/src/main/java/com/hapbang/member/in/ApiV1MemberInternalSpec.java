package com.hapbang.member.in;

import com.hapbang.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.MemberPageDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Tag(name = "회원 (내부)", description = "모듈 간 내부 API. billing이 기존 회원 복제본을 채울(백필) 때 호출한다 "
        + "(docs/api/internal-members.md). 회원 세션으로는 호출할 수 없고, 서버 밖에서는 nginx가 404로 막는다.")
// 검증 조건은 구현 클래스가 아니라 여기(인터페이스)에 둔다. 구현에서 다시 선언하면 Bean Validation이 거부한다
public interface ApiV1MemberInternalSpec {

    @Operation(summary = "회원 목록 (키셋)",
            description = "모든 역할·상태(정지·탈퇴 포함) 회원을 ID 오름차순으로 size개씩 준다. 다음 페이지는 응답의 nextAfterId를 "
                    + "afterId로 넘기고, nextAfterId가 null이면 마지막 페이지다. items는 이벤트와 같은 UserDto(version 포함). "
                    + "오류: size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)")
    RsData<MemberPageDto> findMembers(
            @Parameter(description = "이 ID보다 큰 회원부터 (생략하면 처음부터)") @PositiveOrZero Long afterId,
            @Parameter(description = "페이지 크기 (1~500, 기본 100)") @Min(1) @Max(MemberPageDto.MAX_SIZE) int size);
}
