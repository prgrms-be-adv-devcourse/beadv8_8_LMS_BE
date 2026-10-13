package com.hapbang.appearance.app;

import org.springframework.stereotype.Component;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트 리스너가 출연 회원 복제 기능을 쓰는 진입점. in 계층은 Service를 직접 쓰지 않고 이 Facade를 통한다.
 */
@Component
@RequiredArgsConstructor
public class AppearanceUserFacade {

    private final AppearanceUserService appearanceUserService;

    /** 가입 정보로 출연 회원을 만든다. 이미 있으면 무시한다. */
    public void syncJoined(UserDto user) {
        appearanceUserService.createIfAbsent(user);
    }

    /** 변경 정보로 있는 출연 회원을 갱신한다. 없으면 {@code APPEARANCE_USER_NOT_FOUND}로 실패한다. */
    public void syncModified(UserDto user) {
        appearanceUserService.update(user);
    }
}
