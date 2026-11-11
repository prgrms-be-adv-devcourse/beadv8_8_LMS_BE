package com.hapbang.appearance.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceSyncInternalFacade;
import com.hapbang.appearance.global.response.RsData;
import com.hapbang.shared.appearance.dto.AppearanceReviewSyncPageDto;
import com.hapbang.shared.appearance.dto.AppearanceSyncPageDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 동기화 내부 API (#713). Swagger 명세는 {@link ApiV1AppearanceSyncInternalSpec}, 계약은 docs/api/internal-appearance-sync.md.
 * 로직은 {@link AppearanceSyncInternalFacade}만 부른다. afterId·size 범위 검사는 명세의 제약이 한다.
 */
@RestController
@RequestMapping("/api/v1/internal/appearances/sync")
@RequiredArgsConstructor
public class ApiV1AppearanceSyncInternalController implements ApiV1AppearanceSyncInternalSpec {

    private final AppearanceSyncInternalFacade appearanceSyncInternalFacade;

    @Override
    @GetMapping("/appearances")
    public RsData<AppearanceSyncPageDto> findAppearances(
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("출연 동기화 목록을 조회했습니다.", appearanceSyncInternalFacade.findAppearances(afterId, size));
    }

    @Override
    @GetMapping("/reviews")
    public RsData<AppearanceReviewSyncPageDto> findPublishedReviews(
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("공개된 출연 리뷰 동기화 목록을 조회했습니다.",
                appearanceSyncInternalFacade.findPublishedReviews(afterId, size));
    }
}
