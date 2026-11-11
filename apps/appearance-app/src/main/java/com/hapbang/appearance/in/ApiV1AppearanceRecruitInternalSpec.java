package com.hapbang.appearance.in;

import java.util.List;

import com.hapbang.appearance.app.dto.AppearanceRecruitApplicantIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitAppliedUserIdsResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitInternalResDto;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "출연 모집글 (내부)", description = "모듈 간 내부 API. 채팅 모듈이 모집용 단체방을 만들고 초대할 때 호출한다 "
        + "(docs/appearance/module.md 24장). 회원 로그인으로는 호출할 수 없고, 내부 포트(8081)로만 받고, Gateway·외부 포트로 오면 404다.")
public interface ApiV1AppearanceRecruitInternalSpec {

    @Operation(summary = "모집글 요약",
            description = "모집글 ID·호스트 ID·제목·상태. 상태는 HP 보류 처리 중(HOLD_PENDING)·실패(HOLD_FAILED)를 포함해 그대로 준다. "
                    + "오류: recruitId 1 미만·숫자 아님 400 INVALID_REQUEST, 없는 모집글 404 APPEARANCE_RECRUIT_NOT_FOUND.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<AppearanceRecruitInternalResDto> getRecruit(
            @Parameter(description = "모집글 ID", required = true) Long recruitId);

    @Operation(summary = "모집글 지원자 회원 ID 목록",
            description = "지원 상태(APPLIED·SELECTED·NOT_SELECTED·WITHDRAWN)와 관계없이 지원 이력이 있는 회원 ID를 오름차순 키셋으로 준다. "
                    + "회원마다 한 번만 나온다. 다음 페이지가 있으면 nextAfterUserId를 다음 요청의 afterUserId로 보낸다(마지막 페이지면 빠진다). "
                    + "오류: recruitId 1 미만, afterUserId 음수, size 1~500 밖, 숫자 아님 400 INVALID_REQUEST, 없는 모집글 404 APPEARANCE_RECRUIT_NOT_FOUND.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<AppearanceRecruitApplicantIdsResDto> getApplicantIds(
            @Parameter(description = "모집글 ID", required = true) Long recruitId,
            @Parameter(description = "이 회원 ID보다 큰 회원부터. 생략하거나 0이면 처음부터, 음수면 400") Long afterUserId,
            @Parameter(description = "한 페이지 회원 수. 생략하면 100, 1~500") Integer size);

    @Operation(summary = "모집글 지원 이력 확인",
            description = "주어진 회원 중 이 모집글에 지원 이력(상태 무관)이 있는 회원 ID를 오름차순으로 준다. "
                    + "userIds는 1~100개이고 userIds=1,2 또는 userIds=1&userIds=2로 보낸다. 같은 ID를 여러 번 주면 한 번만 나온다. "
                    + "오류: recruitId·회원 ID 1 미만, userIds 없음·100개 초과, 숫자 아님 400 INVALID_REQUEST, "
                    + "없는 모집글 404 APPEARANCE_RECRUIT_NOT_FOUND.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<AppearanceRecruitAppliedUserIdsResDto> getAppliedUserIds(
            @Parameter(description = "모집글 ID", required = true) Long recruitId,
            @Parameter(description = "확인할 회원 ID 1~100개", required = true) List<Long> userIds);
}
