package com.hapbang.auxiliary.search.in;

import java.security.Principal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.auxiliary.global.auth.LoginUserId;
import com.hapbang.auxiliary.global.response.RsData;
import com.hapbang.auxiliary.search.influencer.app.InfluencerSearchFacade;
import com.hapbang.auxiliary.search.influencer.app.dto.InfluencerSearchItem;
import com.hapbang.auxiliary.search.lecture.app.LectureSearchFacade;
import com.hapbang.auxiliary.search.lecture.app.Requester;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

/**
 * 검색 API(인플루언서 #771, 강의 #883). 인플루언서 검색은 로그인 회원만 쓰고 비회원은 SecurityConfig가 401로 막는다. 강의 검색은 비회원도 쓴다
 * (SecurityConfig permitAll). 값 제약은 Spring MVC 기본 메서드 검증(400 INVALID_REQUEST)으로 본다.
 * 제약 애노테이션은 Spec(인터페이스)에 둔다. 구현 메서드가 다시 정하면 Hibernate Validator가 거절한다(HV000151).
 * {@code @Validated}를 붙이면 AOP 검증이 먼저 돌아 공통 처리기가 모르는 예외(500)가 나므로 붙이지 않는다(챗봇과 같다).
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class ApiV1SearchController implements ApiV1SearchSpec {

    private final InfluencerSearchFacade facade;
    private final LectureSearchFacade lectureFacade;
    private final ClientIpKey clientIpKey;

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

    /**
     * 로그인했으면 회원 ID로, 아니면 client IP 키로 센다. {@code @LoginUserId}는 비회원이면 401을 던져 쓰지 않는다. 비회원이면
     * {@link Principal}이 null이다(Spring Security가 익명 인증을 사용자로 내놓지 않는다).
     */
    @Override
    @GetMapping("/lectures")
    public RsData<Page<LectureSearchItem>> searchLectures(
            Principal principal,
            HttpServletRequest request,
            @RequestParam(required = false) String keyword,
            Pageable pageable) {
        Requester requester = principal == null
                ? Requester.guest(clientIpKey.keyOf(request.getRemoteAddr()))
                : Requester.member(Long.parseLong(principal.getName()));
        return RsData.ok("강의를 검색했습니다.", lectureFacade.search(requester, keyword, pageable));
    }
}
