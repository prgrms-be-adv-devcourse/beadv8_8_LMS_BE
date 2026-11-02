package com.hapbang.appearance.in;

import com.hapbang.appearance.app.dto.AppearanceReviewIdResDto;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.appearance.in.dto.WriteAppearanceReviewReqDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 출연 리뷰 API Swagger 명세 (#512). 매핑은 {@link ApiV1AppearanceReviewController}
 * <p>
 * 성공 응답은 {@link RsData}의 {@code data}에 담는다. 오류는 공통 핸들러의 Problem Detail이다.
 * 파라미터 검증(@Valid 등)은 여기에 둔다.
 */
@Tag(name = "출연 리뷰", description = """
        출연 확인이 끝난 출연에서 호스트와 게스트가 서로 리뷰·별점을 남긴다. 수정·삭제는 없다.
        블라인드: 두 사람이 모두 쓰면 함께 공개된다. 오류는 Problem Detail로 응답하며 `code`로 구분한다.""")
public interface ApiV1AppearanceReviewSpec {

    @Operation(summary = "출연 리뷰 작성 (호스트·게스트)", description = """
            출연의 상대에게 별점(1~5 정수, 필수)과 본문(선택, 1,000자 이하, 공백뿐이면 비움)을 남긴다. 201 `{id}`.
            상대가 이미 썼으면 두 리뷰가 함께 공개된다. 응답에는 공개 여부를 담지 않는다.
            확인 순서대로 오류가 난다.
            1. 출연이 없거나 그 출연의 호스트·게스트가 아니면 404 `APPEARANCE_NOT_FOUND` (다른 회원의 출연은 있다는 사실도 알리지 않는다)
            2. 작성자의 출연 회원 정보가 없으면 404 `APPEARANCE_USER_NOT_FOUND`, 정지·탈퇴했으면 403 `APPEARANCE_USER_NOT_ACTIVE`, 일반 유저로 강등됐으면 403 `NOT_INFLUENCER`
            3. 출연 확인(`CONFIRMED`, 호스트 확인·자동 확인)이 아니면 409 `APPEARANCE_NOT_REVIEWABLE` (불참·취소 포함)
            4. 작성 기한(설정 시 출연 확인 시각 + 기간)이 지났으면 409 `REVIEW_PERIOD_ENDED`. 지금은 무기한
            5. 상대가 정지·탈퇴했으면 409 `REVIEWEE_NOT_ACTIVE`
            6. 이미 썼으면 409 `ALREADY_REVIEWED` (동시에 두 번 보내도 하나만 저장)
            - 인플루언서 이상만 쓸 수 있다 (로그인 역할이 일반 유저·관리자면 403 `FORBIDDEN`)
            - 별점이 없거나 범위 밖, 본문 1,000자 초과는 400 `INVALID_REQUEST`""")
    RsData<AppearanceReviewIdResDto> write(Long userId, Long appearanceId, @Valid WriteAppearanceReviewReqDto request);
}
