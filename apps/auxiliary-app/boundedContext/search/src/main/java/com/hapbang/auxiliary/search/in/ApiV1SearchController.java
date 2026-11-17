package com.hapbang.auxiliary.search.in;

import java.security.Principal;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.search.common.app.SearchPage;
import com.hapbang.auxiliary.search.influencer.app.InfluencerSearchFacade;
import com.hapbang.auxiliary.search.influencer.app.InfluencerSuggestionFacade;
import com.hapbang.auxiliary.search.influencer.app.dto.InfluencerSearchItem;
import com.hapbang.auxiliary.search.influencer.app.dto.InfluencerSuggestion;
import com.hapbang.auxiliary.search.lecture.app.LectureSearchFacade;
import com.hapbang.auxiliary.search.lecture.app.LectureSuggestionFacade;
import com.hapbang.auxiliary.search.lecture.app.Requester;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSuggestion;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

/**
 * 검색 API(인플루언서 #771, 강의 #883)와 검색어 자동완성(#973). 인플루언서 검색·자동완성은 로그인 회원만 쓰고 비회원은 SecurityConfig가 401로
 * 막는다. 강의 검색·자동완성은 비회원도 쓴다(SecurityConfig permitAll). 값 제약은 Spring MVC 기본 메서드 검증(400 INVALID_REQUEST)으로 본다.
 * 제약 애노테이션은 Spec(인터페이스)에 둔다. 구현 메서드가 다시 정하면 Hibernate Validator가 거절한다(HV000151).
 * {@code @Validated}를 붙이면 AOP 검증이 먼저 돌아 공통 처리기가 모르는 예외(500)가 나므로 붙이지 않는다(챗봇과 같다).
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class ApiV1SearchController implements ApiV1SearchSpec {

    private final InfluencerSearchFacade facade;
    private final LectureSearchFacade lectureFacade;
    private final InfluencerSuggestionFacade influencerSuggestions;
    private final LectureSuggestionFacade lectureSuggestions;
    private final ClientIpKey clientIpKey;

    @Override
    @GetMapping("/influencers")
    public RsData<SearchPage<InfluencerSearchItem>> searchInfluencers(
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

    @Override
    @GetMapping("/lectures")
    public RsData<SearchPage<LectureSearchItem>> searchLectures(
            Principal principal,
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            Pageable pageable) {
        return RsData.ok("강의를 검색했습니다.", lectureFacade.search(requester(principal, request), keyword, pageable));
    }

    @Override
    @GetMapping("/influencers/suggestions")
    public RsData<List<InfluencerSuggestion>> suggestInfluencers(@RequestParam String keyword) {
        return RsData.ok("인플루언서 검색어 자동완성 후보를 찾았습니다.", influencerSuggestions.suggest(keyword));
    }

    @Override
    @GetMapping("/lectures/suggestions")
    public RsData<List<LectureSuggestion>> suggestLectures(
            Principal principal,
            HttpServletRequest request,
            @RequestParam String keyword) {
        return RsData.ok("강의 검색어 자동완성 후보를 찾았습니다.",
                lectureSuggestions.suggest(requester(principal, request), keyword));
    }

    /**
     * 강의 검색·자동완성을 부른 사람. 로그인했으면 회원 ID로, 아니면 client IP 키로 센다. {@code @LoginUserId}는 비회원이면 401을 던져 쓰지 않는다.
     * 비회원이면 {@link Principal}이 null이다(Spring Security가 익명 인증을 사용자로 내놓지 않는다). 이름은 늘 숫자다:
     * {@code GatewayUserHeaderFilter}가 숫자가 아닌 {@code X-User-Id}는 인증하지 않는다(#883, global resolver를 바꾸지 않으려고 여기서 읽는다).
     */
    private Requester requester(Principal principal, HttpServletRequest request) {
        return principal == null
                ? Requester.guest(clientIpKey.keyOf(request.getRemoteAddr()))
                : Requester.member(Long.parseLong(principal.getName()));
    }
}
