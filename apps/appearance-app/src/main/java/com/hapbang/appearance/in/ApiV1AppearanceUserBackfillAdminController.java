package com.hapbang.appearance.in;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.appearance.app.AppearanceUserBackfillFacade;
import com.hapbang.appearance.app.dto.AppearanceUserBackfillResDto;
import com.hapbang.appearance.global.response.RsData;

import lombok.RequiredArgsConstructor;

/**
 * 관리자 기존 회원 Backfill REST API (#394). Swagger 명세는 {@link ApiV1AppearanceUserBackfillAdminSpec}.
 * 로직은 {@link AppearanceUserBackfillFacade}만 부른다. afterId·limit 범위 검사는 명세의 제약이 한다.
 * limit을 생략하면 관리자 요청 시간을 줄이려고 100명만 처리한다(최대 1,000은 명시해서 쓴다).
 */
@RestController
@RequestMapping("/api/v1/appearances/admin/users")
@RequiredArgsConstructor
public class ApiV1AppearanceUserBackfillAdminController implements ApiV1AppearanceUserBackfillAdminSpec {

    private final AppearanceUserBackfillFacade appearanceUserBackfillFacade;

    @Override
    @PostMapping("/backfill")
    public RsData<AppearanceUserBackfillResDto> backfill(
            @RequestParam(name = "afterId", defaultValue = "0") long afterId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return RsData.ok("회원 정보 Backfill을 실행했습니다.", appearanceUserBackfillFacade.backfill(afterId, limit));
    }
}
