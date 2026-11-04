package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 회원(복제 회원) 동기화 진입점. 회원 이벤트 리스너가 이 Facade만 부른다.
 * <p>
 * 회원 이벤트로 호출되어 로그인 정보가 없으므로 역할 검사({@code @PreAuthorize})를 두지 않는다.
 * 트랜잭션은 기능마다 여기서 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceUserFacade {

    private final AppearanceUserSyncUseCase appearanceUserSyncUseCase;

    /** 가입 정보로 출연 회원을 만든다. 이미 있으면 무시한다. */
    @Transactional
    public void syncJoined(UserDto user) {
        appearanceUserSyncUseCase.createIfAbsent(user);
    }

    /** 변경 정보로 있는 출연 회원을 갱신한다. 없으면 {@code APPEARANCE_USER_NOT_FOUND}로 실패한다(Kafka 리스너는 재시도 뒤 DLT로 보낸다). */
    @Transactional
    public void syncModified(UserDto user) {
        appearanceUserSyncUseCase.update(user);
    }
}
