package com.hapbang.appearance.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.app.dto.AppearanceApplicationResDto;
import com.hapbang.appearance.domain.AppearanceApplicationStatus;
import com.hapbang.appearance.in.dto.ApplyAppearanceRecruitReqDto;
import com.hapbang.appearance.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 출연 모집 지원 API Swagger 명세. 매핑은 {@link ApiV1AppearanceApplicationController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 * 파라미터 검증(@Valid 등)은 여기에 둔다.
 */
@Tag(name = "출연 모집 지원", description = """
        게스트가 출연 모집글에 지원하고, 호스트가 지원을 수락·거절한다. 정상 상태의 인플루언서 이상만 쓸 수 있다 (관리자 제외).
        오류는 Problem Detail로 응답하며 `code`로 구분한다.""")
public interface ApiV1AppearanceApplicationSpec {

    String STATUS_FILTER = "상태 필터. 여러 번 보낼 수 있다 (예: status=APPLIED&status=SELECTED). 생략하면 전체. "
            + "APPLIED·SELECTED·NOT_SELECTED·WITHDRAWN";

    @Operation(summary = "출연 모집 지원 (게스트)", description = """
            모집 중이고 모집 마감 일시 전인 모집글에 지원한다. 메시지는 선택(500자 이하)이고, 요청 본문 없이 보내도 된다. 201 `APPLIED`.
            - 한 모집글에 한 번만 지원할 수 있다. 취소했거나 거절돼도 다시 지원하면 409 `DUPLICATE_APPLICATION`
            - 자기 모집글이면 400 `CANNOT_APPLY_OWN_RECRUIT`, 모집 중이 아니거나 마감 일시가 지났으면 409 `APPEARANCE_RECRUIT_NOT_OPEN`
            - 정원이 찼어도 지원할 수 있다 (빈자리가 생기면 호스트가 수락할 수 있음)""")
    RsData<AppearanceApplicationResDto> apply(Long userId, Long recruitId, @Valid ApplyAppearanceRecruitReqDto request);

    @Operation(summary = "모집글의 지원자 목록 (호스트)", description = """
            모집글을 올린 호스트만 볼 수 있다 (다른 회원은 403 `NOT_RECRUIT_HOST`). 최신순 고정, 페이지는 page·size(기본 20, 최대 100).
            수락된 지원에는 `appearanceId`가 있다.""")
    RsData<Page<AppearanceApplicationResDto>> getApplicants(Long userId, Long recruitId,
            @Parameter(description = STATUS_FILTER) List<AppearanceApplicationStatus> statuses,
            @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "내 지원 목록 (게스트)", description = """
            내가 지원한 모집 지원. 최신순 고정, 페이지는 page·size(기본 20, 최대 100).""")
    RsData<Page<AppearanceApplicationResDto>> getMine(Long userId,
            @Parameter(description = STATUS_FILTER) List<AppearanceApplicationStatus> statuses,
            @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "지원 취소 (게스트)", description = """
            호스트가 응답하기 전(`APPLIED`)까지만 취소할 수 있다. 취소한 뒤에는 다시 지원할 수 없다.
            - 지원한 게스트가 아니면 403 `NOT_APPLICATION_GUEST`, 대기 중이 아니면 409 `APPLICATION_NOT_PENDING`""")
    RsData<AppearanceApplicationResDto> withdraw(Long userId, Long applicationId);

    @Operation(summary = "지원 수락 (호스트)", description = """
            지원을 수락해 출연을 성립시킨다. HP는 이동하지 않고 모집글 보류가 유지된다. 응답의 `appearanceId`가 성립한 출연이다.
            - 호스트가 아니면 403 `NOT_RECRUIT_HOST`, 모집 중이 아니거나 마감 일시가 지났으면 409 `APPEARANCE_RECRUIT_NOT_OPEN`
            - 대기 중인 지원이 아니면 409 `APPLICATION_NOT_PENDING`, 정원이 찼으면 409 `RECRUIT_FULL` (모집은 마감되지 않음)
            - 지원한 게스트가 정지·탈퇴·강등됐으면 403
            - 같은 모집글을 동시에 처리하면 먼저 반영된 쪽만 성공하고 나머지는 409 `CONCURRENT_UPDATE`""")
    RsData<AppearanceApplicationResDto> accept(Long userId, Long applicationId);

    @Operation(summary = "지원 거절 (호스트)", description = """
            지원을 거절한다 (사유 없음). 거절된 게스트는 다시 지원할 수 없다. 오류는 수락과 같다 (정원 제외).""")
    RsData<AppearanceApplicationResDto> reject(Long userId, Long applicationId);
}
