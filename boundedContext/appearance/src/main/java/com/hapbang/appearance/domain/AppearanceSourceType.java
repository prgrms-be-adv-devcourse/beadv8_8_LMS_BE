package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 출연이 어디에서 성립했는지 */
@Getter
@RequiredArgsConstructor
public enum AppearanceSourceType {
    REQUEST("출연 요청 (게스트가 수락)"),
    RECRUIT("출연 모집 (호스트가 지원을 수락)");

    private final String description;
}
