package com.hapbang.member.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberInternalFacade;
import com.hapbang.shared.member.dto.MemberPageDto;

import lombok.RequiredArgsConstructor;

/** 회원 내부 API (#303). Swagger 명세는 {@link ApiV1MemberInternalSpec}, 계약은 docs/api/internal-members.md */
@RestController
@RequestMapping("/api/v1/internal/members")
@RequiredArgsConstructor
public class ApiV1MemberInternalController implements ApiV1MemberInternalSpec {

    private final MemberInternalFacade memberInternalFacade;

    @Override
    @GetMapping
    public RsData<MemberPageDto> findMembers(
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("회원 목록을 조회했습니다.", memberInternalFacade.findMembers(afterId, size));
    }
}
