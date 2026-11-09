package com.hapbang.member.in;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

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
            @RequestParam(name = "afterUpdatedAt", required = false) String afterUpdatedAt,
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        if ((afterUpdatedAt == null) != (afterId == null)) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST, "afterUpdatedAt과 afterId는 함께 넘기거나 함께 생략합니다.");
        }
        return RsData.ok("회원 변경분을 조회했습니다.",
                memberInternalFacade.findChanges(parseCursorTime(afterUpdatedAt), afterId, size));
    }

    /**
     * 커서 시각을 오프셋 없는 ISO 시각(ISO_LOCAL_DATE_TIME)으로 읽는다. {@code @DateTimeFormat(iso = DATE_TIME)}은
     * {@code Z}·{@code +09:00}이 붙은 값도 받아 오프셋을 버리므로(커서가 시차만큼 어긋남), 문자열로 받아 직접 읽는다.
     * 형식은 Spec의 {@code @Pattern}이 먼저 거르고, 있을 수 없는 날짜(13월 등)만 여기서 400이 된다
     */
    private static LocalDateTime parseCursorTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST, "afterUpdatedAt이 올바른 시각이 아닙니다.");
        }
    }

    @Override
    @GetMapping("/content-categories")
    public RsData<ContentCategoriesDto> findContentCategories() {
        return RsData.ok("콘텐츠 분야 목록을 조회했습니다.", memberInternalFacade.findContentCategories());
    }
}
