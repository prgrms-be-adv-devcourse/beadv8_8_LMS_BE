package com.hapbang.member.in;

import com.hapbang.member.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberPageDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

@Tag(name = "회원 (내부)", description = "모듈 간 내부 API. billing의 회원 복제본 백필, search의 인플루언서 색인·보정이 "
        + "호출한다 (docs/api/internal-members.md). 회원 로그인(Gateway 사용자 헤더)으로는 호출할 수 없고, 외부에서는 Gateway에 route가 없어 닿지 않는다.")
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

    @Operation(summary = "인플루언서 통계 (키셋)",
            description = "정상 인플루언서(INFLUENCER·VERIFIED_INFLUENCER + ACTIVE)를 ID 오름차순으로 size개씩 준다. "
                    + "항목은 userId, 회원 version, 인증된 채널 모두(channelName·followerCount, 채널 ID 순), "
                    + "받은 출연 리뷰 전체의 평점(appearanceRating: average·count, 리뷰가 없으면 average 없음·count 0). "
                    + "다음 페이지는 nextAfterId를 afterId로 넘기고, nextAfterId가 null이면 마지막 페이지다. "
                    + "오류: size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)")
    RsData<InfluencerStatsPageDto> findInfluencerStats(
            @Parameter(description = "이 회원 ID보다 큰 인플루언서부터 (생략하면 처음부터)") @PositiveOrZero Long afterId,
            @Parameter(description = "페이지 크기 (1~500, 기본 100)") @Min(1) @Max(InfluencerStatsPageDto.MAX_SIZE)
            int size);
}
