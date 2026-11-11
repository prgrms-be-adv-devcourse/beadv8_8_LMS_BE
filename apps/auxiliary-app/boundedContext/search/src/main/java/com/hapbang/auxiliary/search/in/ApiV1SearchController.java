package com.hapbang.auxiliary.search.in;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.search.app.InfluencerSearchFacade;
import com.hapbang.auxiliary.search.app.dto.InfluencerSearchItem;

import lombok.RequiredArgsConstructor;

/**
 * 검색 API(#771). 로그인하지 않은 요청은 SecurityConfig가 401로 막는다. 값 제약은 Spring MVC 기본 메서드 검증(400 INVALID_REQUEST)으로 본다.
 * 제약 애노테이션은 Spec(인터페이스)에 둔다. 구현 메서드가 다시 정하면 Hibernate Validator가 거절한다(HV000151).
 * {@code @Validated}를 붙이면 AOP 검증이 먼저 돌아 공통 처리기가 모르는 예외(500)가 나므로 붙이지 않는다(챗봇과 같다).
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class ApiV1SearchController implements ApiV1SearchSpec {

    private final InfluencerSearchFacade facade;

    @Override
    @GetMapping("/influencers")
    public RsData<Page<InfluencerSearchItem>> searchInfluencers(
            @LoginUserId Long userId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> contentCategories,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(required = false) Long minFollowers,
            @RequestParam(required = false) Double minRating,
            Pageable pageable) {
        return RsData.ok("인플루언서를 검색했습니다.",
                facade.search(userId, keyword, contentCategories, verified, minFollowers, minRating, pageable));
    }
}
