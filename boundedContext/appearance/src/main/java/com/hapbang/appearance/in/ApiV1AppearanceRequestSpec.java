package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.app.dto.AppearanceRequestResDto;
import com.hapbang.appearance.app.dto.AppearanceRequestSummaryResDto;
import com.hapbang.appearance.in.dto.CreateAppearanceRequestReqDto;
import com.hapbang.appearance.in.dto.UpdateAppearanceRequestReqDto;
import com.hapbang.global.response.RsData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 출연 요청서 API Swagger 명세. 매핑은 {@link ApiV1AppearanceRequestController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다 (AGENTS.md 7장 "성공 응답"). 오류는 공통 핸들러의 Problem Detail이다.
 * <p>
 * 파라미터 검증(@Valid 등)은 여기에 둔다. 구현 메서드에서 제약을 새로 정의하면 Bean Validation 규칙 위반으로 요청이 실패한다.
 */
@Tag(name = "출연 요청", description = """
        호스트가 게스트 1명에게 보내는 출연 요청서. 정상 상태의 인플루언서 이상만 쓸 수 있다 (관리자 제외).
        오류는 Problem Detail로 응답하며 `code`로 구분한다.""")
public interface ApiV1AppearanceRequestSpec {

    @Operation(summary = "출연 요청서 보내기", description = """
            요청서를 저장하고 출연료만큼 호스트의 HP를 보류한다. 보류에 성공하면 `REQUESTED`로 201 응답한다.
            - 같은 게스트에게 처리 중이거나 응답 대기 중인 요청이 있으면 409 `DUPLICATE_APPEARANCE_REQUEST`
            - 잔액이 부족하면 409 `INSUFFICIENT_HP` (요청서는 기록으로만 남고 목록에 보이지 않는다)
            - HP 보류 서버가 응답하지 않으면 503 `BILLING_UNAVAILABLE`. 처리가 끝나면 보낸 요청 목록에 나타난다""")
    RsData<AppearanceRequestResDto> create(Long userId, @Valid CreateAppearanceRequestReqDto request);

    @Operation(summary = "보낸 요청 목록", description = """
            내가 호스트인 요청서. 최신순 고정(sort 값은 무시), 페이지는 page·size(기본 20, 최대 100).
            보류가 끝나지 않았거나 실패한 요청서는 나오지 않는다.""")
    RsData<Page<AppearanceRequestSummaryResDto>> getSent(Long userId, @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "받은 요청 목록", description = """
            내가 게스트인 요청서. 최신순 고정(sort 값은 무시), 페이지는 page·size(기본 20, 최대 100).""")
    RsData<Page<AppearanceRequestSummaryResDto>> getReceived(Long userId,
                                                             @Parameter(hidden = true) Pageable pageable);

    @Operation(summary = "출연 요청서 상세", description = """
            호스트·게스트만 조회할 수 있다. 다른 회원이 조회하거나 목록에 나오지 않는 요청서면 404 `APPEARANCE_REQUEST_NOT_FOUND`.""")
    RsData<AppearanceRequestResDto> getDetail(Long userId, Long requestId);

    @Operation(summary = "출연 요청서 기본 정보 수정", description = """
            제목·콘텐츠 설명·호스트 채널 URL만 수정한다. 보낸 값만 바꾸고, 보내지 않은 값은 그대로 둔다.
            호스트만(게스트는 403 `NOT_REQUEST_HOST`), 게스트 응답 전(`REQUESTED`)까지만(이후 409 `APPEARANCE_REQUEST_NOT_EDITABLE`) 가능하다.""")
    RsData<AppearanceRequestResDto> updateBasicInfo(Long userId, Long requestId,
                                                    @Valid UpdateAppearanceRequestReqDto request);
}
