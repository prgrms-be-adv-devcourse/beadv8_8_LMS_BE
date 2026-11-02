package com.hapbang.appearance.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 내 출연 목록을 볼 때 어느 쪽으로 참여한 출연인지 */
@Getter
@RequiredArgsConstructor
public enum AppearanceRole {
    HOST("호스트로 참여한 출연 (내가 요청을 보냈거나 모집글을 올림)"),
    GUEST("게스트로 참여한 출연 (내가 요청을 받았거나 모집에 선정됨)");

    private final String description;
}
