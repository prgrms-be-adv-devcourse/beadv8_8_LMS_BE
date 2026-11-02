package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;
import com.hapbang.shared.internal.InternalApiHeaders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "출연 리뷰 (내부)", description = "모듈 간 내부 API. 회원 모듈이 프로필의 받은 리뷰 목록을 보여 줄 때 호출한다 "
        + "(docs/appearance/module.md 22장). 회원 로그인으로는 호출할 수 없고, 내부 포트(8081)로만 받고, Gateway·외부 포트로 오면 404다.")
public interface ApiV1AppearanceReviewInternalSpec {

    @Operation(summary = "회원이 받은 공개 출연 리뷰 목록",
            description = "회원(userId)이 받은 리뷰 중 공개된 것만 최신 공개순(publishedAt desc, reviewId desc)으로 준다(sort 무시). "
                    + "블라인드 공개 전 리뷰는 빠진다. page·size(최대 100). 리뷰가 없는 회원은 빈 페이지다. "
                    + "시각은 offset 없는 KST다. 본문을 쓰지 않은 리뷰는 content가 null이다. "
                    + "오류: userId 1 미만·숫자 아님 400 INVALID_REQUEST.")
    @Parameter(name = InternalApiHeaders.API_KEY, in = ParameterIn.HEADER, required = true,
            description = ApiV1AppearanceEarningInternalSpec.API_KEY_DESCRIPTION)
    RsData<Page<AppearanceReceivedReviewDto>> getReceivedReviews(
            @Parameter(description = "리뷰를 받은 회원 ID. 1 미만이면 400", required = true) Long userId,
            Pageable pageable);
}
