package com.hapbang.member.in;

import java.time.LocalDateTime;

import com.hapbang.member.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberChangePageDto;
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

    @Operation(summary = "회원 변경분 (키셋)",
            description = "users.updated_at이 커서보다 뒤인 회원을 모든 역할·상태(강등·정지·탈퇴 포함)에서 (updatedAt, id) 오름차순으로 "
                    + "size개씩 준다. items는 이벤트와 같은 UserDto(version 포함). 다음 페이지는 응답의 nextAfterUpdatedAt·"
                    + "nextAfterId를 그대로 넘기고, 둘이 null이면 마지막 페이지다. 커서를 생략하면 처음부터. "
                    + "늦게 커밋된 행이 지나간 커서 뒤에 나타날 수 있어, 이어 읽을 때는 커서를 1분 겹쳐 다시 읽고 version으로 거른다. "
                    + "오류: 커서 둘 중 하나만 넘기거나, 시각 형식이 틀리거나, size가 1~500 밖이거나 afterId가 음수면 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)")
    RsData<MemberChangePageDto> findChanges(
            @Parameter(description = "커서 시각, 회원 앱 시간대의 ISO 시각 (예: 2026-11-09T10:15:30.123456). "
                    + "afterId와 함께 넘긴다") LocalDateTime afterUpdatedAt,
            @Parameter(description = "커서 회원 ID. afterUpdatedAt과 함께 넘긴다") @PositiveOrZero Long afterId,
            @Parameter(description = "페이지 크기 (1~500, 기본 100)") @Min(1) @Max(MemberChangePageDto.MAX_SIZE) int size);

    @Operation(summary = "콘텐츠 분야 전체 목록",
            description = "분야 code·name·active를 code 오름차순으로 모두 준다(페이지 없음). 이미 고른 회원의 분야에 이름을 붙일 수 있게 "
                    + "active가 꺼진 분야도 포함한다. 로그인 회원용 공개 목록(GET /api/v1/members/content-categories)과 달리 "
                    + "순서가 고른 수와 상관없다.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = "내부 API 키 (hapbang.internal-api.key). 없거나 틀리면 401(본문 없음)")
    RsData<ContentCategoriesDto> findContentCategories();
}
