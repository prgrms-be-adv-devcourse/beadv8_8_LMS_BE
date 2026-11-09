package com.hapbang.member.in;

import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.member.global.response.RsData;
import com.hapbang.member.app.MemberInternalFacade;
import com.hapbang.member.global.exception.BusinessException;
import com.hapbang.member.global.exception.GlobalErrorCode;
import com.hapbang.shared.member.dto.ContentCategoriesDto;
import com.hapbang.shared.member.dto.InfluencerStatsPageDto;
import com.hapbang.shared.member.dto.MemberChangePageDto;
import com.hapbang.shared.member.dto.MemberPageDto;

import lombok.RequiredArgsConstructor;

/** 회원 내부 API (#303, #769). Swagger 명세는 {@link ApiV1MemberInternalSpec}, 계약은 docs/api/internal-members.md */
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

    @Override
    @GetMapping("/influencer-stats")
    public RsData<InfluencerStatsPageDto> findInfluencerStats(
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("인플루언서 통계를 조회했습니다.", memberInternalFacade.findInfluencerStats(afterId, size));
    }

    @Override
    @GetMapping("/changes")
    public RsData<MemberChangePageDto> findChanges(
            @RequestParam(name = "afterUpdatedAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime afterUpdatedAt,
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        if ((afterUpdatedAt == null) != (afterId == null)) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST, "afterUpdatedAt과 afterId는 함께 넘기거나 함께 생략합니다.");
        }
        return RsData.ok("회원 변경분을 조회했습니다.", memberInternalFacade.findChanges(afterUpdatedAt, afterId, size));
    }

    @Override
    @GetMapping("/content-categories")
    public RsData<ContentCategoriesDto> findContentCategories() {
        return RsData.ok("콘텐츠 분야 목록을 조회했습니다.", memberInternalFacade.findContentCategories());
    }
}
