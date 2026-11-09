package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.global.response.RsData;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

/**
 * 프로필 사진 주소 일괄 조회 (#770). 인플루언서 검색 결과처럼 회원 ID만 아는 화면이 사진을 채울 때 부른다.
 * 회원 API({@link ApiV1MemberController})와 나눠 둔다
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberProfileImageController implements ApiV1MemberProfileImageSpec {

    /** 한 번에 물을 수 있는 회원 ID 수 (중복 포함) */
    static final int MAX_IDS = 100;

    private final MemberFacade memberFacade;

    @Override
    @GetMapping("/profile-images")
    public RsData<Map<Long, String>> profileImages(
            @RequestParam(name = "ids")
            @Size(min = 1, max = MAX_IDS, message = "ids는 1~100개여야 합니다.") List<@NotNull Long> ids) {
        return RsData.ok("프로필 사진 주소를 조회했습니다.", memberFacade.profileImageUrls(ids));
    }
}
