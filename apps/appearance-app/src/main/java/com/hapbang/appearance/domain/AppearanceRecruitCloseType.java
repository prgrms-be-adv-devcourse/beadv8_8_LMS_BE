package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 출연 모집글 마감 방식 (모집 마감 처리에서 사용). 어느 쪽이든 마감 이후 처리는 같다 */
@Getter
@RequiredArgsConstructor
public enum AppearanceRecruitCloseType {
    DEADLINE("모집 마감 일시가 지나 마감"),
    EARLY("호스트가 마감 일시 전에 조기 마감");

    private final String description;
}
