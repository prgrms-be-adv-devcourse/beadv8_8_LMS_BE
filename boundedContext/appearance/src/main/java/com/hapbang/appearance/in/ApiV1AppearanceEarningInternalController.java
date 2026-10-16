package com.hapbang.appearance.in;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceEarningFacade;
import com.hapbang.global.response.RsData;
import com.hapbang.shared.appearance.domain.AppearanceEarningStatus;
import com.hapbang.shared.appearance.domain.AppearanceEarningType;
import com.hapbang.shared.appearance.dto.AppearanceEarningItemDto;
import com.hapbang.shared.appearance.dto.AppearanceEarningSummaryDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 수익 내부 API (#301). Swagger 명세는 {@link ApiV1AppearanceEarningInternalSpec}, 계약은 docs/appearance/module.md 18장
 * (공통 규칙은 docs/api/internal-earnings.md). 로직은 {@link AppearanceEarningFacade}만 부른다.
 */
@RestController
@RequestMapping("/api/v1/internal/appearances/earnings")
@RequiredArgsConstructor
public class ApiV1AppearanceEarningInternalController implements ApiV1AppearanceEarningInternalSpec {

    private final AppearanceEarningFacade appearanceEarningFacade;

    @Override
    @GetMapping("/summary")
    public RsData<AppearanceEarningSummaryDto> getSummary(@RequestParam("userId") Long userId,
            @RequestParam(name = "earningType", required = false) AppearanceEarningType earningType) {
        return RsData.ok("출연 수익 요약을 조회했습니다.", appearanceEarningFacade.summarize(userId, earningType));
    }

    @Override
    @GetMapping
    public RsData<Page<AppearanceEarningItemDto>> getEarnings(@RequestParam("userId") Long userId,
            @RequestParam(name = "status", required = false) Set<AppearanceEarningStatus> statuses,
            @RequestParam(name = "earningType", required = false) AppearanceEarningType earningType,
            Pageable pageable) {
        return RsData.ok("출연 수익 목록을 조회했습니다.",
                appearanceEarningFacade.find(userId, statuses, earningType, pageable));
    }
}
