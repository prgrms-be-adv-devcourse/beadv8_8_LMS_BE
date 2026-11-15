package com.hapbang.member.in;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.app.MemberFacade;
import com.hapbang.member.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 프로필 사진 주소 일괄 조회 (#770). 인플루언서 검색 결과처럼 회원 ID만 아는 화면이 사진을 채울 때 부른다.
 * 회원 API({@link ApiV1MemberController})와 나눠 둔다
 */
@RestController
@RequestMapping("/api/v1/members")
@RequiredArgsConstructor
public class ApiV1MemberProfileImageController implements ApiV1MemberProfileImageSpec {

    private final MemberFacade memberFacade;

    // ids 개수 검증(@Size)은 Spec에 둔다. 구현 메서드가 매개변수 제약을 다시 정하면 Hibernate Validator가 막는다(HV000151)

    @Override
    @GetMapping("/profile-images")
    public RsData<Map<Long, String>> profileImages(@RequestParam(name = "ids") List<Long> ids) {
        return RsData.ok("프로필 사진 주소를 조회했습니다.", memberFacade.profileImageUrls(ids));
    }
}
