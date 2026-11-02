package com.hapbang.appearance.in;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceReviewInternalFacade;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.appearance.dto.AppearanceReceivedReviewDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 내부 API (#514). Swagger 명세는 {@link ApiV1AppearanceReviewInternalSpec}, 계약은 docs/appearance/module.md 22장.
 * 로직은 {@link AppearanceReviewInternalFacade}만 부른다.
 */
@RestController
@RequestMapping("/api/v1/internal/appearances/users")
@RequiredArgsConstructor
public class ApiV1AppearanceReviewInternalController implements ApiV1AppearanceReviewInternalSpec {

    private final AppearanceReviewInternalFacade appearanceReviewInternalFacade;

    @Override
    @GetMapping("/{userId}/reviews")
    public RsData<Page<AppearanceReceivedReviewDto>> getReceivedReviews(@PathVariable("userId") Long userId,
                                                                        Pageable pageable) {
        return RsData.ok("받은 출연 리뷰 목록을 조회했습니다.",
                appearanceReviewInternalFacade.findReceived(userId, pageable));
    }
}
