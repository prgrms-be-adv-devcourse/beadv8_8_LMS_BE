package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.Appearance;

import lombok.RequiredArgsConstructor;

/**
 * 출연의 제목을 찾는다. 출연은 제목을 따로 갖지 않고, 출처(요청서·모집글)의 제목을 쓴다 (출처에서 수정하면 그대로 따라간다).
 * 트랜잭션은 호출한 쪽이 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceTitleSupport {

    private final AppearanceRequestSupport appearanceRequestSupport;
    private final AppearanceRecruitSupport appearanceRecruitSupport;

    public String titleOf(Appearance appearance) {
        if (appearance.isFromRecruit()) {
            return appearanceRecruitSupport.getById(appearance.getRecruitId()).getTitle();
        }
        return appearanceRequestSupport.getById(appearance.getRequestId()).getTitle();
    }
}
