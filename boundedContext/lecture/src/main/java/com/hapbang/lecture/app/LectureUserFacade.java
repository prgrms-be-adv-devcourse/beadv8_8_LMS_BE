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

    /** 같은 회원 정보가 재전달돼도 복제본을 중복 생성하지 않는다. */
    @Transactional
    public void sync(UserDto user) {
        lectureUserSyncUseCase.sync(user);
    }
}
