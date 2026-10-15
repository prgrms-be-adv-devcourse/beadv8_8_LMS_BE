package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.app.dto.AppearanceRecruitResDto;
import com.hapbang.appearance.app.dto.AppearanceRecruitSummaryResDto;
import com.hapbang.appearance.domain.AppearanceRecruitStatus;
import com.hapbang.appearance.in.dto.CreateAppearanceRecruitReqDto;
import com.hapbang.appearance.in.dto.UpdateAppearanceRecruitReqDto;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 출연 모집글 API Swagger 명세. 매핑은 {@link ApiV1AppearanceRecruitController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 * 파라미터 검증(@Valid 등)은 여기에 둔다.
 */
@Tag(name = "출연 모집", description = """
        호스트가 여러 게스트를 공개 모집하는 출연 모집글. 올리기·수정·내 목록은 정상 상태의 인플루언서 이상만 쓸 수 있고 (관리자 제외),
        공개 목록·상세는 로그인한 회원이면 누구나 볼 수 있다. 오류는 Problem Detail로 응답하며 `code`로 구분한다.""")
public interface ApiV1AppearanceRecruitSpec {

    String STATUS_FILTER = "상태 필터. 여러 번 보낼 수 있다 (예: status=RECRUITING&status=CLOSED). 생략하면 전체. "
            + "RECRUITING·CLOSED·CANCELED만 가능 (그 밖은 400)";

    @Operation(summary = "출연 모집글 올리기", description = """
            모집글을 저장하고 1인당 출연료 × 모집 인원만큼 호스트의 HP를 보류한다. 보류에 성공하면 `RECRUITING`으로 201 응답한다.
            - 1인당 출연료가 최저 출연료(20,000 HP) 미만이면 400 `FEE_BELOW_MINIMUM`
            - 모집 인원이 1~10명이 아니면 400 `RECRUIT_COUNT_OUT_OF_RANGE`
            - 현재 < 모집 마감 일시 < 방송 예정 일시가 아니면 400 `INVALID_RECRUIT_SCHEDULE`
            - HP 보류를 Billing이 거절하면 Billing 오류를 그대로 응답한다 (모집글은 기록으로만 남고 목록에 보이지 않는다).
              예: 잔액 부족 409 `INSUFFICIENT_BALANCE`
            - HP 보류 결과를 확인하지 못하면 503 `BILLING_UNAVAILABLE`. 처리가 끝나면 내 모집글 목록에 나타난다""")
    RsData<AppearanceRecruitResDto> create(Long userId, @Valid CreateAppearanceRecruitReqDto request);

    @Operation(summary = "모집 중인 모집글 목록", description = """
            모집 중(`RECRUITING`)이고 모집 마감 일시가 지나지 않은 모집글. 로그인한 회원이면 누구나 볼 수 있다.
            최신순 고정(sort 값은 무시), 페이지는 page·size(기본 20, 최대 100).""")
    RsData<Page<AppearanceRecruitSummaryResDto>> getOpen(@Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "내가 올린 모집글 목록", description = """
            내가 호스트인 모집글. 최신순 고정, 페이지는 page·size(기본 20, 최대 100).
            보류가 끝나지 않았거나 실패한 모집글은 나오지 않는다.""")
    RsData<Page<AppearanceRecruitSummaryResDto>> getMine(Long userId,
            @Parameter(description = STATUS_FILTER) List<AppearanceRecruitStatus> statuses,
            @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "출연 모집글 상세", description = """
            로그인한 회원이면 누구나 볼 수 있다. 목록에 나오지 않는 모집글(보류 처리 중·실패)이면 404 `APPEARANCE_RECRUIT_NOT_FOUND`.
            마감 반환 진행 상태(`hpReleaseStatus`)는 호스트에게만 담긴다.""")
    RsData<AppearanceRecruitResDto> getDetail(Long userId, Long recruitId);

    @Operation(summary = "출연 모집글 기본 정보 수정", description = """
            제목·콘텐츠 설명·호스트 채널 URL만 수정한다. 보낸 값만 바꾸고, 보내지 않은 값은 그대로 둔다.
            호스트만(다른 회원은 403 `NOT_RECRUIT_HOST`), 모집 중이고 모집 마감 일시 전까지만(이후 409 `APPEARANCE_RECRUIT_NOT_EDITABLE`) 가능하다.""")
    RsData<AppearanceRecruitResDto> updateBasicInfo(Long userId, Long recruitId,
                                                    @Valid UpdateAppearanceRecruitReqDto request);

    @Operation(summary = "출연 모집 조기 마감 (호스트)", description = """
            모집 마감 일시 전에 모집을 마감한다. 성립 인원이 0명이어도 마감할 수 있다. 200 `CLOSED`(마감 방식 `EARLY`).
            - 응답을 기다리던 지원은 모두 `NOT_SELECTED`가 된다
            - 1인당 출연료 × (모집 인원 − 성립 인원)만큼 보류 HP를 호스트에게 돌려준다. 정원이 다 찼으면 돌려줄 금액이 없다.
              반환이 늦어져도 마감은 성공으로 응답하고, 진행 상태는 `hpReleaseStatus`로 확인한다
            - 호스트가 아니면 403 `NOT_RECRUIT_HOST`, 모집 중이 아니거나 마감 일시가 지났으면 409 `APPEARANCE_RECRUIT_NOT_OPEN`
            - 같은 모집글을 동시에 처리(수락 등)하면 먼저 반영된 쪽만 성공하고 나머지는 409 `CONCURRENT_UPDATE`
            - 마감 일시가 지나면 배치가 1분 안에 같은 방식으로 마감한다 (마감 방식 `DEADLINE`)""")
    RsData<AppearanceRecruitResDto> closeEarly(Long userId, Long recruitId);
}
