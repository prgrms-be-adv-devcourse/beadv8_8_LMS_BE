package com.hapbang.appearance.in;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceReviewFacade;
import com.hapbang.appearance.app.dto.AppearanceReviewIdResDto;
import com.hapbang.appearance.global.auth.LoginUserId;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.appearance.in.dto.WriteAppearanceReviewReqDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 리뷰 REST API (#512). Swagger 명세는 {@link ApiV1AppearanceReviewSpec}. 로직은 {@link AppearanceReviewFacade}만 부른다.
 * 출연 ID는 숫자만 받는다 ({@link ApiV1AppearanceController}와 같은 이유).
 */
@RestController
@RequestMapping("/api/v1/appearances")
@RequiredArgsConstructor
public class ApiV1AppearanceReviewController implements ApiV1AppearanceReviewSpec {

    private final AppearanceReviewFacade appearanceReviewFacade;

    @Override
    @PostMapping("/{appearanceId:\\d+}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<AppearanceReviewIdResDto> write(@LoginUserId Long userId,
                                                  @PathVariable("appearanceId") Long appearanceId,
                                                  @RequestBody WriteAppearanceReviewReqDto request) {
        AppearanceReviewIdResDto written = appearanceReviewFacade.write(userId, appearanceId, request.rating(),
                request.content());
        return RsData.created("리뷰를 작성했습니다.", written);
    }
}
