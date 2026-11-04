package com.hapbang.lecture.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 회원 이벤트로 강의 모듈의 회원 복제본을 동기화하는 진입점.
 * 이벤트 리스너는 로그인 정보 없이 호출하므로 역할 검사를 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class LectureUserFacade {

    private final LectureUserSyncUseCase lectureUserSyncUseCase;

    /** 가입 정보로 복제본을 만든다. 이미 있으면 무시한다. */
    @Transactional
    public void syncJoined(UserDto user) {
        lectureUserSyncUseCase.createIfAbsent(user);
    }

    /** 변경 정보로 있는 복제본을 갱신한다. 없으면 {@code LECTURE_USER_NOT_FOUND}로 실패한다. */
    @Transactional
    public void syncModified(UserDto user) {
        lectureUserSyncUseCase.update(user);
    }
}
